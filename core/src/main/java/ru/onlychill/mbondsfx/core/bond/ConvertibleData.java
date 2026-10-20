package ru.onlychill.mbondsfx.core.bond;

import java.math.BigDecimal;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.SecurityId;

/** Convertible terms are retained as data; no conversion engine is provided. */
public record ConvertibleData(SecurityId underlying, BigDecimal conversionRatio) {
    public ConvertibleData {
        Objects.requireNonNull(underlying, "underlying");
        Objects.requireNonNull(conversionRatio, "conversionRatio");
        if (conversionRatio.signum() <= 0) {
            throw new IllegalArgumentException("conversionRatio must be positive");
        }
    }
}
