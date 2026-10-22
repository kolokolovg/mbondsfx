package ru.onlychill.mbondsfx.core.ledger;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.Money;

/** Immutable persistence boundary for a ledger, including its complete retained posting history. */
public record LedgerState(PortfolioId portfolio, LedgerChart chart, boolean allowNegativeCash,
                          Map<Account, Money> balances, Map<SecurityId, Position> positions,
                          NavigableMap<LocalDate, List<Posting>> postingsByDate,
                          Set<OperationId> operationIds) {
    public LedgerState {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(chart, "chart");
        balances = immutableMap(balances, "balances");
        positions = immutableMap(positions, "positions");
        postingsByDate = immutableHistory(postingsByDate);
        operationIds = Set.copyOf(Objects.requireNonNull(operationIds, "operationIds"));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values, String name) {
        Objects.requireNonNull(values, name);
        Map<K, V> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, name + " key"),
                Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(copy);
    }

    private static NavigableMap<LocalDate, List<Posting>> immutableHistory(
            NavigableMap<LocalDate, List<Posting>> values) {
        Objects.requireNonNull(values, "postingsByDate");
        NavigableMap<LocalDate, List<Posting>> copy = new TreeMap<>();
        values.forEach((date, postings) -> copy.put(
                Objects.requireNonNull(date, "posting date"),
                List.copyOf(Objects.requireNonNull(postings, "postings"))));
        return Collections.unmodifiableNavigableMap(copy);
    }
}
