package com.securetransfer.api.service;

import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.Customer;
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

/**
 * Business logic for accounts: creating them and reading them back (balance +
 * ledger history).
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final LedgerEntryRepository ledgerEntries;

    public AccountService(AccountRepository accounts,
                          CustomerRepository customers,
                          LedgerEntryRepository ledgerEntries) {
        this.accounts = accounts;
        this.customers = customers;
        this.ledgerEntries = ledgerEntries;
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
     * Read an account's current balance plus its ledger history.
     *
     * readOnly = true tells Spring/Hibernate this transaction won't modify data
     * (a small optimisation, and a clear signal of intent). We map entities to
     * DTOs inside the transaction so any lazy data is still reachable.
     */
    @Transactional(readOnly = true)
    public AccountResponse getById(Long id) {
        Account account = accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));

        List<LedgerEntryResponse> ledger =
                ledgerEntries.findByAccountIdOrderByCreatedAtDescIdDesc(id).stream()
                        .map(LedgerEntryResponse::from)
                        .toList();

        return AccountResponse.from(account, ledger);
    }
}
