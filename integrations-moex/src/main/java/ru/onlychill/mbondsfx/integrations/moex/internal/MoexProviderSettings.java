package ru.onlychill.mbondsfx.integrations.moex.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;

/** Validated MOEX-owned provider settings. */
record MoexProviderSettings(
        URI endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        long maxResponseBytes,
        int maxXmlDepth,
        int maxXmlAttributes,
        int maxXmlAttributeLength,
        int maxXmlTextLength,
        int maxDescriptionRows,
        int maxPageRows) {
    static final String ENDPOINT = "moex.endpoint";
    static final String CONNECT_TIMEOUT = "moex.connectTimeout";
    static final String REQUEST_TIMEOUT = "moex.requestTimeout";
    static final String MAX_RESPONSE_BYTES = "moex.maxResponseBytes";
    static final String MAX_XML_DEPTH = "moex.maxXmlDepth";
    static final String MAX_XML_ATTRIBUTES = "moex.maxXmlAttributes";
    static final String MAX_XML_ATTRIBUTE_LENGTH = "moex.maxXmlAttributeLength";
    static final String MAX_XML_TEXT_LENGTH = "moex.maxXmlTextLength";
    static final String MAX_DESCRIPTION_ROWS = "moex.maxDescriptionRows";
    static final String MAX_PAGE_ROWS = "moex.maxPageRows";

    private static final URI DEFAULT_ENDPOINT = URI.create("http://iss.moex.com/iss");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final long DEFAULT_MAX_RESPONSE_BYTES = 512L * 1024;
    static final int DEFAULT_MAX_XML_DEPTH = 8;
    static final int DEFAULT_MAX_XML_ATTRIBUTES = 32;
    static final int DEFAULT_MAX_XML_ATTRIBUTE_LENGTH = 8192;
    static final int DEFAULT_MAX_XML_TEXT_LENGTH = 1024;
    static final int DEFAULT_MAX_DESCRIPTION_ROWS = 256;
    static final int DEFAULT_MAX_PAGE_ROWS = 128;

    /** Retains the original constructor contract with the finite MOEX defaults. */
    MoexProviderSettings(URI endpoint, Duration connectTimeout, Duration requestTimeout) {
        this(endpoint, connectTimeout, requestTimeout, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_XML_DEPTH,
                DEFAULT_MAX_XML_ATTRIBUTES, DEFAULT_MAX_XML_ATTRIBUTE_LENGTH, DEFAULT_MAX_XML_TEXT_LENGTH,
                DEFAULT_MAX_DESCRIPTION_ROWS, DEFAULT_MAX_PAGE_ROWS);
    }

    MoexProviderSettings {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!positive(connectTimeout) || !positive(requestTimeout)) {
            throw new IllegalArgumentException("MOEX timeouts must be positive");
        }
        if (maxResponseBytes <= 0 || maxXmlDepth <= 0 || maxXmlAttributes <= 0
                || maxXmlAttributeLength <= 0 || maxXmlTextLength <= 0
                || maxDescriptionRows <= 0 || maxPageRows <= 0) {
            throw new IllegalArgumentException("MOEX response limits must be positive");
        }
        validateEndpoint(endpoint);
    }

    static MoexProviderSettings from(ProviderContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> supplied = context.settings();
        for (String key : supplied.keySet()) {
            if (key.startsWith("moex.") && !key.equals(ENDPOINT)
                    && !key.equals(CONNECT_TIMEOUT) && !key.equals(REQUEST_TIMEOUT)
                    && !key.equals(MAX_RESPONSE_BYTES) && !key.equals(MAX_XML_DEPTH)
                    && !key.equals(MAX_XML_ATTRIBUTES) && !key.equals(MAX_XML_ATTRIBUTE_LENGTH)
                    && !key.equals(MAX_XML_TEXT_LENGTH) && !key.equals(MAX_DESCRIPTION_ROWS)
                    && !key.equals(MAX_PAGE_ROWS)) {
                throw new IllegalArgumentException("Unsupported MOEX provider setting: " + key);
            }
        }

        URI endpoint = supplied.containsKey(ENDPOINT)
                ? parseEndpoint(supplied.get(ENDPOINT)) : DEFAULT_ENDPOINT;
        Duration connectTimeout = supplied.containsKey(CONNECT_TIMEOUT)
                ? parseTimeout(CONNECT_TIMEOUT, supplied.get(CONNECT_TIMEOUT)) : DEFAULT_CONNECT_TIMEOUT;
        Duration requestTimeout = supplied.containsKey(REQUEST_TIMEOUT)
                ? parseTimeout(REQUEST_TIMEOUT, supplied.get(REQUEST_TIMEOUT)) : DEFAULT_REQUEST_TIMEOUT;
        long maxResponseBytes = supplied.containsKey(MAX_RESPONSE_BYTES)
                ? parsePositiveLong(MAX_RESPONSE_BYTES, supplied.get(MAX_RESPONSE_BYTES)) : DEFAULT_MAX_RESPONSE_BYTES;
        int maxXmlDepth = settingInt(supplied, MAX_XML_DEPTH, DEFAULT_MAX_XML_DEPTH);
        int maxXmlAttributes = settingInt(supplied, MAX_XML_ATTRIBUTES, DEFAULT_MAX_XML_ATTRIBUTES);
        int maxXmlAttributeLength = settingInt(supplied, MAX_XML_ATTRIBUTE_LENGTH,
                DEFAULT_MAX_XML_ATTRIBUTE_LENGTH);
        int maxXmlTextLength = settingInt(supplied, MAX_XML_TEXT_LENGTH, DEFAULT_MAX_XML_TEXT_LENGTH);
        int maxDescriptionRows = settingInt(supplied, MAX_DESCRIPTION_ROWS, DEFAULT_MAX_DESCRIPTION_ROWS);
        int maxPageRows = settingInt(supplied, MAX_PAGE_ROWS, DEFAULT_MAX_PAGE_ROWS);
        return new MoexProviderSettings(endpoint, connectTimeout, requestTimeout, maxResponseBytes,
                maxXmlDepth, maxXmlAttributes, maxXmlAttributeLength, maxXmlTextLength,
                maxDescriptionRows, maxPageRows);
    }

    private static URI parseEndpoint(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid MOEX endpoint URI", failure);
        }
    }

    private static Duration parseTimeout(String key, String value) {
        try {
            Duration duration = Duration.parse(value);
            if (!positive(duration)) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return duration;
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 duration", failure);
        }
    }

    private static int settingInt(Map<String, String> supplied, String key, int defaultValue) {
        return supplied.containsKey(key) ? parsePositiveInt(key, supplied.get(key)) : defaultValue;
    }

    private static long parsePositiveLong(String key, String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be a positive integer", failure);
        }
    }

    private static int parsePositiveInt(String key, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be a positive integer", failure);
        }
    }

    private static boolean positive(Duration duration) {
        return !duration.isZero() && !duration.isNegative();
    }

    private static void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (!(("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && endpoint.getHost() != null && endpoint.getUserInfo() == null
                && endpoint.getQuery() == null && endpoint.getFragment() == null)) {
            throw new IllegalArgumentException("moex.endpoint must be an HTTP(S) URI without user info, query, or fragment");
        }
    }
}
