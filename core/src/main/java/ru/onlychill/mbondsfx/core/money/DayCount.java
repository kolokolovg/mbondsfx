package ru.onlychill.mbondsfx.core.money;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DayCount {
    BigDecimal yearFraction(LocalDate start, LocalDate end);

    long days(LocalDate start, LocalDate end);
}
