package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.bond.calculation.AccruedInterest;
import ru.onlychill.mbondsfx.core.bond.calculation.BondCalculations;
import ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.bond.calculation.UnsupportedCalculationException;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.DayCount;
import ru.onlychill.mbondsfx.core.money.DayCounts;
import ru.onlychill.mbondsfx.core.money.Money;
import ru.onlychill.mbondsfx.core.bond.AmortizationEvent;
import ru.onlychill.mbondsfx.core.bond.BondDefinition;
import ru.onlychill.mbondsfx.core.bond.ConvertibleData;
import ru.onlychill.mbondsfx.core.bond.Coupon;
import ru.onlychill.mbondsfx.core.bond.DatedPeriod;
import ru.onlychill.mbondsfx.core.bond.FixedCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingRateFormula;
import ru.onlychill.mbondsfx.core.bond.IndexationRule;
import ru.onlychill.mbondsfx.core.bond.IndexLinkedCoupon;
import ru.onlychill.mbondsfx.core.bond.KnownCoupon;
import ru.onlychill.mbondsfx.core.bond.OptionExercise;
import ru.onlychill.mbondsfx.core.bond.Principal;
import ru.onlychill.mbondsfx.core.bond.RedemptionOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final LocalDate ISSUE = LocalDate.of(2026, 1, 1);
    private static final LocalDate MATURITY = LocalDate.of(2027, 1, 1);
    private static final IndexId RATE = new IndexId("TEST_RATE");

    @Test
    void immutableSecurityModel() {
        DatedPeriod first = period(ISSUE, LocalDate.of(2026, 4, 1));
        DatedPeriod second = period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 1));
        List<DatedPeriod> supplied = new ArrayList<>(List.of(second, first));
        FixedCoupon fixed = new FixedCoupon(new BigDecimal("0.10"), supplied);
        supplied.clear();
        assertEquals(2, fixed.periods().size(), "coupon periods sorted and defensively copied");
        assertEquals(first, fixed.periods().get(0), "coupon periods sorted and defensively copied");
        assertThrows(UnsupportedOperationException.class, () -> fixed.periods().clear(),
                "coupon periods immutable");

        FloatingRateFormula floatingFormula = floatingFormula(0, new BigDecimal("0.01"),
                DayCounts.act360(), Optional.empty(), Optional.empty());
        FloatingCoupon floating = new FloatingCoupon(floatingFormula, List.of(first));
        IndexationRule linkage = rule(new IndexId("CPI"), new BigDecimal("100"), ISSUE, 2);
        IndexLinkedCoupon linked = new IndexLinkedCoupon(new BigDecimal("0.07"), linkage,
                IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE, List.of(first));
        KnownCoupon known = new KnownCoupon(List.of(new KnownCoupon.Amount(first, money("1.005", RUB))));
        assertEquals(List.of(first), floating.periods(), "floating coupon construction");
        assertSame(IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE, linked.referenceDate(),
                "index-linked coupon reference policy retained");
        assertEquals(List.of(first), known.periods(), "amount convenience constructor supplies periods");

        List<AmortizationEvent> suppliedEvents = new ArrayList<>();
        suppliedEvents.add(event(LocalDate.of(2026, 10, 1), "100"));
        Principal copiedPrincipal = principal("1000", suppliedEvents, Optional.empty());
        suppliedEvents.clear();
        assertEquals(1, copiedPrincipal.events().size(), "principal amortization list defensively copied");
        assertThrows(UnsupportedOperationException.class, () -> copiedPrincipal.events().clear(),
                "principal amortization list immutable");

        Principal bullet = principal("1000", List.of(), Optional.empty());
        BondDefinition zeroCoupon = bond(ISSUE, Optional.of(MATURITY), Optional.empty(), bullet);
        BondDefinition perpetual = bond(ISSUE, Optional.empty(), Optional.of(fixed), bullet);
        assertTrue(zeroCoupon.coupon().isEmpty(), "empty coupon denotes zero coupon bond");
        assertTrue(perpetual.maturityDate().isEmpty(), "empty maturity denotes perpetual bond");
        assertEquals("TEST-BOND", perpetual.id().value(), "BondDefinition implements Security identity");
        assertEquals(RUB, perpetual.currency(), "BondDefinition currency");
        assertTrue(bullet.events().isEmpty() && bullet.indexation().isEmpty(), "bullet principal composition");

        LocalDate callDate = LocalDate.of(2026, 10, 1);
        RedemptionOptions options = new RedemptionOptions(
                List.of(new OptionExercise(callDate, Optional.of(money("1010", RUB)))),
                List.of(new OptionExercise(callDate.plusMonths(1), Optional.empty())),
                Optional.of(new ConvertibleData(new SecurityId("UNDERLYING"), new BigDecimal("2.5"))));
        BondDefinition optionBond = new BondDefinition(new SecurityId("OPTION-BOND"), RUB, ISSUE,
                Optional.of(MATURITY), Optional.of(fixed), bullet, options);
        assertEquals(money("1010", RUB), optionBond.options().callDates().get(0).price().orElseThrow(),
                "call price retained as per-unit data");
        assertTrue(optionBond.options().putDates().get(0).price().isEmpty(), "empty option price means current face");
        assertEquals(0, optionBond.options().convertible().orElseThrow().conversionRatio()
                .compareTo(new BigDecimal("2.5")), "convertible terms are retained as data");
        assertThrows(UnsupportedOperationException.class, () -> options.callDates().clear(),
                "redemption option dates immutable");
    }

    @Test
    void faceValuesAndAmortizationDateBoundaries() {
        DatedPeriod p = period(ISSUE, LocalDate.of(2026, 7, 1));
        Principal bullet = principal("1000", List.of(), Optional.empty());
        BondDefinition bulletBond = bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(
                BigDecimal.ZERO, List.of(p))), bullet);
        assertEquals(money("1000", RUB), BondCalculations.facePerUnit(bulletBond, ISSUE, noFixings()),
                "bullet face before amortization");

        LocalDate amortDate = LocalDate.of(2026, 4, 1);
        Principal amortizing = principal("1000", List.of(event(amortDate, "100")), Optional.empty());
        BondDefinition amortizingBond = bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(
                BigDecimal.ZERO, List.of(p))), amortizing);
        assertEquals(money("1000", RUB),
                BondCalculations.facePerUnit(amortizingBond, amortDate.minusDays(1), noFixings()),
                "amortization not applied before event date");
        assertEquals(money("900", RUB), BondCalculations.facePerUnit(amortizingBond, amortDate, noFixings()),
                "amortization applies on event date");
        assertEquals(money("900", RUB),
                BondCalculations.facePerUnit(amortizingBond, amortDate.plusDays(1), noFixings()),
                "amortization remains applied after event date");

        IndexationRule indexation = rule(RATE, new BigDecimal("100"), LocalDate.of(2026, 2, 1), 2);
        Principal indexedAmortizing = principal("1000", List.of(event(amortDate, "100")),
                Optional.of(indexation));
        BondDefinition indexedBond = bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(
                BigDecimal.ZERO, List.of(p))), indexedAmortizing);
        IndexValueSource indexFixings = fixings(
                fixing(RATE, LocalDate.of(2026, 3, 29), "105"),
                fixing(RATE, LocalDate.of(2026, 3, 30), "105"));
        assertEquals(money("1050", RUB), BondCalculations.facePerUnit(indexedBond, amortDate.minusDays(1), indexFixings),
                "ratio indexation before amortization");
        assertEquals(money("945", RUB), BondCalculations.facePerUnit(indexedBond, amortDate, indexFixings),
                "amortized face is independently ratio-indexed");
        MissingIndexValueException missing = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.facePerUnit(indexedBond, amortDate, noFixings()),
                "indexed principal requires exact fixing");
        assertEquals(RATE, missing.index(), "missing principal fixing identifies required index");
        assertEquals(LocalDate.of(2026, 3, 30), missing.date(), "missing principal fixing identifies required date");
        LocalDate requestedObservation = LocalDate.of(2026, 3, 30);
        MissingIndexValueException nullValue = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.facePerUnit(indexedBond, amortDate, (index, date) -> null),
                "null domain index value is treated as missing");
        assertEquals(RATE, nullValue.index(), "null domain index value reports requested index");
        assertEquals(requestedObservation, nullValue.date(), "null domain index value reports requested date");
        MissingIndexValueException misreported = new MissingIndexValueException(
                new IndexId("WRONG"), requestedObservation.plusDays(1));
        MissingIndexValueException wrongMetadata = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.facePerUnit(indexedBond, amortDate,
                        (index, date) -> { throw misreported; }),
                "domain source exception with wrong metadata is normalized");
        assertEquals(RATE, wrongMetadata.index(), "domain error reports requested index");
        assertEquals(requestedObservation, wrongMetadata.date(), "domain error reports requested date");
        assertSame(misreported, wrongMetadata.getCause(), "domain error retains mismatched exception cause");

    }

    @Test
    void faceQueryAndZeroCouponAccruedDateBoundaries() {
        DatedPeriod p = period(ISSUE, LocalDate.of(2026, 7, 1));
        BondDefinition bulletBond = bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(
                BigDecimal.ZERO, List.of(p))), principal("1000", List.of(), Optional.empty()));
        assertTrue(BondCalculations.accrued(zeroCouponBond(), ISSUE, DayCounts.act365F(), noFixings())
                .amount().isZero(), "zero-coupon accrued is zero");
        assertThrows(IllegalArgumentException.class,
                () -> BondCalculations.facePerUnit(bulletBond, ISSUE.minusDays(1), noFixings()),
                "face query before issue rejected");
        assertThrows(IllegalArgumentException.class,
                () -> BondCalculations.facePerUnit(bulletBond, MATURITY.plusDays(1), noFixings()),
                "face query after maturity rejected");
    }

    @Test
    void fixedCouponAmountsUseEventAwareSegments() {
        DatedPeriod halfYear = period(ISSUE, LocalDate.of(2026, 7, 1));
        LocalDate internalAmort = LocalDate.of(2026, 4, 1);
        BondDefinition segmented = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FixedCoupon(new BigDecimal("0.10"), List.of(halfYear))),
                principal("1000", List.of(event(internalAmort, "300")), Optional.empty()));
        assertEquals(money("42.11", RUB),
                BondCalculations.couponAmount(segmented, halfYear, DayCounts.act365F(), noFixings()),
                "fixed coupon sums unrounded nominal segments then rounds once");

        DatedPeriod twoDays = period(ISSUE, ISSUE.plusDays(2));
        BondDefinition centSensitive = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FixedCoupon(new BigDecimal("0.72"), List.of(twoDays))),
                principal("2", List.of(event(ISSUE.plusDays(1), "1")), Optional.empty()));
        assertEquals(money("0.01", RUB),
                BondCalculations.couponAmount(centSensitive, twoDays, DayCounts.act360(), noFixings()),
                "fractional segment cents are rounded only after their sum");

        DatedPeriod oneMonth = period(ISSUE, LocalDate.of(2026, 2, 1));
        LocalDate couponEnd = oneMonth.accrualEnd();
        BondDefinition eventAtEnd = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FixedCoupon(new BigDecimal("0.10"), List.of(oneMonth))),
                principal("1000", List.of(event(couponEnd, "100")), Optional.empty()));
        assertEquals(money("8.49", RUB), BondCalculations.couponAmount(eventAtEnd, oneMonth,
                DayCounts.act365F(), noFixings()), "event at coupon end does not reduce that coupon basis");

        LocalDate periodStartAmort = ISSUE.plusDays(1);
        DatedPeriod afterStart = period(periodStartAmort, LocalDate.of(2026, 2, 1));
        BondDefinition eventAtStart = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FixedCoupon(new BigDecimal("0.10"), List.of(afterStart))),
                principal("1000", List.of(event(periodStartAmort, "100")), Optional.empty()));
        assertEquals(money("7.40", RUB), BondCalculations.couponAmount(eventAtStart, afterStart,
                DayCounts.act365F(), noFixings()), "event at accrualStart applies to the period");

    }

    @Test
    void fixedAccruedUsesHalfOpenPeriodBoundaries() {
        DatedPeriod oneMonth = period(ISSUE, LocalDate.of(2026, 2, 1));
        LocalDate couponEnd = oneMonth.accrualEnd();
        BondDefinition eventAtEnd = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FixedCoupon(new BigDecimal("0.10"), List.of(oneMonth))),
                principal("1000", List.of(event(couponEnd, "100")), Optional.empty()));
        AccruedInterest midPeriod = BondCalculations.accrued(eventAtEnd, ISSUE.plusDays(15),
                DayCounts.act365F(), noFixings());
        assertEquals(money("4.11", RUB), midPeriod.amount(), "fixed accrued uses elapsed half-open interval");
        assertEquals(oneMonth, midPeriod.period().orElseThrow(), "fixed accrued identifies its period");
        AccruedInterest atStart = BondCalculations.accrued(eventAtEnd, ISSUE,
                DayCounts.act365F(), noFixings());
        assertTrue(atStart.amount().isZero(), "accrual at period start is zero");
        assertEquals(oneMonth, atStart.period().orElseThrow(), "accrual at period start identifies period");
        AccruedInterest accruedAtEnd = BondCalculations.accrued(eventAtEnd, atEndDate(oneMonth),
                DayCounts.act365F(), noFixings());
        assertTrue(accruedAtEnd.amount().isZero(), "accrual end is outside half-open period");
        assertTrue(accruedAtEnd.period().isEmpty(), "accrual end has no period");
        AccruedInterest afterEnd = BondCalculations.accrued(eventAtEnd, oneMonth.accrualEnd().plusDays(1),
                DayCounts.act365F(), noFixings());
        assertTrue(afterEnd.amount().isZero(), "outside coupon period accrued is zero");
        assertTrue(afterEnd.period().isEmpty(), "outside coupon period has no period");

        DatedPeriod eventSettlePeriod = period(ISSUE, LocalDate.of(2026, 7, 1));
        BondDefinition eventAtSettle = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FixedCoupon(new BigDecimal("0.10"), List.of(eventSettlePeriod))),
                principal("1000", List.of(event(LocalDate.of(2026, 4, 1), "300")), Optional.empty()));
        assertEquals(money("24.66", RUB), BondCalculations.accrued(eventAtSettle, LocalDate.of(2026, 4, 1),
                DayCounts.act365F(), noFixings()).amount(), "event at settle is outside accrued interval");

        assertThrows(IllegalArgumentException.class,
                () -> BondCalculations.couponAmount(eventAtEnd, period(ISSUE.plusDays(1), oneMonth.accrualEnd()),
                        DayCounts.act365F(), noFixings()), "coupon query requires a declared period");
    }

    @Test
    void dayCountCalculationDateVectors() {
        assertEquals(0, DayCounts.act365F().yearFraction(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .compareTo(new BigDecimal("30").divide(new BigDecimal("365"), java.math.MathContext.DECIMAL128)),
                "domain day-count contract includes ACT/365F date vector");
        assertEquals(366, DayCounts.act360().days(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1)),
                "ACT/360 includes leap-year actual days");
        assertEquals(29, DayCounts.thirtyE360().days(LocalDate.of(2020, 1, 31), LocalDate.of(2020, 2, 29)),
                "30E/360 month-end vector");
        assertEquals(0, DayCounts.actActIsda().yearFraction(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1))
                .compareTo(BigDecimal.ONE), "ACT/ACT.ISDA leap-year vector");
    }

    @Test
    void floatingCouponSpreadFloorAndCap() {
        DatedPeriod p = period(ISSUE, LocalDate.of(2026, 2, 1));
        FloatingRateFormula formula = floatingFormula(0, new BigDecimal("0.02"), DayCounts.act360(),
                Optional.of(new BigDecimal("0.05")), Optional.of(new BigDecimal("0.12")));
        BondDefinition floating = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FloatingCoupon(formula, List.of(p))), principal("1000", List.of(), Optional.empty()));
        assertEquals(money("4.31", RUB), BondCalculations.couponAmount(floating, p, DayCounts.act365F(),
                fixings(fixing(RATE, ISSUE, "0.01"))),
                "floating spread then floor; formula's ACT/360 basis applies");
        assertEquals(money("10.33", RUB), BondCalculations.couponAmount(floating, p, DayCounts.act365F(),
                fixings(fixing(RATE, ISSUE, "0.20"))),
                "floating cap clamps the rate");
        assertEquals(money("5.17", RUB), BondCalculations.couponAmount(floating, p, DayCounts.act365F(),
                fixings(fixing(RATE, ISSUE, "0.04"))),
                "floating fixing plus spread inside floor and cap");

    }

    @Test
    void floatingCouponAndAccruedUseFormulaDayCountBasis() {
        DatedPeriod p = period(ISSUE, LocalDate.of(2026, 2, 1));
        FloatingRateFormula basisFormula = floatingFormula(0, BigDecimal.ZERO, DayCounts.act360(),
                Optional.empty(), Optional.empty());
        BondDefinition basisBond = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FloatingCoupon(basisFormula, List.of(p))), principal("1000", List.of(), Optional.empty()));
        IndexValueSource basisFixings = fixings(
                fixing(RATE, ISSUE, "0.10"),
                fixing(RATE, LocalDate.of(2026, 1, 31), "0.10"));
        Money fullCoupon = BondCalculations.couponAmount(basisBond, p, DayCounts.act365F(), basisFixings);
        assertEquals(money("8.61", RUB), fullCoupon, "full floating coupon uses formula ACT/360 basis");
        AccruedInterest formulaBasisAccrued = BondCalculations.accrued(basisBond,
                LocalDate.of(2026, 1, 31), DayCounts.act365F(), basisFixings);
        assertEquals(money("8.33", RUB), formulaBasisAccrued.amount(),
                "floating accrued uses formula ACT/360, not caller ACT/365F");
        assertFalse(formulaBasisAccrued.amount().equals(money("8.22", RUB)),
                "floating accrued does not retain the mismatched ACT/365F result");
        assertSame(DayCounts.act360(), formulaBasisAccrued.dayCount(),
                "AccruedInterest reports the floating formula's actual day-count basis");
        DatedPeriod remainingPeriod = period(LocalDate.of(2026, 1, 31), p.accrualEnd());
        BondDefinition remainingBond = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FloatingCoupon(basisFormula, List.of(remainingPeriod))),
                principal("1000", List.of(), Optional.empty()));
        // The public accrued API is scheduled-period-start based; this tail interval supplies the
        // complementary formula-basis contribution for the split reconciliation check.
        Money accruedSettleToEnd = BondCalculations.couponAmount(remainingBond, remainingPeriod,
                DayCounts.act365F(), basisFixings);
        Money reconciled = formulaBasisAccrued.amount().add(accruedSettleToEnd);
        assertWithinOneCent(reconciled, fullCoupon,
                "rounded floating accrued splits reconcile to full coupon within one cent");

    }

    @Test
    void floatingNegativeRatesAndFixingOffset() {
        DatedPeriod p = period(ISSUE, LocalDate.of(2026, 2, 1));
        FloatingRateFormula negativeFormula = floatingFormula(0, new BigDecimal("-0.01"), DayCounts.act365F(),
                Optional.empty(), Optional.empty());
        BondDefinition negativeRate = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FloatingCoupon(negativeFormula, List.of(p))), principal("1000", List.of(), Optional.empty()));
        assertEquals(money("-2.55", RUB), BondCalculations.couponAmount(negativeRate, p, DayCounts.act365F(),
                fixings(fixing(RATE, ISSUE, "-0.02"))),
                "negative floating rate is supported when the formula permits it");

        FloatingRateFormula offsetFormula = floatingFormula(1, BigDecimal.ZERO, DayCounts.act365F(),
                Optional.empty(), Optional.empty());
        BondDefinition offsetFloating = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FloatingCoupon(offsetFormula, List.of(p))), principal("1000", List.of(), Optional.empty()));
        assertEquals(money("8.49", RUB), BondCalculations.couponAmount(offsetFloating, p, DayCounts.act365F(),
                fixings(fixing(RATE, ISSUE.plusDays(1), "0.10"))),
                "floating fixing offset is applied to accrual start");

    }

    @Test
    void floatingAccruedRejectsFutureFixingBeforeSourceQuery() {
        DatedPeriod p = period(ISSUE, LocalDate.of(2026, 2, 1));
        FloatingRateFormula futureFormula = floatingFormula(10, BigDecimal.ZERO, DayCounts.act365F(),
                Optional.empty(), Optional.empty());
        BondDefinition futureFloating = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new FloatingCoupon(futureFormula, List.of(p))), principal("1000", List.of(), Optional.empty()));
        LocalDate futureFixingDate = ISSUE.plusDays(10);
        AtomicInteger fixingCalls = new AtomicInteger();
        IndexValueSource futureAvailable = (index, date) -> {
            fixingCalls.incrementAndGet();
            return new BigDecimal("0.10");
        };
        MissingIndexValueException future = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.accrued(futureFloating, ISSUE.plusDays(5), DayCounts.act365F(),
                        futureAvailable),
                "accrued rejects future fixing even if the fixture contains it");
        assertEquals(RATE, future.index(), "future fixing exception identifies blocked index");
        assertEquals(futureFixingDate, future.date(), "future fixing exception identifies blocked observation");
        assertEquals(0, fixingCalls.get(), "future-observation guard runs before querying the source");
    }

    @Test
    void indexLinkedCouponReferencePoliciesAndLookahead() {
        LocalDate end = LocalDate.of(2026, 3, 1);
        LocalDate settle = LocalDate.of(2026, 2, 1);
        DatedPeriod p = period(ISSUE, end);
        IndexationRule couponRule = rule(new IndexId("CPI"), new BigDecimal("100"), ISSUE, 0);
        BondDefinition linkedAtEnd = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new IndexLinkedCoupon(new BigDecimal("0.10"), couponRule,
                        IndexLinkedCoupon.ReferenceDate.PERIOD_END, List.of(p))),
                principal("1000", List.of(), Optional.empty()));
        IndexValueSource observations = fixings(
                fixing(couponRule.index(), ISSUE, "100"),
                fixing(couponRule.index(), settle, "105"),
                fixing(couponRule.index(), end, "110"));
        assertEquals(money("17.78", RUB), BondCalculations.couponAmount(linkedAtEnd, p,
                DayCounts.act365F(), observations), "index-linked coupon uses selected period-end ratio");
        MissingIndexValueException periodEndLookahead = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.accrued(linkedAtEnd, settle, DayCounts.act365F(), observations),
                "accrued PERIOD_END policy rejects a future observation");
        assertEquals(end, periodEndLookahead.date(), "period-end lookahead reports the future date");

        BondDefinition linkedAtStart = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new IndexLinkedCoupon(new BigDecimal("0.10"), couponRule,
                        IndexLinkedCoupon.ReferenceDate.PERIOD_START, List.of(p))),
                principal("1000", List.of(), Optional.empty()));
        assertEquals(money("16.16", RUB), BondCalculations.couponAmount(linkedAtStart, p,
                DayCounts.act365F(), observations), "PERIOD_START reference policy");

        BondDefinition linkedAtCalculationDate = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new IndexLinkedCoupon(new BigDecimal("0.10"), couponRule,
                        IndexLinkedCoupon.ReferenceDate.CALCULATION_DATE, List.of(p))),
                principal("1000", List.of(), Optional.empty()));
        assertEquals(money("8.92", RUB), BondCalculations.accrued(linkedAtCalculationDate, settle,
                DayCounts.act365F(), observations).amount(), "accrued calculation date reference is settle date");

        IndexationRule lagged = rule(couponRule.index(), new BigDecimal("100"), ISSUE, 1);
        BondDefinition futureObservation = bond(ISSUE, Optional.of(MATURITY),
                Optional.of(new IndexLinkedCoupon(new BigDecimal("0.10"), lagged,
                        IndexLinkedCoupon.ReferenceDate.PERIOD_END, List.of(p))),
                principal("1000", List.of(), Optional.empty()));
        MissingIndexValueException lookahead = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.accrued(futureObservation, settle, DayCounts.act365F(),
                        fixings(fixing(couponRule.index(), end.minusDays(1), "110"))),
                "indexed accrued rejects future observation even when provided");
        assertEquals(end.minusDays(1), lookahead.date(), "lookahead reports future observation date");

    }

    @Test
    void indexedPrincipalAndFormulaCouponCombinationIsRejected() {
        LocalDate end = LocalDate.of(2026, 3, 1);
        LocalDate settle = LocalDate.of(2026, 2, 1);
        DatedPeriod p = period(ISSUE, end);
        IndexationRule couponRule = rule(new IndexId("CPI"), new BigDecimal("100"), ISSUE, 0);
        IndexValueSource observations = fixings(
                fixing(couponRule.index(), ISSUE, "100"),
                fixing(couponRule.index(), settle, "105"),
                fixing(couponRule.index(), end, "110"));
        Principal indexedPrincipal = principal("1000", List.of(), Optional.of(couponRule));
        Coupon[] formulaCoupons = {
            new FixedCoupon(new BigDecimal("0.10"), List.of(p)),
            new FloatingCoupon(floatingFormula(0, BigDecimal.ZERO, DayCounts.act365F(),
                    Optional.empty(), Optional.empty()), List.of(p)),
            new IndexLinkedCoupon(new BigDecimal("0.10"), couponRule,
                    IndexLinkedCoupon.ReferenceDate.PERIOD_END, List.of(p))
        };
        for (Coupon coupon : formulaCoupons) {
            BondDefinition doubleLinked = bond(ISSUE, Optional.of(MATURITY), Optional.of(coupon), indexedPrincipal);
            assertThrows(UnsupportedCalculationException.class,
                    () -> BondCalculations.couponAmount(doubleLinked, p, DayCounts.act365F(), observations),
                    "formula coupon rejected with indexed principal");
            assertThrows(UnsupportedCalculationException.class,
                    () -> BondCalculations.accrued(doubleLinked, settle, DayCounts.act365F(), observations),
                    "formula accrued rejected with indexed principal");
        }
        assertEquals(end, couponRule.observationDate(end), "index observation date uses configured lag");
        assertEquals(ISSUE, couponRule.baseDate(), "baseDate remains explicit issue-supplied metadata");
    }

    @Test
    void knownCouponAmountsAndAccruedCalculations() {
        DatedPeriod knownPeriod = period(ISSUE, ISSUE.plusDays(2));
        DatedPeriod unknownPeriod = period(ISSUE.plusDays(2), ISSUE.plusDays(4));
        KnownCoupon known = new KnownCoupon(List.of(knownPeriod, unknownPeriod),
                List.of(new KnownCoupon.Amount(knownPeriod, money("5.01", RUB))));
        BondDefinition bond = bond(ISSUE, Optional.of(MATURITY), Optional.of(known),
                principal("1000", List.of(), Optional.empty()));
        assertEquals(money("5.01", RUB),
                BondCalculations.couponAmount(bond, knownPeriod, DayCounts.act360(), noFixings()),
                "KnownCoupon amount is authoritative and exact");
        AccruedInterest knownAccrued = BondCalculations.accrued(bond, ISSUE.plusDays(1),
                DayCounts.act360(), noFixings());
        assertEquals(money("2.51", RUB), knownAccrued.amount(),
                "KnownCoupon accrued uses explicit linear day-count proration");
        assertSame(DayCounts.act360(), knownAccrued.dayCount(), "KnownCoupon accrued retains caller day-count");
        assertTrue(BondCalculations.accrued(bond, ISSUE, DayCounts.act360(), noFixings())
                .amount().isZero(), "KnownCoupon accrual at period start is zero");
        assertThrows(UnsupportedCalculationException.class,
                () -> BondCalculations.couponAmount(bond, unknownPeriod, DayCounts.act360(), noFixings()),
                "unknown explicit coupon period never becomes zero");
        assertThrows(UnsupportedCalculationException.class,
                () -> BondCalculations.accrued(bond, ISSUE.plusDays(3), DayCounts.act360(), noFixings()),
                "unknown coupon amount also blocks accrued query");

        Principal indexedPrincipal = principal("1000", List.of(), Optional.of(
                rule(RATE, new BigDecimal("100"), ISSUE, 0)));
        BondDefinition indexedKnown = bond(ISSUE, Optional.of(MATURITY), Optional.of(known), indexedPrincipal);
        assertEquals(money("5.01", RUB),
                BondCalculations.couponAmount(indexedKnown, knownPeriod, DayCounts.act360(), noFixings()),
                "KnownCoupon authoritative path permits indexed principal");
        assertEquals(money("2.51", RUB),
                BondCalculations.accrued(indexedKnown, ISSUE.plusDays(1), DayCounts.act360(), noFixings()).amount(),
                "KnownCoupon accrued path permits indexed principal");

    }

    @Test
    void knownCouponAmountValidation() {
        DatedPeriod knownPeriod = period(ISSUE, ISSUE.plusDays(2));
        DatedPeriod unknownPeriod = period(ISSUE.plusDays(2), ISSUE.plusDays(4));
        assertThrows(IllegalArgumentException.class,
                () -> new KnownCoupon(List.of(knownPeriod), List.of(
                        new KnownCoupon.Amount(knownPeriod, money("1", RUB)),
                        new KnownCoupon.Amount(knownPeriod, money("2", RUB)))),
                "duplicate KnownCoupon amounts rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new KnownCoupon(List.of(knownPeriod), List.of(
                        new KnownCoupon.Amount(unknownPeriod, money("1", RUB)))),
                "amount outside declared KnownCoupon periods rejected");
    }

    @Test
    void couponAndPrincipalTermValidation() {
        DatedPeriod valid = period(ISSUE, LocalDate.of(2026, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DatedPeriod(ISSUE, ISSUE, ISSUE), "empty accrual interval rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DatedPeriod(ISSUE, ISSUE.plusDays(1), ISSUE), "payment before accrual end rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCoupon(new BigDecimal("-0.01"), List.of(valid)),
                "negative fixed coupon rate rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new IndexLinkedCoupon(new BigDecimal("-0.01"), rule(RATE, BigDecimal.ONE, ISSUE, 0),
                        IndexLinkedCoupon.ReferenceDate.PERIOD_END, List.of(valid)),
                "negative index-linked coupon rate rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FloatingRateFormula(RATE, 0, BigDecimal.ZERO, DayCounts.act365F(),
                        Optional.of(new BigDecimal("0.10")), Optional.of(new BigDecimal("0.05"))),
                "floating floor above cap rejected");
        assertThrows(IllegalArgumentException.class,
                () -> rule(RATE, BigDecimal.ZERO, ISSUE, 0), "non-positive base index rejected");
        assertThrows(IllegalArgumentException.class,
                () -> rule(RATE, BigDecimal.ONE, ISSUE, -1), "negative observation lag rejected");

        DatedPeriod overlap = period(ISSUE.plusDays(10), LocalDate.of(2026, 2, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCoupon(BigDecimal.ONE, List.of(valid, overlap)), "overlapping coupon periods rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new Principal(money("0", RUB), List.of(), Optional.empty()), "zero initial face rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new AmortizationEvent(ISSUE, money("0", RUB)), "zero amortization reduction rejected");
        assertThrows(IllegalArgumentException.class,
                () -> principal("100", List.of(event(ISSUE.plusDays(1), "101")), Optional.empty()),
                "amortization exceeding face rejected");
        assertThrows(IllegalArgumentException.class,
                () -> principal("100", List.of(new AmortizationEvent(ISSUE.plusDays(1), money("1", USD))),
                        Optional.empty()), "amortization currency mismatch rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new OptionExercise(ISSUE, Optional.of(money("-1", RUB))), "negative option price rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ConvertibleData(new SecurityId("UNDER"), BigDecimal.ZERO),
                "non-positive conversion ratio rejected");

    }

    @Test
    void bondDefinitionValidation() {
        DatedPeriod valid = period(ISSUE, LocalDate.of(2026, 2, 1));
        Principal principal = principal("100", List.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(ISSUE), Optional.empty(), principal),
                "maturity must follow issue date");
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(BigDecimal.ONE,
                        List.of(period(ISSUE.minusDays(1), ISSUE.plusDays(1))))), principal),
                "coupon accrual before issue date rejected");
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(ISSUE.plusDays(10)), Optional.of(new FixedCoupon(BigDecimal.ONE,
                        List.of(period(ISSUE, ISSUE.plusDays(11))))), principal),
                "coupon accrual after maturity rejected");
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(ISSUE.plusDays(10)), Optional.of(new FixedCoupon(BigDecimal.ONE,
                        List.of(new DatedPeriod(ISSUE, ISSUE.plusDays(10), ISSUE.plusDays(11))))), principal),
                "coupon payment after maturity rejected");
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(BigDecimal.ONE,
                        List.of(valid))), principal("100", List.of(event(ISSUE, "1")), Optional.empty())),
                "amortization on issue date rejected");
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(BigDecimal.ONE,
                        List.of(period(ISSUE, MATURITY)))), principal("100", List.of(
                                event(ISSUE.plusDays(1), "1"), event(MATURITY.plusDays(1), "1")), Optional.empty())),
                "amortization after maturity rejected");
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(MATURITY), Optional.of(new FixedCoupon(BigDecimal.ONE,
                        List.of(valid))), principal("100", List.of(), Optional.empty()), USD),
                "principal currency must match bond currency");
        assertThrows(IllegalArgumentException.class,
                () -> bond(ISSUE, Optional.of(MATURITY), Optional.of(new KnownCoupon(List.of(
                        new KnownCoupon.Amount(valid, money("1", USD))))), principal),
                "known coupon currency must match bond currency");
        RedemptionOptions mismatchedOption = new RedemptionOptions(
                List.of(new OptionExercise(ISSUE, Optional.of(money("1", USD)))), List.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new BondDefinition(new SecurityId("BOND"), RUB, ISSUE, Optional.of(MATURITY),
                        Optional.empty(), principal, mismatchedOption), "option currency must match bond currency");
        RedemptionOptions optionBeforeIssue = new RedemptionOptions(
                List.of(new OptionExercise(ISSUE.minusDays(1), Optional.empty())), List.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new BondDefinition(new SecurityId("BOND"), RUB, ISSUE, Optional.of(MATURITY),
                        Optional.empty(), principal, optionBeforeIssue), "option date before issue rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new BondDefinition(new SecurityId("BOND"), Currency.getInstance("XDR"), ISSUE,
                        Optional.of(MATURITY), Optional.empty(), principal, RedemptionOptions.none()),
                "bond currency without a minor-unit scale rejected");
        Currency jpy = Currency.getInstance("JPY");
        BondDefinition jpyBond = new BondDefinition(new SecurityId("JPY-BOND"), jpy, ISSUE,
                Optional.of(MATURITY), Optional.empty(),
                new Principal(new Money(new BigDecimal("1000"), jpy), List.of(), Optional.empty()),
                RedemptionOptions.none());
        assertEquals(jpy, jpyBond.currency(), "BondDefinition accepts zero-decimal JPY currency");
    }

    private static BondDefinition zeroCouponBond() {
        return bond(ISSUE, Optional.of(MATURITY), Optional.empty(), principal("1000", List.of(), Optional.empty()));
    }

    private static BondDefinition bond(LocalDate issueDate, Optional<LocalDate> maturity,
                                       Optional<Coupon> coupon, Principal principal) {
        return bond(issueDate, maturity, coupon, principal, RUB);
    }

    private static BondDefinition bond(LocalDate issueDate, Optional<LocalDate> maturity,
                                       Optional<Coupon> coupon, Principal principal, Currency currency) {
        return new BondDefinition(new SecurityId("TEST-BOND"), currency, issueDate, maturity, coupon,
                principal, RedemptionOptions.none());
    }

    private static Principal principal(String face, List<AmortizationEvent> events,
                                       Optional<IndexationRule> indexation) {
        return new Principal(money(face, RUB), events, indexation);
    }

    private static AmortizationEvent event(LocalDate date, String reduction) {
        return new AmortizationEvent(date, money(reduction, RUB));
    }

    private static DatedPeriod period(LocalDate start, LocalDate end) {
        return new DatedPeriod(start, end, end);
    }

    private static Money money(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    private static IndexationRule rule(IndexId index, BigDecimal base, LocalDate baseDate, long lag) {
        return new IndexationRule(index, base, baseDate, lag);
    }

    private static FloatingRateFormula floatingFormula(long offset, BigDecimal spread, DayCount dayCount,
                                                       Optional<BigDecimal> floor, Optional<BigDecimal> cap) {
        return new FloatingRateFormula(RATE, offset, spread, dayCount, floor, cap);
    }

    private static Map.Entry<String, BigDecimal> fixing(IndexId index, LocalDate date, String value) {
        return Map.entry(key(index, date), new BigDecimal(value));
    }

    @SafeVarargs
    private static IndexValueSource fixings(Map.Entry<String, BigDecimal>... values) {
        Map<String, BigDecimal> exact = new HashMap<>();
        for (Map.Entry<String, BigDecimal> fixing : values) {
            exact.put(fixing.getKey(), fixing.getValue());
        }
        return (index, date) -> {
            BigDecimal value = exact.get(key(index, date));
            if (value == null) {
                throw new MissingIndexValueException(index, date);
            }
            return value;
        };
    }

    private static IndexValueSource noFixings() {
        return (index, date) -> {
            throw new MissingIndexValueException(index, date);
        };
    }

    private static String key(IndexId index, LocalDate date) {
        return index.value() + "@" + date;
    }

    private static LocalDate atEndDate(DatedPeriod period) {
        return period.accrualEnd();
    }

    private static void assertWithinOneCent(Money actual, Money expected, String message) {
        BigDecimal difference = actual.amount().subtract(expected.amount()).abs();
        assertTrue(difference.compareTo(new BigDecimal("0.01")) <= 0, message);
    }
}
