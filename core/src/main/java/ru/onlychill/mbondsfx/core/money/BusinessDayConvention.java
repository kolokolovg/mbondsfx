package ru.onlychill.mbondsfx.core.money;

import java.time.LocalDate;
import java.util.Objects;

public enum BusinessDayConvention {
    NONE,
    FOLLOWING,
    PRECEDING,
    MODIFIED_FOLLOWING;

    public LocalDate adjust(LocalDate date, BusinessCalendar calendar) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(calendar, "calendar");
        if (this == NONE || calendar.isBusinessDay(date)) {
            return date;
        }
        if (this == PRECEDING) {
            return preceding(date, calendar);
        }

        LocalDate following = following(date, calendar);
        if (this == MODIFIED_FOLLOWING && following.getMonth() != date.getMonth()) {
            return preceding(date, calendar);
        }
        return following;
    }

    private static LocalDate following(LocalDate date, BusinessCalendar calendar) {
        LocalDate adjusted = date;
        do {
            adjusted = adjusted.plusDays(1);
        } while (!calendar.isBusinessDay(adjusted));
        return adjusted;
    }

    private static LocalDate preceding(LocalDate date, BusinessCalendar calendar) {
        LocalDate adjusted = date;
        do {
            adjusted = adjusted.minusDays(1);
        } while (!calendar.isBusinessDay(adjusted));
        return adjusted;
    }
}
