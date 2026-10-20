package ru.onlychill.mbondsfx.core.money;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/** A weekend calendar with an immutable set of additional holidays. */
public final class HolidayCalendar implements BusinessCalendar {
    private static final HolidayCalendar WEEKENDS_ONLY = new HolidayCalendar(Set.of());
    private final Set<LocalDate> holidays;

    private HolidayCalendar(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(Objects.requireNonNull(holidays, "holidays"));
    }

    public static BusinessCalendar weekendsOnly() {
        return WEEKENDS_ONLY;
    }

    public static BusinessCalendar of(Set<LocalDate> holidays) {
        return new HolidayCalendar(holidays);
    }

    @Override
    public boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    @Override
    public LocalDate shift(LocalDate date, int businessDays) {
        Objects.requireNonNull(date, "date");
        if (businessDays == 0) {
            return date;
        }

        int direction = businessDays > 0 ? 1 : -1;
        long remaining = Math.abs((long) businessDays);
        LocalDate shifted = date;
        while (remaining > 0) {
            shifted = shifted.plusDays(direction);
            if (isBusinessDay(shifted)) {
                remaining--;
            }
        }
        return shifted;
    }
}
