package com.example.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AccountTest {

    private final Account account = new Account(UUID.randomUUID(), "ACC-1", "USD",
            new BigDecimal("100.00"), new BigDecimal("100.00"), AccountStatus.ACTIVE, Instant.EPOCH);

    @Test
    void debitAndCreditReturnNewSnapshotsWithExactArithmetic() {
        Account debited = account.debit(new BigDecimal("0.10"));
        Account credited = debited.credit(new BigDecimal("0.20"));
        assertThat(debited.balance()).isEqualByComparingTo("99.90");
        assertThat(credited.balance()).isEqualByComparingTo("100.10");
        assertThat(account.balance()).isEqualByComparingTo("100.00"); // original unchanged
    }

    @Test
    void debitCannotMakeBalanceNegative() {
        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01"))).isInstanceOf(IllegalStateException.class);
        assertThat(account.debit(new BigDecimal("100.00")).balance()).isEqualByComparingTo("0");
    }

    @Test
    void nonPositiveMovementsAreRejected() {
        assertThatThrownBy(() -> account.credit(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canCoverIsInclusive() {
        assertThat(account.canCover(new BigDecimal("100.00"))).isTrue();
        assertThat(account.canCover(new BigDecimal("100.01"))).isFalse();
    }
}
