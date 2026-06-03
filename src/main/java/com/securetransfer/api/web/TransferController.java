package com.securetransfer.api.web;

import com.securetransfer.api.error.BadRequestException;
import com.securetransfer.api.service.IdempotentTransferService;
import com.securetransfer.api.web.dto.CreateTransferRequest;
import com.securetransfer.api.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web layer for transfers. Reads the required Idempotency-Key header, validates
 * the body, and delegates to the idempotency-aware service; all the money logic
 * lives below this layer.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final IdempotentTransferService transferService;

    public TransferController(IdempotentTransferService transferService) {
        this.transferService = transferService;
    }

    // POST /transfers — move money. Requires an Idempotency-Key header so a
    // retried/double-clicked request can't move money twice. 201 Created on
    // success (or on an idempotent replay of an earlier success).
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new BadRequestException("Idempotency-Key must be at most 255 characters");
        }
        return transferService.transfer(idempotencyKey, request);
    }
}
