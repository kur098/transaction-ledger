package com.example.ledger.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.ledger.common.Money;
import com.example.ledger.transfer.TransferRequest;

/**
 * Deterministic SHA-256 over the canonical request fields (spec 7.2). Amounts are canonicalized
 * to scale 2 so "150", "150.0" and "150.00" are the same request.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /** @param request a request already normalized by {@code TransferValidator.validateAndNormalize}. */
    public static String of(TransferRequest request) {
        String canonical = String.join("\n",
                "v1",
                request.sourceAccountId().toString(),
                request.destinationAccountId().toString(),
                Money.format(request.amount()),
                request.currency(),
                request.reference() == null ? "" : request.reference());
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
