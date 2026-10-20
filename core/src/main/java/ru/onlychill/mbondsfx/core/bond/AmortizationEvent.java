package ru.onlychill.mbondsfx.core.bond;

import java.time.LocalDate;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.money.Money;

public record AmortizationEvent(LocalDate amortDate, Money faceReductionPerUnit) {
    public AmortizationEvent {
        Objects.requireNonNull(amortDate, "amortDate");
        Objects.requireNonNull(faceReductionPerUnit, "faceReductionPerUnit");
        if (!faceReductionPerUnit.isPositive()) {
            throw new IllegalArgumentException("faceReductionPerUnit must be positive");
        }
    }
}
