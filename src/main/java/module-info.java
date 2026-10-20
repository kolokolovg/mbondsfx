module ru.onlychill.mbondsfx {
    requires ru.onlychill.mbondsfx.core;
    requires ru.onlychill.mbondsfx.application;
    requires ru.onlychill.mbondsfx.integrations;

    uses ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider;
    uses ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider;
    uses ru.onlychill.mbondsfx.application.port.LedgerRepositoryFactory;
}
