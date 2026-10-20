package ru.onlychill.mbondsfx.integrations.moex;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.MarketDataPort;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;

public final class IntegrationModelContractTests {
    private static int checks;

    private IntegrationModelContractTests() {
    }

    public static void main(String[] args) {
        testMarketData();
        System.out.println("MOEX IntegrationModelContractTests: " + checks + " checks passed");
    }

    private static void testMarketData() {
        LocalDate early = LocalDate.of(2024, 3, 1);
        LocalDate late = LocalDate.of(2024, 6, 1);
        BondMarketData.CouponRow lateCoupon = new BondMarketData.CouponRow(late,
                Optional.empty(), Optional.empty(), new BigDecimal("1000"), "RUB",
                Optional.empty(), Optional.empty(), Optional.empty());
        BondMarketData.CouponRow earlyCoupon = new BondMarketData.CouponRow(early,
                Optional.of(early.minusMonths(6)), Optional.of(early.minusDays(2)), new BigDecimal("1000"), "RUB",
                Optional.of(new BigDecimal("42.125")), Optional.empty(), Optional.of(new BigDecimal("42125")));
        BondMarketData.AmortizationRow laterAmortization = new BondMarketData.AmortizationRow(late,
                new BigDecimal("700"), new BigDecimal("1000"), "RUB", Optional.empty(),
                Optional.empty(), Optional.empty(), "amortization");
        BondMarketData.AmortizationRow earlierAmortization = new BondMarketData.AmortizationRow(early,
                new BigDecimal("1000"), new BigDecimal("1000"), "RUB", Optional.empty(),
                Optional.empty(), Optional.empty(), "maturity");
        BondMarketData.OfferRow undatedOffer = new BondMarketData.OfferRow(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("Агент"), Optional.empty());
        BondMarketData.OfferRow datedOffer = new BondMarketData.OfferRow(Optional.of(early), Optional.empty(),
                Optional.empty(), Optional.of(new BigDecimal("99.875")), Optional.empty(),
                Optional.empty(), Optional.of("Оферта"));

        Map<String, String> description = new java.util.LinkedHashMap<>();
        description.put("SHORTNAME", "Бонд");
        description.put("EMPTY", "");
        List<BondMarketData.CouponRow> coupons = new ArrayList<>(List.of(lateCoupon, earlyCoupon));
        List<BondMarketData.AmortizationRow> amortizations = new ArrayList<>(
                List.of(laterAmortization, earlierAmortization));
        List<BondMarketData.OfferRow> offers = new ArrayList<>(List.of(undatedOffer, datedOffer));
        BondMarketData data = new BondMarketData(description, coupons, amortizations, offers);
        description.put("MUTATED", "no");
        coupons.clear();
        amortizations.clear();
        offers.clear();

        check(data.description().get("EMPTY").isEmpty(), "raw empty description value preserved");
        check(!data.description().containsKey("MUTATED"), "description map defensively copied");
        check(data.coupons().size() == 2 && data.coupons().get(0).paymentDate().equals(early),
                "coupon rows sorted and copied");
        check(data.coupons().get(0).value().orElseThrow().equals(new BigDecimal("42.125")),
                "raw coupon quote precision preserved");
        check(data.coupons().get(1).value().isEmpty(), "missing coupon amount remains empty");
        check(data.amortizations().get(0).date().equals(early), "amortization rows sorted");
        check(data.amortizations().get(1).faceValue().equals(new BigDecimal("700")),
                "raw amortization faceValue field preserved");
        check(data.amortizations().get(1).source().equals("amortization"), "raw amortization source preserved");
        check(data.offers().get(0).offerDate().orElseThrow().equals(early)
                && data.offers().get(1).offerDate().isEmpty(), "offers sorted with undated rows last");
        expectThrows(UnsupportedOperationException.class,
                () -> data.description().put("NEW", "value"), "description map is immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> data.coupons().add(lateCoupon), "coupon rows are immutable");
        expectThrows(NullPointerException.class,
                () -> new BondMarketData(null, List.of(), List.of(), List.of()), "null description rejected");
        expectThrows(NullPointerException.class,
                () -> new BondMarketData(Map.of(), List.of((BondMarketData.CouponRow) null), List.of(), List.of()),
                "null row rejected");
        expectThrows(NullPointerException.class,
                () -> new BondMarketData.CouponRow(early, null, Optional.empty(), BigDecimal.ONE, "RUB",
                        Optional.empty(), Optional.empty(), Optional.empty()), "null DTO optional rejected");
        expectThrows(NullPointerException.class,
                () -> new BondMarketData.AmortizationRow(early, null, BigDecimal.ONE, "RUB",
                        Optional.empty(), Optional.empty(), Optional.empty(), "amortization"),
                "null raw face value rejected");

        MarketDataPort emptyPort = secid -> Optional.empty();
        check(emptyPort.bond(new SecurityId("TEST")).isEmpty(), "market-data port empty result contract");
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
