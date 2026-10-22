package ru.onlychill.mbondsfx.application;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.application.port.Iis3Terms;
import ru.onlychill.mbondsfx.application.port.InvestmentAccount;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountConflictException;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountRepository;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountType;
import ru.onlychill.mbondsfx.application.port.DuplicateOperationException;
import ru.onlychill.mbondsfx.application.port.LedgerCommit;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.application.port.OperationRecord;
import ru.onlychill.mbondsfx.application.service.CouponReceipt;
import ru.onlychill.mbondsfx.application.service.CouponReceiptService;
import ru.onlychill.mbondsfx.application.service.EarlyClosureAssessment;
import ru.onlychill.mbondsfx.application.service.LedgerApplicationService;
import ru.onlychill.mbondsfx.application.service.PenaltyEstimate;
import ru.onlychill.mbondsfx.application.service.PenaltyEstimateService;
import ru.onlychill.mbondsfx.application.service.TaxCorrectionService;
import ru.onlychill.mbondsfx.application.port.PenaltyInterval;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.Account;
import ru.onlychill.mbondsfx.core.ledger.AccountCode;
import ru.onlychill.mbondsfx.core.ledger.BondAccountingPolicy;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestmentAccountContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final LocalDate OPENING = LocalDate.of(2020, 1, 1);
    private static final LocalDate OP_DATE = LocalDate.of(2026, 10, 20);
    private static final BondAccountingPolicy POLICY = BondAccountingPolicy.standard();
    private static final BondOperations OPERATIONS = new BondOperations(POLICY);

    @Test
    void explicitAccountTypesAndIisTermsAreValidatedAndCalendarDerived() {
        PortfolioId brokeragePortfolio = portfolio("brokerage");
        InvestmentAccount brokerage = InvestmentAccount.brokerage(brokeragePortfolio);
        assertEquals(InvestmentAccountType.BROKERAGE, brokerage.type());
        assertTrue(brokerage.iis3Terms().isEmpty());
        assertTrue(brokerage.openingDate().isEmpty());

        Iis3Terms leapTerms = new Iis3Terms(LocalDate.of(2024, 2, 29), Period.ofYears(3),
                Optional.of(LocalDate.of(2026, 2, 28)));
        InvestmentAccount iis = InvestmentAccount.iis3(portfolio("iis"), leapTerms);
        assertEquals(InvestmentAccountType.IIS_3, iis.type());
        assertEquals(LocalDate.of(2027, 2, 28), iis.earliestEligibleCloseDate().orElseThrow(),
                "date uses calendar-year addition and handles leap day");
        assertEquals(LocalDate.of(2026, 2, 28), iis.contractualCloseDate().orElseThrow(),
                "contractual date remains separate from minimum term");

        assertThrows(NullPointerException.class,
                () -> InvestmentAccount.iis3(portfolio("no-terms"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new InvestmentAccount(portfolio("brokerage-terms"), InvestmentAccountType.BROKERAGE,
                        Optional.of(leapTerms)));
        assertThrows(IllegalArgumentException.class,
                () -> new Iis3Terms(OPENING, Period.ZERO, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new Iis3Terms(OPENING, Period.of(2, -1, 0), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new Iis3Terms(OPENING, Period.ofMonths(36), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new Iis3Terms(OPENING, Period.ofYears(1), Optional.of(OPENING.minusDays(1))));
        assertThrows(DateTimeException.class,
                () -> new Iis3Terms(LocalDate.MAX, Period.ofYears(1), Optional.empty()));

        InvestmentAccount customPre2024 = InvestmentAccount.iis3(portfolio("custom-pre-2024"),
                new Iis3Terms(LocalDate.of(2023, 12, 31), Period.ofYears(1), Optional.empty()));
        assertEquals(LocalDate.of(2023, 12, 31), customPre2024.openingDate().orElseThrow(),
                "custom terms remain structural metadata, not statutory validation");
    }

    @Test
    void newIis3FactorySelectsTheOpeningYearMinimumTermAndRejectsPre2024() {
        Map<Integer, Integer> minimumYears = Map.of(
                2024, 5, 2025, 5, 2026, 5, 2027, 6, 2028, 7, 2029, 8, 2030, 9, 2031, 10, 2040, 10);
        minimumYears.forEach((year, years) -> {
            InvestmentAccount account = InvestmentAccount.newIis3WithMinimumTermForOpeningDate(
                    portfolio("new-iis-" + year), LocalDate.of(year, 1, 1), Optional.empty());
            assertEquals(Period.ofYears(years), account.iis3Terms().orElseThrow().minimumTerm(),
                    "opening year " + year);
        });
        assertThrows(IllegalArgumentException.class,
                () -> InvestmentAccount.newIis3WithMinimumTermForOpeningDate(
                        portfolio("legacy-opening"), LocalDate.of(2023, 12, 31), Optional.empty()));
    }

    @Test
    void couponConfirmationPreservesGrossTaxNetAndDoesNotDoubleDeductTax() {
        Fixture fixture = fixture(InvestmentAccount.brokerage(portfolio("coupon-brokerage")));
        CouponReceipt receipt = fixture.coupons().confirm(fixture.account(), fixture.position(),
                operation("coupon-100-13-87"), OP_DATE, money("100"), money("13"), money("87"), false);

        assertEquals(money("100"), receipt.grossTotal());
        assertEquals(money("13"), receipt.taxWithheldTotal());
        assertEquals(money("87"), receipt.netReceivedTotal());
        OperationRecord journal = couponRecord(fixture.ledger(), fixture.account().portfolio(), receipt.operation());
        assertEquals(2, journal.postings().size(), "nonzero withholding is a distinct balanced posting");
        assertPostingIdentities(journal);
        assertEquals(money("87"), totalBalance(journal, POLICY.cash()),
                "gross cash and the separate withholding credit produce net available cash");
        assertEquals(money("100"), totalPositive(journal, POLICY.cash()),
                "the base coupon posting records gross cash before withholding");
        assertEquals(money("13"), totalBalance(journal, POLICY.taxWithheld()));
        assertEquals(money("100"), totalNegative(journal, POLICY.couponIncome()).negate());
        Ledger state = Ledger.restore(receipt.ledgerSnapshot().state());
        assertEquals(money("1077"), state.cashBalances().get(RUB));
        assertEquals(money("13"), state.taxWithheldBalances().get(RUB));
        assertFalse(state.cashBalances().containsKey(Currency.getInstance("USD")));
    }

    @Test
    void iis3ZeroTaxMustBeExplicitAndKnownZeroIsNotUnknown() {
        InvestmentAccount account = InvestmentAccount.iis3(portfolio("coupon-iis"),
                new Iis3Terms(OPENING, Period.ofYears(3), Optional.empty()));
        Fixture fixture = fixture(account);
        CouponReceipt receipt = fixture.coupons().confirm(account, fixture.position(), operation("iis-coupon"),
                OP_DATE, money("100"), money("0"), money("100"), false);
        assertEquals(money("0"), receipt.taxWithheldTotal());
        OperationRecord coupon = couponRecord(fixture.ledger(), account.portfolio(), receipt.operation());
        assertEquals(1, coupon.postings().size(), "zero withholding creates no extra tax posting");
        assertEquals(money("100"), totalBalance(coupon, POLICY.cash()));
        assertEquals(money("0"), totalBalance(coupon, POLICY.taxWithheld()));
        assertPostingIdentities(coupon);

        LedgerSnapshot before = fixture.ledger().load(account.portfolio()).orElseThrow();
        assertThrows(NullPointerException.class, () -> fixture.coupons().confirm(account, fixture.position(),
                operation("unknown-tax"), OP_DATE, money("100"), null, money("100"), false));
        assertEquals(before, fixture.ledger().load(account.portfolio()).orElseThrow(),
                "missing tax is not silently treated as zero");
    }

    @Test
    void iis3NonzeroWithholdingIsKeptAndGrossSurvivesPurchasedAccruedRelease() {
        InvestmentAccount account = InvestmentAccount.iis3(portfolio("coupon-iis-tax-fact"),
                new Iis3Terms(OPENING, Period.ofYears(3), Optional.empty()));
        Fixture fixture = fixture(account, money("20"));
        CouponReceipt receipt = fixture.coupons().confirm(account, fixture.position(),
                operation("iis-coupon-nonzero-tax"), OP_DATE,
                money("100"), money("13"), money("87"), false);

        assertEquals(money("13"), receipt.taxWithheldTotal(), "actual withholding is retained on IIS-3");
        OperationRecord journal = couponRecord(fixture.ledger(), account.portfolio(), receipt.operation());
        assertEquals(2, journal.postings().size());
        assertPostingIdentities(journal);
        assertEquals(money("87"), totalBalance(journal, POLICY.cash()));
        assertEquals(money("100"), totalPositive(journal, POLICY.cash()));
        assertEquals(money("13"), totalBalance(journal, POLICY.taxWithheld()));
        assertEquals(money("20"), totalNegative(journal, POLICY.accruedInterest()).negate());
        assertEquals(money("80"), totalNegative(journal, POLICY.couponIncome()).negate(),
                "purchased accrued interest release means income alone is not gross coupon");
        assertEquals(money("100"), totalBalance(journal, POLICY.cash())
                .add(totalBalance(journal, POLICY.taxWithheld())),
                "journal reconstructs gross cash from net available cash and actual tax withheld");
    }

    @Test
    void couponApplicationKeepsWithholdingSeparateByCurrencyAndPortfolio() {
        PortfolioId portfolio = portfolio("coupon-multi-currency");
        InvestmentAccount account = InvestmentAccount.brokerage(portfolio);
        TestAccountRepository accounts = new TestAccountRepository();
        accounts.register(account);
        TestLedgerRepository repository = new TestLedgerRepository();
        LedgerApplicationService ledgerService = new LedgerApplicationService(repository, POLICY);
        Currency usd = Currency.getInstance("USD");
        ledgerService.apply(portfolio, false, OPERATIONS.opening(operation("multi-rub-opening"),
                OP_DATE.minusDays(2), portfolio, RUB, money("1000")));
        ledgerService.apply(portfolio, false, OPERATIONS.opening(operation("multi-usd-opening"),
                OP_DATE.minusDays(2), portfolio, usd, new Money(new BigDecimal("1000"), usd)));

        Position rubPosition = boughtPosition(ledgerService, repository, portfolio,
                "MULTI-RUB", RUB, money("100"), operation("multi-rub-buy"));
        Position usdPosition = boughtPosition(ledgerService, repository, portfolio,
                "MULTI-USD", usd, new Money(new BigDecimal("80"), usd), operation("multi-usd-buy"));
        CouponReceiptService service = new CouponReceiptService(accounts, repository, POLICY);
        service.confirm(account, rubPosition, operation("multi-rub-coupon"), OP_DATE,
                money("100"), money("13"), money("87"), false);
        service.confirm(account, usdPosition, operation("multi-usd-coupon"), OP_DATE,
                new Money(new BigDecimal("80"), usd), new Money(new BigDecimal("8"), usd),
                new Money(new BigDecimal("72"), usd), false);

        Ledger ledger = Ledger.restore(repository.load(portfolio).orElseThrow().state());
        assertEquals(Map.of(RUB, money("1077"), usd, new Money(new BigDecimal("1062"), usd)),
                ledger.cashBalances(), "CASH remains net available cash, independently per currency");
        assertEquals(Map.of(RUB, money("13"), usd, new Money(new BigDecimal("8"), usd)),
                ledger.taxWithheldBalances(), "withheld amounts are separate, independently per currency");
        assertThrows(UnsupportedOperationException.class,
                () -> ledger.taxWithheldBalances().put(RUB, money("0")),
                "the per-currency withholding view is immutable");

        InvestmentAccount otherAccount = InvestmentAccount.brokerage(portfolio("coupon-other-portfolio"));
        Fixture other = fixture(otherAccount);
        CouponReceipt otherReceipt = other.coupons().confirm(otherAccount, other.position(),
                operation("other-portfolio-coupon"), OP_DATE, money("20"), money("2"), money("18"), false);
        assertEquals(Map.of(RUB, money("2")),
                Ledger.restore(otherReceipt.ledgerSnapshot().state()).taxWithheldBalances(),
                "another portfolio has an independent withholding subaccount");
        assertEquals(Map.of(RUB, money("13"), usd, new Money(new BigDecimal("8"), usd)),
                ledger.taxWithheldBalances(), "processing another portfolio does not change this ledger");
    }

    @Test
    void taxCorrectionsAppendCumulativeDeltasWithoutChangingOriginalReceiptOrIncome() {
        Fixture fixture = fixture(InvestmentAccount.brokerage(portfolio("tax-correction-history")));
        CouponReceipt receipt = fixture.coupons().confirm(fixture.account(), fixture.position(),
                operation("tax-correction-original-coupon"), OP_DATE,
                money("100"), money("13"), money("87"), false);
        OperationRecord originalRecord = couponRecord(fixture.ledger(), fixture.account().portfolio(),
                receipt.operation());
        Ledger beforeCorrection = Ledger.restore(receipt.ledgerSnapshot().state());
        TaxCorrectionService service = new TaxCorrectionService(fixture.repository(), POLICY);

        int loadsBefore = fixture.repository().loadCalls;
        LedgerSnapshot firstCorrection = service.correct(fixture.account().portfolio(),
                operation("tax-correction-down"), OP_DATE.plusDays(1), receipt.operation(), money("8"), false);
        assertEquals(loadsBefore + 1, fixture.repository().loadCalls,
                "the correction reads one snapshot and commits against that exact version");
        assertEquals(receipt.ledgerSnapshot().version() + 1, firstCorrection.version(),
                "a successful correction appends one CAS-committed ledger version");
        Ledger afterFirst = Ledger.restore(firstCorrection.state());
        OperationRecord downRecord = couponRecord(fixture.ledger(), fixture.account().portfolio(),
                operation("tax-correction-down"));
        assertEquals(money("8"), afterFirst.taxWithheldForOperation(receipt.operation()));
        assertEquals(money("8"), afterFirst.taxWithheldBalances().get(RUB));
        assertEquals(money("1082"), afterFirst.cashBalances().get(RUB),
                "reducing withholding credits the cash subaccount by the adjustment");
        assertEquals(money("-5"), totalBalance(downRecord, POLICY.taxWithheld()));
        assertEquals(money("5"), totalBalance(downRecord, POLICY.cash()));
        assertEquals(Optional.of(receipt.operation()), downRecord.postings().getFirst().adjustsOperation());
        assertPostingIdentities(downRecord);
        assertEquals(originalRecord, couponRecord(fixture.ledger(), fixture.account().portfolio(),
                receipt.operation()), "the original journal operation is immutable");
        assertEquals(money("13"), receipt.taxWithheldTotal(), "the original receipt remains historical");
        assertEquals(beforeCorrection.positions(), afterFirst.positions());
        assertEquals(incomeBalances(beforeCorrection), incomeBalances(afterFirst),
                "tax corrections do not rewrite coupon income");

        LedgerSnapshot increased = service.correct(fixture.account().portfolio(),
                operation("tax-correction-up"), OP_DATE.plusDays(2), receipt.operation(), money("15"), false);
        Ledger afterIncrease = Ledger.restore(increased.state());
        assertEquals(money("15"), afterIncrease.taxWithheldForOperation(receipt.operation()));
        assertEquals(money("1075"), afterIncrease.cashBalances().get(RUB));

        LedgerSnapshot decreasedAgain = service.correct(fixture.account().portfolio(),
                operation("tax-correction-down-again"), OP_DATE.plusDays(3), receipt.operation(), money("3"), false);
        Ledger afterDecrease = Ledger.restore(decreasedAgain.state());
        assertEquals(money("3"), afterDecrease.taxWithheldForOperation(receipt.operation()));
        assertEquals(money("1087"), afterDecrease.cashBalances().get(RUB));
        assertEquals(originalRecord, couponRecord(fixture.ledger(), fixture.account().portfolio(),
                receipt.operation()));
        assertEquals(beforeCorrection.positions(), afterDecrease.positions());
        assertEquals(incomeBalances(beforeCorrection), incomeBalances(afterDecrease));
    }

    @Test
    void deN05_taxCorrectionPreservesQueryErrorsAndRejectsInvalidCorrectionsAtomically() {
        Fixture fixture = fixture(InvestmentAccount.brokerage(portfolio("tax-correction-invalid")));
        CouponReceipt receipt = fixture.coupons().confirm(fixture.account(), fixture.position(),
                operation("tax-correction-invalid-original"), OP_DATE,
                money("100"), money("13"), money("87"), false);
        TaxCorrectionService service = new TaxCorrectionService(fixture.repository(), POLICY);
        LedgerSnapshot before = fixture.ledger().load(fixture.account().portfolio()).orElseThrow();
        List<OperationRecord> journalBefore = fixture.ledger().operations(fixture.account().portfolio());

        assertThrows(IllegalArgumentException.class, () -> service.correct(fixture.account().portfolio(),
                operation("tax-correction-noop"), OP_DATE.plusDays(1), receipt.operation(), money("13"), false));
        assertThrows(IllegalArgumentException.class, () -> service.correct(fixture.account().portfolio(),
                operation("tax-correction-wrong-currency"), OP_DATE.plusDays(1), receipt.operation(),
                new Money(new BigDecimal("13"), Currency.getInstance("USD")), false));
        assertThrows(IllegalStateException.class, () -> service.correct(fixture.account().portfolio(),
                operation("tax-correction-wrong-policy"), OP_DATE.plusDays(1), receipt.operation(), money("8"), true));
        assertThrows(IllegalArgumentException.class, () -> service.correct(fixture.account().portfolio(),
                operation("tax-correction-no-tax-original"), OP_DATE.plusDays(1),
                operation("purchase-" + fixture.account().portfolio().value()), money("8"), false));
        assertThrows(IllegalArgumentException.class, () -> service.correct(fixture.account().portfolio(),
                operation("tax-correction-unknown-original"), OP_DATE.plusDays(1),
                operation("unknown-original-operation"), money("8"), false),
                "an unknown original-operation query is passed through as IllegalArgumentException");
        assertEquals(before, fixture.ledger().load(fixture.account().portfolio()).orElseThrow());
        assertEquals(journalBefore, fixture.ledger().operations(fixture.account().portfolio()));

        assertThrows(IllegalStateException.class, () -> new TaxCorrectionService(new TestLedgerRepository(), POLICY).correct(
                portfolio("unknown-tax-ledger"), operation("missing-ledger-correction"), OP_DATE,
                receipt.operation(), money("8"), false));

        OperationId correctionId = operation("tax-correction-duplicate");
        service.correct(fixture.account().portfolio(), correctionId, OP_DATE.plusDays(1), receipt.operation(),
                money("8"), false);
        assertThrows(IllegalArgumentException.class, () -> service.correct(fixture.account().portfolio(),
                operation("correction-of-correction"), OP_DATE.plusDays(2), correctionId, money("9"), false),
                "a correction operation cannot be queried as the original withholding operation");
        LedgerSnapshot afterFirst = fixture.ledger().load(fixture.account().portfolio()).orElseThrow();
        List<OperationRecord> journalAfterFirst = fixture.ledger().operations(fixture.account().portfolio());
        assertThrows(DuplicateOperationException.class, () -> service.correct(fixture.account().portfolio(),
                correctionId, OP_DATE.plusDays(2), receipt.operation(), money("9"), false));
        assertEquals(afterFirst, fixture.ledger().load(fixture.account().portfolio()).orElseThrow());
        assertEquals(journalAfterFirst, fixture.ledger().operations(fixture.account().portfolio()));

        Fixture failed = fixture(InvestmentAccount.brokerage(portfolio("tax-correction-commit-failure")));
        CouponReceipt failedReceipt = failed.coupons().confirm(failed.account(), failed.position(),
                operation("failed-correction-original"), OP_DATE,
                money("100"), money("13"), money("87"), false);
        LedgerSnapshot failedBefore = failed.ledger().load(failed.account().portfolio()).orElseThrow();
        List<OperationRecord> failedJournal = failed.ledger().operations(failed.account().portfolio());
        failed.repository().failNextCommit = true;
        assertThrows(IllegalStateException.class, () -> new TaxCorrectionService(failed.repository(), POLICY).correct(
                failed.account().portfolio(), operation("failed-tax-correction"), OP_DATE.plusDays(1),
                failedReceipt.operation(), money("8"), false));
        assertEquals(failedBefore, failed.ledger().load(failed.account().portfolio()).orElseThrow());
        assertEquals(failedJournal, failed.ledger().operations(failed.account().portfolio()));
    }

    @Test
    void deN07_taxCorrectionServiceRejectsPersistedChartDriftBeforeCommit() {
        Fixture fixture = fixture(InvestmentAccount.brokerage(portfolio("tax-correction-chart-drift")));
        CouponReceipt receipt = fixture.coupons().confirm(fixture.account(), fixture.position(),
                operation("chart-drift-original"), OP_DATE, money("100"), money("13"), money("87"), false);
        LedgerSnapshot before = fixture.ledger().load(fixture.account().portfolio()).orElseThrow();
        List<OperationRecord> journalBefore = fixture.ledger().operations(fixture.account().portfolio());
        TaxCorrectionService service = new TaxCorrectionService(fixture.repository(),
                policyWithCashCode("AVAILABLE_CASH"));

        assertThrows(IllegalStateException.class, () -> service.correct(fixture.account().portfolio(),
                        operation("chart-drift-correction"), OP_DATE.plusDays(1), receipt.operation(), money("8"), false),
                "tax correction rejects a chart that differs by value");
        assertEquals(before, fixture.ledger().load(fixture.account().portfolio()).orElseThrow());
        assertEquals(journalBefore, fixture.ledger().operations(fixture.account().portfolio()));
    }

    @Test
    void couponConfirmationRejectsUnknownOrConflictingAccountRegistryMetadata() {
        Fixture fixture = fixture(InvestmentAccount.brokerage(portfolio("coupon-registry-check")));
        LedgerSnapshot before = fixture.ledger().load(fixture.account().portfolio()).orElseThrow();
        List<OperationRecord> beforeJournal = fixture.ledger().operations(fixture.account().portfolio());
        CouponReceiptService unknownRegistryService = new CouponReceiptService(
                new TestAccountRepository(), fixture.repository(), POLICY);
        assertThrows(IllegalStateException.class, () -> unknownRegistryService.confirm(fixture.account(),
                fixture.position(), operation("unknown-account"), OP_DATE,
                money("10"), money("0"), money("10"), false));

        TestAccountRepository conflictingRegistry = new TestAccountRepository();
        conflictingRegistry.register(InvestmentAccount.iis3(fixture.account().portfolio(),
                new Iis3Terms(OPENING, Period.ofYears(3), Optional.empty())));
        CouponReceiptService conflictingRegistryService = new CouponReceiptService(
                conflictingRegistry, fixture.repository(), POLICY);
        assertThrows(IllegalArgumentException.class, () -> conflictingRegistryService.confirm(fixture.account(),
                fixture.position(), operation("conflicting-account"), OP_DATE,
                money("10"), money("0"), money("10"), false));
        assertEquals(before, fixture.ledger().load(fixture.account().portfolio()).orElseThrow());
        assertEquals(beforeJournal, fixture.ledger().operations(fixture.account().portfolio()));
    }

    @Test
    void couponConfirmationRejectsStalePositionWithoutSideEffects() {
        Fixture fixture = fixture(InvestmentAccount.brokerage(portfolio("coupon-stale-position")));
        Position stale = fixture.position();
        OperationResult additionalPurchase = OPERATIONS.buy(operation("position-changed"), OP_DATE,
                stale, 1, money("10"), money("0"), money("100"), money("0"));
        new LedgerApplicationService(fixture.repository(), POLICY).apply(fixture.account().portfolio(), false,
                additionalPurchase);
        LedgerSnapshot before = fixture.ledger().load(fixture.account().portfolio()).orElseThrow();
        List<OperationRecord> beforeJournal = fixture.ledger().operations(fixture.account().portfolio());

        assertThrows(IllegalArgumentException.class, () -> fixture.coupons().confirm(fixture.account(), stale,
                operation("coupon-with-stale-position"), OP_DATE.plusDays(1),
                money("10"), money("0"), money("10"), false));
        assertEquals(before, fixture.ledger().load(fixture.account().portfolio()).orElseThrow());
        assertEquals(beforeJournal, fixture.ledger().operations(fixture.account().portfolio()));
    }

    @Test
    void invalidCouponFactsAndPortfolioOrOpeningMismatchesDoNotCommit() {
        Fixture fixture = fixture(InvestmentAccount.brokerage(portfolio("coupon-invalid")));
        List<OperationRecord> before = fixture.ledger().operations(fixture.account().portfolio());
        assertThrows(IllegalArgumentException.class, () -> fixture.coupons().confirm(fixture.account(),
                fixture.position(), operation("bad-sum"), OP_DATE,
                money("100"), money("13"), money("86"), false));
        assertThrows(IllegalArgumentException.class, () -> fixture.coupons().confirm(fixture.account(),
                fixture.position(), operation("bad-currency"), OP_DATE,
                money("100"), new Money(BigDecimal.ONE, Currency.getInstance("USD")),
                new Money(BigDecimal.ONE, Currency.getInstance("USD")), false));

        Position foreign = Position.empty(new PositionId(portfolio("other-portfolio"),
                fixture.position().id().security()), RUB);
        assertThrows(IllegalArgumentException.class, () -> fixture.coupons().confirm(fixture.account(), foreign,
                operation("foreign-position"), OP_DATE, money("1"), money("0"), money("1"), false));
        assertEquals(before, fixture.ledger().operations(fixture.account().portfolio()));

        InvestmentAccount futureIis = InvestmentAccount.iis3(portfolio("future-iis"),
                new Iis3Terms(OP_DATE.plusDays(1), Period.ofYears(3), Optional.empty()));
        Fixture futureFixture = fixture(futureIis);
        assertThrows(IllegalArgumentException.class, () -> futureFixture.coupons().confirm(futureIis,
                futureFixture.position(), operation("before-iis-opening"), OP_DATE,
                money("1"), money("0"), money("1"), false));
        assertEquals(2, futureFixture.ledger().operations(futureIis.portfolio()).size(),
                "failed actual-date validation never enters the journal");
    }

    @Test
    void duplicatesAndCommitFailureHaveNoPartialSideEffects() {
        Fixture duplicateFixture = fixture(InvestmentAccount.brokerage(portfolio("coupon-duplicate")));
        OperationId duplicateId = operation("same-coupon");
        duplicateFixture.coupons().confirm(duplicateFixture.account(), duplicateFixture.position(), duplicateId,
                OP_DATE, money("10"), money("1"), money("9"), false);
        LedgerSnapshot committed = duplicateFixture.ledger().load(duplicateFixture.account().portfolio()).orElseThrow();
        List<OperationRecord> journal = duplicateFixture.ledger().operations(duplicateFixture.account().portfolio());
        assertThrows(DuplicateOperationException.class,
                () -> duplicateFixture.coupons().confirm(duplicateFixture.account(), duplicateFixture.position(),
                        duplicateId, OP_DATE.plusDays(1), money("10"), money("1"), money("9"), false));
        assertEquals(committed, duplicateFixture.ledger().load(duplicateFixture.account().portfolio()).orElseThrow());
        assertEquals(journal, duplicateFixture.ledger().operations(duplicateFixture.account().portfolio()));

        Fixture failedFixture = fixture(InvestmentAccount.brokerage(portfolio("coupon-failed")));
        LedgerSnapshot before = failedFixture.ledger().load(failedFixture.account().portfolio()).orElseThrow();
        List<OperationRecord> beforeJournal = failedFixture.ledger().operations(failedFixture.account().portfolio());
        failedFixture.repository().failNextCommit = true;
        assertThrows(IllegalStateException.class, () -> failedFixture.coupons().confirm(failedFixture.account(),
                failedFixture.position(), operation("failed-coupon"), OP_DATE,
                money("10"), money("1"), money("9"), false));
        assertEquals(before, failedFixture.ledger().load(failedFixture.account().portfolio()).orElseThrow());
        assertEquals(beforeJournal, failedFixture.ledger().operations(failedFixture.account().portfolio()));
    }

    @Test
    void closureAssessmentIsPartialAndTotalsOnlyFullyKnownComponents() {
        InvestmentAccount account = InvestmentAccount.iis3(portfolio("closure"),
                new Iis3Terms(LocalDate.of(2024, 2, 29), Period.ofYears(3), Optional.empty()));
        EarlyClosureAssessment early = new EarlyClosureAssessment(account, LocalDate.of(2026, 2, 28),
                Optional.of(money("10")), Optional.empty(), Optional.of(money("0")));
        assertFalse(early.minimumTermReached());
        assertEquals(365L, early.daysEarly());
        assertTrue(early.totalKnownComponents().isEmpty(), "unknown is not replaced with zero");

        EarlyClosureAssessment complete = new EarlyClosureAssessment(account, LocalDate.of(2027, 2, 28),
                Optional.of(money("10")), Optional.of(money("3")), Optional.of(money("0")));
        assertTrue(complete.minimumTermReached());
        assertEquals(0L, complete.daysEarly());
        assertEquals(Optional.of(money("13")), complete.totalKnownComponents());
        assertThrows(IllegalArgumentException.class, () -> new EarlyClosureAssessment(account,
                LocalDate.of(2023, 1, 1), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new EarlyClosureAssessment(account,
                LocalDate.of(2026, 1, 1), Optional.of(money("1")),
                Optional.of(new Money(BigDecimal.ONE, Currency.getInstance("USD"))), Optional.empty()));
    }

    @Test
    void penaltyEstimateUsesDecimalKeyRatesAndActualCalendarDaysAcrossRateChanges() {
        PenaltyEstimateService service = new PenaltyEstimateService();
        PenaltyInterval leapFebruary = interval("2024-02-28", "2024-03-01", "10000", "0.15");
        PenaltyInterval changedRate = interval("2024-03-01", "2024-03-03", "10000", "0.16");
        PenaltyEstimate estimate = service.estimate(List.of(leapFebruary, changedRate));

        assertEquals(money("20.67"), estimate.amount(),
                "two leap-calendar days at 15% plus two days at 16%, with one final rounding");
        assertEquals(List.of(leapFebruary, changedRate), estimate.coveredIntervals(),
                "estimate exposes precisely the intervals covered, without filling gaps");

        PenaltyEstimate thirtyDayEstimate = service.estimate(List.of(
                interval("2024-03-01", "2024-03-31", "10000", "0.16")));
        assertEquals(money("160"), thirtyDayEstimate.amount(),
                "0.16 is a decimal 16% key rate, not 0.16 percent or 16x");
    }

    @Test
    void penaltyEstimateRejectsOverlappingAndUnsortedIntervals() {
        PenaltyEstimateService service = new PenaltyEstimateService();
        PenaltyInterval first = interval("2024-01-01", "2024-01-10", "1000", "0.16");
        PenaltyInterval overlap = interval("2024-01-09", "2024-01-12", "1000", "0.16");
        PenaltyInterval earlier = interval("2023-12-20", "2023-12-25", "1000", "0.16");
        assertThrows(IllegalArgumentException.class, () -> service.estimate(List.of(first, overlap)));
        assertThrows(IllegalArgumentException.class, () -> service.estimate(List.of(first, earlier)));
        assertThrows(IllegalArgumentException.class,
                () -> interval("2024-01-01", "2024-01-01", "1", "0.16"));
        assertThrows(IllegalArgumentException.class,
                () -> interval("2024-01-01", "2024-01-02", "-1", "0.16"));
        assertThrows(IllegalArgumentException.class,
                () -> interval("2024-01-01", "2024-01-02", "1", "-0.01"));
        assertThrows(IllegalArgumentException.class,
                () -> new PenaltyInterval(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2),
                        new Money(BigDecimal.ONE, Currency.getInstance("USD")), new BigDecimal("0.16")));
    }

    @Test
    void penaltyEstimateDoesNotFillGapsAndRoundsOnlyOnceAfterSummingIntervals() {
        PenaltyEstimateService service = new PenaltyEstimateService();
        PenaltyEstimate withGap = service.estimate(List.of(
                interval("2024-01-01", "2024-01-02", "10000", "0.30"),
                interval("2024-01-03", "2024-01-04", "10000", "0.30")));
        assertEquals(money("20"), withGap.amount(), "the missing Jan 2–3 day is not counted");
        assertEquals(2, withGap.coveredIntervals().size());

        List<PenaltyInterval> oneDaySlices = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int day = 0; day < 15; day++) {
            oneDaySlices.add(new PenaltyInterval(start.plusDays(day), start.plusDays(day + 1),
                    money("1000"), new BigDecimal("0.16")));
        }
        assertEquals(money("8"), service.estimate(oneDaySlices).amount(),
                "the 15 unrounded interval amounts are summed before the single final rounding");
    }

    @Test
    void ordinaryEarlyClosureFactoryBuildsAssessmentFromKnownOrUnknownComponents() {
        InvestmentAccount account = InvestmentAccount.iis3(portfolio("ordinary-closure"),
                new Iis3Terms(LocalDate.of(2024, 2, 29), Period.ofYears(3), Optional.empty()));
        LocalDate earlyDate = LocalDate.of(2026, 2, 28);
        PenaltyEstimateService service = new PenaltyEstimateService();

        PenaltyEstimate emptyIntervalsAreKnownZero = service.estimate(List.of());
        assertEquals(money("0"), emptyIntervalsAreKnownZero.amount());
        EarlyClosureAssessment knownZeros = EarlyClosureAssessment.estimateOrdinaryEarlyClosure(account, earlyDate,
                Optional.of(List.of()), Optional.of(emptyIntervalsAreKnownZero), Optional.of(money("0")));
        assertEquals(Optional.of(money("0")), knownZeros.returnedDeductions());
        assertEquals(Optional.of(money("0")), knownZeros.totalKnownComponents());
        EarlyClosureAssessment unknownRefunds = EarlyClosureAssessment.estimateOrdinaryEarlyClosure(account, earlyDate,
                Optional.empty(), Optional.of(emptyIntervalsAreKnownZero), Optional.of(money("0")));
        assertTrue(unknownRefunds.returnedDeductions().isEmpty());
        assertTrue(unknownRefunds.totalKnownComponents().isEmpty(), "missing refunds remain unknown");

        PenaltyEstimate actualPenalty = service.estimate(List.of(
                interval("2024-01-01", "2024-04-14", "10000", "0.15")));
        assertEquals(money("520"), actualPenalty.amount());
        EarlyClosureAssessment assessment = EarlyClosureAssessment.estimateOrdinaryEarlyClosure(account, earlyDate,
                Optional.of(List.of(money("30000"), money("22000"))),
                Optional.of(actualPenalty), Optional.of(money("1300")));
        assertEquals(Optional.of(money("52000")), assessment.returnedDeductions());
        assertEquals(Optional.of(money("520")), assessment.penalties());
        assertEquals(Optional.of(money("53820")), assessment.totalKnownComponents(),
                "sum only actual refunds 52,000 + one penalty estimate 520 + supplied tax 1,300");
        assertFalse(assessment.minimumTermReached());
        assertThrows(IllegalArgumentException.class, () -> EarlyClosureAssessment.estimateOrdinaryEarlyClosure(
                account, LocalDate.of(2027, 2, 28), Optional.of(List.of()),
                Optional.of(actualPenalty), Optional.of(money("1300"))),
                "ordinary premature estimate rejects the minimum-term date");
        assertThrows(IllegalArgumentException.class, () -> EarlyClosureAssessment.estimateOrdinaryEarlyClosure(
                account, LocalDate.of(2028, 2, 28), Optional.of(List.of()),
                Optional.of(actualPenalty), Optional.of(money("1300"))),
                "ordinary premature estimate also rejects a date after the minimum term");
    }

    private static PenaltyInterval interval(String from, String to, String debt, String annualRate) {
        return new PenaltyInterval(LocalDate.parse(from), LocalDate.parse(to), money(debt), new BigDecimal(annualRate));
    }

    private static Fixture fixture(InvestmentAccount account) {
        return fixture(account, money("0"));
    }

    private static Fixture fixture(InvestmentAccount account, Money accruedPerUnit) {
        InvestmentAccountRepository accounts = new TestAccountRepository();
        accounts.register(account);
        TestLedgerRepository repository = new TestLedgerRepository();
        LedgerApplicationService ledgerService = new LedgerApplicationService(repository, POLICY);
        PortfolioId portfolio = account.portfolio();
        ledgerService.apply(portfolio, false,
                OPERATIONS.opening(operation("opening-" + portfolio.value()), OP_DATE.minusDays(2),
                        portfolio, RUB, money("1000")));
        Position empty = Position.empty(new PositionId(portfolio, new SecurityId("TEST")), RUB);
        OperationResult purchase = OPERATIONS.buy(operation("purchase-" + portfolio.value()),
                OP_DATE.minusDays(1), empty, 1, money("10"), accruedPerUnit, money("100"), money("0"));
        ledgerService.apply(portfolio, false, purchase);
        Position current = repository.load(portfolio).orElseThrow().state().positions()
                .get(empty.id().security());
        return new Fixture(account, current, accounts, repository, new CouponReceiptService(accounts, repository, POLICY));
    }

    private static OperationRecord couponRecord(LedgerRepository repository, PortfolioId portfolio,
                                                OperationId operation) {
        return repository.operations(portfolio).stream().filter(record -> record.operation().equals(operation))
                .findFirst().orElseThrow();
    }

    private static Money totalPositive(OperationRecord record, AccountCode code) {
        return record.postings().stream().flatMap(posting -> posting.entries().stream())
                .filter(entry -> entry.account().code().equals(code) && entry.amount().isPositive())
                .map(entry -> entry.amount()).reduce(Money.zero(RUB), Money::add);
    }

    private static Money totalBalance(OperationRecord record, AccountCode code) {
        return record.postings().stream().flatMap(posting -> posting.entries().stream())
                .filter(entry -> entry.account().code().equals(code))
                .map(entry -> entry.amount()).reduce(Money.zero(RUB), Money::add);
    }

    private static Money totalNegative(OperationRecord record, AccountCode code) {
        return record.postings().stream().flatMap(posting -> posting.entries().stream())
                .filter(entry -> entry.account().code().equals(code) && entry.amount().isNegative())
                .map(entry -> entry.amount()).reduce(Money.zero(RUB), Money::add);
    }

    private static void assertPostingIdentities(OperationRecord record) {
        assertTrue(record.postings().stream().allMatch(posting -> posting.operation().equals(record.operation())
                        && posting.date().equals(record.date())),
                "base and withholding postings share the operation ID and date");
    }

    private static Map<Account, Money> incomeBalances(Ledger ledger) {
        return ledger.balances().entrySet().stream()
                .filter(entry -> entry.getKey().code().equals(POLICY.couponIncome()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Position boughtPosition(LedgerApplicationService service, TestLedgerRepository repository,
                                           PortfolioId portfolio, String security, Currency currency, Money face,
                                           OperationId operation) {
        Position empty = Position.empty(new PositionId(portfolio, new SecurityId(security)), currency);
        Money price = new Money(new BigDecimal("10"), currency);
        service.apply(portfolio, false, OPERATIONS.buy(operation, OP_DATE.minusDays(1), empty,
                1, price, Money.zero(currency), face, Money.zero(currency)));
        return repository.load(portfolio).orElseThrow().state().positions().get(empty.id().security());
    }

    private static PortfolioId portfolio(String value) { return new PortfolioId(value); }
    private static OperationId operation(String value) { return new OperationId(value); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), RUB); }

    private static BondAccountingPolicy policyWithCashCode(String cashCode) {
        return new BondAccountingPolicy(new AccountCode(cashCode), POLICY.equity(), POLICY.securities(),
                POLICY.accruedInterest(), POLICY.couponIncome(), POLICY.amortizationGain(),
                POLICY.realizedPnl(), POLICY.taxWithheld(), POLICY.commissionExpense());
    }

    private record Fixture(InvestmentAccount account, Position position, InvestmentAccountRepository accounts,
                           TestLedgerRepository repository, CouponReceiptService coupons) {
        LedgerRepository ledger() { return repository; }
    }

    private static final class TestLedgerRepository implements LedgerRepository {
        private Optional<LedgerSnapshot> snapshot = Optional.empty();
        private List<OperationRecord> journal = List.of();
        private boolean failNextCommit;
        private int loadCalls;

        @Override public Optional<LedgerSnapshot> load(PortfolioId portfolio) {
            loadCalls++;
            return snapshot.filter(current -> current.portfolio().equals(portfolio));
        }
        @Override public List<OperationRecord> operations(PortfolioId portfolio) {
            return load(portfolio).isPresent() ? List.copyOf(journal) : List.of();
        }
        @Override public LedgerSnapshot commit(LedgerCommit commit) {
            if (failNextCommit) {
                failNextCommit = false;
                throw new IllegalStateException("deliberate commit failure");
            }
            long version = snapshot.map(LedgerSnapshot::version).orElse(0L);
            if (version != commit.expectedVersion()) {
                throw new IllegalStateException("stale test ledger version");
            }
            LedgerState before = snapshot.map(LedgerSnapshot::state)
                    .orElseGet(() -> Ledger.open(commit.portfolio(), commit.before().chart(), commit.before().allowNegativeCash()).state());
            if (!before.equals(commit.before())) {
                throw new IllegalStateException("stale test ledger state");
            }
            if (before.operationIds().contains(commit.operation().operation())) {
                throw new DuplicateOperationException(commit.portfolio(), commit.operation().operation());
            }
            long sequence = Math.addExact(snapshot.map(LedgerSnapshot::lastSequence).orElse(0L), 1L);
            LedgerSnapshot next = new LedgerSnapshot(commit.portfolio(), Math.addExact(version, 1L), sequence,
                    commit.after());
            List<OperationRecord> nextJournal = new ArrayList<>(journal);
            nextJournal.add(OperationRecord.from(commit.operation(), sequence));
            journal = List.copyOf(nextJournal);
            snapshot = Optional.of(next);
            return next;
        }
    }

    private static final class TestAccountRepository implements InvestmentAccountRepository {
        private final Map<PortfolioId, InvestmentAccount> accounts = new HashMap<>();
        @Override public Optional<InvestmentAccount> account(PortfolioId portfolio) {
            return Optional.ofNullable(accounts.get(portfolio));
        }
        @Override public InvestmentAccount register(InvestmentAccount account) {
            InvestmentAccount existing = accounts.putIfAbsent(account.portfolio(), account);
            if (existing != null && !existing.equals(account)) {
                throw new InvestmentAccountConflictException(account.portfolio());
            }
            return existing == null ? account : existing;
        }
    }
}
