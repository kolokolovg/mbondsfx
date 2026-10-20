package ru.onlychill.mbondsfx.core.bond;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.money.Money;

/** Data-only call/put exercise; an empty price means exercise at current face. */
public record OptionExercise(LocalDate date, Optional<Money> price) {
    public OptionExercise {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(price, "price");
        if (price.isPresent() && price.orElseThrow().isNegative()) {
            throw new IllegalArgumentException("Exercise price must be non-negative");
        }
    }
}
