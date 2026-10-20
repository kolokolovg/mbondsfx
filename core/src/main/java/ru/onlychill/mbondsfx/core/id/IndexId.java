package ru.onlychill.mbondsfx.core.id;

import java.util.Objects;

public record IndexId(String value) {
    public IndexId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("IndexId must not be blank");
        }
    }
}
