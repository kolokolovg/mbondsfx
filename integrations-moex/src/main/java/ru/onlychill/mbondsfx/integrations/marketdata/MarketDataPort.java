package ru.onlychill.mbondsfx.integrations.marketdata;

import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;

public interface MarketDataPort {
    Optional<BondMarketData> bond(SecurityId moexSecid);
}
