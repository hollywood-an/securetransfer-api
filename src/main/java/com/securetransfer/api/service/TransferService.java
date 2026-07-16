package com.securetransfer.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.FraudReview;
import com.securetransfer.api.domain.LedgerDirection;
import com.securetransfer.api.domain.LedgerEntry;
import com.securetransfer.api.domain.Tenant;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.domain.TransferStatus;
import com.securetransfer.api.error.BadRequestException;
import com.securetransfer.api.error.ConflictException;
import com.securetransfer.api.error.InsufficientFundsException;
import com.securetransfer.api.error.NotFoundException;
import com.securetransfer.api.fraud.FraudRuleEvaluator;
import com.securetransfer.api.fraud.TransferFlaggedEvent;
import com.securetransfer.api.repository.AccountRepository;
import com.securetransfer.api.repository.FraudReviewRepository;
import com.securetransfer.api.repository.LedgerEntryRepository;
import com.securetransfer.api.repository.TransferRepository;
import com.securetransfer.api.web.dto.CreateTransferRequest;
import com.securetransfer.api.web.dto.TransferResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The core of the system: moving money safely, recording the idempotency result
 * in the SAME transaction, and FLAGGING (never blocking) suspicious transfers
 * for fraud review.
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final FraudRuleEvaluator fraudRules;
    private final FraudReviewRepository fraudReviews;
    private final ApplicationEventPublisher events;

    public TransferService(AccountRepository accounts,
                           TransferRepository transfers,
                           LedgerEntryRepository ledgerEntries,
                           IdempotencyService idempotency,
                           ObjectMapper objectMapper,
                           FraudRuleEvaluator fraudRules,
                           FraudReviewRepository fraudReviews,
                           ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledgerEntries = ledgerEntries;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.fraudRules = fraudRules;
        this.fraudReviews = fraudReviews;
        this.events = events;
    }

    /**
     * Execute a transfer atomically and mark its idempotency key COMPLETED.
     *
     * @Transactional makes the whole method ONE transaction: the transfers row,
     * the two ledger rows, both balance updates, AND flipping the idempotency
     * key to COMPLETED with its response snapshot all commit together — or, if
     * anything throws, all roll back together.
     */
    @Transactional
    public TransferResponse execute(String idempotencyKey, CreateTransferRequest request,
                                    String requestHash, Tenant tenant) {
        Long fromId = request.fromAccount();
        Long toId = request.toAccount();

        // A transfer to the same account is meaningless (DB also forbids it).
        if (fromId.equals(toId)) {
            throw new BadRequestException("fromAccount and toAccount must be different");
        }

        // Lock BOTH account rows before reading balances, ALWAYS in ascending id
        // order so opposing transfers (A->B and B->A) can't deadlock.
        Long firstId = Math.min(fromId, toId);
        Long secondId = Math.max(fromId, toId);
        Account firstLocked = lockAccount(firstId);
        Account secondLocked = lockAccount(secondId);

        Account from = fromId.equals(firstLocked.getId()) ? firstLocked : secondLocked;
        Account to = toId.equals(firstLocked.getId()) ? firstLocked : secondLocked;

        // Tenant isolation: both accounts must belong to the CALLER'S bank. A
        // cross-tenant id looks like "not found" (404) so one bank can't probe the
        // other's account ids — and this check runs BEFORE any balance changes,
        // so a rejected cross-tenant transfer moves no money.
        if (from.getTenant() != tenant) {
            throw new NotFoundException("Account " + from.getId() + " not found");
        }
        if (to.getTenant() != tenant) {
            throw new NotFoundException("Account " + to.getId() + " not found");
        }

        // A frozen account can neither send nor receive money (Phase 5).
        if (from.isFrozen()) {
            throw new ConflictException("Account " + from.getId() + " is frozen");
        }
        if (to.isFrozen()) {
            throw new ConflictException("Account " + to.getId() + " is frozen");
        }

        // Same-currency transfers only — this system has no currency conversion.
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BadRequestException(
                    "Cannot transfer between accounts of different currencies");
        }

        // Verify funds BEFORE any write, so a rejected transfer changes nothing.
        if (from.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    "Account " + from.getId() + " has insufficient funds for this transfer");
        }

        // Fraud rules: evaluate against PRIOR transfers (before saving this one).
        // A flag does NOT block — it marks the transfer FLAGGED and queues a review.
        List<String> flags = fraudRules.evaluate(fromId, toId, request.amount());
        TransferStatus status = flags.isEmpty() ? TransferStatus.COMPLETED : TransferStatus.FLAGGED;

        // Record the transfer (linked to its idempotency key) so we have its id.
        Transfer transfer = transfers.save(new Transfer(
                idempotencyKey, fromId, toId, request.amount(), status, tenant));

        // Move the money — a FLAGGED transfer still completes; flagging only
        // queues a review, it never holds the funds.
        from.debit(request.amount());
        to.credit(request.amount());

        // Double-entry: one DEBIT out of the sender, one CREDIT into the receiver.
        ledgerEntries.save(new LedgerEntry(
                transfer.getId(), from.getId(), LedgerDirection.DEBIT, request.amount()));
        ledgerEntries.save(new LedgerEntry(
                transfer.getId(), to.getId(), LedgerDirection.CREDIT, request.amount()));

        // If flagged, create the PENDING review row and publish an event. The
        // event is handled only AFTER this transaction commits, on a background
        // thread, so the async AI review never holds up this response.
        if (!flags.isEmpty()) {
            FraudReview review = fraudReviews.save(FraudReview.pending(transfer.getId(), flags, tenant));
            events.publishEvent(new TransferFlaggedEvent(review.getId()));
        }

        TransferResponse response = TransferResponse.from(transfer, from);

        // Store the result against the idempotency key, in THIS transaction.
        idempotency.complete(idempotencyKey, toJson(response));

        return response;
    }

    private Account lockAccount(Long id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }

    private String toJson(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize transfer response", e);
        }
    }
}
