package ru.onlychill.mbondsfx.integrations.moex.internal;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.application.port.BondReference;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.MarketDataPort;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;

/** Maps reported MOEX values to the application's sparse, source-neutral read model. */
final class MoexBondReferenceDataSource implements BondReferenceDataSource {
    private static final String SOURCE_ID = "moex";

    private final MarketDataPort marketData;

    MoexBondReferenceDataSource(MarketDataPort marketData) {
        this.marketData = Objects.requireNonNull(marketData, "marketData");
    }

    @Override
    public Optional<BondReference> reference(SecurityId security) {
        Objects.requireNonNull(security, "security");
        Optional<BondMarketData> raw = Objects.requireNonNull(marketData.bond(security), "market data result");
        return raw.map(data -> toReference(security, data));
    }

    private static BondReference toReference(SecurityId security, BondMarketData raw) {
        Optional<BondReference.ReportedValue> faceValue = reportedPair(
                raw.description().get("FACEVALUE"), raw.description().get("FACEUNIT"), "FACEVALUE");

        // Raw ISS provides an undifferentiated schedule, not an explicitly identified next coupon.
        // The current raw DTO also has no observation timestamp; do not substitute clocks or dates.
        return new BondReference(security, SOURCE_ID, Optional.empty(), faceValue, Optional.empty());
    }

    private static Optional<BondReference.ReportedValue> reportedPair(
            String rawValue, String rawUnit, String fieldName) {
        if (rawValue == null || rawValue.isBlank() || rawUnit == null || rawUnit.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BondReference.ReportedValue(new BigDecimal(rawValue), rawUnit));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid MOEX decimal in " + fieldName + ": " + rawValue, failure);
        }
    }
}
