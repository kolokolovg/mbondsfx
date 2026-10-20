package ru.onlychill.mbondsfx.integrations.adapter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.integrations.index.IndexFixing;
import ru.onlychill.mbondsfx.integrations.index.IndexFixingSource;
import ru.onlychill.mbondsfx.integrations.index.MissingFixingException;

/** Immutable caller-supplied fixing tables. Values are decimal fractions, not percentages. */
public final class StaticFixings implements IndexFixingSource {
    private final Map<IndexId, Map<LocalDate, BigDecimal>> values;

    private StaticFixings(Map<IndexId, Map<LocalDate, BigDecimal>> values) {
        Map<IndexId, Map<LocalDate, BigDecimal>> copy = new LinkedHashMap<>();
        values.forEach((index, dates) -> {
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(dates, "dates");
            Map<LocalDate, BigDecimal> dateCopy = new LinkedHashMap<>();
            dates.forEach((date, value) -> dateCopy.put(
                    Objects.requireNonNull(date, "date"), Objects.requireNonNull(value, "value")));
            copy.put(index, Map.copyOf(dateCopy));
        });
        this.values = Map.copyOf(copy);
    }

    /** Values in the supplied map are already decimal fractions (for example 0.14 for 14%). */
    public static StaticFixings of(Map<IndexId, ? extends Map<LocalDate, BigDecimal>> values) {
        Objects.requireNonNull(values, "values");
        Map<IndexId, Map<LocalDate, BigDecimal>> copy = new LinkedHashMap<>();
        values.forEach((index, dates) -> copy.put(index, new LinkedHashMap<>(dates)));
        return new StaticFixings(copy);
    }

    /** Each fixing value is already a decimal fraction; no percent conversion is performed. */
    public static StaticFixings of(IndexFixing... fixings) {
        Objects.requireNonNull(fixings, "fixings");
        Map<IndexId, Map<LocalDate, BigDecimal>> table = new LinkedHashMap<>();
        for (IndexFixing fixing : fixings) {
            Objects.requireNonNull(fixing, "fixing");
            BigDecimal previous = table.computeIfAbsent(fixing.index(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(fixing.date(), fixing.value());
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate fixing for " + fixing.index() + " on " + fixing.date());
            }
        }
        return new StaticFixings(table);
    }

    @Override
    public IndexFixing fixing(IndexId index, LocalDate date) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(date, "date");
        BigDecimal value = values.getOrDefault(index, Map.of()).get(date);
        if (value == null) {
            throw new MissingFixingException(index, date);
        }
        return new IndexFixing(index, date, value);
    }
}
