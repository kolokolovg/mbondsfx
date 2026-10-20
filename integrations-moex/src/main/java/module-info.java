module ru.onlychill.mbondsfx.integrations.moex {
    requires ru.onlychill.mbondsfx.integrations;
    requires java.net.http;
    requires java.xml;

    provides ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider
            with ru.onlychill.mbondsfx.integrations.moex.internal.MoexProviderFactory;
}
