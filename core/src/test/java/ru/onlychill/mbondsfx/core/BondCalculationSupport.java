package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
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
import ru.onlychill.mbondsfx.core.bond.Principal;
import ru.onlychill.mbondsfx.core.bond.RedemptionOptions;
import ru.onlychill.mbondsfx.core.bond.calculation.AccruedInterest;
import ru.onlychill.mbondsfx.core.bond.calculation.BondCalculations;
import ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.DayCount;
import ru.onlychill.mbondsfx.core.money.DayCounts;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared generators, exact oracles and bond builders for the calculation properties.
 *
 * <p>Amounts are RUB with two decimal places. An exact amount is the sum over days of
 * {@code nominal * multiplier / yearLength}: every day weighs one ACT-family day of its calendar year,
 * so the oracle reproduces each segment's year fraction without reusing the production segmentation.
 * A production amount is one HALF_UP rounding of the exact value, hence {@link #tolerance(int)}.
 */
final class BondCalculationSupport {
    static final Currency RUB = Currency.getInstance("RUB");
    static final SecurityId SECURITY = new SecurityId("CALC-PROPERTY");
    static final IndexId RATE_INDEX = new IndexId("CALC-RATE");
    static final IndexId LINK_INDEX = new IndexId("CALC-LINK");
    static final LocalDate BASE = LocalDate.of(2026, 1, 1);
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");
    private static final BigDecimal SLACK = new BigDecimal("0.000000000000001");
    private static final MathContext ORACLE = new MathContext(50);

    private BondCalculationSupport() {
    }

    /** Coupon schedule: sorted, non-overlapping periods; maturity is at or after the last accrual end. */
    record Schedule(LocalDate issue, LocalDate maturity, List<DatedPeriod> periods) {
    }

    /** Day-count basis; one day weighs 1 / yearLength of its calendar year. */
    enum Basis {
        ACT_365F,
        ACT_360,
        ACT_ACT_ISDA;

        DayCount dayCount() {
            return switch (this) {
                case ACT_365F -> DayCounts.act365F();
                case ACT_360 -> DayCounts.act360();
                case ACT_ACT_ISDA -> DayCounts.actActIsda();
            };
        }

        int yearLength(LocalDate day) {
            return switch (this) {
                case ACT_365F -> 365;
                case ACT_360 -> 360;
                case ACT_ACT_ISDA -> day.isLeapYear() ? 366 : 365;
            };
        }

        static Basis of(DayCount dayCount) {
            for (Basis basis : values()) {
                if (basis.dayCount() == dayCount) {
                    return basis;
                }
            }
            throw new IllegalArgumentException("Unknown day count: " + dayCount);
        }
    }

    /** A bond with one coupon; {@code indexValue} is the constant level returned for every fixing. */
    record Terms(Schedule schedule, long faceMinor, List<AmortizationEvent> events, Coupon coupon,
                 BigDecimal indexValue, Basis basis) {
        BondDefinition toBond() {
            return bond(schedule, principal(faceMinor, events), Optional.of(coupon));
        }
    }

    /** A bond with an indexed principal and an optional coupon (formula or known). */
    record IndexedBond(Schedule schedule, long faceMinor, List<AmortizationEvent> events, IndexationRule rule,
                       BigDecimal level, Optional<Coupon> coupon) {
        BondDefinition toBond() {
            return bond(schedule, new Principal(money(faceMinor), events, Optional.of(rule)), coupon);
        }
    }

    /** One requested index value: the exact index and date that a calculation asked the source for. */
    record Query(IndexId index, LocalDate date) {
    }

    // ---------------------------------------------------------------- bond builders

    static Money money(long minor) {
        return new Money(faceAmount(minor), RUB);
    }

    static BigDecimal faceAmount(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    static AmortizationEvent event(LocalDate date, long reductionMinor) {
        return new AmortizationEvent(date, money(reductionMinor));
    }

    static Principal principal(long faceMinor, List<AmortizationEvent> events) {
        return new Principal(money(faceMinor), events, Optional.empty());
    }

    static BondDefinition bond(Schedule schedule, Principal principal, Optional<Coupon> coupon) {
        return new BondDefinition(SECURITY, RUB, schedule.issue(), Optional.of(schedule.maturity()), coupon,
                principal, RedemptionOptions.none());
    }

    static Coupon withPeriods(Coupon coupon, List<DatedPeriod> periods) {
        if (coupon instanceof FixedCoupon fixed) {
            return new FixedCoupon(fixed.annualRate(), periods);
        }
        if (coupon instanceof FloatingCoupon floating) {
            return new FloatingCoupon(floating.formula(), periods);
        }
        if (coupon instanceof IndexLinkedCoupon linked) {
            return new IndexLinkedCoupon(linked.annualRate(), linked.indexation(), linked.referenceDate(), periods);
        }
        throw new IllegalArgumentException("Only formula coupons can be re-scheduled: " + coupon);
    }

    static FloatingRateFormula formula(long offset, BigDecimal spread, Basis basis,
                                       Optional<BigDecimal> floor, Optional<BigDecimal> cap) {
        boolean inverted = floor.isPresent() && cap.isPresent()
                && floor.orElseThrow().compareTo(cap.orElseThrow()) > 0;
        return inverted
                ? new FloatingRateFormula(RATE_INDEX, offset, spread, basis.dayCount(), cap, floor)
                : new FloatingRateFormula(RATE_INDEX, offset, spread, basis.dayCount(), floor, cap);
    }

    // ---------------------------------------------------------------- calendar helpers

    static long days(DatedPeriod period) {
        return ChronoUnit.DAYS.between(period.accrualStart(), period.accrualEnd());
    }

    static DatedPeriod periodAt(Schedule schedule, long selector) {
        return schedule.periods().get(Math.floorMod(selector, schedule.periods().size()));
    }

    /** Day inside the half-open accrual interval of {@code period}. */
    static LocalDate dayAt(DatedPeriod period, long selector) {
        return period.accrualStart().plusDays(Math.floorMod(selector, days(period)));
    }

    /** Day inside the closed calculation window [issue, maturity]. */
    static LocalDate calculationDayAt(Schedule schedule, long selector) {
        long span = ChronoUnit.DAYS.between(schedule.issue(), schedule.maturity());
        return schedule.issue().plusDays(Math.floorMod(selector, span + 1));
    }

    static Optional<DatedPeriod> periodContaining(List<DatedPeriod> periods, LocalDate date) {
        return periods.stream()
                .filter(period -> !date.isBefore(period.accrualStart()) && date.isBefore(period.accrualEnd()))
                .findFirst();
    }

    // ---------------------------------------------------------------- exact oracles

    /** Nominal per unit on {@code on}: face less every amortization dated on or before it. */
    static BigDecimal nominalOn(BigDecimal face, List<AmortizationEvent> events, LocalDate on) {
        BigDecimal nominal = face;
        for (AmortizationEvent event : events) {
            if (!event.amortDate().isAfter(on)) {
                nominal = nominal.subtract(event.faceReductionPerUnit().amount());
            }
        }
        return nominal;
    }

    /** Exact unrounded interest on the half-open interval [start, end) for a constant annual multiplier. */
    static BigDecimal exactInterest(BigDecimal face, List<AmortizationEvent> events, LocalDate start,
                                    LocalDate end, BigDecimal multiplier, Basis basis) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
            weighted = weighted.add(divide(nominalOn(face, events, day), BigDecimal.valueOf(basis.yearLength(day))));
        }
        return weighted.multiply(multiplier);
    }

    /**
     * Exact unrounded interest on [start, end) on the 30E/360 basis. Each day weighs the 30E/360 days it spans, so
     * the day from the 30th to the 31st weighs zero: the segment the production calculation exempts from the strict check.
     */
    static BigDecimal exactThirty360Interest(BigDecimal face, List<AmortizationEvent> events, LocalDate start,
                                             LocalDate end, BigDecimal multiplier) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
            long days = thirty360Ordinal(day.plusDays(1)) - thirty360Ordinal(day);
            weighted = weighted.add(nominalOn(face, events, day).multiply(BigDecimal.valueOf(days)));
        }
        return divide(weighted.multiply(multiplier), BigDecimal.valueOf(360));
    }

    /** 30E/360 running day number: 360 per year, 30 per month, day of month capped at 30. */
    private static long thirty360Ordinal(LocalDate date) {
        return 360L * date.getYear() + 30L * date.getMonthValue() + Math.min(date.getDayOfMonth(), 30);
    }

    /** Exact share of the basis-weighted period length that lies in [start, settle). */
    static BigDecimal elapsedShare(LocalDate start, LocalDate settle, LocalDate end, Basis basis) {
        return divide(weight(start, settle, basis), weight(start, end, basis));
    }

    private static BigDecimal weight(LocalDate start, LocalDate end, Basis basis) {
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
            total = total.add(divide(BigDecimal.ONE, BigDecimal.valueOf(basis.yearLength(day))));
        }
        return total;
    }

    static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return dividend.divide(divisor, ORACLE);
    }

    /** Floating rate: fixing plus spread, then clamped into the optional floor and cap. */
    static BigDecimal clampedRate(FloatingRateFormula formula, BigDecimal fixing) {
        BigDecimal rate = fixing.add(formula.spread());
        if (formula.floor().isPresent()) {
            rate = rate.max(formula.floor().orElseThrow());
        }
        if (formula.cap().isPresent()) {
            rate = rate.min(formula.cap().orElseThrow());
        }
        return rate;
    }

    /** Multiplier applied to the nominal for a formula coupon at a constant index level. */
    static BigDecimal annualMultiplier(Coupon coupon, BigDecimal indexValue) {
        if (coupon instanceof FixedCoupon fixed) {
            return fixed.annualRate();
        }
        if (coupon instanceof FloatingCoupon floating) {
            return clampedRate(floating.formula(), indexValue);
        }
        if (coupon instanceof IndexLinkedCoupon linked) {
            return linked.annualRate().multiply(divide(indexValue, linked.indexation().baseIndexValue()));
        }
        throw new IllegalArgumentException("No formula multiplier for " + coupon);
    }

    /** Basis that prices a coupon: the formula's for floating coupons, the caller's otherwise. */
    static Basis basisOf(Coupon coupon, Basis callerBasis) {
        return coupon instanceof FloatingCoupon floating ? Basis.of(floating.formula().dayCount()) : callerBasis;
    }

    // ---------------------------------------------------------------- calculation shortcuts

    static AccruedInterest accruedOn(BondDefinition bond, LocalDate settle, Terms terms) {
        return BondCalculations.accrued(bond, settle, terms.basis().dayCount(), level(terms.indexValue()));
    }

    static Money couponOn(BondDefinition bond, DatedPeriod period, Terms terms) {
        return BondCalculations.couponAmount(bond, period, terms.basis().dayCount(), level(terms.indexValue()));
    }

    // ---------------------------------------------------------------- index sources

    /** Source returning the same level for every index and date. */
    static IndexValueSource level(BigDecimal value) {
        return (index, date) -> value;
    }

    /** Source that must never be consulted. */
    static IndexValueSource unqueried() {
        return (index, date) -> {
            throw new IllegalStateException("fixing requested for " + index + " on " + date);
        };
    }

    /** Source that throws {@code failure}, or returns null when {@code failure} is null. */
    static IndexValueSource failing(MissingIndexValueException failure) {
        return (index, date) -> {
            if (failure == null) {
                return null;
            }
            throw failure;
        };
    }

    /** Foreign failure kinds: 0 exact, 1 foreign index, 2 foreign date, 3 both foreign, 4 null value. */
    static MissingIndexValueException failureOf(int kind, IndexId index, LocalDate date) {
        return switch (kind) {
            case 0 -> new MissingIndexValueException(index, date);
            case 1 -> new MissingIndexValueException(new IndexId(index.value() + "-OTHER"), date);
            case 2 -> new MissingIndexValueException(index, date.plusDays(1));
            case 3 -> new MissingIndexValueException(new IndexId("OTHER"), date.minusDays(2));
            default -> null;
        };
    }

    /** Records every request so that tests can assert the exact index and date asked for. */
    static final class RecordingSource implements IndexValueSource {
        private final BigDecimal value;
        private final List<Query> queries = new ArrayList<>();

        RecordingSource(BigDecimal value) {
            this.value = value;
        }

        @Override
        public BigDecimal value(IndexId index, LocalDate date) {
            queries.add(new Query(index, date));
            return value;
        }

        List<Query> queries() {
            return List.copyOf(queries);
        }
    }

    // ---------------------------------------------------------------- assertions

    /** Largest total error allowed when {@code roundings} HALF_UP roundings to RUB minor units are summed. */
    static BigDecimal tolerance(int roundings) {
        return HALF_CENT.multiply(BigDecimal.valueOf(roundings)).add(SLACK);
    }

    static void assertSameAmount(Money expected, Money actual, String message) {
        assertEquals(expected.currency(), actual.currency(), message);
        assertEquals(0, expected.amount().compareTo(actual.amount()),
                () -> message + ": expected " + expected + " but was " + actual);
    }

    static void assertWithin(BigDecimal expected, Money actual, BigDecimal tolerance, String message) {
        BigDecimal difference = actual.amount().subtract(expected).abs();
        assertTrue(difference.compareTo(tolerance) <= 0,
                () -> message + ": expected " + expected + " but was " + actual + " (tolerance " + tolerance + ")");
    }

    /**
     * The calculation must report the requested index and date. An exact failure is rethrown unchanged;
     * a foreign failure is replaced and kept as the cause; a null value has no cause.
     */
    static void assertFailureNormalized(MissingIndexValueException thrown, IndexId index, LocalDate date,
                                        MissingIndexValueException failure) {
        assertEquals(index, thrown.index(), "requested index is reported");
        assertEquals(date, thrown.date(), "requested date is reported");
        if (failure == null) {
            assertNull(thrown.getCause(), "a null value carries no cause");
        } else if (failure.index().equals(index) && failure.date().equals(date)) {
            assertSame(failure, thrown, "an exact failure is rethrown unchanged");
        } else {
            assertSame(failure, thrown.getCause(), "a foreign failure is kept as the cause");
        }
    }

    // ---------------------------------------------------------------- generators

    static Arbitrary<Basis> basisValues() {
        return Arbitraries.of(Basis.values());
    }

    static Arbitrary<Long> selectorValues() {
        return Arbitraries.longs().between(0L, 1_000_000L);
    }

    static Arbitrary<Long> faceValues() {
        return Arbitraries.longs().between(10_000L, 100_000_000L);
    }

    static Arbitrary<LocalDate> dateValues() {
        return Arbitraries.longs().between(0L, 1_000L).map(offset -> BASE.plusDays(offset));
    }

    /** Values with four decimal places between {@code min} and {@code max} in units of 0.0001. */
    static Arbitrary<BigDecimal> fourPlaces(long min, long max) {
        return Arbitraries.longs().between(min, max).map(units -> BigDecimal.valueOf(units, 4));
    }

    static Arbitrary<Optional<BigDecimal>> optionalFourPlaces(long min, long max) {
        return Arbitraries.oneOf(
                Arbitraries.just(Optional.<BigDecimal>empty()),
                fourPlaces(min, max).map(Optional::of));
    }

    /** Positive index base between 0.01 and 1000.00. */
    static Arbitrary<BigDecimal> indexBaseValues() {
        return Arbitraries.longs().between(1L, 100_000L).map(value -> BigDecimal.valueOf(value, 2));
    }

    static Arbitrary<IndexationRule> indexationRuleValues() {
        return Combinators.combine(indexBaseValues(), Arbitraries.longs().between(0L, 30L))
                .as((base, lag) -> new IndexationRule(LINK_INDEX, base, BASE, lag));
    }

    static Arbitrary<Schedule> scheduleValues() {
        return Arbitraries.integers().between(1, 3).flatMap(count -> Combinators.combine(
                        Arbitraries.longs().between(0L, 730L),
                        Arbitraries.longs().between(0L, 45L).list().ofSize(count),
                        Arbitraries.longs().between(1L, 200L).list().ofSize(count),
                        Arbitraries.longs().between(0L, 60L))
                .as((issueOffset, gaps, lengths, tail) -> schedule(BASE.plusDays(issueOffset), gaps, lengths, tail)));
    }

    static Schedule schedule(LocalDate issue, List<Long> gaps, List<Long> lengths, long tail) {
        List<DatedPeriod> periods = new ArrayList<>();
        LocalDate cursor = issue;
        for (int index = 0; index < gaps.size(); index++) {
            LocalDate start = cursor.plusDays(gaps.get(index));
            LocalDate end = start.plusDays(lengths.get(index));
            periods.add(new DatedPeriod(start, end, end));
            cursor = end;
        }
        return new Schedule(issue, cursor.plusDays(tail), List.copyOf(periods));
    }

    /** Dates after issue where amortization most likely hits a boundary; never empty (maturity qualifies). */
    private static List<LocalDate> boundaryDates(Schedule schedule) {
        List<LocalDate> dates = new ArrayList<>();
        dates.add(schedule.maturity());
        for (DatedPeriod period : schedule.periods()) {
            dates.add(period.accrualStart());
            dates.add(period.accrualEnd());
        }
        return dates.stream().filter(date -> date.isAfter(schedule.issue())).distinct().toList();
    }

    /** Up to three amortizations of 1..25% of face each, so the total stays below face. */
    static Arbitrary<List<AmortizationEvent>> eventValues(Schedule schedule, long faceMinor) {
        long span = ChronoUnit.DAYS.between(schedule.issue(), schedule.maturity());
        Arbitrary<LocalDate> dates = Arbitraries.oneOf(
                Arbitraries.of(boundaryDates(schedule)),
                Arbitraries.longs().between(1L, span).map(offset -> schedule.issue().plusDays(offset)));
        return Combinators.combine(dates, Arbitraries.longs().between(1L, 25L))
                .as((date, percent) -> event(date, faceMinor * percent / 100))
                .list().ofMaxSize(3);
    }

    /** Bonds with a single coupon from {@code couponsFor}; {@code levels} is the constant index level. */
    static Arbitrary<Terms> termValues(Function<Schedule, Arbitrary<Coupon>> couponsFor,
                                       Arbitrary<BigDecimal> levels) {
        return scheduleValues().flatMap(schedule -> faceValues().flatMap(faceMinor ->
                eventValues(schedule, faceMinor).flatMap(events -> couponsFor.apply(schedule).flatMap(coupon ->
                        levels.flatMap(level -> basisValues().map(basis ->
                                new Terms(schedule, faceMinor, events, coupon, level, basis)))))));
    }

    /** Indexed-principal bonds; {@code couponsFor} may return an empty coupon (zero-coupon bond). */
    static Arbitrary<IndexedBond> indexedBondValues(Function<Schedule, Arbitrary<Optional<Coupon>>> couponsFor,
                                                    Arbitrary<BigDecimal> levels) {
        return scheduleValues().flatMap(schedule -> faceValues().flatMap(faceMinor ->
                eventValues(schedule, faceMinor).flatMap(events -> indexationRuleValues().flatMap(rule ->
                        couponsFor.apply(schedule).flatMap(coupon ->
                                levels.map(level -> new IndexedBond(schedule, faceMinor, events, rule, level,
                                        coupon)))))));
    }

    static Arbitrary<Coupon> fixedCoupons(Schedule schedule, long minUnits, long maxUnits) {
        return fourPlaces(minUnits, maxUnits).map(rate -> (Coupon) new FixedCoupon(rate, schedule.periods()));
    }

    static Arbitrary<Coupon> floatingCoupons(Schedule schedule, long minOffset, long maxOffset, long minSpread,
                                             long maxSpread, long minLimit, long maxLimit) {
        return Combinators.combine(
                        Arbitraries.longs().between(minOffset, maxOffset),
                        fourPlaces(minSpread, maxSpread),
                        optionalFourPlaces(minLimit, maxLimit),
                        optionalFourPlaces(minLimit, maxLimit),
                        basisValues())
                .as((offset, spread, floor, cap, basis) -> (Coupon) new FloatingCoupon(
                        formula(offset, spread, basis, floor, cap), schedule.periods()));
    }

    static Arbitrary<Coupon> linkedCoupons(Schedule schedule, Arbitrary<IndexLinkedCoupon.ReferenceDate> references) {
        return Combinators.combine(fourPlaces(0L, 3000L), indexBaseValues(), Arbitraries.longs().between(0L, 30L),
                        references)
                .as((rate, base, lag, reference) -> (Coupon) new IndexLinkedCoupon(rate,
                        new IndexationRule(LINK_INDEX, base, BASE, lag), reference, schedule.periods()));
    }

    /** Amounts for every period, with {@code scale} decimal places (2 for RUB minor units). */
    static Arbitrary<Coupon> knownCoupons(Schedule schedule, int scale, long maxMinorUnits) {
        return Arbitraries.longs().between(0L, maxMinorUnits).list().ofSize(schedule.periods().size())
                .map(amounts -> {
                    List<KnownCoupon.Amount> announced = new ArrayList<>();
                    for (int index = 0; index < amounts.size(); index++) {
                        announced.add(new KnownCoupon.Amount(schedule.periods().get(index),
                                new Money(BigDecimal.valueOf(amounts.get(index), scale), RUB)));
                    }
                    return (Coupon) new KnownCoupon(schedule.periods(), announced);
                });
    }

    /**
     * Non-negative effective rates: fixed, floating with non-negative floor/cap, linked, known. Index levels are
     * non-negative here, so linked coefficients are too; a negative coefficient is rejected rather than accrued.
     */
    static Arbitrary<Coupon> nonNegativeCoupons(Schedule schedule) {
        return Arbitraries.oneOf(
                fixedCoupons(schedule, 0L, 3000L),
                floatingCoupons(schedule, -3L, 0L, 0L, 500L, 0L, 3000L),
                linkedCoupons(schedule, Arbitraries.of(IndexLinkedCoupon.ReferenceDate.PERIOD_START,
                        IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE)),
                knownCoupons(schedule, 2, 1_000_000L));
    }

    /** Formula coupons whose amounts are a constant multiplier of the nominal: fixed, floating, linked. */
    static Arbitrary<Coupon> formulaCoupons(Schedule schedule) {
        return Arbitraries.oneOf(
                fixedCoupons(schedule, 0L, 3000L),
                floatingCoupons(schedule, -3L, 0L, 0L, 500L, 0L, 3000L),
                linkedCoupons(schedule, Arbitraries.of(IndexLinkedCoupon.ReferenceDate.PERIOD_START,
                        IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE)));
    }

    /** Any coupon kind with fixings that may lie after the accrual start. */
    static Arbitrary<Coupon> anyCoupon(Schedule schedule) {
        return Arbitraries.oneOf(
                fixedCoupons(schedule, 0L, 3000L),
                floatingCoupons(schedule, -3L, 10L, -500L, 500L, -300L, 3000L),
                linkedCoupons(schedule, Arbitraries.of(IndexLinkedCoupon.ReferenceDate.values())),
                knownCoupons(schedule, 2, 1_000_000L));
    }
}
