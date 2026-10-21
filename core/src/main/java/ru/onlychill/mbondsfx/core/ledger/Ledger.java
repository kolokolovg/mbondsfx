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
        validateTaxCorrection(result);

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

    /** Returns immutable cumulative actual withholding balances, separated by currency. */
    public Map<Currency, Money> taxWithheldBalances() {
        Map<Currency, Money> withheld = new LinkedHashMap<>();
        for (Map.Entry<Account, Money> entry : balances.entrySet()) {
            if (entry.getKey().kind() == AccountKind.TAX_WITHHELD) {
                withheld.merge(entry.getKey().currency(), entry.getValue(), Money::add);
            }
        }
        return Collections.unmodifiableMap(withheld);
    }

    /** Returns actual withholding for an original taxable operation plus its linked corrections. */
    public Money taxWithheldForOperation(OperationId originalOperation) {
        Objects.requireNonNull(originalOperation, "originalOperation");
        Posting original = null;
        boolean isCorrection = false;
        for (List<Posting> datedPostings : postingsByDate.values()) {
            for (Posting posting : datedPostings) {
                if (posting.operation().equals(originalOperation)) {
                    if (posting.adjustsOperation().isPresent()) {
                        isCorrection = true;
                    } else if (containsWithheldTax(posting)) {
                        if (original != null) {
                            throw new IllegalArgumentException("Operation has multiple original withholding postings");
                        }
                        original = posting;
                    }
                }
            }
        }
        if (isCorrection) {
            throw new IllegalArgumentException("Tax correction cannot be queried as an original operation");
        }
        if (original == null) {
            throw new IllegalArgumentException("Operation has no original nonzero withholding: " + originalOperation);
        }

        Money total = validateWithholdingOriginPosting(original, portfolio);
        for (List<Posting> datedPostings : postingsByDate.values()) {
            for (Posting posting : datedPostings) {
                if (posting.adjustsOperation().filter(originalOperation::equals).isPresent()) {
                    Money adjustment = validateTaxCorrectionPosting(posting, portfolio);
                    if (!total.currency().equals(adjustment.currency())) {
                        throw new IllegalArgumentException("Tax correction currency differs from original withholding");
                    }
                    total = total.add(adjustment);
                }
            }
        }
        return total;
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
        validateTaxCorrectionHistory(state);
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

    private void validateTaxCorrection(OperationResult result) {
        if (result.kind() != OperationResult.OperationKind.TAX_CORRECTION) {
            return;
        }
        Posting correction = result.postings().getFirst();
        OperationId originalOperation = correction.adjustsOperation().orElseThrow(
                () -> new IllegalStateException("Tax correction is missing its original operation link"));
        if (!operationIds.contains(originalOperation)) {
            throw new IllegalStateException("Tax correction references an unknown operation: " + originalOperation);
        }
        LocalDate originalDate = operationDate(originalOperation);
        if (originalDate == null || result.date().isBefore(originalDate)) {
            throw new IllegalStateException("Tax correction precedes or lacks its original operation");
        }

        Money adjustment;
        Money current;
        try {
            adjustment = validateTaxCorrectionPosting(correction, portfolio);
            current = taxWithheldForOperation(originalOperation);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid tax correction: " + invalid.getMessage(), invalid);
        }
        if (!current.currency().equals(adjustment.currency())) {
            throw new IllegalStateException("Tax correction currency differs from original withholding");
        }
        if (current.add(adjustment).isNegative()) {
            throw new IllegalStateException("Tax correction cannot reduce cumulative withholding below zero");
        }
    }

    private LocalDate operationDate(OperationId operation) {
        for (Map.Entry<LocalDate, List<Posting>> dated : postingsByDate.entrySet()) {
            if (dated.getValue().stream().anyMatch(posting -> posting.operation().equals(operation))) {
                return dated.getKey();
            }
        }
        return null;
    }

    private static void validateTaxCorrectionHistory(LedgerState state) {
        Map<OperationId, List<Posting>> postingsByOperation = new HashMap<>();
        Map<OperationId, Posting> originalTaxPostings = new HashMap<>();
        Set<OperationId> correctionOperations = new HashSet<>();
        for (List<Posting> datedPostings : state.postingsByDate().values()) {
            for (Posting posting : datedPostings) {
                postingsByOperation.computeIfAbsent(posting.operation(), ignored -> new ArrayList<>()).add(posting);
                if (posting.adjustsOperation().isPresent()) {
                    correctionOperations.add(posting.operation());
                    try {
                        validateTaxCorrectionPosting(posting, state.portfolio());
                    } catch (IllegalArgumentException invalid) {
                        throw invalidState("Malformed tax correction posting: " + invalid.getMessage());
                    }
                } else if (containsWithheldTax(posting)) {
                    if (originalTaxPostings.putIfAbsent(posting.operation(), posting) != null) {
                        throw invalidState("Operation has multiple original withholding postings");
                    }
                    try {
                        validateWithholdingOriginPosting(posting, state.portfolio());
                    } catch (IllegalArgumentException invalid) {
                        throw invalidState("Malformed original withholding posting: " + invalid.getMessage());
                    }
                }
            }
        }

        for (OperationId correctionOperation : correctionOperations) {
            if (postingsByOperation.get(correctionOperation).size() != 1
                    || originalTaxPostings.containsKey(correctionOperation)) {
                throw invalidState("Tax correction operation must contain only its linked correction posting");
            }
        }

        Map<OperationId, Money> cumulativeWithholding = new HashMap<>();
        for (List<Posting> datedPostings : state.postingsByDate().values()) {
            for (Posting posting : datedPostings) {
                if (posting.adjustsOperation().isPresent()) {
                    OperationId originalOperation = posting.adjustsOperation().orElseThrow();
                    if (correctionOperations.contains(originalOperation)) {
                        throw invalidState("Tax correction cannot link to another tax correction");
                    }
                    Money originalTax = cumulativeWithholding.get(originalOperation);
                    if (originalTax == null) {
                        throw invalidState("Tax correction references a missing or later original withholding posting");
                    }
                    Money adjustment = validateTaxCorrectionPosting(posting, state.portfolio());
                    if (!originalTax.currency().equals(adjustment.currency())) {
                        throw invalidState("Tax correction currency differs from original withholding");
                    }
                    Money corrected = originalTax.add(adjustment);
                    if (corrected.isNegative()) {
                        throw invalidState("Cumulative withholding for an operation cannot be negative");
                    }
                    cumulativeWithholding.put(originalOperation, corrected);
                } else if (originalTaxPostings.get(posting.operation()) == posting) {
                    // Validate only the identified withholding posting, not its operation's base postings.
                    Money originalTax = validateWithholdingOriginPosting(posting, state.portfolio());
                    cumulativeWithholding.put(posting.operation(), originalTax);
                }
            }
        }
    }

    private static boolean containsWithheldTax(Posting posting) {
        return posting.entries().stream().anyMatch(entry -> entry.account().kind() == AccountKind.TAX_WITHHELD);
    }

    private static Money validateWithholdingOriginPosting(Posting posting, PortfolioId portfolio) {
        if (posting.adjustsOperation().isPresent()) {
            throw new IllegalArgumentException("Original withholding posting must not link to another operation");
        }
        Money amount = validateTaxTransferPosting(posting, portfolio);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Original withholding amount must be positive");
        }
        return amount;
    }

    private static Money validateTaxCorrectionPosting(Posting posting, PortfolioId portfolio) {
        OperationId original = posting.adjustsOperation().orElseThrow(
                () -> new IllegalArgumentException("Correction posting has no adjusted operation"));
        if (posting.operation().equals(original)) {
            throw new IllegalArgumentException("Tax correction cannot adjust itself");
        }
        Money amount = validateTaxTransferPosting(posting, portfolio);
        if (amount.isZero()) {
            throw new IllegalArgumentException("Tax correction amount must not be zero");
        }
        return amount;
    }

    private static Money validateTaxTransferPosting(Posting posting, PortfolioId portfolio) {
        if (posting.entries().size() != 2) {
            throw new IllegalArgumentException("Tax transfer posting must contain exactly two entries");
        }
        Posting.Entry taxEntry = null;
        Posting.Entry cashEntry = null;
        for (Posting.Entry entry : posting.entries()) {
            Account account = entry.account();
            if (!portfolio.equals(account.portfolio())) {
                throw new IllegalArgumentException("Tax transfer belongs to a different portfolio");
            }
            if (account.kind() == AccountKind.TAX_WITHHELD && taxEntry == null) {
                taxEntry = entry;
            } else if (account.kind() == AccountKind.CASH && cashEntry == null) {
                cashEntry = entry;
            } else {
                throw new IllegalArgumentException("Tax transfer must contain one withheld-tax and one cash entry");
            }
        }
        if (taxEntry == null || cashEntry == null
                || !taxEntry.amount().currency().equals(cashEntry.amount().currency())
                || !taxEntry.amount().negate().equals(cashEntry.amount())) {
            throw new IllegalArgumentException("Tax transfer entries must be equal and opposite in one currency");
        }
        return taxEntry.amount();
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
