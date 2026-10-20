package ru.onlychill.mbondsfx.core.id;

import java.util.Objects;

public record OperationId(String value) {
    public OperationId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("OperationId must not be blank");
        }
    }
}
