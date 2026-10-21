package ru.onlychill.mbondsfx.integrations.cbr.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.integrations.adapter.IndexValueSourceAdapter;
import ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;
import ru.onlychill.mbondsfx.integrations.transport.HttpTransport;
import ru.onlychill.mbondsfx.integrations.transport.Transport;
import ru.onlychill.mbondsfx.integrations.xml.SecureXml;

/** Shared-SPI factory for exact-date CBR index values. */
public final class CbrProviderFactory implements IndexValueSourceProvider {
    static final String ENDPOINT_SETTING = "endpoint";
    static final String CONNECT_TIMEOUT_SETTING = "connect-timeout";
    static final String REQUEST_TIMEOUT_SETTING = "request-timeout";
    static final String MAX_RESPONSE_BYTES_SETTING = "max-response-bytes";
    static final String MAX_XML_DEPTH_SETTING = "max-xml-depth";
    static final String MAX_XML_ATTRIBUTES_SETTING = "max-xml-attributes";
    static final String MAX_XML_ATTRIBUTE_LENGTH_SETTING = "max-xml-attribute-length";
    static final String MAX_XML_TEXT_LENGTH_SETTING = "max-xml-text-length";
    static final String MAX_SERIES_ROWS_SETTING = "max-series-rows";
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final long DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024 * 1024;
    static final int DEFAULT_MAX_XML_DEPTH = 32;
    static final int DEFAULT_MAX_XML_ATTRIBUTES = 16;
    static final int DEFAULT_MAX_XML_ATTRIBUTE_LENGTH = 1024;
    static final int DEFAULT_MAX_XML_TEXT_LENGTH = 1024;
    static final int DEFAULT_MAX_SERIES_ROWS = 20_000;
    private static final Set<String> SUPPORTED_SETTINGS = Set.of(
            ENDPOINT_SETTING, CONNECT_TIMEOUT_SETTING, REQUEST_TIMEOUT_SETTING, MAX_RESPONSE_BYTES_SETTING,
            MAX_XML_DEPTH_SETTING, MAX_XML_ATTRIBUTES_SETTING, MAX_XML_ATTRIBUTE_LENGTH_SETTING,
            MAX_XML_TEXT_LENGTH_SETTING, MAX_SERIES_ROWS_SETTING);

    private final Transport testTransport;

    /** Creates a factory that constructs its production JDK HTTP transport on provider creation. */
    public CbrProviderFactory() {
        this.testTransport = null;
    }

    /** Package-private offline seam; production callers use the public no-argument constructor. */
    CbrProviderFactory(Transport testTransport) {
        this.testTransport = Objects.requireNonNull(testTransport, "testTransport");
    }

    @Override
    public String providerId() {
        return "cbr";
    }

    @Override
    public IndexValueSource create(ProviderContext context) {
        Configuration configuration = Configuration.from(context);
        Transport transport = testTransport;
        if (transport == null) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(configuration.connectTimeout())
                    .build();
            transport = new HttpTransport(client, configuration.requestTimeout(), configuration.maxResponseBytes());
        }
        return new IndexValueSourceAdapter(new CbrIndexAdapter(transport, configuration.endpoint(),
                configuration.maxResponseBytes(), configuration.xmlLimits(), configuration.maxSeriesRows()));
    }

    record Configuration(
            URI endpoint,
            Duration connectTimeout,
            Duration requestTimeout,
            long maxResponseBytes,
            int maxXmlDepth,
            int maxXmlAttributes,
            int maxXmlAttributeLength,
            int maxXmlTextLength,
            int maxSeriesRows) {
        Configuration {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (maxResponseBytes <= 0 || maxXmlDepth <= 0 || maxXmlAttributes <= 0
                    || maxXmlAttributeLength <= 0 || maxXmlTextLength <= 0 || maxSeriesRows <= 0) {
                throw new IllegalArgumentException("CBR response and XML limits must be positive");
            }
        }

        SecureXml.Limits xmlLimits() {
            return new SecureXml.Limits(maxXmlDepth, maxXmlAttributes, maxXmlAttributeLength, maxXmlTextLength);
        }

        static Configuration from(ProviderContext context) {
            Objects.requireNonNull(context, "context");
            Map<String, String> settings = context.settings();
            for (String key : settings.keySet()) {
                if (!SUPPORTED_SETTINGS.contains(key)) {
                    throw new IllegalArgumentException("Unsupported CBR provider setting: " + key);
                }
            }

            URI endpoint = CbrIndexAdapter.DEFAULT_ENDPOINT;
            if (settings.containsKey(ENDPOINT_SETTING)) {
                try {
                    endpoint = URI.create(settings.get(ENDPOINT_SETTING));
                } catch (IllegalArgumentException invalidUri) {
                    throw new IllegalArgumentException("CBR endpoint must be a valid HTTP(S) URL", invalidUri);
                }
            }
            if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("CBR endpoint must be an HTTP(S) URL without query/fragment");
            }

            Duration connectTimeout = duration(settings, CONNECT_TIMEOUT_SETTING, DEFAULT_CONNECT_TIMEOUT);
            Duration requestTimeout = duration(settings, REQUEST_TIMEOUT_SETTING, DEFAULT_REQUEST_TIMEOUT);
            long maxResponseBytes = positiveLong(settings, MAX_RESPONSE_BYTES_SETTING, DEFAULT_MAX_RESPONSE_BYTES);
            int maxXmlDepth = positiveInt(settings, MAX_XML_DEPTH_SETTING, DEFAULT_MAX_XML_DEPTH);
            int maxXmlAttributes = positiveInt(settings, MAX_XML_ATTRIBUTES_SETTING, DEFAULT_MAX_XML_ATTRIBUTES);
            int maxXmlAttributeLength = positiveInt(
                    settings, MAX_XML_ATTRIBUTE_LENGTH_SETTING, DEFAULT_MAX_XML_ATTRIBUTE_LENGTH);
            int maxXmlTextLength = positiveInt(settings, MAX_XML_TEXT_LENGTH_SETTING, DEFAULT_MAX_XML_TEXT_LENGTH);
            int maxSeriesRows = positiveInt(settings, MAX_SERIES_ROWS_SETTING, DEFAULT_MAX_SERIES_ROWS);
            return new Configuration(endpoint, connectTimeout, requestTimeout, maxResponseBytes, maxXmlDepth,
                    maxXmlAttributes, maxXmlAttributeLength, maxXmlTextLength, maxSeriesRows);
        }

        private static Duration duration(Map<String, String> settings, String key, Duration defaultValue) {
            if (!settings.containsKey(key)) {
                return defaultValue;
            }
            Duration value;
            try {
                value = Duration.parse(settings.get(key));
            } catch (DateTimeParseException invalidDuration) {
                throw new IllegalArgumentException("CBR setting " + key + " must be an ISO-8601 duration",
                        invalidDuration);
            }
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("CBR setting " + key + " must be positive");
            }
            return value;
        }

        private static long positiveLong(Map<String, String> settings, String key, long defaultValue) {
            if (!settings.containsKey(key)) {
                return defaultValue;
            }
            long value;
            try {
                value = Long.parseLong(settings.get(key));
            } catch (NumberFormatException invalidNumber) {
                throw new IllegalArgumentException("CBR setting " + key + " must be a positive integer",
                        invalidNumber);
            }
            if (value <= 0) {
                throw new IllegalArgumentException("CBR setting " + key + " must be positive");
            }
            return value;
        }

        private static int positiveInt(Map<String, String> settings, String key, int defaultValue) {
            long value = positiveLong(settings, key, defaultValue);
            if (value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("CBR setting " + key + " exceeds the supported integer range");
            }
            return (int) value;
        }
    }
}
