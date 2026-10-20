package ru.onlychill.mbondsfx.integrations.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Raw market-data rows; no currency, amount, or normalized bond terms are inferred here. */
public record BondMarketData(
        Map<String, String> description,
        List<CouponRow> coupons,
        List<AmortizationRow> amortizations,
        List<OfferRow> offers) {

    public BondMarketData {
        Objects.requireNonNull(description, "description");
        Map<String, String> descriptionCopy = new LinkedHashMap<>();
        description.forEach((name, value) -> descriptionCopy.put(
                Objects.requireNonNull(name, "description name"),
                Objects.requireNonNull(value, "description value")));
        description = Collections.unmodifiableMap(descriptionCopy);
        coupons = sortedCopy(coupons, Comparator.comparing(CouponRow::paymentDate));
        amortizations = sortedCopy(amortizations, Comparator.comparing(AmortizationRow::date));
        offers = sortedCopy(offers,
                Comparator.comparing(row -> row.offerDate().orElse(LocalDate.MAX)));
    }

    public record CouponRow(
            LocalDate paymentDate,
            Optional<LocalDate> periodStart,
            Optional<LocalDate> registryDate,
            BigDecimal faceValueAtPayment,
            String faceUnit,
            Optional<BigDecimal> value,
            Optional<BigDecimal> valuePercent,
            Optional<BigDecimal> valueRub) {
        public CouponRow {
            Objects.requireNonNull(paymentDate, "paymentDate");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(registryDate, "registryDate");
            Objects.requireNonNull(faceValueAtPayment, "faceValueAtPayment");
            Objects.requireNonNull(faceUnit, "faceUnit");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(valuePercent, "valuePercent");
            Objects.requireNonNull(valueRub, "valueRub");
        }
    }

    public record AmortizationRow(
            LocalDate date,
            BigDecimal faceValue,
            BigDecimal initialFaceValue,
            String faceUnit,
            Optional<BigDecimal> valuePercent,
            Optional<BigDecimal> value,
            Optional<BigDecimal> valueRub,
            String source) {
        public AmortizationRow {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(faceValue, "faceValue");
            Objects.requireNonNull(initialFaceValue, "initialFaceValue");
            Objects.requireNonNull(faceUnit, "faceUnit");
            Objects.requireNonNull(valuePercent, "valuePercent");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(valueRub, "valueRub");
            Objects.requireNonNull(source, "source");
        }
    }

    public record OfferRow(
            Optional<LocalDate> offerDate,
            Optional<LocalDate> offerDateStart,
            Optional<LocalDate> offerDateEnd,
            Optional<BigDecimal> price,
            Optional<BigDecimal> value,
            Optional<String> agent,
            Optional<String> offerType) {
        public OfferRow {
            Objects.requireNonNull(offerDate, "offerDate");
            Objects.requireNonNull(offerDateStart, "offerDateStart");
            Objects.requireNonNull(offerDateEnd, "offerDateEnd");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(offerType, "offerType");
        }
    }

    private static <T> List<T> sortedCopy(List<T> rows, Comparator<? super T> comparator) {
        Objects.requireNonNull(rows, "rows");
        List<T> copy = new ArrayList<>(rows);
        copy.forEach(row -> Objects.requireNonNull(row, "row"));
        copy.sort(comparator);
        return List.copyOf(copy);
    }
}
