package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import org.junit.jupiter.api.function.Executable;
import ru.onlychill.mbondsfx.core.bond.DatedPeriod;
import ru.onlychill.mbondsfx.core.bond.FixedCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingRateFormula;
import ru.onlychill.mbondsfx.core.bond.IndexLinkedCoupon;
import ru.onlychill.mbondsfx.core.bond.IndexationRule;
import ru.onlychill.mbondsfx.core.bond.KnownCoupon;
import ru.onlychill.mbondsfx.core.money.DayCounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.onlychill.mbondsfx.core.BondCalculationSupport.*;

/**
 * Coupon-schedule contract of {@code core.bond}. A {@link DatedPeriod} needs a non-empty accrual interval and a payment
 * date not before its accrual end. The periods of a coupon are kept sorted, are pairwise disjoint on their half-open accrual
 * intervals (adjacent periods are allowed; overlapping and duplicate periods are not), and belong to the coupon alone.
 * A {@link KnownCoupon} amount must name a declared, unique period. A fixed or index-linked rate is rejected exactly when it
 * is negative, and a missing component is a {@link NullPointerException}. Every property runs 250 tries.
 */
final class CouponScheduleProperties {
    private static final String OVERLAP = "Coupon accrual periods must not overlap";
    private static final String UNDECLARED = "Known amount period is not declared by this coupon";
    private static final String DUPLICATE = "Duplicate known amount for coupon period";
    /** Lies after every declared period of the generators below, which all end before BASE + 500 days. */
    private static final DatedPeriod FOREIGN = new DatedPeriod(BASE.plusDays(900), BASE.plusDays(901),
            BASE.plusDays(901));

    /** One amount per choice; {@code index} names a declared period, or the foreign period when it equals the size. */
    private record AmountChoice(int index, long minor) {
    }

    private record KnownInput(List<DatedPeriod> declared, List<AmountChoice> choices) {
    }

    /** A period is well formed exactly when its accrual interval is non-empty and its payment is not before its end. */
    @Property(tries = 250)
    void datedPeriodNeedsPositiveAccrualAndPaymentNotBeforeAccrualEnd(@ForAll("dates") LocalDate start,
            @ForAll("periodSpans") long span, @ForAll("paymentShifts") long shift) {
        LocalDate end = start.plusDays(span);
        LocalDate payment = end.plusDays(shift);
        if (span > 0 && shift >= 0) {
            DatedPeriod period = new DatedPeriod(start, end, payment);
            assertEquals(start, period.accrualStart(), "the accrual start is kept");
            assertEquals(end, period.accrualEnd(), "the accrual end is kept");
            assertEquals(payment, period.paymentDate(), "the payment date is kept");
        } else {
            String expected = span > 0 ? "paymentDate must not precede accrualEnd"
                    : "accrualStart must be before accrualEnd";
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new DatedPeriod(start, end, payment), "an invalid period is rejected");
            assertEquals(expected, rejected.getMessage(), "the failing rule is named");
        }
        assertThrows(NullPointerException.class, () -> new DatedPeriod(null, end, payment), "a start is required");
        assertThrows(NullPointerException.class, () -> new DatedPeriod(start, null, payment), "an end is required");
        assertThrows(NullPointerException.class, () -> new DatedPeriod(start, end, null), "a payment is required");
    }

    /**
     * A coupon accepts its periods exactly when no two of them share a day of their half-open accrual intervals, and then keeps
     * them sorted by accrual start. Duplicates and one-day overlaps count as overlaps.
     */
    @Property(tries = 250)
    void couponPeriodsAcceptExactlyPairwiseDisjointSchedules(@ForAll("periodLists") List<DatedPeriod> periods,
            @ForAll("rates") BigDecimal rate) {
        if (anyOverlap(periods)) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new FixedCoupon(rate, periods), "overlapping or duplicate periods are rejected");
            assertEquals(OVERLAP, rejected.getMessage(), "the overlap rule is named");
        } else {
            FixedCoupon coupon = new FixedCoupon(rate, periods);
            assertEquals(sortedByStart(periods), coupon.periods(),
                    "a disjoint schedule is kept sorted by accrual start");
            for (int index = 1; index < coupon.periods().size(); index++) {
                DatedPeriod before = coupon.periods().get(index - 1);
                DatedPeriod after = coupon.periods().get(index);
                assertFalse(before.accrualEnd().isAfter(after.accrualStart()),
                        "neighbouring periods never intersect: " + before + " and " + after);
            }
        }
    }

    /**
     * Two periods of one coupon are accepted when they are adjacent (one ends on the day the other starts, on either side) or
     * separated by a gap, and rejected when they share a day: the same start, or a one-day overlap at either end.
     */
    @Property(tries = 250)
    void adjacentPeriodsAreAcceptedAndAnyOverlapIsRejected(@ForAll("dates") LocalDate start,
            @ForAll("lengths") long length) {
        LocalDate end = start.plusDays(length);
        DatedPeriod first = new DatedPeriod(start, end, end);
        List<DatedPeriod> candidates = List.of(
                new DatedPeriod(start, end.plusDays(1), end.plusDays(1)),
                new DatedPeriod(end.minusDays(1), end.plusDays(length), end.plusDays(length)),
                new DatedPeriod(end, end.plusDays(length), end.plusDays(length)),
                new DatedPeriod(end.plusDays(1), end.plusDays(1 + length), end.plusDays(1 + length)),
                new DatedPeriod(start.minusDays(length), start, start),
                new DatedPeriod(start.minusDays(length), start.plusDays(1), start.plusDays(1)));
        for (DatedPeriod second : candidates) {
            List<DatedPeriod> pair = List.of(first, second);
            if (anyOverlap(pair)) {
                IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                        () -> new FixedCoupon(BigDecimal.ONE, pair), "a shared day is an overlap: " + second);
                assertEquals(OVERLAP, rejected.getMessage(), "the overlap rule is named");
            } else {
                assertEquals(2, new FixedCoupon(BigDecimal.ONE, pair).periods().size(),
                        "adjacent periods are accepted: " + second);
            }
        }
    }

    /** The order of the supplied periods does not matter: a reversed or rotated input yields an equal coupon. */
    @Property(tries = 250)
    void couponPeriodOrderDoesNotChangeTheCoupon(@ForAll("disjointPeriods") List<DatedPeriod> periods,
            @ForAll("selectors") long selector, @ForAll("rates") BigDecimal rate) {
        FixedCoupon sorted = new FixedCoupon(rate, periods);
        List<DatedPeriod> reversed = new ArrayList<>(periods);
        Collections.reverse(reversed);
        assertEquals(sorted, new FixedCoupon(rate, reversed), "a reversed input gives the same coupon");
        assertEquals(sorted, new FixedCoupon(rate, rotate(periods, selector)),
                "a rotated input gives the same coupon");
    }

    /** A coupon owns its periods: the supplied list may change afterwards without effect, and the periods are read-only. */
    @Property(tries = 250)
    void couponPeriodsAreDetachedFromTheSourceAndReadOnly(@ForAll("disjointPeriods") List<DatedPeriod> periods,
            @ForAll("rates") BigDecimal rate) {
        List<DatedPeriod> source = new ArrayList<>(periods);
        FixedCoupon coupon = new FixedCoupon(rate, source);
        List<DatedPeriod> snapshot = List.copyOf(coupon.periods());
        source.clear();
        assertEquals(snapshot, coupon.periods(), "clearing the supplied list leaves the coupon unchanged");
        assertThrows(UnsupportedOperationException.class, () -> coupon.periods().add(FOREIGN),
                "periods cannot be added");
        assertThrows(UnsupportedOperationException.class, () -> coupon.periods().clear(),
                "periods cannot be cleared");
    }

    /**
     * An amount must name a declared period and no declared period may carry two amounts; the first offending amount decides
     * the rejection. Accepted periods and amounts are kept sorted by accrual start.
     */
    @Property(tries = 250)
    void knownCouponAmountsMustReferToDeclaredUniquePeriods(@ForAll("knownInputs") KnownInput input) {
        List<DatedPeriod> declared = input.declared();
        List<KnownCoupon.Amount> amounts = new ArrayList<>();
        for (AmountChoice choice : input.choices()) {
            DatedPeriod period = choice.index() < declared.size() ? declared.get(choice.index()) : FOREIGN;
            amounts.add(new KnownCoupon.Amount(period, money(choice.minor())));
        }
        String expected = firstKnownRejection(input.choices(), declared.size());
        if (expected == null) {
            KnownCoupon known = new KnownCoupon(declared, amounts);
            assertEquals(sortedByStart(declared), known.periods(), "the declared periods are kept sorted");
            assertEquals(sortedAmounts(amounts), known.amounts(), "the amounts are kept sorted by accrual start");
        } else {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new KnownCoupon(declared, amounts), "an invalid known amount is rejected");
            assertEquals(expected, rejected.getMessage(), "the first offending amount decides the message");
        }
    }

    /**
     * The single-argument form declares exactly the periods of its amounts: an empty list has no period, overlapping or repeated
     * periods are rejected with the overlap message, and otherwise periods and amounts are kept sorted.
     */
    @Property(tries = 250)
    void knownCouponConvenienceFormDeclaresItsAmountPeriods(@ForAll("periodListsOrEmpty") List<DatedPeriod> periods) {
        List<KnownCoupon.Amount> amounts = periods.stream()
                .map(period -> new KnownCoupon.Amount(period, money(1000L))).toList();
        if (periods.isEmpty()) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new KnownCoupon(amounts), "a coupon without amounts has no period");
            assertEquals("A coupon must have at least one period", rejected.getMessage());
        } else if (anyOverlap(periods)) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new KnownCoupon(amounts), "overlapping amount periods are rejected");
            assertEquals(OVERLAP, rejected.getMessage(), "the overlap rule is named");
        } else {
            KnownCoupon known = new KnownCoupon(amounts);
            assertEquals(sortedByStart(periods), known.periods(), "the periods of the amounts are kept sorted");
            assertEquals(sortedAmounts(amounts), known.amounts(), "the amounts are kept sorted by accrual start");
        }
    }

    /** A fixed or index-linked rate is rejected exactly when it is negative; zero is a valid rate. */
    @Property(tries = 250)
    void couponRatesAreRejectedExactlyWhenNegative(@ForAll("signedRates") BigDecimal rate,
            @ForAll("disjointPeriods") List<DatedPeriod> periods) {
        IndexationRule rule = new IndexationRule(LINK_INDEX, new BigDecimal("100"), BASE, 0L);
        IndexLinkedCoupon.ReferenceDate reference = IndexLinkedCoupon.ReferenceDate.PERIOD_START;
        if (rate.signum() < 0) {
            IllegalArgumentException fixed = assertThrows(IllegalArgumentException.class,
                    () -> new FixedCoupon(rate, periods), "a negative fixed rate is rejected");
            assertEquals("Fixed coupon annualRate must be non-negative", fixed.getMessage());
            IllegalArgumentException linked = assertThrows(IllegalArgumentException.class,
                    () -> new IndexLinkedCoupon(rate, rule, reference, periods),
                    "a negative index-linked rate is rejected");
            assertEquals("Index-linked coupon annualRate must be non-negative", linked.getMessage());
        } else {
            assertEquals(rate, new FixedCoupon(rate, periods).annualRate(), "a non-negative fixed rate is kept");
            assertEquals(rate, new IndexLinkedCoupon(rate, rule, reference, periods).annualRate(),
                    "a non-negative index-linked rate is kept");
        }
    }

    /** A missing component of any coupon kind, or a null element of a list, is a NullPointerException. */
    @Property(tries = 250)
    void missingCouponComponentIsRejectedWithNullPointerException(@ForAll("missingCouponField") int field,
            @ForAll("disjointPeriods") List<DatedPeriod> periods) {
        IndexationRule rule = new IndexationRule(LINK_INDEX, new BigDecimal("100"), BASE, 0L);
        FloatingRateFormula floating = new FloatingRateFormula(RATE_INDEX, 0L, BigDecimal.ZERO,
                DayCounts.act365F(), Optional.empty(), Optional.empty());
        IndexLinkedCoupon.ReferenceDate reference = IndexLinkedCoupon.ReferenceDate.PERIOD_START;
        List<Executable> attempts = List.of(
                () -> new FixedCoupon(null, periods),
                () -> new FixedCoupon(BigDecimal.ONE, null),
                () -> new FixedCoupon(BigDecimal.ONE, Arrays.asList(periods.get(0), null)),
                () -> new FloatingCoupon(null, periods),
                () -> new FloatingCoupon(floating, null),
                () -> new IndexLinkedCoupon(null, rule, reference, periods),
                () -> new IndexLinkedCoupon(BigDecimal.ONE, null, reference, periods),
                () -> new IndexLinkedCoupon(BigDecimal.ONE, rule, null, periods),
                () -> new IndexLinkedCoupon(BigDecimal.ONE, rule, reference, null),
                () -> new KnownCoupon(null, List.of()),
                () -> new KnownCoupon(periods, null),
                () -> new KnownCoupon(Arrays.asList(new KnownCoupon.Amount(periods.get(0), money(1L)), null)));
        assertThrows(NullPointerException.class, attempts.get(field), "a missing component is a NullPointerException");
    }

    /** True when two periods share a day of their half-open accrual intervals; every pair is checked, not only neighbours. */
    private static boolean anyOverlap(List<DatedPeriod> periods) {
        for (int first = 0; first < periods.size(); first++) {
            for (int second = first + 1; second < periods.size(); second++) {
                DatedPeriod earlier = periods.get(first);
                DatedPeriod later = periods.get(second);
                if (earlier.accrualStart().isBefore(later.accrualEnd())
                        && later.accrualStart().isBefore(earlier.accrualEnd())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<DatedPeriod> sortedByStart(List<DatedPeriod> periods) {
        return periods.stream().sorted(Comparator.comparing(DatedPeriod::accrualStart)).toList();
    }

    private static List<KnownCoupon.Amount> sortedAmounts(List<KnownCoupon.Amount> amounts) {
        return amounts.stream().sorted(Comparator.comparing(amount -> amount.period().accrualStart())).toList();
    }

    private static List<DatedPeriod> rotate(List<DatedPeriod> periods, long selector) {
        int shift = Math.floorMod(selector, periods.size());
        List<DatedPeriod> rotated = new ArrayList<>(periods.subList(shift, periods.size()));
        rotated.addAll(periods.subList(0, shift));
        return rotated;
    }

    /** The rejection a known coupon reports for its amounts, decided in list order; null when every amount is accepted. */
    private static String firstKnownRejection(List<AmountChoice> choices, int declaredCount) {
        Set<Integer> seen = new HashSet<>();
        for (AmountChoice choice : choices) {
            if (choice.index() >= declaredCount) {
                return UNDECLARED;
            }
            if (!seen.add(choice.index())) {
                return DUPLICATE;
            }
        }
        return null;
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return dateValues();
    }

    @Provide
    Arbitrary<Long> selectors() {
        return selectorValues();
    }

    @Provide
    Arbitrary<Long> lengths() {
        return Arbitraries.longs().between(1L, 40L);
    }

    /** Spans around zero, so that the zero-length and the one-day cases occur on every run. */
    @Provide
    Arbitrary<Long> periodSpans() {
        return Arbitraries.oneOf(Arbitraries.longs().between(-20L, 40L), Arbitraries.of(-1L, 0L, 1L));
    }

    @Provide
    Arbitrary<Long> paymentShifts() {
        return Arbitraries.oneOf(Arbitraries.longs().between(-5L, 10L), Arbitraries.of(-1L, 0L));
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return fourPlaces(0L, 30_000L);
    }

    @Provide
    Arbitrary<BigDecimal> signedRates() {
        return Arbitraries.oneOf(fourPlaces(-30_000L, 30_000L),
                Arbitraries.of(BigDecimal.ZERO, new BigDecimal("-0.0001"), new BigDecimal("0.0001")));
    }

    @Provide
    Arbitrary<Integer> missingCouponField() {
        return Arbitraries.integers().between(0, 11);
    }

    /** Sorted, pairwise disjoint schedules of one to five periods; a zero gap makes adjacent periods common. */
    @Provide
    Arbitrary<List<DatedPeriod>> disjointPeriods() {
        return Arbitraries.integers().between(1, 5).flatMap(count -> Combinators.combine(
                        Arbitraries.oneOf(Arbitraries.just(0L), Arbitraries.longs().between(0L, 40L)).list().ofSize(count),
                        Arbitraries.longs().between(1L, 40L).list().ofSize(count),
                        Arbitraries.longs().between(0L, 10L))
                .as((gaps, widths, tail) -> schedule(BASE, gaps, widths, tail).periods()));
    }

    /** One period with a start, a length and a payment shift drawn from a window in which overlaps are common. */
    private static Arbitrary<DatedPeriod> arbitraryPeriods() {
        return Combinators.combine(Arbitraries.longs().between(0L, 80L), Arbitraries.longs().between(1L, 40L),
                        Arbitraries.longs().between(0L, 10L))
                .as((start, length, shift) -> new DatedPeriod(BASE.plusDays(start), BASE.plusDays(start + length),
                        BASE.plusDays(start + length + shift)));
    }

    /** Disjoint schedules, arbitrary (often overlapping) lists, and lists that repeat one period. */
    @Provide
    Arbitrary<List<DatedPeriod>> periodLists() {
        return Arbitraries.oneOf(disjointPeriods(), arbitraryPeriods().list().ofMinSize(1).ofMaxSize(5),
                arbitraryPeriods().map(period -> List.of(period, period)));
    }

    @Provide
    Arbitrary<List<DatedPeriod>> periodListsOrEmpty() {
        return Arbitraries.oneOf(periodLists(), Arbitraries.just(List.<DatedPeriod>of()));
    }

    /** A declared schedule with amount choices that name its periods, repeat them, or name the foreign period. */
    @Provide
    Arbitrary<KnownInput> knownInputs() {
        return disjointPeriods().flatMap(declared -> Combinators.combine(
                        Arbitraries.integers().between(0, declared.size()), Arbitraries.longs().between(0L, 1_000_000L))
                .as((index, minor) -> new AmountChoice(index, minor))
                .list().ofMaxSize(declared.size() + 2)
                .map(choices -> new KnownInput(declared, choices)));
    }
}
