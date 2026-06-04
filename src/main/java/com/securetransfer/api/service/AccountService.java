package com.securetransfer.api.service;

import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.Customer;
import com.securetransfer.api.error.ForbiddenException;
import com.securetransfer.api.error.NotFoundException;
import com.securetransfer.api.repository.AccountRepository;
import com.securetransfer.api.repository.CustomerRepository;
import com.securetransfer.api.repository.LedgerEntryRepository;
import com.securetransfer.api.web.dto.AccountResponse;
import com.securetransfer.api.web.dto.CreateAccountRequest;
import com.securetransfer.api.web.dto.LedgerEntryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Business logic for accounts: creating them, reading them back (balance +
 * ledger), and admin freeze/unfreeze.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final LedgerEntryRepository ledgerEntries;
    private final AuditService auditService;

    public AccountService(AccountRepository accounts,
                          CustomerRepository customers,
                          LedgerEntryRepository ledgerEntries,
                          AuditService auditService) {
        this.accounts = accounts;
        this.customers = customers;
        this.ledgerEntries = ledgerEntries;
        this.auditService = auditService;
    }

    /** Create an account for an existing customer. */
    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        // The owner must exist, or it's a 404 (not a 500).
        Customer owner = customers.findById(request.customerId())
                .orElseThrow(() -> new NotFoundException(
                        "Customer " + request.customerId() + " not found"));

        // initialBalance is optional → treat a missing value as 0.
        BigDecimal initial = request.initialBalance() == null
                ? BigDecimal.ZERO
                : request.initialBalance();

        Account saved = accounts.save(new Account(owner, request.currency(), initial));

        // A brand-new account has no ledger history yet.
        return AccountResponse.from(saved, List.of());
    }

    /**
     * Read an account's current balance, status, and ledger history.
     *
     * readOnly = true tells Spring/Hibernate this transaction won't modify data.
     * We map entities to DTOs inside the transaction so lazy data is reachable.
     */
    @Transactional(readOnly = true)
    public AccountResponse getById(Long id, Long restrictToCustomerId) {
        Account account = accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));

        // A CUSTOMER (restrictToCustomerId != null) may only see their own
        // accounts; staff (null) may see any.
        if (restrictToCustomerId != null
                && !account.getCustomer().getId().equals(restrictToCustomerId)) {
            throw new ForbiddenException("You may only view your own accounts");
        }

        return AccountResponse.from(account, loadLedger(id));
    }

    /**
     * Assert that {@code customerId} owns the account: 404 if the account doesn't
     * exist, 403 if it belongs to someone else. Used to stop a CUSTOMER from
     * transferring FROM an account that isn't theirs.
     */
    @Transactional(readOnly = true)
    public void assertCustomerOwns(Long accountId, Long customerId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
        if (!account.getCustomer().getId().equals(customerId)) {
            throw new ForbiddenException("You may only transfer from your own accounts");
        }
    }

    /**
     * Freeze or unfreeze an account (an ADMIN action) and record it in the audit
     * log — both in ONE transaction, so the status change and its audit entry
     * commit together. {@code actor} is the authenticated admin's username.
     */
    @Transactional
    public AccountResponse setFrozen(Long accountId, boolean frozen, String actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));

        if (frozen) {
            account.freeze();
        } else {
            account.unfreeze();
        }

        auditService.record(
                actor,
                frozen ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN",
                "account:" + accountId,
                Map.of("accountId", accountId, "status", account.getStatus().name()));

        return AccountResponse.from(account, loadLedger(accountId));
    }

    private List<LedgerEntryResponse> loadLedger(Long accountId) {
        return ledgerEntries.findByAccountIdOrderByCreatedAtDescIdDesc(accountId).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }
}
