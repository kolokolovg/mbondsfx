package ru.onlychill.mbondsfx.persistence.memory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.application.port.LedgerCommit;
import ru.onlychill.mbondsfx.application.port.LedgerConcurrencyException;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.application.port.OperationRecord;
import ru.onlychill.mbondsfx.application.service.LedgerApplicationService;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.Account;
import ru.onlychill.mbondsfx.core.ledger.AccountCode;
import ru.onlychill.mbondsfx.core.ledger.BondAccountingPolicy;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Commissions;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.ledger.Posting;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxCorrectionMemoryContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final BondAccountingPolicy STANDARD = BondAccountingPolicy.standard();
    private static final BondOperations OPERATIONS = STANDARD.operations();

    @Test
    void correctionsAreAppendOnlyLinkedAndCasAtomic() {
        PortfolioId portfolio = new PortfolioId("tax-correction-memory");
        OperationId couponId = id("tax-coupon");
        InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        Position position = Position.empty(new PositionId(portfolio, new SecurityId("SU-TAX-CORRECTION")), RUB);

        service.apply(portfolio, false,
                OPERATIONS.deposit(id("tax-deposit"), DAY, portfolio, RUB, money("200")));
        OperationResult purchase = OPERATIONS.buy(id("tax-buy"), DAY.plusDays(1), position, 1,
                money("90"), money("0"), money("100"), new Commissions(money("1.25"), money("0.25")));
        service.apply(portfolio, false, purchase);
        OperationResult coupon = OPERATIONS.coupon(couponId, DAY.plusDays(2),
                purchase.positionAfter().orElseThrow(), money("10"), Optional.of(money("2")));
        service.apply(portfolio, false, coupon);

        service.apply(portfolio, false,
                OPERATIONS.taxCorrection(id("tax-up"), DAY.plusDays(3), portfolio,
                        couponId, money("1")));
        service.apply(portfolio, false,
                OPERATIONS.taxCorrection(id("tax-down"), DAY.plusDays(4), portfolio,
                        couponId, money("-0.50")));

        LedgerSnapshot snapshot = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journal = repository.operations(portfolio);
        OperationRecord originalCoupon = journal.get(2);
        OperationRecord upwardCorrection = journal.get(3);
        OperationRecord downwardCorrection = journal.get(4);
        Account cash = account(portfolio, STANDARD.cash());
        Account tax = account(portfolio, STANDARD.taxWithheld());
        Account commissionExpense = account(portfolio, STANDARD.commissionExpense());
        Ledger restored = Ledger.restore(snapshot.state());

        assertEquals(5, snapshot.version(), "each operation, including each correction, advances the version");
        assertEquals(5, snapshot.lastSequence(), "each append receives a new journal sequence");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), journal.stream().map(OperationRecord::sequence).toList(),
                "journal sequences remain contiguous");
        assertEquals(OperationResult.OperationKind.COUPON, originalCoupon.kind());
        assertEquals(coupon.postings(), originalCoupon.postings(), "the original coupon postings are retained unchanged");
        assertEquals(money("2"), originalCoupon.postings().stream()
                .flatMap(posting -> posting.entries().stream())
                .filter(entry -> entry.account().equals(tax)).map(Posting.Entry::amount)
                .reduce(Money.zero(RUB), Money::add), "original recorded withholding remains two RUB");

        assertEquals(OperationResult.OperationKind.TAX_CORRECTION, upwardCorrection.kind());
        assertEquals(OperationResult.OperationKind.TAX_CORRECTION, downwardCorrection.kind());
        assertCorrectionPosting(upwardCorrection, id("tax-up"), couponId, "1");
        assertCorrectionPosting(downwardCorrection, id("tax-down"), couponId, "-0.50");
        assertEquals(money("2.50"), restored.taxWithheldForOperation(couponId),
                "linked corrections yield the corrected cumulative withholding");
        assertEquals(money("2.50"), restored.taxWithheldBalances().get(RUB),
                "tax balance reflects both signed corrections");
        assertEquals(money("116"), restored.balance(cash), "cash includes coupon net of corrected tax");
        assertEquals(money("1.50"), restored.balance(commissionExpense), "both buy fees are expensed");
        assertEquals(money("118.50"), restored.balance(cash).add(restored.balance(tax)),
                "cash plus withheld tax reconciles to net funded and coupon economics");
        assertEquals(snapshot.state(), restored.state(), "repository snapshot survives Ledger.restore round-trip");

        LedgerSnapshot beforeOverRefund = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journalBeforeOverRefund = repository.operations(portfolio);
        OperationResult overRefund = OPERATIONS.taxCorrection(id("tax-over-refund"), DAY.plusDays(5),
                portfolio, couponId, money("-2.51"));
        assertThrows(IllegalStateException.class,
                () -> service.apply(portfolio, false, overRefund), "withholding cannot be corrected below zero");
        assertEquals(beforeOverRefund, repository.load(portfolio).orElseThrow(),
                "rejected over-refund leaves the repository snapshot unchanged");
        assertEquals(journalBeforeOverRefund, repository.operations(portfolio),
                "rejected over-refund adds no journal record");

        LedgerSnapshot staleBase = repository.load(portfolio).orElseThrow();
        OperationResult staleCorrection = OPERATIONS.taxCorrection(id("tax-stale-correction"),
                DAY.plusDays(6), portfolio, couponId, money("-0.50"));
        Ledger staleAfter = Ledger.restore(staleBase.state()).apply(staleCorrection);
        LedgerCommit staleCommit = new LedgerCommit(staleBase.version(), staleBase.state(),
                staleCorrection, staleAfter.state());
        service.apply(portfolio, false,
                OPERATIONS.deposit(id("tax-cas-winner"), DAY.plusDays(6), portfolio, RUB, money("1")));
        LedgerSnapshot afterWinner = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journalAfterWinner = repository.operations(portfolio);

        LedgerConcurrencyException conflict = assertThrows(LedgerConcurrencyException.class,
                () -> repository.commit(staleCommit), "a correction built from a stale snapshot is rejected");
        assertEquals(staleBase.version(), conflict.expectedVersion());
        assertEquals(afterWinner.version(), conflict.actualVersion());
        assertEquals(afterWinner, repository.load(portfolio).orElseThrow(),
                "stale correction leaves the winning snapshot unchanged");
        assertEquals(journalAfterWinner, repository.operations(portfolio),
                "stale correction adds no journal record");
    }

    private static void assertCorrectionPosting(OperationRecord record, OperationId correction,
                                               OperationId original, String adjustment) {
        assertEquals(correction, record.operation());
        assertEquals(1, record.postings().size(), "correction is a separate single balanced posting");
        Posting posting = record.postings().getFirst();
        assertEquals(Optional.of(original), posting.adjustsOperation(), "correction retains its original-operation link");
        Money expectedTax = money(adjustment);
        Money expectedCash = expectedTax.negate();
        assertEquals(expectedTax, posting.entries().stream()
                .filter(entry -> entry.account().code().equals(STANDARD.taxWithheld()))
                .map(Posting.Entry::amount).reduce(Money.zero(RUB), Money::add));
        assertEquals(expectedCash, posting.entries().stream()
                .filter(entry -> entry.account().code().equals(STANDARD.cash()))
                .map(Posting.Entry::amount).reduce(Money.zero(RUB), Money::add));
        assertTrue(posting.entries().stream().map(Posting.Entry::amount)
                .reduce(Money.zero(RUB), Money::add).isZero(), "correction posting balances");
    }

    private static Account account(PortfolioId portfolio, AccountCode code) {
        return new Account(portfolio, code, Optional.empty(), RUB);
    }

    private static OperationId id(String value) { return new OperationId(value); }

    private static Money money(String value) {
        return new Money(new BigDecimal(value), RUB);
    }
}
