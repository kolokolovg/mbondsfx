package ru.onlychill.mbondsfx.core.money;

import java.time.LocalDate;

public interface BusinessCalendar {
    boolean isBusinessDay(LocalDate date);

    LocalDate shift(LocalDate date, int businessDays);
}
