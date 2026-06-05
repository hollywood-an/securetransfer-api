package com.securetransfer.api.web;

import com.securetransfer.api.domain.FraudReview;
import com.securetransfer.api.domain.FraudReviewStatus;
import com.securetransfer.api.security.AuthenticatedUser;
import com.securetransfer.api.service.FraudReviewService;
import com.securetransfer.api.web.dto.FraudReviewResponse;
import com.securetransfer.api.web.dto.PagedResponse;
import com.securetransfer.api.web.dto.RecordDecisionRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fraud-review queue and the human-in-the-loop decision endpoint.
 *
 * TELLER/ADMIN only (class-level @PreAuthorize). The agent only ever produced a
 * recommendation; a human acts here, and every decision is audit-logged.
 */
@RestController
@RequestMapping("/fraud-reviews")
@PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
public class FraudReviewController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FraudReviewService fraudReviewService;

    public FraudReviewController(FraudReviewService fraudReviewService) {
        this.fraudReviewService = fraudReviewService;
    }

    // GET /fraud-reviews?status=&page=&size= — the review queue, newest first.
    @GetMapping
    public PagedResponse<FraudReviewResponse> list(
            @RequestParam(required = false) FraudReviewStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<FraudReview> result = fraudReviewService.list(status, pageable);
        return PagedResponse.from(result.map(FraudReviewResponse::from));
    }

    // GET /fraud-reviews/{id} — one review (404 if unknown).
    @GetMapping("/{id}")
    public FraudReviewResponse getById(@PathVariable Long id) {
        return FraudReviewResponse.from(fraudReviewService.getById(id));
    }

    // POST /fraud-reviews/{id}/decision — record the human's final decision.
    @PostMapping("/{id}/decision")
    public FraudReviewResponse decide(@PathVariable Long id,
                                      @Valid @RequestBody RecordDecisionRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser currentUser) {
        FraudReview updated = fraudReviewService.recordDecision(
                id, request.decision(), currentUser.getUsername());
        return FraudReviewResponse.from(updated);
    }
}
