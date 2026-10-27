package com.example.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import com.example.ledger.transfer.TransferRequest;
import com.example.ledger.transfer.TransferValidator;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static String fingerprint(UUID src, UUID dst, String amount, String currency, String reference) {
        TransferRequest normalized = TransferValidator.validateAndNormalize(
                new TransferRequest(src, dst, new BigDecimal(amount), currency, reference), Set.of("USD", "EUR"));
        return RequestFingerprint.of(normalized);
    }

    @Test
    void isDeterministicSha256Hex() {
        String first = fingerprint(A, B, "150.00", "USD", null);
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(fingerprint(A, B, "150.00", "USD", null)).isEqualTo(first);
    }

    @Test
    void equivalentRepresentationsProduceTheSameFingerprint() {
        String canonical = fingerprint(A, B, "150.00", "USD", null);
        assertThat(fingerprint(A, B, "150", "USD", null)).isEqualTo(canonical);
        assertThat(fingerprint(A, B, "150.0", "usd", null)).isEqualTo(canonical);
        assertThat(fingerprint(A, B, "150.00", "USD", "  ")).isEqualTo(canonical);
    }

    @Test
    void everyCanonicalFieldChangesTheFingerprint() {
        String base = fingerprint(A, B, "150.00", "USD", "R1");
        assertThat(fingerprint(B, A, "150.00", "USD", "R1")).isNotEqualTo(base);
        assertThat(fingerprint(A, B, "150.01", "USD", "R1")).isNotEqualTo(base);
        assertThat(fingerprint(A, B, "150.00", "EUR", "R1")).isNotEqualTo(base);
        assertThat(fingerprint(A, B, "150.00", "USD", "R2")).isNotEqualTo(base);
        assertThat(fingerprint(A, B, "150.00", "USD", null)).isNotEqualTo(base);
    }
}
