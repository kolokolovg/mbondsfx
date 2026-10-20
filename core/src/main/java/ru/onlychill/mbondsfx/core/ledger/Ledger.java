package ru.onlychill.mbondsfx.core.ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.Money;

/** Immutable, single-portfolio double-entry ledger. */
public final class Ledger {
    private final PortfolioId portfolio;
    private final boolean allowNegativeCash;
    private final Map<Account, Money> balances;
    private final Map<SecurityId, Position> positions;
    private final NavigableMap<LocalDate, List<Posting>> postingsByDate;
    private final Set<OperationId> operationIds;

    private Ledger(PortfolioId portfolio, boolean allowNegativeCash, Map<Account, Money> balances,
                   Map<SecurityId, Position> positions, NavigableMap<LocalDate, List<Posting>> postingsByDate,
                   Set<OperationId> operationIds) {
        this.portfolio = Objects.requireNonNull(portfolio, "portfolio");
        this.allowNegativeCash = allowNegativeCash;
        this.balances = Collections.unmodifiableMap(new LinkedHashMap<>(balances));
        this.positions = Collections.unmodifiableMap(new LinkedHashMap<>(positions));
        TreeMap<LocalDate, List<Posting>> dates = new TreeMap<>();
        postingsByDate.forEach((date, postings) -> dates.put(date, List.copyOf(postings)));
        this.postingsByDate = Collections.unmodifiableNavigableMap(dates);
        this.operationIds = Set.copyOf(operationIds);
    }

    public static Ledger open(PortfolioId portfolio) {
        return open(portfolio, false);
    }

    public static Ledger open(PortfolioId portfolio, boolean allowNegativeCash) {
        return new Ledger(Objects.requireNonNull(portfolio, "portfolio"), allowNegativeCash,
                Map.of(), Map.of(), new TreeMap<>(), Set.of());
    }

    /** Restores a ledger after validating the supplied state as untrusted structural data. */
    public static Ledger restore(LedgerState state) {
        Objects.requireNonNull(state, "state");
        validateRestorableState(state);
        return new Ledger(state.portfolio(), state.allowNegativeCash(), state.balances(), state.positions(),
                state.postingsByDate(), state.operationIds());
    }

    public Ledger apply(OperationResult result) {
        Objects.requireNonNull(result, "result");
        if (operationIds.contains(result.operation())) {
            throw new IllegalStateException("Duplicate operation ID: " + result.operation());
        }
        LocalDate lastDate = postingsByDate.isEmpty() ? null : postingsByDate.lastKey();
        if (lastDate != null && result.date().isBefore(lastDate)) {
            throw new IllegalStateException("Backdated operation " + result.date() + " before " + lastDate);
        }

        Position before = result.positionBefore().orElse(null);
        Position after = result.positionAfter().orElse(null);
        SecurityId affectedSecurity = null;
        if (before != null) {
            if (!before.id().portfolio().equals(portfolio) || !after.id().portfolio().equals(portfolio)) {
                throw new IllegalStateException("Position belongs to a different portfolio");
            }
            if (!before.id().equals(after.id())) {
                throw new IllegalStateException("Operation position snapshots identify different positions");
            }
            affectedSecurity = before.id().security();
            Position stored = positions.get(affectedSecurity);
            if (stored == null) {
                stored = Position.empty(before.id(), before.carryingAmount().currency());
            }
            if (!stored.equals(before)) {
                throw new IllegalStateException("Stale position snapshot for " + affectedSecurity);
            }
            if (!before.carryingAmount().currency().equals(after.carryingAmount().currency())) {
                throw new IllegalStateException("Position currency changed during operation");
            }
        }

        Map<Account, Money> nextBalances = new LinkedHashMap<>(balances);
        for (Posting posting : result.postings()) {
            if (!posting.operation().equals(result.operation()) || !posting.date().equals(result.date())) {
                throw new IllegalStateException("Posting identity/date differs from its operation result");
            }
            for (Posting.Entry entry : posting.entries()) {
                Account account = entry.account();
                if (!portfolio.equals(account.portfolio())) {
                    throw new IllegalStateException("Posting account belongs to a different portfolio");
                }
                if (account.kind().requiresSecurity()) {
                    if (affectedSecurity == null || !account.security().orElseThrow().equals(affectedSecurity)) {
                        throw new IllegalStateException("Posting account belongs to a different security");
                    }
                    if (!account.currency().equals(before.carryingAmount().currency())) {
                        throw new IllegalStateException("Posting currency differs from position currency");
                    }
                }
                Money updated = nextBalances.getOrDefault(account, Money.zero(account.currency())).add(entry.amount());
                if (updated.isZero()) {
                    nextBalances.remove(account);
                } else {
                    nextBalances.put(account, updated);
                }
            }
        }

        Map<SecurityId, Position> nextPositions = new LinkedHashMap<>(positions);
        if (after != null) {
            nextPositions.put(affectedSecurity, after);
        }
        validatePositionBalances(nextBalances, nextPositions);
        if (!allowNegativeCash) {
            for (Map.Entry<Account, Money> balance : nextBalances.entrySet()) {
                if (balance.getKey().kind() == AccountKind.CASH && balance.getValue().isNegative()) {
                    throw new IllegalStateException("Insufficient cash in " + balance.getKey().currency());
                }
            }
        }

        NavigableMap<LocalDate, List<Posting>> nextPostings = new TreeMap<>(postingsByDate);
        List<Posting> datePostings = new ArrayList<>(nextPostings.getOrDefault(result.date(), List.of()));
        datePostings.addAll(result.postings());
        nextPostings.put(result.date(), List.copyOf(datePostings));
        Set<OperationId> nextOperationIds = new HashSet<>(operationIds);
        nextOperationIds.add(result.operation());
        return new Ledger(portfolio, allowNegativeCash, nextBalances, nextPositions,
                nextPostings, nextOperationIds);
    }

    public Money balance(Account account) {
        Objects.requireNonNull(account, "account");
        if (!portfolio.equals(account.portfolio())) {
            throw new IllegalArgumentException("Account belongs to a different portfolio");
        }
        return balances.getOrDefault(account, Money.zero(account.currency()));
    }

    public Map<Account, Money> balances() {
        return balances;
    }

    public Map<Currency, Money> cashBalances() {
        Map<Currency, Money> cash = new LinkedHashMap<>();
        for (Map.Entry<Account, Money> entry : balances.entrySet()) {
            if (entry.getKey().kind() == AccountKind.CASH) {
                cash.merge(entry.getKey().currency(), entry.getValue(), Money::add);
            }
        }
        return Collections.unmodifiableMap(cash);
    }

    public Optional<Position> position(SecurityId security) {
        Objects.requireNonNull(security, "security");
        return Optional.ofNullable(positions.get(security));
    }

    public Map<SecurityId, Position> positions() {
        return positions;
    }

    public NavigableMap<LocalDate, List<Posting>> postingsByDate() {
        return postingsByDate;
    }

    public Set<OperationId> operationIds() {
        return operationIds;
    }

    /** Returns a defensive immutable snapshot of all state, including retained posting history. */
    public LedgerState state() {
        return new LedgerState(portfolio, allowNegativeCash, balances, positions, postingsByDate, operationIds);
    }

    private static void validateRestorableState(LedgerState state) {
        PortfolioId portfolio = state.portfolio();
        for (Map.Entry<Account, Money> entry : state.balances().entrySet()) {
            Account account = entry.getKey();
            Money amount = entry.getValue();
            if (!portfolio.equals(account.portfolio())) {
                throw invalidState("Balance account belongs to a different portfolio");
            }
            if (!account.currency().equals(amount.currency())) {
                throw invalidState("Balance currency differs from account currency");
            }
            if (amount.isZero()) {
                throw invalidState("Zero balances must be omitted");
            }
            if (!state.allowNegativeCash() && account.kind() == AccountKind.CASH && amount.isNegative()) {
                throw invalidState("Strict ledger state contains negative cash");
            }
        }

        for (Map.Entry<SecurityId, Position> entry : state.positions().entrySet()) {
            Position position = entry.getValue();
            if (!entry.getKey().equals(position.id().security())
                    || !portfolio.equals(position.id().portfolio())) {
                throw invalidState("Position key or portfolio does not match its identity");
            }
        }

        Map<Account, Money> replayedBalances = new HashMap<>();
        Map<OperationId, LocalDate> operationDates = new HashMap<>();
        for (Map.Entry<LocalDate, List<Posting>> dated : state.postingsByDate().entrySet()) {
            LocalDate date = dated.getKey();
            List<Posting> postings = dated.getValue();
            if (postings.isEmpty()) {
                throw invalidState("Posting history must not contain empty date buckets");
            }
            for (Posting posting : postings) {
                if (!date.equals(posting.date())) {
                    throw invalidState("Posting date differs from its history bucket");
                }
                LocalDate earlierDate = operationDates.putIfAbsent(posting.operation(), date);
                if (earlierDate != null && !earlierDate.equals(date)) {
                    throw invalidState("One operation ID is associated with multiple dates");
                }
                for (Posting.Entry entry : posting.entries()) {
                    Account account = entry.account();
                    if (!portfolio.equals(account.portfolio())) {
                        throw invalidState("Posting account belongs to a different portfolio");
                    }
                    if (account.kind().requiresSecurity()) {
                        SecurityId security = account.security().orElseThrow();
                        Position position = state.positions().get(security);
                        if (position == null
                                || !position.carryingAmount().currency().equals(account.currency())) {
                            throw invalidState("Posting security account has no matching position/currency");
                        }
                    }
                    Money total = replayedBalances.getOrDefault(account, Money.zero(account.currency()))
                            .add(entry.amount());
                    if (total.isZero()) {
                        replayedBalances.remove(account);
                    } else {
                        replayedBalances.put(account, total);
                    }
                }
            }
        }

        if (!operationDates.keySet().equals(state.operationIds())) {
            throw invalidState("Operation IDs do not match posting history");
        }
        if (!replayedBalances.equals(state.balances())) {
            throw invalidState("Balances do not equal the retained posting history");
        }
        try {
            validatePositionBalances(state.balances(), state.positions());
        } catch (IllegalStateException inconsistent) {
            throw new IllegalArgumentException("Invalid ledger state: " + inconsistent.getMessage(), inconsistent);
        }
    }

    private static IllegalArgumentException invalidState(String message) {
        return new IllegalArgumentException("Invalid ledger state: " + message);
    }

    private static void validatePositionBalances(Map<Account, Money> balances,
                                                 Map<SecurityId, Position> positions) {
        for (Map.Entry<SecurityId, Position> entry : positions.entrySet()) {
            Position position = entry.getValue();
            Money carrying = securityBalance(balances, position, AccountKind.SECURITIES);
            Money accrued = securityBalance(balances, position, AccountKind.ACCRUED_INTEREST);
            if (!carrying.equals(position.carryingAmount()) || !accrued.equals(position.purchasedAccrued())) {
                throw new IllegalStateException("Position assets disagree with ledger balances for " + entry.getKey());
            }
        }
        for (Map.Entry<Account, Money> entry : balances.entrySet()) {
            Account account = entry.getKey();
            if (account.kind().requiresSecurity()) {
                Position position = positions.get(account.security().orElseThrow());
                if (position == null || !position.carryingAmount().currency().equals(account.currency())) {
                    throw new IllegalStateException("Security asset account has no matching position");
                }
            }
        }
    }

    private static Money securityBalance(Map<Account, Money> balances, Position position, AccountKind kind) {
        Account account = new Account(position.id().portfolio(), kind,
                Optional.of(position.id().security()), position.carryingAmount().currency());
        return balances.getOrDefault(account, Money.zero(position.carryingAmount().currency()));
    }
}
