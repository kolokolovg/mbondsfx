package ru.onlychill.mbondsfx.core.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the day-count conventions returned by {@link DayCounts}: actual days for ACT/365F, ACT/360 and
 * ACT/ACT.ISDA; day-of-month capped at 30 and 360-day years for 30E/360; ISDA divides each calendar-year segment by
 * that year's length. 30/360 and 30E+/360 follow formula (1) of docs/specs/day-count/v1.0.0.md, and their properties
 * state only what those rules guarantee: additivity, for example, is limited to splits whose middle date the rules
 * keep intact. Year fractions use DECIMAL128, so approximate comparisons allow {@code 1E-25}.
 */
final class DayCountProperties {
    private static final BigDecimal TOLERANCE = BigDecimal.valueOf(1, 25);
    private static final LocalDate FIRST_DATE = LocalDate.of(1990, 1, 1);
    private static final LocalDate LAST_DATE = LocalDate.of(2060, 12, 31);

    @Property(tries = 250)
    void dayCountsAreZeroOnTheSameDateAndReverseSignWithTheInterval(@ForAll("conventions") Convention convention,
            @ForAll("dates") LocalDate start, @ForAll("dates") LocalDate end) {
        DayCount dayCount = convention.dayCount();
        assertEquals(0L, dayCount.days(start, start), "days on the same date are zero");
        assertEquals(0, dayCount.yearFraction(start, start).signum(), "fraction on the same date is zero");
        assertEquals(-dayCount.days(start, end), dayCount.days(end, start), "days reverse sign with the interval");
        assertEquals(0, dayCount.yearFraction(end, start).compareTo(dayCount.yearFraction(start, end).negate()),
                "year fraction reverses sign with the interval");
    }

    @Property(tries = 250)
    void dayCountsAddUpAcrossAnyIntermediateDate(@ForAll("conventions") Convention convention,
            @ForAll("dates") LocalDate a, @ForAll("dates") LocalDate b, @ForAll("dates") LocalDate c) {
        DayCount dayCount = convention.dayCount();
        assertEquals(dayCount.days(a, b) + dayCount.days(b, c), dayCount.days(a, c),
                "days add up across any split, in any order");
        assertWithinTolerance(dayCount.yearFraction(a, b).add(dayCount.yearFraction(b, c)),
                dayCount.yearFraction(a, c), "year fractions add up across any split, in any order");
    }

    @Property(tries = 250)
    void forwardIntervalsHaveNonNegativeDaysAndFractions(@ForAll("conventions") Convention convention,
            @ForAll("dates") LocalDate start, @ForAll("forwardSpans") int span) {
        DayCount dayCount = convention.dayCount();
        LocalDate end = start.plusDays(span);
        assertTrue(dayCount.days(start, end) >= 0, "days are non-negative when start is not after end");
        assertTrue(dayCount.yearFraction(start, end).signum() >= 0,
                "year fraction is non-negative when start is not after end");
    }

    @Property(tries = 250)
    void movingTheEndForwardNeverDecreasesTheCount(@ForAll("conventions") Convention convention,
            @ForAll("dates") LocalDate start, @ForAll("dates") LocalDate end, @ForAll("forwardSpans") int span) {
        DayCount dayCount = convention.dayCount();
        LocalDate later = end.plusDays(span);
        assertTrue(dayCount.days(start, end) <= dayCount.days(start, later), "days never decrease");
        assertTrue(dayCount.yearFraction(start, end).compareTo(dayCount.yearFraction(start, later)) <= 0,
                "year fraction never decreases");
    }

    @Property(tries = 250)
    void movingTheStartForwardNeverIncreasesTheCount(@ForAll("conventions") Convention convention,
            @ForAll("dates") LocalDate start, @ForAll("dates") LocalDate end, @ForAll("forwardSpans") int span) {
        DayCount dayCount = convention.dayCount();
        LocalDate later = start.plusDays(span);
        assertTrue(dayCount.days(later, end) <= dayCount.days(start, end), "days never increase");
        assertTrue(dayCount.yearFraction(later, end).compareTo(dayCount.yearFraction(start, end)) <= 0,
                "year fraction never increases");
    }

    @Property(tries = 250)
    void fixedBasisFractionTimesBasisRecoversTheDayCount(@ForAll("fixedBases") FixedBasis basis,
            @ForAll("dates") LocalDate start, @ForAll("dates") LocalDate end) {
        DayCount dayCount = basis.dayCount();
        BigDecimal scaled = dayCount.yearFraction(start, end).multiply(BigDecimal.valueOf(basis.length()));
        assertWithinTolerance(BigDecimal.valueOf(dayCount.days(start, end)), scaled,
                "fraction times basis equals days for " + basis);
    }

    @Property(tries = 250)
    void actualDayCountsMeasureWholeBasisPeriodsAsWholeYears(@ForAll("actualBases") FixedBasis basis,
            @ForAll("dates") LocalDate start, @ForAll("periodCounts") int periods) {
        LocalDate end = start.plusDays((long) periods * basis.length());
        assertEquals(0, basis.dayCount().yearFraction(start, end).compareTo(BigDecimal.valueOf(periods)),
                "whole basis periods are whole years for " + basis);
    }

    @Property(tries = 250)
    void actActIsdaFractionIsBoundedByLeapAndCommonYearLengths(@ForAll("dates") LocalDate start,
            @ForAll("forwardSpans") int span) {
        DayCount isda = DayCounts.actActIsda();
        LocalDate end = start.plusDays(span);
        BigDecimal fraction = isda.yearFraction(start, end);
        BigDecimal days = BigDecimal.valueOf(isda.days(start, end));
        // days / 366 <= fraction <= days / 365, checked without division.
        assertTrue(fraction.multiply(BigDecimal.valueOf(366)).compareTo(days.subtract(TOLERANCE)) >= 0,
                () -> "fraction is at least days / 366: " + fraction + " for " + days + " days");
        assertTrue(fraction.multiply(BigDecimal.valueOf(365)).compareTo(days.add(TOLERANCE)) <= 0,
                () -> "fraction is at most days / 365: " + fraction + " for " + days + " days");
    }

    @Property(tries = 250)
    void actActIsdaDividesWithinOneCalendarYearByThatYearsLength(@ForAll("dates") LocalDate start,
            @ForAll("withinYearOffsets") int offset) {
        DayCount isda = DayCounts.actActIsda();
        long daysToYearEnd = ChronoUnit.DAYS.between(start, start.withDayOfYear(start.lengthOfYear()));
        LocalDate end = start.plusDays(offset % (daysToYearEnd + 1));
        BigDecimal scaled = isda.yearFraction(start, end).multiply(BigDecimal.valueOf(start.lengthOfYear()));
        assertWithinTolerance(BigDecimal.valueOf(isda.days(start, end)), scaled,
                "a single calendar year is divided by its own length");
    }

    @Property(tries = 250)
    void actActIsdaCountsWholeCalendarYearsAsWholeYears(@ForAll("years") int year,
            @ForAll("wholeYearCounts") int count) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year + count, 1, 1);
        assertEquals(0, DayCounts.actActIsda().yearFraction(start, end).compareTo(BigDecimal.valueOf(count)),
                "whole calendar years count as whole years");
    }

    @Property(tries = 250)
    void thirtyE360CapsTheDayOfMonthAtThirty(@ForAll("dayThirtyOrThirtyOneDates") LocalDate date,
            @ForAll("dates") LocalDate other) {
        DayCount thirty = DayCounts.thirtyE360();
        LocalDate capped = capAtDay(date, 30);
        assertEquals(thirty.days(capped, other), thirty.days(date, other),
                "day 31 counts as day 30 when it starts an interval");
        assertEquals(thirty.days(other, capped), thirty.days(other, date),
                "day 31 counts as day 30 when it ends an interval");
    }

    @Property(tries = 250)
    void thirtyE360CountsEveryWholeMonthAsThirtyDays(@ForAll("datesUpToDayTwentyEight") LocalDate start,
            @ForAll("monthOffsets") int months) {
        assertEquals(30L * months, DayCounts.thirtyE360().days(start, start.plusMonths(months)),
                "each whole month is 30 days under 30E/360");
    }

    /**
     * Distinct dates reverse the sign of days and year fractions. The sign rule of docs/specs/day-count/v1.0.0.md
     * (section 2) covers a reversed interval, that is an end before the start, so equal dates are outside it.
     */
    @Property(tries = 250)
    void thirtyFamilyDayCountsReverseTheSignOfDistinctDates(@ForAll("thirtyFamilyConventions") Convention convention,
            @ForAll("dates") LocalDate start, @ForAll("dates") LocalDate end) {
        Assume.that(!start.equals(end));
        DayCount dayCount = convention.dayCount();
        assertEquals(-dayCount.days(start, end), dayCount.days(end, start), "days reverse sign with the interval");
        assertEquals(0, dayCount.yearFraction(end, start).compareTo(dayCount.yearFraction(start, end).negate()),
                "year fraction reverses sign with the interval");
    }

    /**
     * A zero-length interval has no days, except that 30E+/360 counts the 31st to the 31st as one day. That is the
     * approved definition (docs/specs/day-count/v1.0.1.md, point 4). Accrued interest never relies on it: on the start
     * date of a period the day-count is not consulted (BondCalculations.accrued).
     */
    @Property(tries = 250)
    void thirtyFamilySameDateIntervalsHaveNoDaysExceptThirtyFirstUnderThirtyEPlusAsOneDay(
            @ForAll("thirtyFamilyConventions") Convention convention, @ForAll("dates") LocalDate date) {
        long expectedDays = convention.code().equals("30E+/360") && date.getDayOfMonth() == 31 ? 1 : 0;
        DayCount dayCount = convention.dayCount();
        assertEquals(expectedDays, dayCount.days(date, date), "days from a date to itself");
        assertEquals(0, dayCount.yearFraction(date, date).compareTo(
                BigDecimal.valueOf(expectedDays).divide(BigDecimal.valueOf(360), MathContext.DECIMAL128)),
                "year fraction from a date to itself is the same number of days over 360");
    }

    @Property(tries = 250)
    void thirtyFamilyForwardIntervalsHaveNonNegativeDaysAndFractions(
            @ForAll("thirtyFamilyConventions") Convention convention, @ForAll("dates") LocalDate start,
            @ForAll("forwardSpans") int span) {
        DayCount dayCount = convention.dayCount();
        LocalDate end = start.plusDays(span);
        assertTrue(dayCount.days(start, end) >= 0, "days are non-negative when start is not after end");
        assertTrue(dayCount.yearFraction(start, end).signum() >= 0,
                "year fraction is non-negative when start is not after end");
    }

    /** Unlike 30/360, which gives 30 Jan to 31 Jan zero days, 30E+/360 counts every such forward interval as a day. */
    @Property(tries = 250)
    void thirtyEPlus360CountsEveryForwardIntervalOfDistinctDatesAsAtLeastOneDay(
            @ForAll("dates") LocalDate start, @ForAll("positiveSpans") int span) {
        assertTrue(DayCounts.thirtyEPlus360().days(start, start.plusDays(span)) >= 1,
                "30E+/360 counts a forward interval of distinct dates as at least one day");
    }

    @Property(tries = 250)
    void thirtyFamilyNeverDecreasesWhenTheEndMovesForward(@ForAll("thirtyFamilyConventions") Convention convention,
            @ForAll("dates") LocalDate start, @ForAll("dates") LocalDate end, @ForAll("forwardSpans") int span) {
        DayCount dayCount = convention.dayCount();
        LocalDate later = end.plusDays(span);
        assertTrue(dayCount.days(start, end) <= dayCount.days(start, later), "days never decrease");
        assertTrue(dayCount.yearFraction(start, end).compareTo(dayCount.yearFraction(start, later)) <= 0,
                "year fraction never decreases");
    }

    @Property(tries = 250)
    void thirtyFamilyNeverIncreasesWhenTheStartMovesForward(@ForAll("thirtyFamilyConventions") Convention convention,
            @ForAll("dates") LocalDate start, @ForAll("dates") LocalDate end, @ForAll("forwardSpans") int span) {
        DayCount dayCount = convention.dayCount();
        LocalDate later = start.plusDays(span);
        assertTrue(dayCount.days(later, end) <= dayCount.days(start, end), "days never increase");
        assertTrue(dayCount.yearFraction(later, end).compareTo(dayCount.yearFraction(start, end)) <= 0,
                "year fraction never increases");
    }

    /** Both conventions read a start on the 31st as the 30th: every forward interval from it matches the 30th. */
    @Property(tries = 250)
    void thirtyFamilyCountsAForwardStartOnTheThirtyFirstAsTheThirtieth(
            @ForAll("thirtyFamilyConventions") Convention convention, @ForAll("thirtyFirstDates") LocalDate start,
            @ForAll("forwardSpans") int span) {
        DayCount dayCount = convention.dayCount();
        LocalDate end = start.plusDays(span);
        assertEquals(dayCount.days(start.withDayOfMonth(30), end), dayCount.days(start, end),
                "day 31 counts as day 30 when it starts a forward interval");
    }

    /**
     * 30/360 reads an end on the 31st as the 30th only when the start is on the 30th or 31st. For a start on an earlier
     * day of the month the 31st stays, so the interval is exactly one day longer than the one that ends on the 30th.
     */
    @Property(tries = 250)
    void thirty360ReadsAForwardEndOnTheThirtyFirstAsTheThirtiethOnlyAfterAThirtyOrThirtyFirstStart(
            @ForAll("dates") LocalDate start, @ForAll("thirtyFirstDates") LocalDate end) {
        Assume.that(!start.isAfter(end));
        DayCount thirty360 = DayCounts.thirty360();
        long endingOnTheThirtieth = thirty360.days(start, end.withDayOfMonth(30));
        if (start.getDayOfMonth() >= 30) {
            assertEquals(endingOnTheThirtieth, thirty360.days(start, end),
                    "a start on the 30th or 31st reads the 31st end as the 30th");
        } else {
            assertEquals(endingOnTheThirtieth + 1, thirty360.days(start, end),
                    "an earlier start keeps the 31st end, one day longer than the 30th");
        }
    }

    /** 30E+/360 reads a forward end on the 31st as the 1st of the following month, whatever the start. */
    @Property(tries = 250)
    void thirtyEPlus360ReadsAForwardEndOnTheThirtyFirstAsTheFirstOfTheNextMonth(@ForAll("dates") LocalDate start,
            @ForAll("thirtyFirstDates") LocalDate end) {
        Assume.that(!start.isAfter(end));
        DayCount thirtyEPlus360 = DayCounts.thirtyEPlus360();
        assertEquals(thirtyEPlus360.days(start, end.plusMonths(1).withDayOfMonth(1)),
                thirtyEPlus360.days(start, end), "day 31 at the end counts as day 1 of the following month");
    }

    /**
     * 30/360 is additive over a forward split whose middle and end dates are not on the 31st. Other forward splits can
     * fail: 29 Jan to 31 Jan is 2 days, but 29 Jan to 30 Jan and 30 Jan to 31 Jan add up to only 1.
     */
    @Property(tries = 250)
    void thirty360IsAdditiveOverAForwardSplitWhenTheMiddleAndEndAreNotThirtyFirsts(
            @ForAll("nearbyDates") LocalDate first, @ForAll("nearbyDates") LocalDate second,
            @ForAll("nearbyDates") LocalDate third) {
        LocalDate[] sorted = {first, second, third};
        Arrays.sort(sorted);
        LocalDate start = sorted[0];
        LocalDate middle = sorted[1];
        LocalDate end = sorted[2];
        Assume.that(middle.getDayOfMonth() != 31 && end.getDayOfMonth() != 31);
        DayCount thirty360 = DayCounts.thirty360();
        assertEquals(thirty360.days(start, middle) + thirty360.days(middle, end), thirty360.days(start, end),
                "days add up across a forward split");
        assertWithinTolerance(thirty360.yearFraction(start, middle).add(thirty360.yearFraction(middle, end)),
                thirty360.yearFraction(start, end), "year fractions add up across a forward split");
    }

    /**
     * 30E+/360 is additive over a forward split whose middle date is not the 31st. A middle on the 31st ends the first
     * part as the 1st of the next month but starts the second part as the 30th, so one day is counted twice.
     */
    @Property(tries = 250)
    void thirtyEPlus360IsAdditiveOverAForwardSplitWhenTheMiddleIsNotThirtyFirst(
            @ForAll("nearbyDates") LocalDate first, @ForAll("nearbyDates") LocalDate second,
            @ForAll("nearbyDates") LocalDate third) {
        LocalDate[] sorted = {first, second, third};
        Arrays.sort(sorted);
        LocalDate start = sorted[0];
        LocalDate middle = sorted[1];
        LocalDate end = sorted[2];
        Assume.that(middle.getDayOfMonth() != 31);
        DayCount thirtyEPlus360 = DayCounts.thirtyEPlus360();
        assertEquals(thirtyEPlus360.days(start, middle) + thirtyEPlus360.days(middle, end),
                thirtyEPlus360.days(start, end), "days add up across a forward split");
        assertWithinTolerance(thirtyEPlus360.yearFraction(start, middle)
                        .add(thirtyEPlus360.yearFraction(middle, end)),
                thirtyEPlus360.yearFraction(start, end), "year fractions add up across a forward split");
    }

    /** With no date on the 31st, both conventions are additive for any order of the three dates. */
    @Property(tries = 250)
    void thirtyFamilyIsAdditiveWhenNoDateIsThirtyFirst(@ForAll("thirtyFamilyConventions") Convention convention,
            @ForAll("nearbyDates") LocalDate first, @ForAll("nearbyDates") LocalDate second,
            @ForAll("nearbyDates") LocalDate third) {
        Assume.that(first.getDayOfMonth() != 31 && second.getDayOfMonth() != 31 && third.getDayOfMonth() != 31);
        DayCount dayCount = convention.dayCount();
        assertEquals(dayCount.days(first, second) + dayCount.days(second, third), dayCount.days(first, third),
                "days add up across any split without a 31st");
    }

    @Property(tries = 250)
    void thirtyFamilyCountsEveryWholeMonthAsThirtyDays(@ForAll("thirtyFamilyConventions") Convention convention,
            @ForAll("datesUpToDayTwentyEight") LocalDate start, @ForAll("monthOffsets") int months) {
        assertEquals(30L * months, convention.dayCount().days(start, start.plusMonths(months)),
                "each whole month is 30 days");
    }

    @Provide
    Arbitrary<Convention> thirtyFamilyConventions() {
        return Arbitraries.of(List.of(
                new Convention("30/360", DayCounts.of("30/360")),
                new Convention("30E+/360", DayCounts.of("30E+/360"))));
    }

    /** Dates on the 31st, generated only from the months that have one. */
    @Provide
    Arbitrary<LocalDate> thirtyFirstDates() {
        return dates().filter(date -> date.getDayOfMonth() == 31);
    }

    /** Dates in 2025-2027: three of them land close together, so month ends meet within one split. */
    @Provide
    Arbitrary<LocalDate> nearbyDates() {
        return Arbitraries.longs()
                .between(LocalDate.of(2025, 1, 1).toEpochDay(), LocalDate.of(2027, 12, 31).toEpochDay())
                .map(LocalDate::ofEpochDay);
    }

    @Provide
    Arbitrary<Integer> positiveSpans() {
        return Arbitraries.integers().between(1, 3000);
    }

    @Provide
    Arbitrary<Convention> conventions() {
        return Arbitraries.of(List.of(
                new Convention("ACT/365F", DayCounts.of("ACT/365F")),
                new Convention("ACT/360", DayCounts.of("ACT/360")),
                new Convention("ACT/ACT.ISDA", DayCounts.of("ACT/ACT.ISDA")),
                new Convention("30E/360", DayCounts.of("30E/360"))));
    }

    @Provide
    Arbitrary<FixedBasis> fixedBases() {
        return Arbitraries.of(List.of(
                new FixedBasis("ACT/365F", DayCounts.act365F(), 365),
                new FixedBasis("ACT/360", DayCounts.act360(), 360),
                new FixedBasis("30E/360", DayCounts.thirtyE360(), 360),
                new FixedBasis("30/360", DayCounts.thirty360(), 360),
                new FixedBasis("30E+/360", DayCounts.thirtyEPlus360(), 360)));
    }

    @Provide
    Arbitrary<FixedBasis> actualBases() {
        return Arbitraries.of(List.of(
                new FixedBasis("ACT/365F", DayCounts.act365F(), 365),
                new FixedBasis("ACT/360", DayCounts.act360(), 360)));
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return Arbitraries.longs().between(FIRST_DATE.toEpochDay(), LAST_DATE.toEpochDay())
                .map(LocalDate::ofEpochDay);
    }

    /** Dates on day-of-month 30 or 31; February is clamped to its last day, so no invalid date is built. */
    @Provide
    Arbitrary<LocalDate> dayThirtyOrThirtyOneDates() {
        return Combinators.combine(Arbitraries.integers().between(1990, 2060),
                        Arbitraries.integers().between(1, 12), Arbitraries.integers().between(30, 31))
                .as((year, month, day) -> {
                    YearMonth yearMonth = YearMonth.of(year, month);
                    return yearMonth.atDay(Math.min(day, yearMonth.lengthOfMonth()));
                });
    }

    @Provide
    Arbitrary<LocalDate> datesUpToDayTwentyEight() {
        return dates().map(date -> capAtDay(date, 28));
    }

    @Provide
    Arbitrary<Integer> forwardSpans() {
        return Arbitraries.integers().between(0, 3000);
    }

    @Provide
    Arbitrary<Integer> withinYearOffsets() {
        return Arbitraries.integers().between(0, 365);
    }

    @Provide
    Arbitrary<Integer> years() {
        return Arbitraries.integers().between(1990, 2060);
    }

    @Provide
    Arbitrary<Integer> wholeYearCounts() {
        return Arbitraries.integers().between(0, 12);
    }

    @Provide
    Arbitrary<Integer> periodCounts() {
        return Arbitraries.integers().between(-10, 10);
    }

    @Provide
    Arbitrary<Integer> monthOffsets() {
        return Arbitraries.integers().between(-240, 240);
    }

    private static LocalDate capAtDay(LocalDate date, int maxDay) {
        return date.getDayOfMonth() > maxDay ? date.withDayOfMonth(maxDay) : date;
    }

    private static void assertWithinTolerance(BigDecimal expected, BigDecimal actual, String message) {
        assertTrue(expected.subtract(actual).abs().compareTo(TOLERANCE) <= 0,
                () -> message + ": expected " + expected + " but was " + actual);
    }

    private record Convention(String code, DayCount dayCount) {
        @Override
        public String toString() {
            return code;
        }
    }

    private record FixedBasis(String code, DayCount dayCount, int length) {
        @Override
        public String toString() {
            return code;
        }
    }
}
