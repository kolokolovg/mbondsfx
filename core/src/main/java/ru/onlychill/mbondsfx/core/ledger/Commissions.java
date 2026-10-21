package ru.onlychill.mbondsfx.core.ledger;

import java.util.Objects;
import ru.onlychill.mbondsfx.core.money.Money;

/** Explicit broker and other commission components in one currency. */
public record Commissions(Money brokerCommission, Money otherCommission) {
    public Commissions {
        Objects.requireNonNull(brokerCommission, "brokerCommission");
        Objects.requireNonNull(otherCommission, "otherCommission");
        if (!brokerCommission.currency().equals(otherCommission.currency())) {
            throw new IllegalArgumentException("Commission currencies must match");
        }
        if (brokerCommission.isNegative() || otherCommission.isNegative()) {
            throw new IllegalArgumentException("Commissions must not be negative");
        }
    }

    public Money total() {
        return brokerCommission.add(otherCommission);
    }
}
