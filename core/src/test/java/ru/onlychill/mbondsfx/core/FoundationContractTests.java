package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

public final class FoundationContractTests {
    private static int checks;

    private FoundationContractTests() {
    }

    public static void main(String[] args) {
        testMoney();
        testDayCounts();
        testCalendars();
        testIdentifiersAndFixings();
        System.out.println("FoundationContractTests: " + checks + " checks passed");
    }

    private static void testMoney() {
        Currency usd = Currency.getInstance("USD");
        Currency rub = Currency.getInstance("RUB");
        Currency jpy = Currency.getInstance("JPY");
        Money canonical = new Money(new BigDecimal("12.3400"), usd);
        check(canonical.equals(new Money(new BigDecimal("12.34"), usd)), "Money equality canonicalizes scale");
        check(canonical.amount().scale() == 2, "Money amount stores canonical scale");
        check(new Money(new BigDecimal("1.23456789123"), usd).amount()
                .equals(new BigDecimal("1.23456789123")), "construction preserves quote precision");
        check(new Money(new BigDecimal("1.26"), rub).currency().equals(rub), "RUB currency accepted");
        check(new Money(new BigDecimal("1.4"), jpy).amount().equals(new BigDecimal("1.4")),
                "zero-decimal JPY currency accepted without constructor rounding");
        check(Money.zero(usd).isZero(), "currency zero factory");
        check(Money.of(usd, 5).equals(new Money(new BigDecimal("5"), usd)), "major-unit factory");
        check(new Money(new BigDecimal("1.20"), usd).add(new Money(new BigDecimal("2.30"), usd))
                .equals(new Money(new BigDecimal("3.50"), usd)), "addition");
        check(new Money(new BigDecimal("3.00"), usd).subtract(new Money(new BigDecimal("1"), usd))
                .equals(new Money(new BigDecimal("2"), usd)), "subtraction");
        check(new Money(new BigDecimal("1.005"), usd).multiply(2)
                .equals(new Money(new BigDecimal("2.01"), usd)), "integer multiplication is exact");
        check(new Money(new BigDecimal("1.00"), usd).multiply(new BigDecimal("0.005"))
                .equals(new Money(new BigDecimal("0.01"), usd)), "HALF_UP multiplication rounds once");
        check(new Money(new BigDecimal("-1.00"), usd).multiply(new BigDecimal("0.005"))
                .equals(new Money(new BigDecimal("-0.01"), usd)), "negative HALF_UP multiplication");
        check(new Money(new BigDecimal("1"), usd).divide(8)
                .equals(new Money(new BigDecimal("0.13"), usd)), "HALF_UP division");
        check(new Money(new BigDecimal("1"), rub).divide(new BigDecimal("3"))
                .equals(new Money(new BigDecimal("0.33"), rub)), "division uses currency minor-unit scale");
        check(new Money(new BigDecimal("1.50"), usd).negate().isNegative(), "negation and sign");
        check(new Money(new BigDecimal("1"), usd).compareTo(new Money(new BigDecimal("2"), usd)) < 0,
                "same-currency comparison");
        expectThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, usd).add(new Money(BigDecimal.ONE, rub)),
                "addition rejects currency mismatch");
        expectThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, usd).compareTo(new Money(BigDecimal.ONE, rub)),
                "comparison rejects currency mismatch");
        expectThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, Currency.getInstance("XXX")), "XXX pseudo currency rejected");
        for (String pseudoCode : List.of("XDR", "XTS", "XAU")) {
            expectThrows(IllegalArgumentException.class,
                    () -> new Money(new BigDecimal("1.26"), Currency.getInstance(pseudoCode)),
                    pseudoCode + " currency without minor-unit scale rejected");
        }
        expectThrows(NullPointerException.class, () -> new Money(null, usd), "null amount rejected");
        expectThrows(NullPointerException.class, () -> new Money(BigDecimal.ONE, null), "null currency rejected");
        expectThrows(ArithmeticException.class,
                () -> new Money(BigDecimal.ONE, usd).divide(0), "division by zero rejected");
    }

    private static void testDayCounts() {
        LocalDate leapStart = LocalDate.of(2020, 1, 1);
        LocalDate nextYear = LocalDate.of(2021, 1, 1);
        check(DayCounts.act365F().days(leapStart, nextYear) == 366, "ACT/365F leap-year days");
        check(DayCounts.act365F().yearFraction(leapStart, nextYear)
                .compareTo(new BigDecimal("366").divide(new BigDecimal("365"), MathContext.DECIMAL128)) == 0,
                "ACT/365F fraction");
        check(DayCounts.act360().yearFraction(leapStart, nextYear)
                .compareTo(new BigDecimal("366").divide(new BigDecimal("360"), MathContext.DECIMAL128)) == 0,
                "ACT/360 leap-year fraction");

        DayCount actAct = DayCounts.actActIsda();
        check(actAct.yearFraction(leapStart, nextYear).compareTo(BigDecimal.ONE) == 0,
                "ACT/ACT.ISDA full leap year fraction");
        LocalDate splitStart = LocalDate.of(2019, 7, 1);
        LocalDate splitEnd = LocalDate.of(2020, 7, 1);
        BigDecimal isdaExpected = new BigDecimal("184").divide(new BigDecimal("365"), MathContext.DECIMAL128)
                .add(new BigDecimal("182").divide(new BigDecimal("366"), MathContext.DECIMAL128),
                        MathContext.DECIMAL128);
        check(actAct.yearFraction(splitStart, splitEnd).compareTo(isdaExpected) == 0,
                "ACT/ACT.ISDA splits across leap-year boundary");
        check(actAct.yearFraction(splitEnd, splitStart)
                .compareTo(actAct.yearFraction(splitStart, splitEnd).negate()) == 0,
                "ACT/ACT.ISDA reverse interval is signed");

        DayCount thirtyE = DayCounts.thirtyE360();
        check(thirtyE.days(LocalDate.of(2020, 1, 31), LocalDate.of(2020, 2, 29)) == 29,
                "30E/360 caps day-of-month at 30");
        check(thirtyE.yearFraction(LocalDate.of(2020, 2, 28), LocalDate.of(2020, 3, 31))
                .compareTo(new BigDecimal("32").divide(new BigDecimal("360"), MathContext.DECIMAL128)) == 0,
                "30E/360 fraction");
        check(DayCounts.of("ACT/365F") == DayCounts.act365F(), "day-count code lookup");
        check(DayCounts.of("ACT/360") == DayCounts.act360(), "ACT/360 code lookup");
        check(DayCounts.of("ACT/ACT.ISDA") == DayCounts.actActIsda(), "ACT/ACT.ISDA code lookup");
        check(DayCounts.of("30E/360") == DayCounts.thirtyE360(), "30E/360 code lookup");
        expectThrows(IllegalArgumentException.class, () -> DayCounts.of("ACT/ACT"), "unknown day-count rejected");
    }

    private static void testCalendars() {
        BusinessCalendar weekends = HolidayCalendar.weekendsOnly();
        LocalDate friday = LocalDate.of(2024, 6, 14);
        LocalDate saturday = friday.plusDays(1);
        check(weekends.isBusinessDay(friday), "weekday is business day");
        check(!weekends.isBusinessDay(saturday), "Saturday is not a business day");
        check(weekends.shift(friday, 1).equals(LocalDate.of(2024, 6, 17)), "weekend shift forward");
        check(weekends.shift(saturday, 0).equals(saturday), "zero shift preserves weekend date");

        Set<LocalDate> suppliedHolidays = new HashSet<>();
        LocalDate mondayHoliday = LocalDate.of(2024, 6, 17);
        suppliedHolidays.add(mondayHoliday);
        BusinessCalendar holidays = HolidayCalendar.of(suppliedHolidays);
        suppliedHolidays.add(LocalDate.of(2024, 7, 1));
        check(!holidays.isBusinessDay(mondayHoliday), "supplied holiday is not a business day");
        check(holidays.isBusinessDay(LocalDate.of(2024, 7, 1)), "calendar copies input holiday set");
        check(holidays.shift(friday, 1).equals(LocalDate.of(2024, 6, 18)), "holiday shift forward");
        check(holidays.shift(LocalDate.of(2024, 6, 18), -1).equals(friday), "holiday shift backward");
        check(holidays.shift(friday, 0).equals(friday), "zero business-day shift");

        LocalDate monthEndSaturday = LocalDate.of(2024, 8, 31);
        check(BusinessDayConvention.NONE.adjust(monthEndSaturday, weekends).equals(monthEndSaturday),
                "NONE convention leaves date unchanged");
        check(BusinessDayConvention.FOLLOWING.adjust(monthEndSaturday, weekends)
                .equals(LocalDate.of(2024, 9, 2)), "FOLLOWING convention");
        check(BusinessDayConvention.PRECEDING.adjust(monthEndSaturday, weekends)
                .equals(LocalDate.of(2024, 8, 30)), "PRECEDING convention");
        check(BusinessDayConvention.MODIFIED_FOLLOWING.adjust(monthEndSaturday, weekends)
                .equals(LocalDate.of(2024, 8, 30)), "MODIFIED_FOLLOWING preserves month");
    }

    private static void testIdentifiersAndFixings() {
        SecurityId security = new SecurityId("SU0000000001");
        PortfolioId portfolio = new PortfolioId("portfolio-1");
        check(new PositionId(portfolio, security).security().equals(security), "position identifier composition");
        check(new OperationId("operation-1").value().equals("operation-1"), "caller-supplied operation ID");
        expectThrows(IllegalArgumentException.class, () -> new SecurityId("  "), "blank security ID rejected");
        expectThrows(IllegalArgumentException.class, () -> new PortfolioId(""), "blank portfolio ID rejected");
        expectThrows(IllegalArgumentException.class, () -> new OperationId("\t"), "blank operation ID rejected");
        expectThrows(NullPointerException.class, () -> new SecurityId(null), "null security ID rejected");
        expectThrows(NullPointerException.class, () -> new PositionId(null, security), "null portfolio in position rejected");
        expectThrows(NullPointerException.class, () -> new PositionId(portfolio, null), "null security in position rejected");

        IndexId index = new IndexId("RU_KEY_RATE");
        check(index.value().equals("RU_KEY_RATE"), "index identifier value");
        expectThrows(IllegalArgumentException.class, () -> new IndexId(""), "blank index ID rejected");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> T expectThrows(Class<T> expectedType, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expectedType.isInstance(thrown)) {
                checks++;
                return expectedType.cast(thrown);
            }
            throw new AssertionError(message + ": expected " + expectedType.getName()
                    + " but got " + thrown.getClass().getName(), thrown);
        }
        throw new AssertionError(message + ": expected " + expectedType.getName());
    }
}
