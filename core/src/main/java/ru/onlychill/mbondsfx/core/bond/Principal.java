package ru.onlychill.mbondsfx.core.bond;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.money.Money;

/** Composable principal terms: initial face, amortizations, and optional ratio indexation. */
public record Principal(Money initialFacePerUnit, List<AmortizationEvent> events,
                        Optional<IndexationRule> indexation) {
    public Principal {
        Objects.requireNonNull(initialFacePerUnit, "initialFacePerUnit");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(indexation, "indexation");
        if (!initialFacePerUnit.isPositive()) {
            throw new IllegalArgumentException("initialFacePerUnit must be positive");
        }

        List<AmortizationEvent> copy = new ArrayList<>(events);
        copy.forEach(event -> Objects.requireNonNull(event, "event"));
        copy.sort(Comparator.comparing(AmortizationEvent::amortDate));
        BigDecimal totalReduction = BigDecimal.ZERO;
        for (AmortizationEvent event : copy) {
            if (!event.faceReductionPerUnit().currency().equals(initialFacePerUnit.currency())) {
                throw new IllegalArgumentException("Amortization currency must match initial face currency");
            }
            totalReduction = totalReduction.add(event.faceReductionPerUnit().amount());
        }
        if (totalReduction.compareTo(initialFacePerUnit.amount()) > 0) {
            throw new IllegalArgumentException("Total amortization must not exceed initial face");
        }
        events = List.copyOf(copy);
    }
}
