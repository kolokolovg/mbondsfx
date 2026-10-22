package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.money.BusinessDayConvention;
import ru.onlychill.mbondsfx.core.money.BusinessCalendar;
import ru.onlychill.mbondsfx.core.money.DayCount;
import ru.onlychill.mbondsfx.core.money.DayCounts;
import ru.onlychill.mbondsfx.core.money.HolidayCalendar;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FoundationContractTests {
    @Test
    void moneyConstructionAndArithmetic() {
        Currency usd = Currency.getInstance("USD");
        Currency rub = Currency.getInstance("RUB");
        Currency jpy = Currency.getInstance("JPY");
        Money canonical = new Money(new BigDecimal("12.3400"), usd);
        assertEquals(new Money(new BigDecimal("12.34"), usd), canonical, "Money equality canonicalizes scale");
        assertEquals(2, canonical.amount().scale(), "Money amount stores canonical scale");
        assertEquals(new BigDecimal("1.23456789123"), new Money(new BigDecimal("1.23456789123"), usd).amount(),
                "construction preserves quote precision");
        assertEquals(rub, new Money(new BigDecimal("1.26"), rub).currency(), "RUB currency accepted");
        assertEquals(new BigDecimal("1.4"), new Money(new BigDecimal("1.4"), jpy).amount(),
                "zero-decimal JPY currency accepted without constructor rounding");
        assertTrue(Money.zero(usd).isZero(), "currency zero factory");
        assertEquals(new Money(new BigDecimal("5"), usd), Money.of(usd, 5), "major-unit factory");
        assertEquals(new Money(new BigDecimal("3.50"), usd),
                new Money(new BigDecimal("1.20"), usd).add(new Money(new BigDecimal("2.30"), usd)), "addition");
        assertEquals(new Money(new BigDecimal("2"), usd),
                new Money(new BigDecimal("3.00"), usd).subtract(new Money(new BigDecimal("1"), usd)), "subtraction");
        assertEquals(new Money(new BigDecimal("2.01"), usd),
                new Money(new BigDecimal("1.005"), usd).multiply(2), "integer multiplication is exact");
        assertEquals(new Money(new BigDecimal("0.01"), usd),
                new Money(new BigDecimal("1.00"), usd).multiply(new BigDecimal("0.005")),
                "HALF_UP multiplication rounds once");
        assertEquals(new Money(new BigDecimal("-0.01"), usd),
                new Money(new BigDecimal("-1.00"), usd).multiply(new BigDecimal("0.005")),
                "negative HALF_UP multiplication");
        assertEquals(new Money(new BigDecimal("0.13"), usd), new Money(new BigDecimal("1"), usd).divide(8),
                "HALF_UP division");
        assertEquals(new Money(new BigDecimal("0.33"), rub),
                new Money(new BigDecimal("1"), rub).divide(new BigDecimal("3")),
                "division uses currency minor-unit scale");
        assertTrue(new Money(new BigDecimal("1.50"), usd).negate().isNegative(), "negation and sign");
        assertTrue(new Money(new BigDecimal("1"), usd).compareTo(new Money(new BigDecimal("2"), usd)) < 0,
                "same-currency comparison");
    }

    @Test
    void moneyValidation() {
        Currency usd = Currency.getInstance("USD");
        Currency rub = Currency.getInstance("RUB");
        assertThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, usd).add(new Money(BigDecimal.ONE, rub)),
                "addition rejects currency mismatch");
        assertThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, usd).compareTo(new Money(BigDecimal.ONE, rub)),
                "comparison rejects currency mismatch");
        assertThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, Currency.getInstance("XXX")), "XXX pseudo currency rejected");
        for (String pseudoCode : List.of("XDR", "XTS", "XAU")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Money(new BigDecimal("1.26"), Currency.getInstance(pseudoCode)),
                    pseudoCode + " currency without minor-unit scale rejected");
        }
        assertThrows(NullPointerException.class, () -> new Money(null, usd), "null amount rejected");
        assertThrows(NullPointerException.class, () -> new Money(BigDecimal.ONE, null), "null currency rejected");
        assertThrows(ArithmeticException.class,
                () -> new Money(BigDecimal.ONE, usd).divide(0), "division by zero rejected");
    }

    @Test
    void actualDayCountFractions() {
        LocalDate leapStart = LocalDate.of(2020, 1, 1);
        LocalDate nextYear = LocalDate.of(2021, 1, 1);
        assertEquals(366, DayCounts.act365F().days(leapStart, nextYear), "ACT/365F leap-year days");
        assertEquals(0, DayCounts.act365F().yearFraction(leapStart, nextYear)
                .compareTo(new BigDecimal("366").divide(new BigDecimal("365"), MathContext.DECIMAL128)),
                "ACT/365F fraction");
        assertEquals(0, DayCounts.act360().yearFraction(leapStart, nextYear)
                .compareTo(new BigDecimal("366").divide(new BigDecimal("360"), MathContext.DECIMAL128)),
                "ACT/360 leap-year fraction");

        DayCount actAct = DayCounts.actActIsda();
        assertEquals(0, actAct.yearFraction(leapStart, nextYear).compareTo(BigDecimal.ONE),
                "ACT/ACT.ISDA full leap year fraction");
        LocalDate splitStart = LocalDate.of(2019, 7, 1);
        LocalDate splitEnd = LocalDate.of(2020, 7, 1);
        BigDecimal isdaExpected = new BigDecimal("184").divide(new BigDecimal("365"), MathContext.DECIMAL128)
                .add(new BigDecimal("182").divide(new BigDecimal("366"), MathContext.DECIMAL128),
                        MathContext.DECIMAL128);
        assertEquals(0, actAct.yearFraction(splitStart, splitEnd).compareTo(isdaExpected),
                "ACT/ACT.ISDA splits across leap-year boundary");
        assertEquals(0, actAct.yearFraction(splitEnd, splitStart)
                .compareTo(actAct.yearFraction(splitStart, splitEnd).negate()),
                "ACT/ACT.ISDA reverse interval is signed");
    }

    @Test
    void thirtyE360AndDayCountLookup() {
        DayCount thirtyE = DayCounts.thirtyE360();
        assertEquals(29, thirtyE.days(LocalDate.of(2020, 1, 31), LocalDate.of(2020, 2, 29)),
                "30E/360 caps day-of-month at 30");
        assertEquals(0, thirtyE.yearFraction(LocalDate.of(2020, 2, 28), LocalDate.of(2020, 3, 31))
                .compareTo(new BigDecimal("32").divide(new BigDecimal("360"), MathContext.DECIMAL128)),
                "30E/360 fraction");
        assertSame(DayCounts.act365F(), DayCounts.of("ACT/365F"), "day-count code lookup");
        assertSame(DayCounts.act360(), DayCounts.of("ACT/360"), "ACT/360 code lookup");
        assertSame(DayCounts.actActIsda(), DayCounts.of("ACT/ACT.ISDA"), "ACT/ACT.ISDA code lookup");
        assertSame(DayCounts.thirtyE360(), DayCounts.of("30E/360"), "30E/360 code lookup");
        assertThrows(IllegalArgumentException.class, () -> DayCounts.of("ACT/ACT"), "unknown day-count rejected");
    }

    @Test
    void moneySubtractionRejectsCurrencyMismatchWithItsMessage() {
        Currency usd = Currency.getInstance("USD");
        Currency rub = Currency.getInstance("RUB");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, usd).subtract(new Money(BigDecimal.ONE, rub)));
        assertEquals("Currency mismatch: USD and RUB", thrown.getMessage(), "subtraction rejects currency mismatch");
    }

    @Test
    void dayCountDaysCoverMultiMonthSpansReverseIntervalsAndActActIsda() {
        assertEquals(60, DayCounts.thirtyE360().days(LocalDate.of(2020, 1, 15), LocalDate.of(2020, 3, 15)),
                "30E/360 counts two whole months as 60 days");
        assertEquals(-60, DayCounts.thirtyE360().days(LocalDate.of(2020, 3, 15), LocalDate.of(2020, 1, 15)),
                "30E/360 reverse interval is signed");
        assertEquals(100, DayCounts.thirtyE360().days(LocalDate.of(2020, 1, 10), LocalDate.of(2020, 4, 20)),
                "30E/360 spans three months with day-of-month remainder");
        assertEquals(366, DayCounts.actActIsda().days(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1)),
                "ACT/ACT.ISDA days are actual calendar days");
        assertEquals(-366, DayCounts.actActIsda().days(LocalDate.of(2021, 1, 1), LocalDate.of(2020, 1, 1)),
                "ACT/ACT.ISDA days are signed");
    }

    /** Rows of docs/specs/day-count/v1.0.0.md, section 3: the day count of each row for 30/360, 30E/360, 30E+/360. */
    @Test
    void thirty360FamilyReproducesTheSpecificationTable() {
        assertSpecificationRow(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 1, 31), 0, 0, 1);
        assertSpecificationRow(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), 60, 60, 61);
        assertSpecificationRow(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 31), 76, 75, 76);
        assertSpecificationRow(LocalDate.of(2026, 1, 29), LocalDate.of(2026, 3, 31), 62, 61, 62);
        assertSpecificationRow(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31), 30, 30, 31);
    }

    private static void assertSpecificationRow(LocalDate start, LocalDate end, long thirty360, long thirtyE360,
                                               long thirtyEPlus360) {
        assertEquals(thirty360, DayCounts.thirty360().days(start, end), "30/360 days " + start + " -> " + end);
        assertEquals(thirtyE360, DayCounts.thirtyE360().days(start, end), "30E/360 days " + start + " -> " + end);
        assertEquals(thirtyEPlus360, DayCounts.thirtyEPlus360().days(start, end),
                "30E+/360 days " + start + " -> " + end);
        assertEquals(0, DayCounts.thirty360().yearFraction(start, end)
                        .compareTo(new BigDecimal(thirty360).divide(new BigDecimal("360"), MathContext.DECIMAL128)),
                "30/360 year fraction is days / 360 " + start + " -> " + end);
        assertEquals(0, DayCounts.thirtyEPlus360().yearFraction(start, end)
                        .compareTo(new BigDecimal(thirtyEPlus360).divide(new BigDecimal("360"),
                                MathContext.DECIMAL128)),
                "30E+/360 year fraction is days / 360 " + start + " -> " + end);
    }

    @Test
    void thirty360FamilyReverseIntervalNegatesTheForwardResult() {
        // Forward (15 Jan, 31 Jan) is 16 days under 30/360. The reverse interval is its negation, not a direct
        // evaluation that would read the 31st as the 30th and give -15.
        assertEquals(-16, DayCounts.thirty360().days(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 15)),
                "30/360 reverse interval is the negated forward result");
        assertEquals(-76, DayCounts.thirty360().days(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 15)),
                "30/360 reverse of table row 3");
        assertEquals(-61, DayCounts.thirtyEPlus360().days(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 31)),
                "30E+/360 reverse of table row 2");
        assertEquals(-61, DayCounts.thirtyE360().days(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 29)),
                "30E/360 reverse of table row 4");
    }

    @Test
    void thirty360FamilyCodesResolveToTheirOwnSingletons() {
        assertSame(DayCounts.thirty360(), DayCounts.of("30/360"), "30/360 code lookup");
        assertSame(DayCounts.thirtyEPlus360(), DayCounts.of("30E+/360"), "30E+/360 code lookup");
        assertNotSame(DayCounts.thirty360(), DayCounts.thirtyE360(), "30/360 is not the 30E/360 instance");
        assertNotSame(DayCounts.thirtyE360(), DayCounts.thirtyEPlus360(), "30E+/360 is not the 30E/360 instance");
        assertThrows(IllegalArgumentException.class, () -> DayCounts.of("30E+360"), "codes are matched exactly");
        assertThrows(IllegalArgumentException.class, () -> DayCounts.of("30/360 "), "codes are matched exactly");
    }

    /**
     * docs/specs/day-count/v1.0.1.md, point 4: the definitions stay as they are, so 31 Jan to 31 Jan is one day for
     * 30E+/360.
     */
    @Test
    void thirty360FamilyKeepsTheDefinitionOfAThirtyFirstToItselfUnchanged() {
        LocalDate thirtyFirst = LocalDate.of(2026, 1, 31);
        assertEquals(1, DayCounts.thirtyEPlus360().days(thirtyFirst, thirtyFirst),
                "30E+/360 counts the 31st to the 31st as one day");
        assertEquals(0, DayCounts.thirty360().days(thirtyFirst, thirtyFirst), "30/360 counts zero days");
        assertEquals(0, DayCounts.thirtyE360().days(thirtyFirst, thirtyFirst), "30E/360 counts zero days");
    }

    @Test
    void calendarsAndBusinessDayConventions() {
        BusinessCalendar weekends = HolidayCalendar.weekendsOnly();
        LocalDate friday = LocalDate.of(2024, 6, 14);
        LocalDate saturday = friday.plusDays(1);
        assertTrue(weekends.isBusinessDay(friday), "weekday is business day");
        assertFalse(weekends.isBusinessDay(saturday), "Saturday is not a business day");
        assertEquals(LocalDate.of(2024, 6, 17), weekends.shift(friday, 1), "weekend shift forward");
        assertEquals(saturday, weekends.shift(saturday, 0), "zero shift preserves weekend date");

        Set<LocalDate> suppliedHolidays = new HashSet<>();
        LocalDate mondayHoliday = LocalDate.of(2024, 6, 17);
        suppliedHolidays.add(mondayHoliday);
        BusinessCalendar holidays = HolidayCalendar.of(suppliedHolidays);
        suppliedHolidays.add(LocalDate.of(2024, 7, 1));
        assertFalse(holidays.isBusinessDay(mondayHoliday), "supplied holiday is not a business day");
        assertTrue(holidays.isBusinessDay(LocalDate.of(2024, 7, 1)), "calendar copies input holiday set");
        assertEquals(LocalDate.of(2024, 6, 18), holidays.shift(friday, 1), "holiday shift forward");
        assertEquals(friday, holidays.shift(LocalDate.of(2024, 6, 18), -1), "holiday shift backward");
        assertEquals(friday, holidays.shift(friday, 0), "zero business-day shift");

        LocalDate monthEndSaturday = LocalDate.of(2024, 8, 31);
        assertEquals(monthEndSaturday, BusinessDayConvention.NONE.adjust(monthEndSaturday, weekends),
                "NONE convention leaves date unchanged");
        assertEquals(LocalDate.of(2024, 9, 2), BusinessDayConvention.FOLLOWING.adjust(monthEndSaturday, weekends),
                "FOLLOWING convention");
        assertEquals(LocalDate.of(2024, 8, 30), BusinessDayConvention.PRECEDING.adjust(monthEndSaturday, weekends),
                "PRECEDING convention");
        assertEquals(LocalDate.of(2024, 8, 30),
                BusinessDayConvention.MODIFIED_FOLLOWING.adjust(monthEndSaturday, weekends),
                "MODIFIED_FOLLOWING preserves month");
    }

    @Test
    void identifiersAndFixings() {
        SecurityId security = new SecurityId("SU0000000001");
        PortfolioId portfolio = new PortfolioId("portfolio-1");
        assertEquals(security, new PositionId(portfolio, security).security(), "position identifier composition");
        assertEquals("operation-1", new OperationId("operation-1").value(), "caller-supplied operation ID");
        assertThrows(IllegalArgumentException.class, () -> new SecurityId("  "), "blank security ID rejected");
        assertThrows(IllegalArgumentException.class, () -> new PortfolioId(""), "blank portfolio ID rejected");
        assertThrows(IllegalArgumentException.class, () -> new OperationId("\t"), "blank operation ID rejected");
        assertThrows(NullPointerException.class, () -> new SecurityId(null), "null security ID rejected");
        assertThrows(NullPointerException.class, () -> new PositionId(null, security), "null portfolio in position rejected");
        assertThrows(NullPointerException.class, () -> new PositionId(portfolio, null), "null security in position rejected");

        IndexId index = new IndexId("RU_KEY_RATE");
        assertEquals("RU_KEY_RATE", index.value(), "index identifier value");
        assertThrows(IllegalArgumentException.class, () -> new IndexId(""), "blank index ID rejected");
    }
}
