package ru.onlychill.mbondsfx.core.bond.calculation.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import ru.onlychill.mbondsfx.core.id.IndexId;

/** Exact-index, exact-date value lookup for domain calculations. */
@FunctionalInterface
public interface IndexValueSource {
    BigDecimal value(IndexId index, LocalDate date);
}
