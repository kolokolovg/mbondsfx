package ru.onlychill.mbondsfx.application.service;

import java.util.List;
import java.util.Objects;
import ru.onlychill.mbondsfx.application.port.PenaltyInterval;
import ru.onlychill.mbondsfx.core.money.Money;

/** Immutable scenario estimate and exactly the caller-supplied intervals included in its coverage. */
public final class PenaltyEstimate {
    private final List<PenaltyInterval> coveredIntervals;
    private final Money amount;

    PenaltyEstimate(List<PenaltyInterval> coveredIntervals, Money amount) {
        this.coveredIntervals = List.copyOf(Objects.requireNonNull(coveredIntervals, "coveredIntervals"));
        this.amount = Objects.requireNonNull(amount, "amount");
    }

    public List<PenaltyInterval> coveredIntervals() {
        return coveredIntervals;
    }

    public Money amount() {
        return amount;
    }
}
