package ru.onlychill.mbondsfx.integrations.provider;

import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;

/** Service-provider capability for exact-date domain index values. */
public interface IndexValueSourceProvider {
    String providerId();

    IndexValueSource create(ProviderContext context);
}
