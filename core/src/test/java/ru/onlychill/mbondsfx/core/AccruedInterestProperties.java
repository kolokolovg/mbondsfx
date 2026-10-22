package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.Basis;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.Schedule;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.Terms;
import ru.onlychill.mbondsfx.core.bond.AmortizationEvent;
import ru.onlychill.mbondsfx.core.bond.BondDefinition;
import ru.onlychill.mbondsfx.core.bond.Coupon;
import ru.onlychill.mbondsfx.core.bond.DatedPeriod;
import ru.onlychill.mbondsfx.core.bond.FixedCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingRateFormula;
import ru.onlychill.mbondsfx.core.bond.IndexLinkedCoupon;
import ru.onlychill.mbondsfx.core.bond.IndexationRule;
import ru.onlychill.mbondsfx.core.bond.KnownCoupon;
import ru.onlychill.mbondsfx.core.bond.calculation.AccruedInterest;
import ru.onlychill.mbondsfx.core.bond.calculation.BondCalculations;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.money.DayCount;
import ru.onlychill.mbondsfx.core.money.DayCounts;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.onlychill.mbondsfx.core.BondCalculationSupport.*;

/**
 * Accrued interest and coupon contract (1.3): non-negativity, monotonicity inside a coupon period,
 * coupon summation against the exact schedule, half-open boundaries, degenerate periods
 * (zero-length intervals, amortization dated on a boundary), the zero segment of a period that
 * starts on the 30th under 30/360 and 30E/360, and the zero accrual on the start date of a period that starts on the
 * 31st (docs/specs/day-count/v1.0.1.md). Every property runs 250 tries, or fewer when the domain is exhausted.
 */
final class AccruedInterestProperties {

    /** Non-negative effective rates never accrue a negative amount; accrual outside periods is zero. */
    @Property(tries = 250)
    void accruedIsNeverNegativeForNonNegativeEffectiveRates(@ForAll("nonNegativeTerms") Terms terms) {
        Schedule schedule = terms.schedule();
        BondDefinition bond = terms.toBond();
        for (LocalDate settle = schedule.issue(); !settle.isAfter(schedule.maturity()); settle = settle.plusDays(1)) {
            AccruedInterest accrued = accruedOn(bond, settle, terms);
            assertFalse(accrued.amount().isNegative(), "accrued interest is non-negative on " + settle);
            assertEquals(periodContaining(schedule.periods(), settle), accrued.period(),
                    "accrued reports the half-open period that contains " + settle);
            if (accrued.period().isEmpty()) {
                assertTrue(accrued.amount().isZero(), "no accrued interest outside coupon periods on " + settle);
            }
        }
    }

    /**
     * A negative effective floating rate (spread below zero, or a floor/cap below zero) is supported by the
     * formula, so accrued interest follows the sign of the effective rate instead of being clamped to zero.
     */
    @Property(tries = 250)
    void accruedSignFollowsTheEffectiveFloatingRate(@ForAll("floatingTerms") Terms terms) {
        FloatingCoupon floating = (FloatingCoupon) terms.coupon();
        BigDecimal rate = clampedRate(floating.formula(), terms.indexValue());
        BondDefinition bond = terms.toBond();
        for (DatedPeriod period : terms.schedule().periods()) {
            for (LocalDate day = period.accrualStart(); day.isBefore(period.accrualEnd()); day = day.plusDays(1)) {
                int sign = accruedOn(bond, day, terms).amount().amount().signum();
                if (rate.signum() > 0) {
                    assertTrue(sign >= 0, "a positive rate never accrues a negative amount on " + day);
                } else if (rate.signum() < 0) {
                    assertTrue(sign <= 0, "a negative rate never accrues a positive amount on " + day);
                } else {
                    assertEquals(0, sign, "a zero rate accrues nothing on " + day);
                }
            }
        }
    }

    /** Inside each coupon period accrued interest starts at zero and never decreases as the settle date moves. */
    @Property(tries = 250)
    void accruedIsMonotoneInsideEachCouponPeriod(@ForAll("nonNegativeTerms") Terms terms) {
        BondDefinition bond = terms.toBond();
        for (DatedPeriod period : terms.schedule().periods()) {
            AccruedInterest atStart = accruedOn(bond, period.accrualStart(), terms);
            assertEquals(Optional.of(period), atStart.period(), "the accrual start belongs to its own period");
            assertTrue(atStart.amount().isZero(), "accrual at the period start is zero");
            Money previous = atStart.amount();
            for (LocalDate day = period.accrualStart().plusDays(1); day.isBefore(period.accrualEnd());
                 day = day.plusDays(1)) {
                AccruedInterest accrued = accruedOn(bond, day, terms);
                assertEquals(Optional.of(period), accrued.period(), "every day of the period accrues to it");
                assertTrue(accrued.amount().compareTo(previous) >= 0,
                        "accrued interest never decreases inside the period on " + day);
                previous = accrued.amount();
            }
        }
    }

    /** Accrued interest inside a period never exceeds the full coupon of that period. */
    @Property(tries = 250)
    void accruedNeverExceedsTheFullCouponOfItsPeriod(@ForAll("nonNegativeTerms") Terms terms) {
        BondDefinition bond = terms.toBond();
        for (DatedPeriod period : terms.schedule().periods()) {
            Money full = couponOn(bond, period, terms);
            for (LocalDate day = period.accrualStart(); day.isBefore(period.accrualEnd()); day = day.plusDays(1)) {
                assertTrue(accruedOn(bond, day, terms).amount().compareTo(full) <= 0,
                        "accrued interest on " + day + " stays within the full coupon");
            }
        }
    }

    /**
     * Each unrounded value is the exact integral of the nominal over the half-open interval, with amortization
     * splitting it. Production rounds once, so the error is at most one half cent.
     */
    @Property(tries = 250)
    void accruedAndCouponMatchTheEventAwareExactIntegral(@ForAll("formulaTerms") Terms terms,
                                                         @ForAll("selectors") long selector) {
        BondDefinition bond = terms.toBond();
        Coupon coupon = terms.coupon();
        BigDecimal multiplier = annualMultiplier(coupon, terms.indexValue());
        Basis basis = basisOf(coupon, terms.basis());
        BigDecimal face = faceAmount(terms.faceMinor());
        DatedPeriod period = periodAt(terms.schedule(), selector);
        for (LocalDate settle : List.of(period.accrualStart(), dayAt(period, selector),
                period.accrualEnd().minusDays(1))) {
            AccruedInterest accrued = accruedOn(bond, settle, terms);
            assertEquals(Optional.of(period), accrued.period(), "settle " + settle + " is inside the period");
            assertWithin(exactInterest(face, terms.events(), period.accrualStart(), settle, multiplier, basis),
                    accrued.amount(), tolerance(1), "accrued matches the exact integral up to " + settle);
        }
        assertWithin(exactInterest(face, terms.events(), period.accrualStart(), period.accrualEnd(), multiplier, basis),
                couponOn(bond, period, terms), tolerance(1), "coupon matches the exact integral of its period");
    }

    /** Coupon amounts over the whole schedule sum to the exact total, within one half cent per coupon. */
    @Property(tries = 250)
    void couponAmountsSumToTheExactScheduleWithinOneHalfCentEach(@ForAll("formulaTerms") Terms terms) {
        BondDefinition bond = terms.toBond();
        Coupon coupon = terms.coupon();
        BigDecimal multiplier = annualMultiplier(coupon, terms.indexValue());
        Basis basis = basisOf(coupon, terms.basis());
        BigDecimal face = faceAmount(terms.faceMinor());
        List<DatedPeriod> periods = terms.schedule().periods();
        BigDecimal exactTotal = BigDecimal.ZERO;
        Money actualTotal = Money.zero(RUB);
        for (DatedPeriod period : periods) {
            exactTotal = exactTotal.add(exactInterest(face, terms.events(), period.accrualStart(),
                    period.accrualEnd(), multiplier, basis));
            actualTotal = actualTotal.add(couponOn(bond, period, terms));
        }
        assertWithin(exactTotal, actualTotal, tolerance(periods.size()),
                "the sum of coupon amounts matches the exact schedule total");
    }

    /**
     * Accrued interest up to a split date plus the coupon of the remaining tail reconciles with the full coupon.
     * Three HALF_UP roundings are involved (accrued, tail, full), so the bound is three half cents.
     */
    @Property(tries = 250)
    void accruedPlusTailCouponReconcilesWithTheFullCoupon(@ForAll("formulaTerms") Terms terms,
                                                          @ForAll("selectors") long selector) {
        BondDefinition bond = terms.toBond();
        DatedPeriod period = periodAt(terms.schedule(), selector);
        LocalDate split = dayAt(period, selector / 3);
        DatedPeriod tail = new DatedPeriod(split, period.accrualEnd(), period.accrualEnd());
        BondDefinition tailBond = bond(terms.schedule(), principal(terms.faceMinor(), terms.events()),
                Optional.of(withPeriods(terms.coupon(), List.of(tail))));
        Money accrued = accruedOn(bond, split, terms).amount();
        Money remainder = couponOn(tailBond, tail, terms);
        assertWithin(couponOn(bond, period, terms).amount(), accrued.add(remainder), tolerance(3),
                "accrued up to the split plus the tail coupon reconciles to the full coupon");
    }

    /**
     * Half-open boundaries: a period accrues from its start date (zero, own period) and ends before its end date.
     * The end date belongs to the next period only when that period starts there. The issue and maturity dates are
     * accepted calculation dates; the days outside them are rejected.
     */
    @Property(tries = 250)
    void periodBoundariesFollowTheHalfOpenAccrualContract(@ForAll("nonNegativeTerms") Terms terms) {
        Schedule schedule = terms.schedule();
        BondDefinition bond = terms.toBond();
        List<DatedPeriod> periods = schedule.periods();
        for (int index = 0; index < periods.size(); index++) {
            DatedPeriod period = periods.get(index);
            AccruedInterest atStart = accruedOn(bond, period.accrualStart(), terms);
            assertEquals(Optional.of(period), atStart.period(), "the start date belongs to the period it opens");
            assertTrue(atStart.amount().isZero(), "accrual at the start date is zero");

            boolean adjacent = index + 1 < periods.size()
                    && periods.get(index + 1).accrualStart().equals(period.accrualEnd());
            Optional<DatedPeriod> expectedAtEnd = adjacent ? Optional.of(periods.get(index + 1)) : Optional.empty();
            AccruedInterest atEnd = accruedOn(bond, period.accrualEnd(), terms);
            assertEquals(expectedAtEnd, atEnd.period(),
                    "the end date is outside the period; it belongs to the next period only if that starts there");
            assertTrue(atEnd.amount().isZero(), "accrual at the end date is zero");
        }

        assertTrue(accruedOn(bond, schedule.issue(), terms).amount().isZero(), "accrual on the issue date is zero");
        accruedOn(bond, schedule.maturity(), terms);
        assertThrows(IllegalArgumentException.class, () -> accruedOn(bond, schedule.issue().minusDays(1), terms),
                "the day before issue is rejected");
        assertThrows(IllegalArgumentException.class, () -> accruedOn(bond, schedule.maturity().plusDays(1), terms),
                "the day after maturity is rejected");

        BondDefinition zeroCoupon = bond(schedule, principal(terms.faceMinor(), terms.events()), Optional.empty());
        for (LocalDate day : List.of(schedule.issue(), schedule.maturity())) {
            AccruedInterest accrued = accruedOn(zeroCoupon, day, terms);
            assertTrue(accrued.amount().isZero() && accrued.period().isEmpty(),
                    "a zero-coupon bond never accrues interest");
        }
    }

    /** A zero-length or inverted accrual interval cannot exist; the domain rejects it at construction. */
    @Property(tries = 250)
    void zeroLengthAndInvertedAccrualIntervalsAreRejected(@ForAll("dates") LocalDate date,
                                                          @ForAll("selectors") long back) {
        assertThrows(IllegalArgumentException.class, () -> new DatedPeriod(date, date, date),
                "a zero-length accrual interval is rejected");
        assertThrows(IllegalArgumentException.class, () -> new DatedPeriod(date, date.minusDays(1 + back % 400), date),
                "an inverted accrual interval is rejected");
    }

    /**
     * Amortization dated on a boundary of a period: on the settle date it is outside the accrual [start, settle);
     * on the accrual end it does not reduce that coupon; on the accrual start it applies to the whole period,
     * exactly like a smaller face.
     */
    @Property(tries = 250)
    void amortizationOnBoundaryDatesFollowTheHalfOpenRules(@ForAll("formulaTerms") Terms terms,
                                                           @ForAll("selectors") long selector) {
        Schedule schedule = terms.schedule();
        DatedPeriod period = periodAt(schedule, selector);
        Coupon coupon = terms.coupon();
        long face = terms.faceMinor();
        long reduction = face / 10;
        BondDefinition plain = bond(schedule, principal(face, List.of()), Optional.of(coupon));

        LocalDate settle = dayAt(period, selector / 7);
        if (settle.isAfter(schedule.issue())) {
            BondDefinition settleEvent = bond(schedule, principal(face, List.of(event(settle, reduction))),
                    Optional.of(coupon));
            assertEquals(accruedOn(plain, settle, terms), accruedOn(settleEvent, settle, terms),
                    "amortization dated on the settle date is outside the accrual [start, settle)");
        }

        BondDefinition endEvent = bond(schedule, principal(face, List.of(event(period.accrualEnd(), reduction))),
                Optional.of(coupon));
        assertSameAmount(couponOn(plain, period, terms), couponOn(endEvent, period, terms),
                "amortization dated on the accrual end does not reduce that coupon");

        if (period.accrualStart().isAfter(schedule.issue())) {
            BondDefinition startEvent = bond(schedule,
                    principal(face, List.of(event(period.accrualStart(), reduction))), Optional.of(coupon));
            BondDefinition smallerFace = bond(schedule, principal(face - reduction, List.of()), Optional.of(coupon));
            assertSameAmount(couponOn(smallerFace, period, terms), couponOn(startEvent, period, terms),
                    "amortization dated on the accrual start applies to the whole period");
            assertEquals(accruedOn(smallerFace, settle, terms), accruedOn(startEvent, settle, terms),
                    "amortization dated on the accrual start also reduces accrual inside the period");
        }
    }

    /**
     * A KnownCoupon returns its announced amount exactly, including sub-minor precision, and accrues linearly
     * on the caller's day-count basis, rounded once.
     */
    @Property(tries = 250)
    void knownCouponIsAuthoritativeAndAccruesLinearlyOnItsBasis(@ForAll("knownTerms") Terms terms,
                                                                 @ForAll("selectors") long selector) {
        BondDefinition bond = terms.toBond();
        KnownCoupon known = (KnownCoupon) terms.coupon();
        for (KnownCoupon.Amount amount : known.amounts()) {
            assertSameAmount(amount.amountPerUnit(), couponOn(bond, amount.period(), terms),
                    "a KnownCoupon reports its announced amount exactly");
        }
        DatedPeriod period = periodAt(terms.schedule(), selector);
        Money announced = known.amounts().stream()
                .filter(amount -> amount.period().equals(period))
                .findFirst().orElseThrow().amountPerUnit();
        for (LocalDate settle : List.of(period.accrualStart(), dayAt(period, selector),
                period.accrualEnd().minusDays(1))) {
            AccruedInterest accrued = accruedOn(bond, settle, terms);
            assertEquals(Optional.of(period), accrued.period(), "settle " + settle + " is inside the period");
            BigDecimal share = elapsedShare(period.accrualStart(), settle, period.accrualEnd(), terms.basis());
            assertWithin(announced.amount().multiply(share), accrued.amount(), tolerance(1),
                    "known accrual is the announced amount times the elapsed share on " + settle);
        }
    }

    /**
     * Floating coupons take their day count from the formula: the caller's day count is ignored for amount and
     * accrual, and the accrued result reports the formula's basis.
     */
    @Property(tries = 250)
    void floatingCouponIgnoresTheCallerDayCountAndReportsItsOwn(@ForAll("floatingTerms") Terms terms,
                                                                @ForAll("selectors") long selector) {
        BondDefinition bond = terms.toBond();
        Basis formulaBasis = basisOf(terms.coupon(), terms.basis());
        Basis otherCaller = Basis.values()[(terms.basis().ordinal() + 1) % Basis.values().length];
        DatedPeriod period = periodAt(terms.schedule(), selector);
        IndexValueSource fixings = level(terms.indexValue());
        assertSameAmount(BondCalculations.couponAmount(bond, period, terms.basis().dayCount(), fixings),
                BondCalculations.couponAmount(bond, period, otherCaller.dayCount(), fixings),
                "the formula day count replaces the caller's one for a floating coupon");
        for (LocalDate day = period.accrualStart(); day.isBefore(period.accrualEnd()); day = day.plusDays(1)) {
            AccruedInterest mine = BondCalculations.accrued(bond, day, terms.basis().dayCount(), fixings);
            AccruedInterest other = BondCalculations.accrued(bond, day, otherCaller.dayCount(), fixings);
            assertSameAmount(other.amount(), mine.amount(), "accrual ignores the caller's day count on " + day);
            assertEquals(other.period(), mine.period(), "period ignores the caller's day count on " + day);
            assertSame(formulaBasis.dayCount(), mine.dayCount(), "accrual reports the formula's day count");
        }
    }

    /**
     * 30/360 and 30E/360 cap the day of month at 30, so the 31st adds no days after a period that starts on the 30th.
     * Accrual on the 30th and on the 31st is therefore zero, does not throw, and still belongs to the period; a split
     * at the 31st reconciles exactly with the full coupon. Only this zero segment is exempt from the strict
     * positive-fraction check; the full KnownCoupon period stays strict (see DomainContractTests).
     */
    @Property(tries = 250)
    void thirty360FamilyThirtyFirstAddsNoAccrualAndSplitsExactly(@ForAll("thirty360FamilyTerms") ThirtyTerms terms) {
        DatedPeriod period = terms.period();
        BondDefinition bond = terms.toBond();
        LocalDate thirtyFirst = period.accrualStart().plusDays(1);
        assertTrue(thirtyAccrued(bond, period.accrualStart(), terms).amount().isZero(), "accrual on the 30th is zero");
        AccruedInterest onThirtyFirst = thirtyAccrued(bond, thirtyFirst, terms);
        assertTrue(onThirtyFirst.amount().isZero(), "accrual on the 31st is zero");
        assertEquals(Optional.of(period), onThirtyFirst.period(), "the 31st still belongs to the period");

        DatedPeriod tail = new DatedPeriod(thirtyFirst, period.accrualEnd(), period.accrualEnd());
        BondDefinition tailBond = bond(terms.schedule(), principal(terms.faceMinor(), terms.events()),
                Optional.of(withPeriods(terms.coupon(), List.of(tail))));
        assertSameAmount(thirtyCoupon(bond, period, terms),
                onThirtyFirst.amount().add(thirtyCoupon(tailBond, tail, terms)),
                "accrued on the 31st plus the tail coupon reconciles exactly with the full coupon");
    }

    /**
     * Under 30/360 and 30E/360 the usual accrual invariants still hold: the coupon matches the exact 30E/360 integral
     * (the same integral for both bases, because the period starts on the 30th), accrued interest is non-negative,
     * never decreases inside the period, never exceeds the full coupon, and tracks the exact integral up to a settle
     * date within one half cent.
     */
    @Property(tries = 250)
    void thirty360FamilyKeepsTheUsualAccrualInvariants(@ForAll("thirty360FamilyTerms") ThirtyTerms terms) {
        DatedPeriod period = terms.period();
        BondDefinition bond = terms.toBond();
        BigDecimal multiplier = annualMultiplier(terms.coupon(), terms.indexValue());
        BigDecimal face = faceAmount(terms.faceMinor());
        Money full = thirtyCoupon(bond, period, terms);
        assertWithin(exactThirty360Interest(face, terms.events(), period.accrualStart(), period.accrualEnd(),
                multiplier), full, tolerance(1), "the coupon matches the exact day-by-day integral of its period");
        Money previous = Money.zero(RUB);
        for (LocalDate day = period.accrualStart(); day.isBefore(period.accrualEnd()); day = day.plusDays(1)) {
            AccruedInterest accrued = thirtyAccrued(bond, day, terms);
            assertEquals(Optional.of(period), accrued.period(), "every day of the period accrues to it");
            assertFalse(accrued.amount().isNegative(), "accrued interest is non-negative on " + day);
            assertTrue(accrued.amount().compareTo(previous) >= 0, "accrued interest never decreases on " + day);
            assertTrue(accrued.amount().compareTo(full) <= 0,
                    "accrued interest stays within the full coupon on " + day);
            previous = accrued.amount();
        }
        for (LocalDate settle : List.of(period.accrualStart().plusDays(1), period.accrualEnd().minusDays(1))) {
            assertWithin(exactThirty360Interest(face, terms.events(), period.accrualStart(), settle, multiplier),
                    thirtyAccrued(bond, settle, terms).amount(), tolerance(1),
                    "accrued interest matches the exact day-by-day integral up to " + settle);
        }
    }

    /**
     * v1.0.1, points 1-2: on the start date of a period that starts on the 31st, accrued interest is zero for every
     * coupon kind and every day-count, and the day-count is never consulted for that zero-length interval. The
     * day-count given here refuses every zero-length query, so consulting it would fail the property.
     */
    @Property(tries = 250)
    void accrualOnAThirtyFirstStartDateIsZeroWithoutConsultingTheDayCount(
            @ForAll("startCases") StartCase startCase, @ForAll("refusingDayCounts") DayCount dayCount) {
        LocalDate start = startCase.start();
        assertThrows(IllegalStateException.class, () -> dayCount.days(start, start),
                "the refusing day-count does fail on a zero-length query");
        DatedPeriod period = new DatedPeriod(start, startCase.end(), startCase.end());
        Schedule schedule = new Schedule(start, startCase.end(), List.of(period));
        List<Coupon> coupons = List.of(
                new KnownCoupon(schedule.periods(), List.of(new KnownCoupon.Amount(period, money(1000L)))),
                new FixedCoupon(new BigDecimal("0.1000"), schedule.periods()),
                new FloatingCoupon(new FloatingRateFormula(RATE_INDEX, 0L, BigDecimal.ZERO, dayCount,
                        Optional.empty(), Optional.empty()), schedule.periods()),
                new IndexLinkedCoupon(new BigDecimal("0.1000"),
                        new IndexationRule(LINK_INDEX, new BigDecimal("100"), BASE, 0L),
                        IndexLinkedCoupon.ReferenceDate.PERIOD_START, schedule.periods()));
        for (Coupon coupon : coupons) {
            BondDefinition bond = bond(schedule, principal(1000L, List.of()), Optional.of(coupon));
            AccruedInterest accrued = BondCalculations.accrued(bond, start, dayCount, level(BigDecimal.valueOf(100)));
            assertTrue(accrued.amount().isZero(),
                    "accrued interest on the start date is zero for " + coupon.getClass().getSimpleName());
            assertEquals(Optional.of(period), accrued.period(), "the start date belongs to its own period");
        }
    }

    /**
     * v1.0.1, point 3: after the start of a KnownCoupon period, the elapsed share follows the segment rule. On the
     * 31st of a period that starts on the 30th, the identity instances of 30/360 and 30E/360 give zero, a lookalike of
     * either is rejected with the segment message, and 30E+/360 and ACT/365F give a positive share.
     */
    @Property(tries = 250)
    void knownElapsedShareAfterTheStartFollowsTheSegmentRule(@ForAll("thirtyStartCases") StartCase startCase) {
        LocalDate start = startCase.start();
        LocalDate settle = start.plusDays(1);
        DatedPeriod period = new DatedPeriod(start, startCase.end(), startCase.end());
        Schedule schedule = new Schedule(start, startCase.end(), List.of(period));
        KnownCoupon known = new KnownCoupon(schedule.periods(), List.of(new KnownCoupon.Amount(period, money(1000L))));
        BondDefinition bond = bond(schedule, principal(1000L, List.of()), Optional.of(known));

        assertTrue(knownAccrued(bond, settle, DayCounts.thirty360()).amount().isZero(),
                "identity 30/360 gives zero for the 31st inside the zero segment");
        assertTrue(knownAccrued(bond, settle, DayCounts.thirtyE360()).amount().isZero(),
                "identity 30E/360 gives zero for the 31st inside the zero segment");
        for (DayCount lookalike : List.of(new Lookalike(DayCounts.thirty360()),
                new Lookalike(DayCounts.thirtyE360()))) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> knownAccrued(bond, settle, lookalike), "a lookalike is not exempt");
            assertEquals("DayCount must return a positive fraction for a positive interval", rejected.getMessage(),
                    "the lookalike is rejected with the segment message");
        }
        assertTrue(knownAccrued(bond, settle, DayCounts.thirtyEPlus360()).amount().compareTo(Money.zero(RUB)) > 0,
                "30E+/360 counts the 31st as a positive share of the period");
        assertTrue(knownAccrued(bond, settle, DayCounts.act365F()).amount().compareTo(Money.zero(RUB)) > 0,
                "ACT/365F counts the 31st as a positive share of the period");
    }

    @Provide
    Arbitrary<Terms> nonNegativeTerms() {
        // Index levels stay non-negative: a negative coefficient is rejected by the calculation, not accrued
        // (see FloatingRateIndexationProperties.negativeIndexCoefficientIsRejectedForLinkedCouponAndAccrued).
        return termValues(BondCalculationSupport::nonNegativeCoupons, fourPlaces(0L, 3000L));
    }

    @Provide
    Arbitrary<Terms> formulaTerms() {
        // Index levels stay non-negative: a negative coefficient is rejected, so the oracle sees only accepted inputs.
        return termValues(BondCalculationSupport::formulaCoupons, fourPlaces(0L, 3000L));
    }

    @Provide
    Arbitrary<Terms> floatingTerms() {
        return termValues(schedule -> floatingCoupons(schedule, -3L, 0L, -500L, 300L, -200L, 3000L),
                fourPlaces(0L, 2000L));
    }

    @Provide
    Arbitrary<Terms> knownTerms() {
        return termValues(schedule -> knownCoupons(schedule, 4, 1_000_000L), fourPlaces(0L, 0L));
    }

    @Provide
    Arbitrary<Long> selectors() {
        return selectorValues();
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return dateValues();
    }

    /**
     * Single-period bonds on 30/360 or 30E/360: the period starts on the 30th of a 31-day month, so its 31st exists,
     * and an amortization may be dated on that 31st. The coupon is fixed, floating on the same basis, or index-linked.
     */
    @Provide
    Arbitrary<ThirtyTerms> thirty360FamilyTerms() {
        return Arbitraries.of(DayCounts.thirtyE360(), DayCounts.thirty360()).flatMap(dayCount ->
                thirtyFirstStarts().flatMap(start -> Arbitraries.integers().between(1, 6).flatMap(months -> {
                    LocalDate end = start.plusMonths(months);
                    Schedule schedule = new Schedule(start, end, List.of(new DatedPeriod(start, end, end)));
                    LocalDate thirtyFirst = start.plusDays(1);
                    return faceValues().flatMap(faceMinor -> Arbitraries.oneOf(
                                    eventValues(schedule, faceMinor),
                                    Arbitraries.longs().between(1L, 25L)
                                            .map(percent -> List.of(event(thirtyFirst, faceMinor * percent / 100))))
                            .flatMap(events -> thirtyCoupons(schedule, dayCount).flatMap(coupon -> fourPlaces(0L, 3000L)
                                    .map(level -> new ThirtyTerms(schedule, faceMinor, events, coupon, level,
                                            dayCount)))));
                })));
    }

    /** Thirtieths of 31-day months, so the 31st exists in the same month and follows the period start. */
    private static Arbitrary<LocalDate> thirtyFirstStarts() {
        return Arbitraries.integers().between(0, 35)
                .map(offset -> YearMonth.from(BASE).plusMonths(offset))
                .filter(month -> month.lengthOfMonth() == 31)
                .map(month -> month.atDay(30));
    }

    /**
     * Coupons whose accrual passes through the zero-segment check: fixed, floating on {@code dayCount}, index-linked.
     */
    private static Arbitrary<Coupon> thirtyCoupons(Schedule schedule, DayCount dayCount) {
        return Arbitraries.oneOf(
                fourPlaces(0L, 3000L).map(rate -> (Coupon) new FixedCoupon(rate, schedule.periods())),
                fourPlaces(0L, 500L).map(spread -> (Coupon) new FloatingCoupon(new FloatingRateFormula(RATE_INDEX,
                        0L, spread, dayCount, Optional.empty(), Optional.empty()), schedule.periods())),
                Combinators.combine(fourPlaces(0L, 3000L), indexBaseValues(),
                                Arbitraries.of(IndexLinkedCoupon.ReferenceDate.PERIOD_START,
                                        IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE))
                        .as((rate, base, reference) -> (Coupon) new IndexLinkedCoupon(rate,
                                new IndexationRule(LINK_INDEX, base, BASE, 0L), reference, schedule.periods())));
    }

    /** Periods that start on the 31st of a 31-day month and last one to six months. */
    @Provide
    Arbitrary<StartCase> startCases() {
        return thirtyFirstDates().flatMap(start -> Arbitraries.integers().between(1, 6)
                .map(months -> new StartCase(start, start.plusMonths(months))));
    }

    /** Periods that start on the 30th of a 31-day month and last one to six months, so the 31st follows the start. */
    @Provide
    Arbitrary<StartCase> thirtyStartCases() {
        return thirtyFirstStarts().flatMap(start -> Arbitraries.integers().between(1, 6)
                .map(months -> new StartCase(start, start.plusMonths(months))));
    }

    /** Day-counts that refuse every zero-length query, so consulting one for a zero-length interval fails. */
    @Provide
    Arbitrary<DayCount> refusingDayCounts() {
        return Arbitraries.of(List.of(DayCounts.act365F(), DayCounts.act360(), DayCounts.actActIsda(),
                        DayCounts.thirtyE360(), DayCounts.thirty360(), DayCounts.thirtyEPlus360()))
                .map(delegate -> (DayCount) new ZeroLengthRefusal(delegate));
    }

    /** The 31st of months that have one; the same window as {@link #thirtyFirstStarts()}. */
    private static Arbitrary<LocalDate> thirtyFirstDates() {
        return Arbitraries.integers().between(0, 35)
                .map(offset -> YearMonth.from(BASE).plusMonths(offset))
                .filter(month -> month.lengthOfMonth() == 31)
                .map(month -> month.atDay(31));
    }

    private static AccruedInterest knownAccrued(BondDefinition bond, LocalDate settle, DayCount dayCount) {
        return BondCalculations.accrued(bond, settle, dayCount, level(BigDecimal.ONE));
    }

    private static AccruedInterest thirtyAccrued(BondDefinition bond, LocalDate settle, ThirtyTerms terms) {
        return BondCalculations.accrued(bond, settle, terms.dayCount(), level(terms.indexValue()));
    }

    private static Money thirtyCoupon(BondDefinition bond, DatedPeriod period, ThirtyTerms terms) {
        return BondCalculations.couponAmount(bond, period, terms.dayCount(), level(terms.indexValue()));
    }

    /**
     * A single-period bond priced on 30/360 or 30E/360 only; kept apart from {@link Terms}, whose basis is ACT-family.
     */
    private record ThirtyTerms(Schedule schedule, long faceMinor, List<AmortizationEvent> events, Coupon coupon,
                               BigDecimal indexValue, DayCount dayCount) {
        DatedPeriod period() {
            return schedule.periods().get(0);
        }

        BondDefinition toBond() {
            return bond(schedule, principal(faceMinor, events), Optional.of(coupon));
        }
    }

    /** One period under test: its start and end dates. */
    private record StartCase(LocalDate start, LocalDate end) {
    }

    /** Delegates queries on distinct dates; a zero-length query fails, so it cannot be consulted unnoticed. */
    private record ZeroLengthRefusal(DayCount delegate) implements DayCount {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            refuseZeroLength(start, end);
            return delegate.yearFraction(start, end);
        }

        @Override
        public long days(LocalDate start, LocalDate end) {
            refuseZeroLength(start, end);
            return delegate.days(start, end);
        }

        private static void refuseZeroLength(LocalDate start, LocalDate end) {
            if (start.equals(end)) {
                throw new IllegalStateException("day-count consulted for the zero-length interval on " + start);
            }
        }
    }

    /** Same values as its delegate but another object, so it earns no identity exemption. */
    private record Lookalike(DayCount delegate) implements DayCount {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            return delegate.yearFraction(start, end);
        }

        @Override
        public long days(LocalDate start, LocalDate end) {
            return delegate.days(start, end);
        }
    }
}
