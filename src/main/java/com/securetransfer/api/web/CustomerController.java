package com.securetransfer.api.web;

import com.securetransfer.api.service.CustomerService;
import com.securetransfer.api.web.dto.CreateCustomerRequest;
import com.securetransfer.api.web.dto.CustomerResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller = the web layer. It maps HTTP requests to Java methods, validates
 * the incoming body (@Valid), and delegates to the service. It holds NO business
 * logic — that lives in CustomerService.
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    // POST /customers — create a customer. 201 Created on success.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CreateCustomerRequest request) {
        return customerService.create(request);
    }
}
