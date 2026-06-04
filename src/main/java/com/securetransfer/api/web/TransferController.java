package com.securetransfer.api.web;

import com.securetransfer.api.error.BadRequestException;
import com.securetransfer.api.security.AuthenticatedUser;
import com.securetransfer.api.service.AccountService;
import com.securetransfer.api.service.IdempotentTransferService;
import com.securetransfer.api.web.dto.CreateTransferRequest;
import com.securetransfer.api.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web layer for transfers. Requires authentication; a CUSTOMER may only send
 * money FROM their own account (checked here before the transfer runs). The
 * money logic itself lives in the services below.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final IdempotentTransferService transferService;
    private final AccountService accountService;

    public TransferController(IdempotentTransferService transferService,
                             AccountService accountService) {
        this.transferService = transferService;
        this.accountService = accountService;
    }

    // POST /transfers — move money. Requires an Idempotency-Key header (Phase 3).
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody CreateTransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new BadRequestException("Idempotency-Key must be at most 255 characters");
        }

        // Authorization: a CUSTOMER may only transfer FROM their own account.
        // (Staff — TELLER/ADMIN — may move money from any account.)
        if (currentUser.isCustomer()) {
            accountService.assertCustomerOwns(request.fromAccount(), currentUser.getCustomerId());
        }

        return transferService.transfer(idempotencyKey, request);
    }
}
