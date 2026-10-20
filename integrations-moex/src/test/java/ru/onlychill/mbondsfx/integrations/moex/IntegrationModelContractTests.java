package ru.onlychill.mbondsfx.integrations.moex;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.MarketDataPort;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationModelContractTests {
    @Test
    void marketDataRawValuesAreCopiedSortedAndImmutable() {
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

        assertTrue(data.description().get("EMPTY").isEmpty(), "raw empty description value preserved");
        assertFalse(data.description().containsKey("MUTATED"), "description map defensively copied");
        assertTrue(data.coupons().size() == 2 && data.coupons().get(0).paymentDate().equals(early),
                "coupon rows sorted and copied");
        assertEquals(new BigDecimal("42.125"), data.coupons().get(0).value().orElseThrow(),
                "raw coupon quote precision preserved");
        assertTrue(data.coupons().get(1).value().isEmpty(), "missing coupon amount remains empty");
        assertEquals(early, data.amortizations().get(0).date(), "amortization rows sorted");
        assertEquals(new BigDecimal("700"), data.amortizations().get(1).faceValue(),
                "raw amortization faceValue field preserved");
        assertEquals("amortization", data.amortizations().get(1).source(), "raw amortization source preserved");
        assertTrue(data.offers().get(0).offerDate().orElseThrow().equals(early)
                && data.offers().get(1).offerDate().isEmpty(), "offers sorted with undated rows last");
        assertThrows(UnsupportedOperationException.class,
                () -> data.description().put("NEW", "value"), "description map is immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> data.coupons().add(lateCoupon), "coupon rows are immutable");
        assertThrows(NullPointerException.class,
                () -> new BondMarketData(null, List.of(), List.of(), List.of()), "null description rejected");
        assertThrows(NullPointerException.class,
                () -> new BondMarketData(Map.of(), List.of((BondMarketData.CouponRow) null), List.of(), List.of()),
                "null row rejected");
        assertThrows(NullPointerException.class,
                () -> new BondMarketData.CouponRow(early, null, Optional.empty(), BigDecimal.ONE, "RUB",
                        Optional.empty(), Optional.empty(), Optional.empty()), "null DTO optional rejected");
        assertThrows(NullPointerException.class,
                () -> new BondMarketData.AmortizationRow(early, null, BigDecimal.ONE, "RUB",
                        Optional.empty(), Optional.empty(), Optional.empty(), "amortization"),
                "null raw face value rejected");

        MarketDataPort emptyPort = secid -> Optional.empty();
        assertTrue(emptyPort.bond(new SecurityId("TEST")).isEmpty(), "market-data port empty result contract");
    }
}
