package ru.onlychill.mbondsfx.integrations.provider;

import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;

/** Service-provider capability for sparse bond reference facts. */
public interface BondReferenceDataSourceProvider {
    String providerId();

    BondReferenceDataSource create(ProviderContext context);
}
