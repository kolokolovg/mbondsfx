package ru.onlychill.mbondsfx.core.id;

import java.util.Objects;

public record SecurityId(String value) {
    public SecurityId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SecurityId must not be blank");
        }
    }
}
