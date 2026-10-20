package ru.onlychill.mbondsfx.core.bond.calculation;

import java.time.LocalDate;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.IndexId;

/** No usable value was available for the requested index and date. */
public final class MissingIndexValueException extends RuntimeException {
    private final IndexId index;
    private final LocalDate date;

    public MissingIndexValueException(IndexId index, LocalDate date) {
        super("Missing index value for " + Objects.requireNonNull(index, "index") + " on "
                + Objects.requireNonNull(date, "date"));
        this.index = index;
        this.date = date;
    }

    public MissingIndexValueException(IndexId index, LocalDate date, Throwable cause) {
        super("Missing index value for " + Objects.requireNonNull(index, "index") + " on "
                + Objects.requireNonNull(date, "date"), cause);
        this.index = index;
        this.date = date;
    }

    public IndexId index() {
        return index;
    }

    public LocalDate date() {
        return date;
    }
}
