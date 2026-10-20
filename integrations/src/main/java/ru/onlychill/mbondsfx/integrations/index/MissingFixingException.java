package ru.onlychill.mbondsfx.integrations.index;

import java.time.LocalDate;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.IndexId;

public final class MissingFixingException extends RuntimeException {
    private final IndexId index;
    private final LocalDate date;

    public MissingFixingException(IndexId index, LocalDate date) {
        super("Missing fixing for " + Objects.requireNonNull(index, "index") + " on "
                + Objects.requireNonNull(date, "date"));
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
