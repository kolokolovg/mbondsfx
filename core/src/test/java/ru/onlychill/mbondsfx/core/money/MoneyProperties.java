package ru.onlychill.mbondsfx.core.money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of {@link Money}. Addition and subtraction are exact; {@code multiply(BigDecimal)} and {@code divide}
 * round once with HALF_UP to the currency's minor-unit scale; {@code multiply(long)} never rounds; operations between
 * different currencies are rejected. Expectations use BigDecimal arithmetic only and do not reimplement rounding.
 */
final class MoneyProperties {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");
    /** Three minor-unit digits, so rounding is exercised at a scale other than 0 or 2. */
    private static final Currency BHD = Currency.getInstance("BHD");
    private static final List<Currency> CURRENCIES = List.of(RUB, USD, JPY, BHD);
    /** Unscaled bound for amounts: up to 10^15 with up to six fractional digits. */
    private static final long AMOUNT_BOUND = 1_000_000_000_000_000L;
    private static final int AMOUNT_SCALE = 6;
    /** Unscaled bound for factors and divisors: magnitudes up to 10^6 with up to six fractional digits. */
    private static final long FACTOR_BOUND = 1_000_000L;
    private static final int FACTOR_SCALE = 6;
    private static final long WHOLE_BOUND = 1_000_000_000L;

    @Property(tries = 250)
    void additionIsCommutative(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b) {
        Money x = new Money(a, currency);
        Money y = new Money(b, currency);
        assertEquals(x.add(y), y.add(x), "addition is commutative");
    }

    @Property(tries = 250)
    void additionIsAssociative(@ForAll("currencies") Currency currency, @ForAll("amounts") BigDecimal a,
            @ForAll("amounts") BigDecimal b, @ForAll("amounts") BigDecimal c) {
        Money x = new Money(a, currency);
        Money y = new Money(b, currency);
        Money z = new Money(c, currency);
        assertEquals(x.add(y).add(z), x.add(y.add(z)), "addition is associative");
    }

    @Property(tries = 250)
    void zeroIsTheAdditiveIdentityAndSubtractionAddsTheNegation(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b) {
        Money x = new Money(a, currency);
        Money y = new Money(b, currency);
        assertEquals(x, x.add(Money.zero(currency)), "zero is the additive identity");
        assertTrue(x.add(x.negate()).isZero(), "an amount plus its negation is zero");
        assertEquals(x.add(y.negate()), x.subtract(y), "subtraction adds the negation");
        assertEquals(x, x.negate().negate(), "negation is an involution");
    }

    @Property(tries = 250)
    void everyOperationKeepsTheOperandCurrency(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b,
            @ForAll("factors") BigDecimal factor, @ForAll("wholeFactors") long whole,
            @ForAll("divisors") BigDecimal divisor, @ForAll("nonZeroWholeFactors") long wholeDivisor) {
        Money x = new Money(a, currency);
        Money y = new Money(b, currency);
        List<Money> results = List.of(x.add(y), x.subtract(y), x.negate(), x.multiply(whole),
                x.multiply(factor), x.divide(divisor), x.divide(wholeDivisor), Money.zero(currency));
        for (Money result : results) {
            assertEquals(currency, result.currency(), "operation keeps the operand currency");
        }
    }

    @Property(tries = 250)
    void mixedCurrencyArithmeticAndComparisonAreRejected(@ForAll("distinctCurrencyPairs") CurrencyPair pair,
            @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b) {
        Money first = new Money(a, pair.first());
        Money second = new Money(b, pair.second());
        assertCurrencyMismatch(() -> first.add(second));
        assertCurrencyMismatch(() -> first.subtract(second));
        assertCurrencyMismatch(() -> first.compareTo(second));
        assertNotEquals(new Money(a, pair.first()), new Money(a, pair.second()),
                "equal amounts in different currencies are different Money values");
    }

    @Property(tries = 250)
    void amountsAtMinorUnitScaleAreFixedByRounding(@ForAll("currencies") Currency currency,
            @ForAll("minorUnits") long minorUnits) {
        Money amount = new Money(BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits()), currency);
        assertEquals(amount, amount.multiply(BigDecimal.ONE), "multiplying by one keeps a minor-unit amount");
        assertEquals(amount, amount.divide(BigDecimal.ONE), "dividing by one keeps a minor-unit amount");
    }

    @Property(tries = 250)
    void roundingIsIdempotent(@ForAll("currencies") Currency currency, @ForAll("amounts") BigDecimal a,
            @ForAll("factors") BigDecimal factor, @ForAll("divisors") BigDecimal divisor) {
        Money x = new Money(a, currency);
        Money product = x.multiply(factor);
        Money quotient = x.divide(divisor);
        assertEquals(product, product.multiply(BigDecimal.ONE), "a rounded product is already rounded");
        assertEquals(product, product.divide(BigDecimal.ONE), "a rounded product is stable under division rounding");
        assertEquals(quotient, quotient.multiply(BigDecimal.ONE), "a rounded quotient is already rounded");
        assertEquals(quotient, quotient.divide(BigDecimal.ONE),
                "a rounded quotient is stable under division rounding");
        assertEquals(x.multiply(BigDecimal.ONE), x.divide(BigDecimal.ONE),
                "both rounding paths use the currency minor-unit scale");
    }

    @Property(tries = 250)
    void multiplicationRoundsTheExactProductOnceToTheNearestMinorUnit(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("factors") BigDecimal factor) {
        Money x = new Money(a, currency);
        BigDecimal exact = x.amount().multiply(factor);
        BigDecimal rounded = x.multiply(factor).amount();
        assertTrue(rounded.scale() <= currency.getDefaultFractionDigits(),
                () -> "product has no digits beyond the minor unit: " + rounded);
        assertTrue(rounded.subtract(exact).abs().compareTo(halfMinorUnit(currency)) <= 0,
                () -> "rounding error is at most half a minor unit: exact " + exact + ", rounded " + rounded);
    }

    @Property(tries = 250)
    void divisionRoundsTheExactQuotientOnceToTheNearestMinorUnit(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("divisors") BigDecimal divisor) {
        Money x = new Money(a, currency);
        BigDecimal quotient = x.divide(divisor).amount();
        // |quotient - a / divisor| <= half a minor unit, checked without division as |quotient * divisor - a|.
        BigDecimal residual = quotient.multiply(divisor).subtract(x.amount()).abs();
        assertTrue(residual.compareTo(halfMinorUnit(currency).multiply(divisor.abs())) <= 0,
                () -> "quotient is within half a minor unit: " + quotient + " for " + a + " / " + divisor);
        assertTrue(quotient.scale() <= currency.getDefaultFractionDigits(),
                () -> "quotient has no digits beyond the minor unit: " + quotient);
    }

    @Property(tries = 250)
    void roundingIsSymmetricAroundZero(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("factors") BigDecimal factor,
            @ForAll("divisors") BigDecimal divisor) {
        Money x = new Money(a, currency);
        assertEquals(x.multiply(factor).negate(), x.negate().multiply(factor),
                "HALF_UP ties round away from zero on both sides");
        assertEquals(x.multiply(factor).negate(), x.multiply(factor.negate()),
                "a negated factor negates the rounded product");
        assertEquals(x.divide(divisor).negate(), x.negate().divide(divisor), "HALF_UP division is symmetric");
    }

    /**
     * An exact tie of half a minor unit (0.005 USD, 0.5 JPY) rounds away from zero. This distinguishes HALF_UP from
     * HALF_EVEN and HALF_DOWN, which the error-bound property alone cannot do.
     */
    @Property(tries = 250)
    void exactHalfUnitTiesRoundAwayFromZero(@ForAll("currencies") Currency currency,
            @ForAll("magnitudes") long magnitude, @ForAll("signs") int sign) {
        int digits = currency.getDefaultFractionDigits();
        long oddMinorUnits = 2 * magnitude + 1;
        // (magnitude + 1/2) minor units, written exactly at scale digits + 1: 5 * oddMinorUnits * 10^-(digits + 1).
        Money tie = new Money(BigDecimal.valueOf(sign * 5 * oddMinorUnits, digits + 1), currency);
        Money awayFromZero = new Money(BigDecimal.valueOf(sign * (magnitude + 1), digits), currency);
        assertEquals(awayFromZero, tie.multiply(BigDecimal.ONE),
                "a tie of half a minor unit rounds away from zero by multiplication");
        assertEquals(awayFromZero, tie.divide(BigDecimal.ONE),
                "a tie of half a minor unit rounds away from zero by division");
        assertEquals(awayFromZero, new Money(BigDecimal.valueOf(sign * oddMinorUnits, digits), currency).divide(2L),
                "an odd count of minor units halved is a tie that rounds away from zero");
    }

    @Property(tries = 250)
    void roundedProductsKeepTheOrderOfTheirOperands(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b,
            @ForAll("factors") BigDecimal factor) {
        Money x = new Money(a, currency);
        Money y = new Money(b, currency);
        Money low = x.compareTo(y) <= 0 ? x : y;
        Money high = x.compareTo(y) <= 0 ? y : x;
        int order = low.multiply(factor).compareTo(high.multiply(factor));
        assertTrue(factor.signum() >= 0 ? order <= 0 : order >= 0,
                () -> "rounding preserves order for factor " + factor);
    }

    @Property(tries = 250)
    void wholeNumberMultiplicationIsExactAndDistributesOverAddition(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("wholeFactors") long first,
            @ForAll("wholeFactors") long second) {
        Money x = new Money(a, currency);
        assertEquals(new Money(a.multiply(BigDecimal.valueOf(first)), currency), x.multiply(first),
                "whole-number multiplication does not round, even beyond the minor unit");
        assertEquals(x.multiply(first).add(x.multiply(second)), x.multiply(first + second),
                "whole-number factors distribute over addition");
    }

    @Property(tries = 250)
    void equalNumericValuesAreEqualMoneyAndConstructionKeepsTheValue(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("extraScales") int extraScale) {
        Money plain = new Money(a, currency);
        Money padded = new Money(a.setScale(a.scale() + extraScale), currency);
        assertEquals(plain, padded, "scale does not change Money equality");
        assertEquals(plain.hashCode(), padded.hashCode(), "equal Money values share a hash code");
        assertEquals(0, a.compareTo(plain.amount()), "construction keeps the numeric value");
    }

    @Property(tries = 250)
    void comparisonAgreesWithSubtractionAndIsAntisymmetric(@ForAll("currencies") Currency currency,
            @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b) {
        Money x = new Money(a, currency);
        Money y = new Money(b, currency);
        int order = Integer.signum(x.compareTo(y));
        assertEquals(order, x.subtract(y).amount().signum(), "compareTo has the sign of the difference");
        assertEquals(-order, Integer.signum(y.compareTo(x)), "compareTo is antisymmetric");
    }

    @Property(tries = 250)
    void divisionByZeroIsRejected(@ForAll("currencies") Currency currency, @ForAll("amounts") BigDecimal a) {
        Money x = new Money(a, currency);
        assertThrows(ArithmeticException.class, () -> x.divide(BigDecimal.ZERO), "zero decimal divisor is rejected");
        assertThrows(ArithmeticException.class, () -> x.divide(0L), "zero whole divisor is rejected");
    }

    @Provide
    Arbitrary<Currency> currencies() {
        return Arbitraries.of(CURRENCIES);
    }

    @Provide
    Arbitrary<CurrencyPair> distinctCurrencyPairs() {
        List<CurrencyPair> pairs = new ArrayList<>();
        for (Currency first : CURRENCIES) {
            for (Currency second : CURRENCIES) {
                if (!first.equals(second)) {
                    pairs.add(new CurrencyPair(first, second));
                }
            }
        }
        return Arbitraries.of(pairs);
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return decimals(AMOUNT_BOUND, AMOUNT_SCALE);
    }

    /** Factors may be zero; sign and scale vary, so rounding is exercised across signs and scales. */
    @Provide
    Arbitrary<BigDecimal> factors() {
        return decimals(FACTOR_BOUND, FACTOR_SCALE);
    }

    @Provide
    Arbitrary<BigDecimal> divisors() {
        return nonZeroDecimals(FACTOR_BOUND, FACTOR_SCALE);
    }

    /** Unscaled values at a currency's own minor-unit scale; rounding must leave them unchanged. */
    @Provide
    Arbitrary<Long> minorUnits() {
        return Arbitraries.longs().between(-AMOUNT_BOUND, AMOUNT_BOUND);
    }

    @Provide
    Arbitrary<Long> wholeFactors() {
        return Arbitraries.longs().between(-WHOLE_BOUND, WHOLE_BOUND);
    }

    @Provide
    Arbitrary<Long> nonZeroWholeFactors() {
        return nonZeroLongs(WHOLE_BOUND);
    }

    @Provide
    Arbitrary<Integer> extraScales() {
        return Arbitraries.integers().between(0, 5);
    }

    @Provide
    Arbitrary<Long> magnitudes() {
        return Arbitraries.longs().between(0L, WHOLE_BOUND * 1_000L);
    }

    @Provide
    Arbitrary<Integer> signs() {
        return Arbitraries.of(1, -1);
    }

    private static Arbitrary<BigDecimal> decimals(long unscaledBound, int maxScale) {
        return Combinators.combine(Arbitraries.longs().between(-unscaledBound, unscaledBound),
                        Arbitraries.integers().between(0, maxScale))
                .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    private static Arbitrary<BigDecimal> nonZeroDecimals(long unscaledBound, int maxScale) {
        return Combinators.combine(nonZeroLongs(unscaledBound), Arbitraries.integers().between(0, maxScale))
                .as((unscaled, scale) -> BigDecimal.valueOf(unscaled, scale));
    }

    private static Arbitrary<Long> nonZeroLongs(long bound) {
        return Arbitraries.oneOf(Arbitraries.longs().between(-bound, -1L), Arbitraries.longs().between(1L, bound));
    }

    /** Half of one minor unit (0.005 for USD, 0.5 for JPY): the largest error HALF_UP rounding may introduce. */
    private static BigDecimal halfMinorUnit(Currency currency) {
        return BigDecimal.valueOf(5, currency.getDefaultFractionDigits() + 1);
    }

    private static void assertCurrencyMismatch(Executable operation) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, operation);
        assertTrue(thrown.getMessage().startsWith("Currency mismatch"),
                () -> "mismatch is reported as a currency mismatch: " + thrown.getMessage());
    }

    private record CurrencyPair(Currency first, Currency second) { }
}
