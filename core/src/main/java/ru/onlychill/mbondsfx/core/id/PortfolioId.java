package ru.onlychill.mbondsfx.core.id;

import java.util.Objects;

public record PortfolioId(String value) {
    public PortfolioId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PortfolioId must not be blank");
        }
    }
}
