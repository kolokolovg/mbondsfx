package ru.onlychill.mbondsfx.core.ledger;

import java.util.Currency;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.money.Money;

/** Security position amounts are totals, not per-unit values. */
public record Position(PositionId id, long quantity, Money carryingAmount,
                       Money faceAmount, Money purchasedAccrued) {
    public Position {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(carryingAmount, "carryingAmount");
        Objects.requireNonNull(faceAmount, "faceAmount");
        Objects.requireNonNull(purchasedAccrued, "purchasedAccrued");
        Currency currency = carryingAmount.currency();
        if (!currency.equals(faceAmount.currency()) || !currency.equals(purchasedAccrued.currency())) {
            throw new IllegalArgumentException("Position amounts must use one currency");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Position quantity must not be negative");
        }
        if (carryingAmount.isNegative() || faceAmount.isNegative() || purchasedAccrued.isNegative()) {
            throw new IllegalArgumentException("Position amounts must not be negative");
        }
        if (quantity == 0 && (!carryingAmount.isZero() || !faceAmount.isZero() || !purchasedAccrued.isZero())) {
            throw new IllegalArgumentException("A zero-quantity position must have zero amounts");
        }
    }

    public static Position empty(PositionId id, Currency currency) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Money zero = Money.zero(currency);
        return new Position(id, 0, zero, zero, zero);
    }
}
