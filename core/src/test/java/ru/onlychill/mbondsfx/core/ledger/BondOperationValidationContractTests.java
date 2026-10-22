package ru.onlychill.mbondsfx.core.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Each BondOperations guard rejects its own bad input, so the exact message identifies the guard. */
final class BondOperationValidationContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
    private static final PortfolioId PORTFOLIO = new PortfolioId("validation-portfolio");
    private static final SecurityId SECURITY = new SecurityId("VALIDATION-SEC");
    private static final BondOperations OPERATIONS = BondAccountingPolicy.standard().operations();

    private static final String NON_EMPTY = "Operation requires a non-empty position";
    private static final String POSITIVE_QUANTITY = "quantity must be positive";
    private static final String FOREIGN_CURRENCY = " currency does not match position/operation currency";

    @Test
    void buyRejectsEachInvalidInputWithItsOwnMessage() {
        Position empty = Position.empty(positionId(), RUB);
        assertRejected(POSITIVE_QUANTITY, () -> OPERATIONS.buy(id("buy-zero-qty"), DAY, empty, 0,
                m("100"), m("0"), m("100"), m("0")));
        assertRejected(POSITIVE_QUANTITY, () -> OPERATIONS.buy(id("buy-negative-qty"), DAY, empty, -1,
                m("100"), m("0"), m("100"), m("0")));
        assertRejected("cleanPricePerUnit must not be negative", () -> OPERATIONS.buy(id("buy-clean"), DAY, empty, 1,
                m("-1"), m("0"), m("100"), m("0")));
        assertRejected("accruedPerUnit must not be negative", () -> OPERATIONS.buy(id("buy-accrued"), DAY, empty, 1,
                m("100"), m("-1"), m("100"), m("0")));
        assertRejected("facePerUnit must be positive", () -> OPERATIONS.buy(id("buy-face-zero"), DAY, empty, 1,
                m("100"), m("0"), m("0"), m("0")));
        assertRejected("cleanPricePerUnit" + FOREIGN_CURRENCY, () -> OPERATIONS.buy(id("buy-clean-usd"), DAY, empty, 1,
                usd("100"), m("0"), m("100"), m("0")));
        assertRejected("brokerCommission" + FOREIGN_CURRENCY, () -> OPERATIONS.buy(id("buy-broker-usd"), DAY, empty, 1,
                m("100"), m("0"), m("100"), usd("1")));
    }

    @Test
    void sellRejectsEachInvalidInputWithItsOwnMessage() {
        Position one = new Position(positionId(), 1, m("100"), m("100"), m("10"));
        Position empty = Position.empty(positionId(), RUB);
        assertRejected(NON_EMPTY, () -> OPERATIONS.sell(id("sell-empty"), DAY, empty, 1,
                m("100"), m("0"), m("0"), Optional.empty()));
        assertRejected(POSITIVE_QUANTITY, () -> OPERATIONS.sell(id("sell-zero-qty"), DAY, one, 0,
                m("100"), m("0"), m("0"), Optional.empty()));
        assertRejected("cleanPricePerUnit must not be negative", () -> OPERATIONS.sell(id("sell-clean"), DAY, one, 1,
                m("-1"), m("0"), m("0"), Optional.empty()));
        assertRejected("accruedReceivedPerUnit must not be negative", () -> OPERATIONS.sell(id("sell-accrued"), DAY,
                one, 1, m("100"), m("-1"), m("0"), Optional.empty()));
        assertRejected("brokerCommission" + FOREIGN_CURRENCY, () -> OPERATIONS.sell(id("sell-broker-usd"), DAY, one, 1,
                m("100"), m("0"), usd("1"), Optional.empty()));
        assertRejected("taxOnGainTotal must not be negative", () -> OPERATIONS.sell(id("sell-tax"), DAY, one, 1,
                m("100"), m("0"), m("0"), Optional.of(m("-1"))));
    }

    @Test
    void couponAndAmortizationRejectInvalidTotalsWithItsOwnMessage() {
        Position one = new Position(positionId(), 1, m("100"), m("100"), m("10"));
        Position empty = Position.empty(positionId(), RUB);
        assertRejected(NON_EMPTY, () -> OPERATIONS.coupon(id("coupon-empty"), DAY, empty, m("10"), Optional.empty()));
        assertRejected("grossCouponTotal must not be negative", () -> OPERATIONS.coupon(id("coupon-gross"), DAY, one,
                m("-1"), Optional.empty()));
        assertRejected("taxWithheldTotal must not be negative", () -> OPERATIONS.coupon(id("coupon-tax"), DAY, one,
                m("10"), Optional.of(m("-1"))));
        assertRejected(NON_EMPTY, () -> OPERATIONS.amortization(id("amort-empty"), DAY, empty, m("10"), m("10"),
                Optional.empty()));
        assertRejected("faceReductionTotal must be positive", () -> OPERATIONS.amortization(id("amort-zero"), DAY, one,
                m("0"), m("0"), Optional.empty()));
        assertRejected("paymentTotal must not be negative", () -> OPERATIONS.amortization(id("amort-payment"), DAY, one,
                m("10"), m("-1"), Optional.empty()));
        assertRejected("taxWithheldTotal must not be negative", () -> OPERATIONS.amortization(id("amort-tax"), DAY, one,
                m("10"), m("100"), Optional.of(m("-1"))));
    }

    @Test
    void cashOperationsRejectNonPositiveAndForeignCurrencyAmounts() {
        assertRejected("amount must be positive", () -> OPERATIONS.opening(id("open-zero"), DAY, PORTFOLIO, RUB,
                m("0")));
        assertRejected("amount must be positive", () -> OPERATIONS.deposit(id("deposit-zero"), DAY, PORTFOLIO, RUB,
                m("0")));
        assertRejected("amount must be positive", () -> OPERATIONS.withdrawal(id("withdraw-negative"), DAY, PORTFOLIO,
                RUB, m("-1")));
        assertRejected("amount" + FOREIGN_CURRENCY, () -> OPERATIONS.deposit(id("deposit-usd"), DAY, PORTFOLIO, RUB,
                usd("1")));
    }

    private static void assertRejected(String message, Supplier<?> operation) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, operation::get);
        assertEquals(message, thrown.getMessage());
    }

    private static PositionId positionId() {
        return new PositionId(PORTFOLIO, SECURITY);
    }

    private static OperationId id(String value) {
        return new OperationId(value);
    }

    private static Money m(String value) {
        return new Money(new BigDecimal(value), RUB);
    }

    private static Money usd(String value) {
        return new Money(new BigDecimal(value), USD);
    }
}
