package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountStatus;
import com.example.ledger.common.DomainException;
import com.example.ledger.common.ErrorCategory;
import com.example.ledger.transfer.exception.AccountNotActiveException;
import com.example.ledger.transfer.exception.CurrencyMismatchException;
import com.example.ledger.transfer.exception.InsufficientFundsException;
import com.example.ledger.transfer.exception.InvalidTransferException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for spec 11.1 transfer validity cases. */
class TransferValidatorTest {

    private static final Set<String> CURRENCIES = Set.of("USD", "EUR");
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static TransferRequest request(String amount) {
        return new TransferRequest(A, B, amount == null ? null : new BigDecimal(amount), "USD", null);
    }

    @Nested
    class RequestRules {

        @Test
        void positiveAmountIsAcceptedAndNormalizedToTwoDecimals() {
            TransferRequest normalized = TransferValidator.validateAndNormalize(request("150"), CURRENCIES);
            assertThat(normalized.amount()).isEqualByComparingTo("150.00");
            assertThat(normalized.amount().scale()).isEqualTo(2);
        }

        @Test
        void smallestUnitIsAccepted() {
            assertThatCode(() -> TransferValidator.validateAndNormalize(request("0.01"), CURRENCIES))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
        void zeroOrNegativeAmountIsRejected(String amount) {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(request(amount), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class)
                    .extracting(e -> ((DomainException) e).getCode())
                    .isEqualTo("INVALID_AMOUNT");
        }

        @ParameterizedTest
        @ValueSource(strings = {"1.001", "0.005", "123456789012345678.00"})
        void amountThatCannotBeRepresentedExactlyIsRejected(String amount) {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(request(amount), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class)
                    .hasMessageContaining("decimal places");
        }

        @Test
        void trailingZerosBeyondScaleAreNotRounding() {
            TransferRequest normalized = TransferValidator.validateAndNormalize(request("10.5000"), CURRENCIES);
            assertThat(normalized.amount()).isEqualByComparingTo("10.50");
        }

        @Test
        void missingAmountIsRejected() {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(request(null), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class);
        }

        @Test
        void identicalSourceAndDestinationAreRejected() {
            TransferRequest same = new TransferRequest(A, A, new BigDecimal("10.00"), "USD", null);
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(same, CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class)
                    .extracting(e -> ((DomainException) e).getCode())
                    .isEqualTo("SAME_ACCOUNT");
        }

        @Test
        void missingAccountIdsAreRejected() {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(
                    new TransferRequest(null, B, BigDecimal.TEN, "USD", null), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class);
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(
                    new TransferRequest(A, null, BigDecimal.TEN, "USD", null), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class);
        }

        @Test
        void currencyIsNormalizedToUpperCase() {
            TransferRequest normalized = TransferValidator.validateAndNormalize(
                    new TransferRequest(A, B, BigDecimal.TEN, " usd ", null), CURRENCIES);
            assertThat(normalized.currency()).isEqualTo("USD");
        }

        @ParameterizedTest
        @ValueSource(strings = {"US", "USDX", "U5D", ""})
        void malformedCurrencyIsRejected(String currency) {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(
                    new TransferRequest(A, B, BigDecimal.TEN, currency, null), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class);
        }

        @Test
        void unsupportedCurrencyIsRejected() {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(
                    new TransferRequest(A, B, BigDecimal.TEN, "JPY", null), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class)
                    .extracting(e -> ((DomainException) e).getCode())
                    .isEqualTo("UNSUPPORTED_CURRENCY");
        }

        @Test
        void blankReferenceBecomesNullAndReferenceIsTrimmed() {
            assertThat(TransferValidator.validateAndNormalize(
                    new TransferRequest(A, B, BigDecimal.TEN, "USD", "   "), CURRENCIES).reference()).isNull();
            assertThat(TransferValidator.validateAndNormalize(
                    new TransferRequest(A, B, BigDecimal.TEN, "USD", " EXT-1 "), CURRENCIES).reference())
                    .isEqualTo("EXT-1");
        }

        @Test
        void referenceWithSpacesOrTooLongIsRejected() {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(
                    new TransferRequest(A, B, BigDecimal.TEN, "USD", "EXT 1"), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class);
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(
                    new TransferRequest(A, B, BigDecimal.TEN, "USD", "X".repeat(65)), CURRENCIES))
                    .isInstanceOf(InvalidTransferException.class);
        }

        @Test
        void idempotencyKeyRules() {
            assertThat(TransferValidator.validateIdempotencyKey("abc-123")).isEqualTo("abc-123");
            assertThatThrownBy(() -> TransferValidator.validateIdempotencyKey(null))
                    .isInstanceOf(InvalidTransferException.class);
            assertThatThrownBy(() -> TransferValidator.validateIdempotencyKey(""))
                    .isInstanceOf(InvalidTransferException.class);
            assertThatThrownBy(() -> TransferValidator.validateIdempotencyKey("has space"))
                    .isInstanceOf(InvalidTransferException.class);
            assertThatThrownBy(() -> TransferValidator.validateIdempotencyKey("k".repeat(129)))
                    .isInstanceOf(InvalidTransferException.class);
        }

        @Test
        void clientIdDefaultsAndValidation() {
            assertThat(TransferValidator.normalizeClientId(null)).isEqualTo("default");
            assertThat(TransferValidator.normalizeClientId(" partner-1 ")).isEqualTo("partner-1");
            assertThatThrownBy(() -> TransferValidator.normalizeClientId("bad id!"))
                    .isInstanceOf(InvalidTransferException.class);
        }

        @Test
        void validationErrorsMapToHttp400Category() {
            assertThatThrownBy(() -> TransferValidator.validateAndNormalize(request("0"), CURRENCIES))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).getCategory())
                    .isEqualTo(ErrorCategory.VALIDATION);
        }
    }

    @Nested
    class AccountRules {

        private Account account(UUID id, String currency, String balance, AccountStatus status) {
            return new Account(id, "ACC-" + id.toString().substring(0, 4), currency, new BigDecimal(balance),
                    new BigDecimal(balance), status, Instant.EPOCH);
        }

        private final TransferRequest hundred = new TransferRequest(A, B, new BigDecimal("100.00"), "USD", null);

        @Test
        void validAccountsWithExactFundsPass() {
            assertThatCode(() -> TransferValidator.validateAccounts(
                    account(A, "USD", "100.00", AccountStatus.ACTIVE),
                    account(B, "USD", "0.00", AccountStatus.ACTIVE), hundred))
                    .doesNotThrowAnyException();
        }

        @Test
        void insufficientFundsIsRejectedAsBusinessRule() {
            assertThatThrownBy(() -> TransferValidator.validateAccounts(
                    account(A, "USD", "99.99", AccountStatus.ACTIVE),
                    account(B, "USD", "0.00", AccountStatus.ACTIVE), hundred))
                    .isInstanceOf(InsufficientFundsException.class)
                    .extracting(e -> ((DomainException) e).getCategory())
                    .isEqualTo(ErrorCategory.BUSINESS_RULE);
        }

        @Test
        void inactiveSourceOrDestinationIsRejected() {
            assertThatThrownBy(() -> TransferValidator.validateAccounts(
                    account(A, "USD", "500.00", AccountStatus.FROZEN),
                    account(B, "USD", "0.00", AccountStatus.ACTIVE), hundred))
                    .isInstanceOf(AccountNotActiveException.class);
            assertThatThrownBy(() -> TransferValidator.validateAccounts(
                    account(A, "USD", "500.00", AccountStatus.ACTIVE),
                    account(B, "USD", "0.00", AccountStatus.CLOSED), hundred))
                    .isInstanceOf(AccountNotActiveException.class);
        }

        @Test
        void currencyMismatchBetweenAccountsIsRejected() {
            assertThatThrownBy(() -> TransferValidator.validateAccounts(
                    account(A, "USD", "500.00", AccountStatus.ACTIVE),
                    account(B, "EUR", "0.00", AccountStatus.ACTIVE), hundred))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        void currencyMismatchBetweenRequestAndAccountsIsRejected() {
            TransferRequest eur = new TransferRequest(A, B, new BigDecimal("100.00"), "EUR", null);
            assertThatThrownBy(() -> TransferValidator.validateAccounts(
                    account(A, "USD", "500.00", AccountStatus.ACTIVE),
                    account(B, "USD", "0.00", AccountStatus.ACTIVE), eur))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }
}
