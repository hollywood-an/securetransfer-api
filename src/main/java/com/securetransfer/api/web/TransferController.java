package com.securetransfer.api.web;

import com.securetransfer.api.service.TransferService;
import com.securetransfer.api.web.dto.CreateTransferRequest;
import com.securetransfer.api.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web layer for transfers. Validates the request and delegates to the service;
 * all the money logic (locking, atomicity, ledger) lives in TransferService.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    // POST /transfers — move money between two accounts. 201 Created on success.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse create(@Valid @RequestBody CreateTransferRequest request) {
        return transferService.transfer(request);
    }
}
