package com.example.ledger.account.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param openingBalance initial funds; recorded immutably on the account so that
 *                       balance = opening_balance + credits - debits can always be verified.
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "may contain only letters, digits, '.', '_' and '-'") String accountNumber,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code such as USD") String currency,
        BigDecimal openingBalance) {
}
