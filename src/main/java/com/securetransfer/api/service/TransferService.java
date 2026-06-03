package com.securetransfer.api.service;

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
 * The core of the system: moving money safely.
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;

    public TransferService(AccountRepository accounts,
                           TransferRepository transfers,
                           LedgerEntryRepository ledgerEntries) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledgerEntries = ledgerEntries;
    }

    /**
     * Execute a transfer atomically.
     *
     * @Transactional makes the entire method ONE database transaction: every
     * write below (the transfers row, the two ledger rows, both balance
     * updates) either all commit together, or — if anything throws — all roll
     * back together. There is no way to leave money half-moved.
     */
    @Transactional
    public TransferResponse transfer(CreateTransferRequest request) {
        Long fromId = request.fromAccount();
        Long toId = request.toAccount();

        // A transfer to the same account is meaningless (the DB also forbids it
        // via a CHECK constraint). Reject early as a 400.
        if (fromId.equals(toId)) {
            throw new BadRequestException("fromAccount and toAccount must be different");
        }

        // Lock BOTH account rows before reading balances, ALWAYS in ascending id
        // order. Consistent ordering is what prevents a deadlock between an
        // A->B transfer and a simultaneous B->A transfer.
        Long firstId = Math.min(fromId, toId);
        Long secondId = Math.max(fromId, toId);
        Account firstLocked = lockAccount(firstId);
        Account secondLocked = lockAccount(secondId);

        // Figure out which locked account is the sender and which is the receiver.
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

        // Record the transfer first so we have its id for the ledger rows.
        Transfer transfer = transfers.save(
                new Transfer(fromId, toId, request.amount(), TransferStatus.COMPLETED));

        // Move the money. These mutate the locked, managed entities; Hibernate
        // flushes the UPDATEs when the transaction commits.
        from.debit(request.amount());
        to.credit(request.amount());

        // Double-entry: one DEBIT out of the sender and one CREDIT into the
        // receiver — equal amounts that net to zero.
        ledgerEntries.save(new LedgerEntry(
                transfer.getId(), from.getId(), LedgerDirection.DEBIT, request.amount()));
        ledgerEntries.save(new LedgerEntry(
                transfer.getId(), to.getId(), LedgerDirection.CREDIT, request.amount()));

        return TransferResponse.from(transfer, from, to);
    }

    /** Load an account with a pessimistic write lock, or 404 if it doesn't exist. */
    private Account lockAccount(Long id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }
}
