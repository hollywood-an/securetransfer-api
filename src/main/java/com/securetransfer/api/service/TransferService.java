package com.securetransfer.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.LedgerDirection;
import com.securetransfer.api.domain.LedgerEntry;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.domain.TransferStatus;
import com.securetransfer.api.error.BadRequestException;
import com.securetransfer.api.error.InsufficientFundsException;
import com.securetransfer.api.error.NotFoundException;
import com.securetransfer.api.repository.AccountRepository;
import com.securetransfer.api.repository.LedgerEntryRepository;
import com.securetransfer.api.repository.TransferRepository;
import com.securetransfer.api.web.dto.CreateTransferRequest;
import com.securetransfer.api.web.dto.TransferResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core of the system: moving money safely, and recording the idempotency
 * result in the SAME transaction.
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public TransferService(AccountRepository accounts,
                           TransferRepository transfers,
                           LedgerEntryRepository ledgerEntries,
                           IdempotencyService idempotency,
                           ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledgerEntries = ledgerEntries;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute a transfer atomically and mark its idempotency key COMPLETED.
     *
     * @Transactional makes the whole method ONE transaction: the transfers row,
     * the two ledger rows, both balance updates, AND flipping the idempotency
     * key to COMPLETED with its response snapshot all commit together — or, if
     * anything throws, all roll back together. The key is only ever "done" if
     * the money actually moved.
     */
    @Transactional
    public TransferResponse execute(String idempotencyKey, CreateTransferRequest request, String requestHash) {
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

        // Record the transfer (linked to its idempotency key) so we have its id.
        Transfer transfer = transfers.save(new Transfer(
                idempotencyKey, fromId, toId, request.amount(), TransferStatus.COMPLETED));

        // Move the money.
        from.debit(request.amount());
        to.credit(request.amount());

        // Double-entry: one DEBIT out of the sender, one CREDIT into the receiver.
        ledgerEntries.save(new LedgerEntry(
                transfer.getId(), from.getId(), LedgerDirection.DEBIT, request.amount()));
        ledgerEntries.save(new LedgerEntry(
                transfer.getId(), to.getId(), LedgerDirection.CREDIT, request.amount()));

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
