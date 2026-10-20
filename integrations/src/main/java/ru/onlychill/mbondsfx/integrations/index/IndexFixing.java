package ru.onlychill.mbondsfx.integrations.index;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.IndexId;

/** An index value expressed as a decimal fraction. */
public record IndexFixing(IndexId index, LocalDate date, BigDecimal value) {
    public IndexFixing {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(value, "value");
    }
}
