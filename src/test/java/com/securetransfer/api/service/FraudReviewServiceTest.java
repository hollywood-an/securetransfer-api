package com.securetransfer.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.FraudReview;
import com.securetransfer.api.domain.FraudReviewStatus;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.fraud.FraudContext;
import com.securetransfer.api.repository.AccountRepository;
import com.securetransfer.api.repository.FraudReviewRepository;
import com.securetransfer.api.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Unit tests for {@link FraudReviewService#loadContextIfPending}, focused on the
 * PRE-transfer balance the agent receives.
 *
 * <p>Money moves even when a transfer is flagged, so an account's live balance is
 * already post-debit. Feeding the agent only that made it hallucinate "insufficient
 * funds / overdraft" on transfers that had clearly passed the funds check. The fix
 * reconstructs the sender's balance before this transfer (current balance + amount)
 * so the agent reasons on the right number.
 *
 * <p>These are plain Mockito unit tests — no Spring context, no database.
 */
class FraudReviewServiceTest {

    private final FraudReviewRepository reviews = mock(FraudReviewRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private final FraudReviewService service =
            new FraudReviewService(reviews, transfers, accounts, auditService);

    @Test
    @DisplayName("Reconstructs the sender's pre-transfer balance (current + amount) for the agent")
    void loadContextComputesPreTransferBalance() {
        // The exact scenario that used to hallucinate an overdraft: a $49,000 drain
        // leaving the sender with only $1,000 (the live, post-debit balance).
        FraudReview review = mock(FraudReview.class);
        when(review.getStatus()).thenReturn(FraudReviewStatus.PENDING);
        when(review.getTransferId()).thenReturn(7L);
        when(review.getFlagReasons()).thenReturn(List.of("LARGE_AMOUNT", "NEW_PAYEE"));
        when(reviews.findById(99L)).thenReturn(Optional.of(review));

        Transfer transfer = mock(Transfer.class);
        when(transfer.getId()).thenReturn(7L);
        when(transfer.getFromAccount()).thenReturn(5L);
        when(transfer.getToAccount()).thenReturn(8L);
        when(transfer.getAmount()).thenReturn(new BigDecimal("49000.00"));
        when(transfers.findById(7L)).thenReturn(Optional.of(transfer));

        Account sender = mock(Account.class);
        when(sender.getBalance()).thenReturn(new BigDecimal("1000.00")); // post-debit
        when(accounts.findById(5L)).thenReturn(Optional.of(sender));

        FraudContext ctx = service.loadContextIfPending(99L).orElseThrow();

        // 1,000 (current) + 49,000 (this transfer) = 50,000 before the transfer.
        assertThat(ctx.fromAccountBalanceBeforeTransfer()).isEqualByComparingTo("50000.00");
        assertThat(ctx.amount()).isEqualByComparingTo("49000.00");
        assertThat(ctx.fromAccount()).isEqualTo(5L);
        assertThat(ctx.flagReasons()).containsExactly("LARGE_AMOUNT", "NEW_PAYEE");
    }

    @Test
    @DisplayName("Leaves the pre-transfer balance null (no error) when the sender account is missing")
    void loadContextToleratesMissingSender() {
        FraudReview review = mock(FraudReview.class);
        when(review.getStatus()).thenReturn(FraudReviewStatus.PENDING);
        when(review.getTransferId()).thenReturn(7L);
        when(review.getFlagReasons()).thenReturn(List.of("LARGE_AMOUNT"));
        when(reviews.findById(99L)).thenReturn(Optional.of(review));

        Transfer transfer = mock(Transfer.class);
        when(transfer.getId()).thenReturn(7L);
        when(transfer.getFromAccount()).thenReturn(5L);
        when(transfer.getToAccount()).thenReturn(8L);
        when(transfer.getAmount()).thenReturn(new BigDecimal("12000.00"));
        when(transfers.findById(7L)).thenReturn(Optional.of(transfer));
        when(accounts.findById(5L)).thenReturn(Optional.empty());

        FraudContext ctx = service.loadContextIfPending(99L).orElseThrow();

        assertThat(ctx.fromAccountBalanceBeforeTransfer()).isNull();
        assertThat(ctx.amount()).isEqualByComparingTo("12000.00");
    }

    @Test
    @DisplayName("Returns empty when the review is no longer pending (e.g. a human already decided)")
    void loadContextEmptyWhenNotPending() {
        FraudReview review = mock(FraudReview.class);
        when(review.getStatus()).thenReturn(FraudReviewStatus.DECIDED);
        when(reviews.findById(99L)).thenReturn(Optional.of(review));

        assertThat(service.loadContextIfPending(99L)).isEmpty();
    }
}
