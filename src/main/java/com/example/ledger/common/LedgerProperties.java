package com.example.ledger.common;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        @DefaultValue("USD") Set<String> supportedCurrencies,
        @DefaultValue Transfer transfer,
        @DefaultValue Reconciliation reconciliation) {

    public LedgerProperties {
        supportedCurrencies = supportedCurrencies.stream()
                .map(c -> c.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Transfer(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("25") long retryBackoffMillis,
            @DefaultValue("10") int transactionTimeoutSeconds) {
    }

    public record Reconciliation(
            @DefaultValue("3600") int defaultTimeToleranceSeconds,
            @DefaultValue("200000") int maxRows) {
    }
}
