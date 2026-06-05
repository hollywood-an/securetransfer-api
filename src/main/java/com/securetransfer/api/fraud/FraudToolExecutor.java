package com.securetransfer.api.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetransfer.api.config.FraudProperties;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.repository.AccountRepository;
import com.securetransfer.api.repository.TransferRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the agent's THREE read-only tools. This is the core guardrail: the
 * executor is constructed only with repositories and calls only their READ
 * methods — there is no code path here that can write, move money, freeze an
 * account, or change a transfer. The agent can look, never touch.
 *
 * Each call runs in its own short read-only transaction.
 */
@Component
public class FraudToolExecutor {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FraudProperties props;
    private final ObjectMapper objectMapper;

    public FraudToolExecutor(AccountRepository accounts,
                             TransferRepository transfers,
                             FraudProperties props,
                             ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Dispatch a tool call by name. Returns a JSON string for the model. */
    @Transactional(readOnly = true)
    public String execute(String toolName, Map<String, Object> input) {
        return switch (toolName) {
            case "get_account" -> getAccount(toLong(input.get("accountId")));
            case "get_transaction_history" ->
                    getTransactionHistory(toLong(input.get("accountId")), toIntOrDefault(input.get("limit"), 10));
            case "get_velocity_stats" -> getVelocityStats(toLong(input.get("accountId")));
            default -> toJson(Map.of("error", "unknown tool: " + toolName));
        };
    }

    private String getAccount(Long accountId) {
        if (accountId == null) {
            return toJson(Map.of("error", "accountId is required"));
        }
        return accounts.findById(accountId)
                .map(a -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("accountId", a.getId());
                    out.put("customerId", a.getCustomer().getId());
                    out.put("currency", a.getCurrency());
                    out.put("balance", a.getBalance());
                    out.put("status", a.getStatus().name());
                    return toJson(out);
                })
                .orElseGet(() -> toJson(Map.of("error", "account " + accountId + " not found")));
    }

    private String getTransactionHistory(Long accountId, int limit) {
        if (accountId == null) {
            return toJson(Map.of("error", "accountId is required"));
        }
        int capped = Math.min(Math.max(1, limit), 50);
        List<Transfer> recent = transfers.findByFromAccountOrToAccountOrderByCreatedAtDescIdDesc(
                accountId, accountId, PageRequest.of(0, capped));
        List<Map<String, Object>> rows = recent.stream().map(t -> {
            boolean outgoing = t.getFromAccount().equals(accountId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transferId", t.getId());
            row.put("direction", outgoing ? "OUT" : "IN");
            row.put("amount", t.getAmount());
            row.put("counterpartyAccount", outgoing ? t.getToAccount() : t.getFromAccount());
            row.put("status", t.getStatus().name());
            row.put("createdAt", t.getCreatedAt());
            return row;
        }).toList();
        return toJson(Map.of("accountId", accountId, "count", rows.size(), "transactions", rows));
    }

    private String getVelocityStats(Long accountId) {
        if (accountId == null) {
            return toJson(Map.of("error", "accountId is required"));
        }
        int windowMinutes = props.getVelocityWindowMinutes();
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(windowMinutes);
        long count = transfers.countByFromAccountAndCreatedAtAfter(accountId, after);
        BigDecimal total = transfers.sumOutgoingSince(accountId, after);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("windowMinutes", windowMinutes);
        out.put("outgoingCount", count);
        out.put("outgoingTotal", total);
        return toJson(out);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"could not serialize tool result\"}";
        }
    }

    // Tool inputs arrive as JSON numbers/strings; coerce to Long defensively.
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toIntOrDefault(Object value, int fallback) {
        Long l = toLong(value);
        return l == null ? fallback : l.intValue();
    }
}
