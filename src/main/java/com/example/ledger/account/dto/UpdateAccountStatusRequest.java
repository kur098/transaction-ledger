package com.example.ledger.account.dto;

import com.example.ledger.account.AccountStatus;

import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(@NotNull AccountStatus status) {
}
