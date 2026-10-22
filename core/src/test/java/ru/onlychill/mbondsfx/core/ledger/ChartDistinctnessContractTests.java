package ru.onlychill.mbondsfx.core.ledger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that distinctness/role validation is reachable and reports its intended message. */
final class ChartDistinctnessContractTests {
    private static final AccountCode CASH = AccountCode.of("CASH");
    private static final AccountCode EQUITY = AccountCode.of("EQUITY");
    private static final AccountCode SECURITIES = AccountCode.of("SECURITIES");
    private static final AccountCode ACCRUED = AccountCode.of("ACCRUED");
    private static final AccountCode TAX = AccountCode.of("TAX");
    private static final AccountCode PASSIVE = AccountCode.of("PASSIVE");
    private static final AccountCode SPENDABLE_2 = AccountCode.of("SPENDABLE_2");

    private static final String POLICY_DUPLICATE = "Bond account codes must be pairwise distinct";
    private static final String INCOMPATIBLE = "Incompatible account roles";
    private static final String MEASURE_DISTINCT = "Each security-scoped code must bind one distinct position measure";
    private static final String UNKNOWN_ROLE = "Every role code must be known";

    @Test
    void policyRejectsEveryDuplicatePairWithDistinctnessMessage() {
        List<String> slots = List.of("CASH", "EQUITY", "SECURITIES", "ACCRUED_INTEREST", "COUPON_INCOME",
                "AMORTIZATION_GAIN", "REALIZED_PNL", "TAX_WITHHELD", "COMMISSION_EXPENSE");
        for (int first = 0; first < slots.size(); first++) {
            for (int second = first + 1; second < slots.size(); second++) {
                AccountCode[] codes = slots.stream().map(AccountCode::of).toArray(AccountCode[]::new);
                codes[second] = codes[first];
                assertMessage(IllegalArgumentException.class, POLICY_DUPLICATE,
                        () -> new BondAccountingPolicy(codes[0], codes[1], codes[2], codes[3], codes[4],
                                codes[5], codes[6], codes[7], codes[8]));
            }
        }
    }

    @Test
    void chartRejectsAdjustableEqualToCounterpartWithRoleMessage() {
        Set<AccountCode> known = Set.of(CASH, EQUITY, SECURITIES, ACCRUED, TAX);
        Map<AccountCode, PositionMeasure> bindings = Map.of(SECURITIES, PositionMeasure.CARRYING,
                ACCRUED, PositionMeasure.PURCHASED_ACCRUED);
        assertMessage(IllegalArgumentException.class, INCOMPATIBLE,
                () -> new LedgerChart(known, Set.of(CASH), TAX, TAX, Set.of(CASH),
                        Set.of(SECURITIES, ACCRUED), bindings));
        assertMessage(IllegalArgumentException.class, INCOMPATIBLE,
                () -> new LedgerChart(known, Set.of(CASH), CASH, CASH, Set.of(CASH),
                        Set.of(SECURITIES, ACCRUED), bindings));
    }

    @Test
    void chartRejectsRepeatedPositionMeasureWithDistinctnessMessage() {
        Set<AccountCode> known = Set.of(CASH, EQUITY, SECURITIES, ACCRUED, TAX, PASSIVE, SPENDABLE_2);
        assertMessage(IllegalArgumentException.class, MEASURE_DISTINCT,
                () -> new LedgerChart(known, Set.of(CASH), TAX, CASH, Set.of(CASH),
                        Set.of(SECURITIES, ACCRUED), Map.of(SECURITIES, PositionMeasure.CARRYING,
                                ACCRUED, PositionMeasure.CARRYING)));
        // Three codes over two measures: distinct() is 2 here, so only the count condition rejects it.
        assertMessage(IllegalArgumentException.class, MEASURE_DISTINCT,
                () -> new LedgerChart(known, Set.of(CASH), TAX, CASH, Set.of(CASH),
                        Set.of(SECURITIES, ACCRUED, PASSIVE), Map.of(SECURITIES, PositionMeasure.CARRYING,
                                ACCRUED, PositionMeasure.PURCHASED_ACCRUED, PASSIVE, PositionMeasure.CARRYING)));
    }

    @Test
    void unknownRoleCodeInEachRoleSlotIsReportedAsUnknownNotAsRoleIncompatibility() {
        AccountCode ghost = AccountCode.of("GHOST");
        Set<AccountCode> known = Set.of(CASH, EQUITY, SECURITIES, ACCRUED, TAX);
        Map<AccountCode, PositionMeasure> bindings = Map.of(SECURITIES, PositionMeasure.CARRYING,
                ACCRUED, PositionMeasure.PURCHASED_ACCRUED);
        // Spendable role: the other roles are valid, so only the known-code rule explains the rejection.
        assertMessage(IllegalArgumentException.class, UNKNOWN_ROLE,
                () -> new LedgerChart(known, Set.of(ghost), TAX, CASH, Set.of(),
                        Set.of(SECURITIES, ACCRUED), bindings));
        assertMessage(IllegalArgumentException.class, UNKNOWN_ROLE,
                () -> new LedgerChart(known, Set.of(CASH), TAX, CASH, Set.of(ghost),
                        Set.of(SECURITIES, ACCRUED), bindings));
        assertMessage(IllegalArgumentException.class, UNKNOWN_ROLE,
                () -> new LedgerChart(known, Set.of(CASH), TAX, CASH, Set.of(),
                        Set.of(SECURITIES, ACCRUED, ghost), bindings));
        // Bindings keyed by an unknown code while the scoped set itself is valid.
        assertMessage(IllegalArgumentException.class, UNKNOWN_ROLE,
                () -> new LedgerChart(known, Set.of(CASH), TAX, CASH, Set.of(), Set.of(SECURITIES),
                        Map.of(SECURITIES, PositionMeasure.CARRYING, ghost, PositionMeasure.PURCHASED_ACCRUED)));
    }

    private static void assertMessage(Class<? extends Throwable> type, String message, Supplier<?> action) {
        Throwable thrown = assertThrows(type, action::get);
        assertEquals(message, thrown.getMessage());
    }
}
