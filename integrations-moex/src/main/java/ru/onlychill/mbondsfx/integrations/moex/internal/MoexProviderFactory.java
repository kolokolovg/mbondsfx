package ru.onlychill.mbondsfx.integrations.moex.internal;

import java.net.http.HttpClient;
import java.util.Objects;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;
import ru.onlychill.mbondsfx.integrations.transport.HttpTransport;
import ru.onlychill.mbondsfx.integrations.transport.Transport;

/** Service-provider factory for MOEX bond reference data. No network work occurs during construction. */
public final class MoexProviderFactory implements BondReferenceDataSourceProvider {
    private final Transport testTransport;

    /** Public no-argument constructor for future JPMS service binding. */
    public MoexProviderFactory() {
        this.testTransport = null;
    }

    /** Whitebox seam for deterministic provider tests; production uses the JDK transport. */
    MoexProviderFactory(Transport testTransport) {
        this.testTransport = Objects.requireNonNull(testTransport, "testTransport");
    }

    @Override
    public String providerId() {
        return "moex";
    }

    @Override
    public BondReferenceDataSource create(ProviderContext context) {
        MoexProviderSettings settings = MoexProviderSettings.from(context);
        Transport transport = testTransport != null ? testTransport : new HttpTransport(
                HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build(),
                settings.requestTimeout());
        return new MoexBondReferenceDataSource(new MoexBondAdapter(transport, settings.endpoint()));
    }
}
