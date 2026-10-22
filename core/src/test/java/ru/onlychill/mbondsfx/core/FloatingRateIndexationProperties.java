package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.Basis;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.IndexedBond;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.Query;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.RecordingSource;
import ru.onlychill.mbondsfx.core.BondCalculationSupport.Terms;
import ru.onlychill.mbondsfx.core.bond.BondDefinition;
import ru.onlychill.mbondsfx.core.bond.Coupon;
import ru.onlychill.mbondsfx.core.bond.DatedPeriod;
import ru.onlychill.mbondsfx.core.bond.FixedCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingRateFormula;
import ru.onlychill.mbondsfx.core.bond.IndexLinkedCoupon;
import ru.onlychill.mbondsfx.core.bond.IndexationRule;
import ru.onlychill.mbondsfx.core.bond.KnownCoupon;
import ru.onlychill.mbondsfx.core.bond.Principal;
import ru.onlychill.mbondsfx.core.bond.calculation.AccruedInterest;
import ru.onlychill.mbondsfx.core.bond.calculation.BondCalculations;
import ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException;
import ru.onlychill.mbondsfx.core.bond.calculation.UnsupportedCalculationException;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.money.DayCount;
import ru.onlychill.mbondsfx.core.money.DayCounts;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.onlychill.mbondsfx.core.BondCalculationSupport.*;

/**
 * Floating-rate floor/cap and indexation contract (1.4): the clamped effective rate, saturation and monotonicity,
 * the formula day count, the future-fixing guard, the observation lag of {@link IndexationRule}, exact queries to
 * {@code IndexValueSource}, the index/date metadata of {@link MissingIndexValueException}, and the rejection of a
 * negative index coefficient for index-linked coupons. Every property runs 250 tries.
 */
final class FloatingRateIndexationProperties {
    private static final String NEGATIVE_COEFFICIENT = "Indexation must not produce negative index coefficient";

    /**
     * The effective rate is fixing plus spread clamped into [floor, cap]. Its coupon equals the coupon of a fixed
     * coupon at that rate and matches the exact integral on the formula's own day count.
     */
    @Property(tries = 250)
    void floatingRateIsFixingPlusSpreadClampedIntoFloorAndCap(@ForAll("floatingAnyTerms") Terms terms,
                                                             @ForAll("selectors") long selector) {
        FloatingRateFormula formula = ((FloatingCoupon) terms.coupon()).formula();
        BigDecimal rate = clampedRate(formula, terms.indexValue());
        DatedPeriod period = periodAt(terms.schedule(), selector);
        BondDefinition bond = terms.toBond();
        Money actual = couponOn(bond, period, terms);
        assertWithin(exactInterest(faceAmount(terms.faceMinor()), terms.events(), period.accrualStart(),
                        period.accrualEnd(), rate, Basis.of(formula.dayCount())),
                actual, tolerance(1),
                "the coupon uses fixing + spread clamped into floor and cap, on the formula day count");
        if (rate.signum() >= 0) {
            BondDefinition fixed = bond(terms.schedule(), principal(terms.faceMinor(), terms.events()),
                    Optional.of(new FixedCoupon(rate, terms.schedule().periods())));
            assertSameAmount(BondCalculations.couponAmount(fixed, period, formula.dayCount(), unqueried()), actual,
                    "the clamped rate prices exactly like a fixed coupon at that rate");
        }
    }

    /**
     * A higher fixing never lowers the coupon. Two fixings that both stay at or below the floor pay the same
     * coupon, and two fixings that both reach the cap pay the same coupon.
     */
    @Property(tries = 250)
    void floatingCouponIsMonotoneAndSaturatesAtFloorAndCap(@ForAll("floatingAnyTerms") Terms terms,
                                                          @ForAll("signedLevels") BigDecimal first,
                                                          @ForAll("signedLevels") BigDecimal second,
                                                          @ForAll("selectors") long selector) {
        FloatingRateFormula formula = ((FloatingCoupon) terms.coupon()).formula();
        BigDecimal low = first.min(second);
        BigDecimal high = first.max(second);
        DatedPeriod period = periodAt(terms.schedule(), selector);
        BondDefinition bond = terms.toBond();
        DayCount dayCount = terms.basis().dayCount();
        Money atLow = BondCalculations.couponAmount(bond, period, dayCount, level(low));
        Money atHigh = BondCalculations.couponAmount(bond, period, dayCount, level(high));
        assertTrue(atLow.compareTo(atHigh) <= 0, "the coupon never decreases when the fixing rises");

        BigDecimal lowRate = low.add(formula.spread());
        BigDecimal highRate = high.add(formula.spread());
        if (formula.floor().isPresent() && lowRate.compareTo(formula.floor().orElseThrow()) <= 0
                && highRate.compareTo(formula.floor().orElseThrow()) <= 0) {
            assertSameAmount(atLow, atHigh, "fixings that stay at or below the floor all pay the floor");
        }
        if (formula.cap().isPresent() && lowRate.compareTo(formula.cap().orElseThrow()) >= 0
                && highRate.compareTo(formula.cap().orElseThrow()) >= 0) {
            assertSameAmount(atLow, atHigh, "fixings that reach the cap all pay the cap");
        }
    }

    /** A floor above the cap is rejected by the formula; an ordered pair (equal bounds included) is accepted. */
    @Property(tries = 250)
    void floorAboveCapIsRejectedAndOrderedBoundsAreAccepted(@ForAll("signedLevels") BigDecimal floor,
                                                            @ForAll("signedLevels") BigDecimal cap) {
        DayCount dayCount = DayCounts.act365F();
        if (floor.compareTo(cap) > 0) {
            assertThrows(IllegalArgumentException.class,
                    () -> new FloatingRateFormula(RATE_INDEX, 0L, BigDecimal.ZERO, dayCount,
                            Optional.of(floor), Optional.of(cap)),
                    "a floor above the cap is rejected");
        } else {
            FloatingRateFormula accepted = new FloatingRateFormula(RATE_INDEX, 0L, BigDecimal.ZERO, dayCount,
                    Optional.of(floor), Optional.of(cap));
            assertEquals(Optional.of(floor), accepted.floor(), "an ordered floor is kept as given");
            assertEquals(Optional.of(cap), accepted.cap(), "an ordered cap is kept as given");
        }
    }

    /**
     * With non-negative rates the accrual of a floating coupon equals the accrual of a fixed coupon at the
     * clamped rate, on the formula's day count, for every settle date of the period (fixing never in the future).
     */
    @Property(tries = 250)
    void accruedUsesTheSameClampAndTheFormulaDayCount(@ForAll("nonNegativeFloatingTerms") Terms terms) {
        FloatingRateFormula formula = ((FloatingCoupon) terms.coupon()).formula();
        BigDecimal rate = clampedRate(formula, terms.indexValue());
        BondDefinition floatingBond = terms.toBond();
        BondDefinition fixedBond = bond(terms.schedule(), principal(terms.faceMinor(), terms.events()),
                Optional.of(new FixedCoupon(rate, terms.schedule().periods())));
        for (DatedPeriod period : terms.schedule().periods()) {
            for (LocalDate day = period.accrualStart(); day.isBefore(period.accrualEnd()); day = day.plusDays(1)) {
                AccruedInterest floating = accruedOn(floatingBond, day, terms);
                AccruedInterest fixed = BondCalculations.accrued(fixedBond, day, formula.dayCount(), unqueried());
                assertEquals(fixed.period(), floating.period(), "the period matches the fixed-rate equivalent on " + day);
                assertSameAmount(fixed.amount(), floating.amount(),
                        "the clamped floating accrual matches the fixed-rate equivalent on " + day);
                assertSame(formula.dayCount(), floating.dayCount(), "the accrual reports the formula's day count");
            }
        }
    }

    /**
     * A fixing dated after the settle date is reported before any source query, with the formula's index and the
     * fixing date. An available fixing is requested exactly once, with that index and date.
     */
    @Property(tries = 250)
    void futureFixingIsReportedByItsDateBeforeAnySourceQuery(@ForAll("floatingAnyTerms") Terms terms,
                                                             @ForAll("selectors") long selector) {
        FloatingRateFormula formula = ((FloatingCoupon) terms.coupon()).formula();
        DatedPeriod period = periodAt(terms.schedule(), selector);
        LocalDate fixingDate = period.accrualStart().plusDays(formula.fixingOffsetDays());
        BondDefinition bond = terms.toBond();
        DayCount dayCount = terms.basis().dayCount();
        for (LocalDate day = period.accrualStart(); day.isBefore(period.accrualEnd()); day = day.plusDays(1)) {
            LocalDate settle = day;
            RecordingSource source = new RecordingSource(terms.indexValue());
            if (fixingDate.isAfter(settle)) {
                MissingIndexValueException missing = assertThrows(MissingIndexValueException.class,
                        () -> BondCalculations.accrued(bond, settle, dayCount, source),
                        "a fixing dated after the settle date is not available yet");
                assertEquals(formula.index(), missing.index(), "the guard names the formula index");
                assertEquals(fixingDate, missing.date(), "the guard names the fixing date");
                assertTrue(source.queries().isEmpty(), "the guard runs before the source is consulted");
            } else {
                BondCalculations.accrued(bond, settle, dayCount, source);
                assertEquals(List.of(new Query(formula.index(), fixingDate)), source.queries(),
                        "an available fixing is requested once, with the exact index and date");
            }
        }
    }

    /**
     * An index base must be positive and an observation lag non-negative; otherwise the rule is rejected. The
     * observation date is the date minus the lag, so adding the lag back returns the date.
     */
    @Property(tries = 250)
    void indexationRuleRejectsInvalidInputsAndShiftsObservationByLag(@ForAll("indexBases") BigDecimal base,
                                                                     @ForAll("lags") long lag,
                                                                     @ForAll("dates") LocalDate on) {
        if (base.signum() <= 0 || lag < 0) {
            assertThrows(IllegalArgumentException.class, () -> new IndexationRule(RATE_INDEX, base, BASE, lag),
                    "a non-positive base or a negative lag is rejected");
            return;
        }
        IndexationRule rule = new IndexationRule(RATE_INDEX, base, BASE, lag);
        assertEquals(on.minusDays(lag), rule.observationDate(on), "the observation date is the lag before the date");
        assertEquals(on, rule.observationDate(on).plusDays(lag), "the lag shifts the observation back exactly");
        assertTrue(rule.observationDate(on.plusDays(1)).isAfter(rule.observationDate(on)),
                "observation dates advance with the date");
    }

    /**
     * The indexed face is the nominal on the date times the observed ratio of the index to its base. The source is
     * asked exactly once, for the lag-shifted date.
     */
    @Property(tries = 250)
    void indexedFaceUsesTheLagShiftedObservationAndTheRatioOfTheNominal(@ForAll("indexedBonds") IndexedBond indexed,
                                                                        @ForAll("selectors") long selector) {
        LocalDate on = calculationDayAt(indexed.schedule(), selector);
        RecordingSource source = new RecordingSource(indexed.level());
        Money face = BondCalculations.facePerUnit(indexed.toBond(), on, source);
        LocalDate observation = on.minusDays(indexed.rule().observationLagDays());
        assertEquals(List.of(new Query(LINK_INDEX, observation)), source.queries(),
                "the face asks once for the exact index at the lag-shifted date");
        BigDecimal expected = divide(nominalOn(faceAmount(indexed.faceMinor()), indexed.events(), on)
                .multiply(indexed.level()), indexed.rule().baseIndexValue());
        assertWithin(expected, face, tolerance(1), "the indexed face is the nominal times the observed ratio");
    }

    /** Amortization reduces the nominal before indexation: the result equals indexing the reduced nominal once. */
    @Property(tries = 250)
    void amortizationReducesTheNominalBeforeIndexation(@ForAll("indexedBonds") IndexedBond indexed,
                                                       @ForAll("selectors") long selector) {
        LocalDate on = calculationDayAt(indexed.schedule(), selector);
        BigDecimal nominal = nominalOn(faceAmount(indexed.faceMinor()), indexed.events(), on);
        BondDefinition reduced = bond(indexed.schedule(),
                new Principal(new Money(nominal, RUB), List.of(), Optional.of(indexed.rule())), Optional.empty());
        IndexValueSource fixings = level(indexed.level());
        assertSameAmount(BondCalculations.facePerUnit(reduced, on, fixings),
                BondCalculations.facePerUnit(indexed.toBond(), on, fixings),
                "the face after amortization is indexed exactly once, from the reduced nominal");
    }

    /** A negative indexed face is rejected with the domain message; a zero index gives a zero face. */
    @Property(tries = 250)
    void negativeIndexedFaceIsRejectedAndZeroIndexGivesZeroFace(@ForAll("indexedBonds") IndexedBond indexed,
                                                                @ForAll("negativeLevels") BigDecimal negative,
                                                                @ForAll("selectors") long selector) {
        BondDefinition bond = indexed.toBond();
        LocalDate on = calculationDayAt(indexed.schedule(), selector);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> BondCalculations.facePerUnit(bond, on, level(negative)),
                "a negative index value that makes the face negative is rejected");
        assertEquals("Indexation must not produce negative outstanding face", rejected.getMessage());
        assertTrue(BondCalculations.facePerUnit(bond, on, level(BigDecimal.ZERO)).isZero(),
                "a zero index value gives a zero face");
    }

    /**
     * An index-linked coupon asks the source once for the reference date minus the lag. Its amount is the rate
     * times the observed ratio times the nominal. An observed ratio equal to the base reproduces the fixed coupon.
     */
    @Property(tries = 250)
    void indexLinkedCouponUsesItsReferenceObservationAndRatio(@ForAll("linkedTerms") Terms terms,
                                                              @ForAll("selectors") long selector) {
        IndexLinkedCoupon linked = (IndexLinkedCoupon) terms.coupon();
        DatedPeriod period = periodAt(terms.schedule(), selector);
        BondDefinition bond = terms.toBond();
        DayCount dayCount = terms.basis().dayCount();
        RecordingSource source = new RecordingSource(terms.indexValue());
        Money actual = BondCalculations.couponAmount(bond, period, dayCount, source);
        LocalDate reference = switch (linked.referenceDate()) {
            case PERIOD_START -> period.accrualStart();
            case PERIOD_END, CALCULATION_DATE -> period.accrualEnd();
        };
        assertEquals(List.of(new Query(LINK_INDEX, reference.minusDays(linked.indexation().observationLagDays()))),
                source.queries(), "the coupon asks once for the reference date minus the observation lag");
        assertWithin(exactInterest(faceAmount(terms.faceMinor()), terms.events(), period.accrualStart(),
                        period.accrualEnd(), annualMultiplier(linked, terms.indexValue()), terms.basis()),
                actual, tolerance(1), "the index-linked coupon is the rate times the observed ratio times the nominal");
        BondDefinition fixed = bond(terms.schedule(), principal(terms.faceMinor(), terms.events()),
                Optional.of(new FixedCoupon(linked.annualRate(), terms.schedule().periods())));
        assertSameAmount(BondCalculations.couponAmount(fixed, period, dayCount, unqueried()),
                BondCalculations.couponAmount(bond, period, dayCount, level(linked.indexation().baseIndexValue())),
                "an observed ratio of one reproduces the fixed-rate coupon exactly");
    }

    /**
     * A negative observed coefficient (observation divided by base) is rejected with the domain message, for the coupon
     * and for the accrual on the period start and on a random day of the period, whatever the rate. A zero observation
     * is valid: it gives a zero coupon and a zero accrual.
     */
    @Property(tries = 250)
    void negativeIndexCoefficientIsRejectedForLinkedCouponAndAccrued(
            @ForAll("linkedTermsWithoutLookahead") Terms terms, @ForAll("negativeLevels") BigDecimal negative,
            @ForAll("selectors") long selector) {
        DatedPeriod period = periodAt(terms.schedule(), selector);
        BondDefinition bond = terms.toBond();
        DayCount dayCount = terms.basis().dayCount();
        IndexValueSource negativeObservation = level(negative);
        IllegalArgumentException coupon = assertThrows(IllegalArgumentException.class,
                () -> BondCalculations.couponAmount(bond, period, dayCount, negativeObservation),
                "a negative coefficient rejects the index-linked coupon");
        assertEquals(NEGATIVE_COEFFICIENT, coupon.getMessage(), "the coupon reports the coefficient rule");
        for (LocalDate settle : List.of(period.accrualStart(), dayAt(period, selector))) {
            IllegalArgumentException accrued = assertThrows(IllegalArgumentException.class,
                    () -> BondCalculations.accrued(bond, settle, dayCount, negativeObservation),
                    "a negative coefficient rejects the accrual on " + settle);
            assertEquals(NEGATIVE_COEFFICIENT, accrued.getMessage(), "the accrual reports the coefficient rule");
        }
        IndexValueSource zeroObservation = level(BigDecimal.ZERO);
        assertSameAmount(Money.zero(RUB), BondCalculations.couponAmount(bond, period, dayCount, zeroObservation),
                "a zero observation gives a zero coupon");
        assertSameAmount(Money.zero(RUB), BondCalculations.accrued(bond, dayAt(period, selector), dayCount,
                zeroObservation).amount(), "a zero observation gives a zero accrual");
    }

    /**
     * A period-end observation that does not exist yet blocks accrual before the source is consulted, reporting the
     * observation date. Once it exists, the accrual requests exactly that date.
     */
    @Property(tries = 250)
    void periodEndObservationIsNotUsedBeforeItExists(@ForAll("periodEndTerms") Terms terms,
                                                     @ForAll("selectors") long selector) {
        IndexLinkedCoupon linked = (IndexLinkedCoupon) terms.coupon();
        DatedPeriod period = periodAt(terms.schedule(), selector);
        BondDefinition bond = terms.toBond();
        DayCount dayCount = terms.basis().dayCount();
        LocalDate observation = period.accrualEnd().minusDays(linked.indexation().observationLagDays());
        BigDecimal face = faceAmount(terms.faceMinor());
        BigDecimal multiplier = annualMultiplier(linked, terms.indexValue());
        for (LocalDate day = period.accrualStart(); day.isBefore(period.accrualEnd()); day = day.plusDays(1)) {
            LocalDate settle = day;
            RecordingSource source = new RecordingSource(terms.indexValue());
            if (observation.isAfter(settle)) {
                MissingIndexValueException missing = assertThrows(MissingIndexValueException.class,
                        () -> BondCalculations.accrued(bond, settle, dayCount, source),
                        "a period-end observation that does not exist yet is rejected");
                assertEquals(LINK_INDEX, missing.index(), "the guard names the linked index");
                assertEquals(observation, missing.date(), "the guard names the future observation date");
                assertTrue(source.queries().isEmpty(), "the guard runs before the source is consulted");
            } else {
                AccruedInterest accrued = BondCalculations.accrued(bond, settle, dayCount, source);
                assertEquals(List.of(new Query(LINK_INDEX, observation)), source.queries(),
                        "an available observation is requested once, with the exact date");
                assertWithin(exactInterest(face, terms.events(), period.accrualStart(), settle, multiplier,
                                terms.basis()),
                        accrued.amount(), tolerance(1), "accrual with an available observation on " + settle);
            }
        }
    }

    /**
     * An indexed principal rejects every formula coupon (fixed, floating, index-linked) in both accrual and coupon
     * queries, but accepts a known coupon whose amounts are announced.
     */
    @Property(tries = 250)
    void indexedPrincipalRejectsFormulaCouponsAndAcceptsKnownAmounts(
            @ForAll("indexedCouponBonds") IndexedBond indexed, @ForAll("selectors") long selector) {
        Coupon coupon = indexed.coupon().orElseThrow();
        BondDefinition bond = indexed.toBond();
        IndexValueSource fixings = level(indexed.level());
        DatedPeriod period = periodAt(indexed.schedule(), selector);
        LocalDate settle = dayAt(period, selector / 5);
        DayCount dayCount = DayCounts.act365F();
        if (coupon instanceof KnownCoupon known) {
            Money announced = known.amounts().stream()
                    .filter(amount -> amount.period().equals(period))
                    .findFirst().orElseThrow().amountPerUnit();
            assertSameAmount(announced, BondCalculations.couponAmount(bond, period, dayCount, fixings),
                    "a known amount on an indexed principal stays authoritative");
            BondCalculations.accrued(bond, settle, dayCount, fixings);
        } else {
            assertThrows(UnsupportedCalculationException.class,
                    () -> BondCalculations.accrued(bond, settle, dayCount, fixings),
                    "formula accrual on an indexed principal is rejected");
            assertThrows(UnsupportedCalculationException.class,
                    () -> BondCalculations.couponAmount(bond, period, dayCount, fixings),
                    "a formula coupon on an indexed principal is rejected");
        }
    }

    /**
     * A foreign or null failure from the source never escapes with another index or date: the calculation reports the
     * requested index and fixing date, and an exact failure is rethrown unchanged.
     */
    @Property(tries = 250)
    void floatingFailuresKeepTheRequestedIndexAndDate(@ForAll("floatingAnyTerms") Terms terms,
                                                      @ForAll("failureKinds") int kind,
                                                      @ForAll("selectors") long selector) {
        FloatingRateFormula formula = ((FloatingCoupon) terms.coupon()).formula();
        DatedPeriod period = periodAt(terms.schedule(), selector);
        LocalDate fixingDate = period.accrualStart().plusDays(formula.fixingOffsetDays());
        MissingIndexValueException failure = failureOf(kind, formula.index(), fixingDate);
        IndexValueSource source = failing(failure);
        BondDefinition bond = terms.toBond();
        DayCount dayCount = terms.basis().dayCount();

        MissingIndexValueException couponFailure = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.couponAmount(bond, period, dayCount, source),
                "a coupon fixing failure is reported");
        assertFailureNormalized(couponFailure, formula.index(), fixingDate, failure);

        if (fixingDate.isBefore(period.accrualEnd())) {
            LocalDate settle = fixingDate.isAfter(period.accrualStart()) ? fixingDate : period.accrualStart();
            MissingIndexValueException accruedFailure = assertThrows(MissingIndexValueException.class,
                    () -> BondCalculations.accrued(bond, settle, dayCount, source),
                    "an accrued fixing failure is reported");
            assertFailureNormalized(accruedFailure, formula.index(), fixingDate, failure);
        }
    }

    /** The same normalization for the indexed face: the requested index and the lag-shifted observation date. */
    @Property(tries = 250)
    void indexedFaceFailuresKeepTheRequestedIndexAndObservation(@ForAll("indexedBonds") IndexedBond indexed,
                                                                @ForAll("failureKinds") int kind,
                                                                @ForAll("selectors") long selector) {
        LocalDate on = calculationDayAt(indexed.schedule(), selector);
        LocalDate observation = on.minusDays(indexed.rule().observationLagDays());
        MissingIndexValueException failure = failureOf(kind, LINK_INDEX, observation);
        IndexValueSource source = failing(failure);
        BondDefinition bond = indexed.toBond();
        MissingIndexValueException thrown = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.facePerUnit(bond, on, source), "an indexed face failure is reported");
        assertFailureNormalized(thrown, LINK_INDEX, observation, failure);
    }

    /** The exception keeps the index and date it was built with, and keeps a wrapped cause unchanged. */
    @Property(tries = 250)
    void missingIndexValueExceptionKeepsIndexDateAndCause(@ForAll("indexNames") String name,
                                                          @ForAll("dates") LocalDate date) {
        IndexId index = new IndexId(name);
        MissingIndexValueException plain = new MissingIndexValueException(index, date);
        assertEquals(index, plain.index(), "index() returns the requested index");
        assertEquals(date, plain.date(), "date() returns the requested date");
        assertNull(plain.getCause(), "no cause without a source failure");

        IllegalStateException cause = new IllegalStateException("source failed");
        MissingIndexValueException wrapped = new MissingIndexValueException(index, date, cause);
        assertEquals(index, wrapped.index(), "a wrapped exception keeps the index");
        assertEquals(date, wrapped.date(), "a wrapped exception keeps the date");
        assertSame(cause, wrapped.getCause(), "a wrapped exception keeps the cause");

        assertThrows(NullPointerException.class, () -> new MissingIndexValueException(null, date),
                "the index is required");
        assertThrows(NullPointerException.class, () -> new MissingIndexValueException(index, null),
                "the date is required");
    }

    @Provide
    Arbitrary<Terms> floatingAnyTerms() {
        return termValues(schedule -> floatingCoupons(schedule, -3L, 10L, -500L, 500L, -300L, 3000L),
                fourPlaces(0L, 3000L));
    }

    @Provide
    Arbitrary<Terms> nonNegativeFloatingTerms() {
        return termValues(schedule -> floatingCoupons(schedule, -3L, 0L, 0L, 500L, 0L, 3000L),
                fourPlaces(0L, 3000L));
    }

    @Provide
    Arbitrary<Terms> linkedTerms() {
        // Observations stay non-negative: a negative coefficient is rejected, not accrued (see the negative property).
        return termValues(schedule -> linkedCoupons(schedule, Arbitraries.of(IndexLinkedCoupon.ReferenceDate.values())),
                fourPlaces(0L, 3000L));
    }

    @Provide
    Arbitrary<Terms> periodEndTerms() {
        // Observations stay non-negative: a negative coefficient is rejected, not accrued (see the negative property).
        return termValues(schedule -> linkedCoupons(schedule,
                        Arbitraries.just(IndexLinkedCoupon.ReferenceDate.PERIOD_END)),
                fourPlaces(0L, 3000L));
    }

    /** Reference is the period start or the settle date, so the observation never lies after the settle date. */
    @Provide
    Arbitrary<Terms> linkedTermsWithoutLookahead() {
        return termValues(schedule -> linkedCoupons(schedule,
                        Arbitraries.of(IndexLinkedCoupon.ReferenceDate.PERIOD_START,
                                IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE)),
                fourPlaces(0L, 3000L));
    }

    @Provide
    Arbitrary<IndexedBond> indexedBonds() {
        return indexedBondValues(schedule -> Arbitraries.just(Optional.<Coupon>empty()), fourPlaces(0L, 30_000L));
    }

    @Provide
    Arbitrary<IndexedBond> indexedCouponBonds() {
        return indexedBondValues(schedule -> anyCoupon(schedule).map(Optional::of), fourPlaces(0L, 30_000L));
    }

    @Provide
    Arbitrary<BigDecimal> signedLevels() {
        return fourPlaces(-500L, 4000L);
    }

    @Provide
    Arbitrary<BigDecimal> negativeLevels() {
        return fourPlaces(-30_000L, -1L);
    }

    @Provide
    Arbitrary<BigDecimal> indexBases() {
        return fourPlaces(-100L, 20_000L);
    }

    @Provide
    Arbitrary<Long> lags() {
        return Arbitraries.longs().between(-5L, 30L);
    }

    @Provide
    Arbitrary<Integer> failureKinds() {
        return Arbitraries.integers().between(0, 4);
    }

    @Provide
    Arbitrary<String> indexNames() {
        return Arbitraries.integers().between(1, 50).map(number -> "IDX-" + number);
    }

    @Provide
    Arbitrary<Long> selectors() {
        return selectorValues();
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return dateValues();
    }
}
