package ru.onlychill.mbondsfx.core.ledger;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable account-role data used by the ledger engine. */
public final class LedgerChart {
    private static final Comparator<AccountCode> CODE_ORDER = Comparator.comparing(AccountCode::value);

    private final Set<AccountCode> knownCodes;
    private final Set<AccountCode> spendableCodes;
    private final AccountCode adjustableCode;
    private final AccountCode adjustmentCounterpartCode;
    private final Set<AccountCode> noNegativeCodes;
    private final Set<AccountCode> securityScopedCodes;
    private final Map<AccountCode, PositionMeasure> positionBindings;

    public LedgerChart(Set<AccountCode> knownCodes,
                       Set<AccountCode> spendableCodes,
                       AccountCode adjustableCode,
                       AccountCode adjustmentCounterpartCode,
                       Set<AccountCode> noNegativeCodes,
                       Set<AccountCode> securityScopedCodes,
                       Map<AccountCode, PositionMeasure> positionBindings) {
        this.knownCodes = immutableSet(knownCodes, "knownCodes");
        this.spendableCodes = immutableSet(spendableCodes, "spendableCodes");
        this.adjustableCode = Objects.requireNonNull(adjustableCode, "adjustableCode");
        this.adjustmentCounterpartCode = Objects.requireNonNull(
                adjustmentCounterpartCode, "adjustmentCounterpartCode");
        this.noNegativeCodes = immutableSet(noNegativeCodes, "noNegativeCodes");
        this.securityScopedCodes = immutableSet(securityScopedCodes, "securityScopedCodes");
        this.positionBindings = immutableBindings(positionBindings);
        validateRoles();
    }

    public Set<AccountCode> knownCodes() {
        return knownCodes;
    }

    public boolean isKnown(AccountCode code) {
        return knownCodes.contains(Objects.requireNonNull(code, "code"));
    }

    public Set<AccountCode> spendableCodes() {
        return spendableCodes;
    }

    public boolean isSpendable(AccountCode code) {
        return spendableCodes.contains(Objects.requireNonNull(code, "code"));
    }

    public AccountCode adjustableCode() {
        return adjustableCode;
    }

    public AccountCode adjustmentCounterpartCode() {
        return adjustmentCounterpartCode;
    }

    public Set<AccountCode> noNegativeCodes() {
        return noNegativeCodes;
    }

    public boolean isNoNegative(AccountCode code) {
        return noNegativeCodes.contains(Objects.requireNonNull(code, "code"));
    }

    public Set<AccountCode> securityScopedCodes() {
        return securityScopedCodes;
    }

    public boolean isSecurityScoped(AccountCode code) {
        return securityScopedCodes.contains(Objects.requireNonNull(code, "code"));
    }

    public Map<AccountCode, PositionMeasure> positionBindings() {
        return positionBindings;
    }

    public PositionMeasure positionBinding(AccountCode code) {
        Objects.requireNonNull(code, "code");
        PositionMeasure binding = positionBindings.get(code);
        if (binding == null) {
            throw new IllegalArgumentException("Account code is not security-scoped: " + code.value());
        }
        return binding;
    }

    private void validateRoles() {
        if (knownCodes.isEmpty()) {
            throw new IllegalArgumentException("knownCodes must not be empty");
        }
        if (spendableCodes.size() != 1 || noNegativeCodes.size() > 1) {
            throw new IllegalArgumentException("Chart requires one spendable and at most one no-negative code");
        }
        requireKnown(spendableCodes);
        requireKnown(noNegativeCodes);
        requireKnown(securityScopedCodes);
        requireKnown(positionBindings.keySet());
        requireKnown(new LinkedHashSet<>(List.of(adjustableCode, adjustmentCounterpartCode)));

        if (intersects(spendableCodes, securityScopedCodes)
                || intersects(noNegativeCodes, securityScopedCodes)
                || spendableCodes.contains(adjustableCode)
                || noNegativeCodes.contains(adjustableCode)
                || securityScopedCodes.contains(adjustableCode)
                || !spendableCodes.contains(adjustmentCounterpartCode)
                || securityScopedCodes.contains(adjustmentCounterpartCode)
                || adjustableCode.equals(adjustmentCounterpartCode)
                || !spendableCodes.containsAll(noNegativeCodes)) {
            throw new IllegalArgumentException("Incompatible account roles");
        }

        // Bijection check: the binding count must equal the measure count and every binding must be distinct.
        // The count condition is not redundant with the distinct count: with three codes and two measures
        // a repeated measure leaves distinct() == 2 and would otherwise pass.
        if (!positionBindings.keySet().equals(securityScopedCodes)
                || positionBindings.size() != PositionMeasure.values().length
                || new LinkedHashSet<>(positionBindings.values()).size() != positionBindings.size()) {
            throw new IllegalArgumentException("Each security-scoped code must bind one distinct position measure");
        }
    }

    private void requireKnown(Set<AccountCode> roleCodes) {
        if (!knownCodes.containsAll(roleCodes)) {
            throw new IllegalArgumentException("Every role code must be known");
        }
    }

    private static boolean intersects(Set<AccountCode> left, Set<AccountCode> right) {
        return left.stream().anyMatch(right::contains);
    }

    private static Set<AccountCode> immutableSet(Set<AccountCode> values, String name) {
        Objects.requireNonNull(values, name);
        var sorted = new TreeMap<AccountCode, Boolean>(CODE_ORDER);
        for (AccountCode code : values) {
            sorted.put(Objects.requireNonNull(code, name + " element"), Boolean.TRUE);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted.keySet()));
    }

    private static Map<AccountCode, PositionMeasure> immutableBindings(
            Map<AccountCode, PositionMeasure> values) {
        Objects.requireNonNull(values, "positionBindings");
        Map<AccountCode, PositionMeasure> sorted = new TreeMap<>(CODE_ORDER);
        values.forEach((code, measure) -> sorted.put(
                Objects.requireNonNull(code, "positionBindings key"),
                Objects.requireNonNull(measure, "positionBindings value")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LedgerChart chart)) {
            return false;
        }
        return knownCodes.equals(chart.knownCodes)
                && spendableCodes.equals(chart.spendableCodes)
                && adjustableCode.equals(chart.adjustableCode)
                && adjustmentCounterpartCode.equals(chart.adjustmentCounterpartCode)
                && noNegativeCodes.equals(chart.noNegativeCodes)
                && securityScopedCodes.equals(chart.securityScopedCodes)
                && positionBindings.equals(chart.positionBindings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(knownCodes, spendableCodes, adjustableCode, adjustmentCounterpartCode,
                noNegativeCodes, securityScopedCodes, positionBindings);
    }
}
