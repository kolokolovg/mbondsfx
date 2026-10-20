module ru.onlychill.mbondsfx.integrations.cbr {
    requires ru.onlychill.mbondsfx.integrations;
    requires java.net.http;
    requires java.xml;

    provides ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider
            with ru.onlychill.mbondsfx.integrations.cbr.internal.CbrProviderFactory;
}
