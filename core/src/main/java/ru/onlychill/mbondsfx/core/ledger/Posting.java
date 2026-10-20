package ru.onlychill.mbondsfx.core.ledger;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Currency;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.money.Money;

/** A double-entry transaction. Positive entry amounts are debits; negative are credits. */
public record Posting(OperationId operation, LocalDate date, List<Entry> entries) {
    public Posting {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(date, "date");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));

        Map<Currency, Money> totals = new HashMap<>();
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!entry.account().currency().equals(entry.amount().currency())) {
                throw new IllegalArgumentException("Entry amount currency does not match account currency");
            }
            Currency currency = entry.amount().currency();
            totals.merge(currency, entry.amount(), Money::add);
        }
        for (Map.Entry<Currency, Money> total : totals.entrySet()) {
            if (!total.getValue().isZero()) {
                throw new UnbalancedPostingException("Posting is not balanced in " + total.getKey()
                        + ": " + total.getValue());
            }
        }
    }

    public record Entry(Account account, Money amount) {
        public Entry {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
