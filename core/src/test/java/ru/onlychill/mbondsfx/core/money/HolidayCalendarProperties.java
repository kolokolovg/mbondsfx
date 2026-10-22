package ru.onlychill.mbondsfx.core.money;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of {@link HolidayCalendar} business-day rules and {@link BusinessCalendar#shift}. A business day is a
 * non-weekend date that is not a supplied holiday. A non-zero shift returns the n-th business day in the direction of
 * travel, never counting the start date; a zero shift returns the start date unchanged.
 */
final class HolidayCalendarProperties {
    private static final LocalDate FIRST_DATE = LocalDate.of(2023, 1, 1);
    private static final LocalDate LAST_DATE = LocalDate.of(2025, 12, 31);

    @Property(tries = 250)
    void businessDaysExcludeWeekendsAndSuppliedHolidaysOnly(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date) {
        assertEquals(!isWeekend(date) && !holidays.contains(date), HolidayCalendar.of(holidays).isBusinessDay(date),
                "a business day is a non-weekend date that is not a supplied holiday");
        assertEquals(!isWeekend(date), HolidayCalendar.weekendsOnly().isBusinessDay(date),
                "the weekends-only calendar has no holidays");
    }

    @Property(tries = 250)
    void zeroShiftReturnsTheStartDateEvenOnAClosedDay(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date) {
        assertEquals(date, HolidayCalendar.of(holidays).shift(date, 0), "zero shift keeps a holiday date");
        assertEquals(date, HolidayCalendar.weekendsOnly().shift(date, 0), "zero shift keeps a weekend date");
    }

    @Property(tries = 250)
    void nonZeroShiftLandsOnABusinessDayInTheDirectionOfTravel(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date, @ForAll("nonZeroBusinessDayCounts") int count) {
        BusinessCalendar calendar = HolidayCalendar.of(holidays);
        LocalDate shifted = calendar.shift(date, count);
        assertTrue(calendar.isBusinessDay(shifted), "a shift never lands on a weekend or a holiday");
        if (count > 0) {
            assertTrue(shifted.isAfter(date), "a forward shift never moves backward");
            assertTrue(ChronoUnit.DAYS.between(date, shifted) >= count,
                    "a forward shift covers at least one calendar day per business day");
        } else {
            assertTrue(shifted.isBefore(date), "a backward shift never moves forward");
            assertTrue(ChronoUnit.DAYS.between(shifted, date) >= -count,
                    "a backward shift covers at least one calendar day per business day");
        }
    }

    /** Independent oracle: counting business days over the interval the shift must cover. */
    @Property(tries = 250)
    void shiftSkipsExactlyTheRequestedBusinessDays(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date, @ForAll("nonZeroBusinessDayCounts") int count) {
        BusinessCalendar calendar = HolidayCalendar.of(holidays);
        LocalDate shifted = calendar.shift(date, count);
        long counted = count > 0
                ? countBusinessDays(calendar, date.plusDays(1), shifted)
                : countBusinessDays(calendar, shifted, date.minusDays(1));
        assertEquals(Math.abs(count), counted, "the interval contains exactly the requested business days");
    }

    @Property(tries = 250)
    void shiftIsStrictlyIncreasingInTheBusinessDayCount(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date, @ForAll("businessDayCounts") int lower,
            @ForAll("stepSizes") int step) {
        BusinessCalendar calendar = HolidayCalendar.of(holidays);
        assertTrue(calendar.shift(date, lower).isBefore(calendar.shift(date, lower + step)),
                "more business days always reach a later date");
    }

    @Property(tries = 250)
    void sameDirectionShiftsComposeFromAnyDate(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date, @ForAll("sameDirectionShiftPairs") ShiftPair pair) {
        assertComposes(HolidayCalendar.of(holidays), date, pair);
    }

    @Property(tries = 250)
    void shiftsComposeFromBusinessDaysInEitherDirection(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date, @ForAll("shiftPairs") ShiftPair pair) {
        BusinessCalendar calendar = HolidayCalendar.of(holidays);
        assertComposes(calendar, firstBusinessDayOnOrAfter(calendar, date), pair);
    }

    @Property(tries = 250)
    void addingHolidaysNeverMovesForwardShiftsEarlierOrBackwardShiftsLater(
            @ForAll("holidaySets") Set<LocalDate> holidays, @ForAll("holidaySets") Set<LocalDate> extra,
            @ForAll("dates") LocalDate date, @ForAll("businessDayCounts") int count) {
        Set<LocalDate> enlarged = new HashSet<>(holidays);
        enlarged.addAll(extra);
        LocalDate before = HolidayCalendar.of(holidays).shift(date, count);
        LocalDate after = HolidayCalendar.of(enlarged).shift(date, count);
        if (count >= 0) {
            assertFalse(after.isBefore(before), "more holidays never move a forward shift earlier");
        } else {
            assertFalse(after.isAfter(before), "more holidays never move a backward shift later");
        }
    }

    @Property(tries = 250)
    void weekendsOnlyFiveBusinessDaysSpanSevenCalendarDays(@ForAll("weekdays") LocalDate date,
            @ForAll("weekCounts") int weeks) {
        assertEquals(date.plusDays(7L * weeks), HolidayCalendar.weekendsOnly().shift(date, 5 * weeks),
                "five business days from a weekday are seven calendar days later");
    }

    @Property(tries = 250)
    void calendarIgnoresLaterChangesToTheSuppliedHolidays(@ForAll("holidaySets") Set<LocalDate> holidays,
            @ForAll("dates") LocalDate date, @ForAll("businessDayCounts") int count) {
        Set<LocalDate> supplied = new HashSet<>(holidays);
        BusinessCalendar calendar = HolidayCalendar.of(supplied);
        boolean businessBefore = calendar.isBusinessDay(date);
        LocalDate shiftedBefore = calendar.shift(date, count);
        supplied.clear();
        supplied.add(date);
        assertEquals(businessBefore, calendar.isBusinessDay(date), "the calendar copied its holidays");
        assertEquals(shiftedBefore, calendar.shift(date, count), "the calendar copied its holidays for shifts");
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return Arbitraries.longs().between(FIRST_DATE.toEpochDay(), LAST_DATE.toEpochDay())
                .map(LocalDate::ofEpochDay);
    }

    @Provide
    Arbitrary<LocalDate> weekdays() {
        return dates().filter(date -> !isWeekend(date));
    }

    /** Up to six closure blocks of 1-10 consecutive days, so multi-day closures and clusters are common. */
    @Provide
    Arbitrary<Set<LocalDate>> holidaySets() {
        return closureBlock().list().ofMinSize(0).ofMaxSize(6).map(blocks -> {
            Set<LocalDate> holidays = new HashSet<>();
            blocks.forEach(holidays::addAll);
            return holidays;
        });
    }

    @Provide
    Arbitrary<Integer> businessDayCounts() {
        return Arbitraries.integers().between(-40, 40);
    }

    @Provide
    Arbitrary<Integer> nonZeroBusinessDayCounts() {
        return Arbitraries.oneOf(Arbitraries.integers().between(-40, -1), Arbitraries.integers().between(1, 40));
    }

    @Provide
    Arbitrary<Integer> stepSizes() {
        return Arbitraries.integers().between(1, 40);
    }

    @Provide
    Arbitrary<Integer> weekCounts() {
        return Arbitraries.integers().between(-8, 8);
    }

    @Provide
    Arbitrary<ShiftPair> shiftPairs() {
        return Combinators.combine(Arbitraries.integers().between(-30, 30), Arbitraries.integers().between(-30, 30))
                .as((first, second) -> new ShiftPair(first, second));
    }

    /** Both shifts share one direction (or are zero), so every intermediate date stays on one side of the start. */
    @Provide
    Arbitrary<ShiftPair> sameDirectionShiftPairs() {
        return Arbitraries.of(-1, 1).flatMap(sign -> Combinators.combine(
                        Arbitraries.integers().between(0, 30), Arbitraries.integers().between(0, 30))
                .as((first, second) -> new ShiftPair(sign * first, sign * second)));
    }

    private static Arbitrary<List<LocalDate>> closureBlock() {
        return Combinators.combine(
                        Arbitraries.longs().between(FIRST_DATE.toEpochDay(), LAST_DATE.toEpochDay())
                                .map(LocalDate::ofEpochDay),
                        Arbitraries.integers().between(1, 10))
                .as((start, length) -> IntStream.range(0, length)
                        .mapToObj(offset -> start.plusDays(offset))
                        .toList());
    }

    /** Test-side walk over weekends and holidays using only isBusinessDay, so inputs never rely on shift. */
    private static LocalDate firstBusinessDayOnOrAfter(BusinessCalendar calendar, LocalDate date) {
        LocalDate day = date;
        while (!calendar.isBusinessDay(day)) {
            day = day.plusDays(1);
        }
        return day;
    }

    private static void assertComposes(BusinessCalendar calendar, LocalDate date, ShiftPair pair) {
        LocalDate stepwise = calendar.shift(calendar.shift(date, pair.first()), pair.second());
        assertEquals(calendar.shift(date, pair.first() + pair.second()), stepwise,
                "shifting by " + pair.first() + " and then " + pair.second() + " equals one shift by the sum");
    }

    private static long countBusinessDays(BusinessCalendar calendar, LocalDate fromInclusive, LocalDate toInclusive) {
        long count = 0;
        for (LocalDate day = fromInclusive; !day.isAfter(toInclusive); day = day.plusDays(1)) {
            if (calendar.isBusinessDay(day)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private record ShiftPair(int first, int second) { }
}
