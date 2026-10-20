package ru.onlychill.mbondsfx.integrations.moex.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;

/** Validated MOEX-owned provider settings. */
record MoexProviderSettings(URI endpoint, Duration connectTimeout, Duration requestTimeout) {
    static final String ENDPOINT = "moex.endpoint";
    static final String CONNECT_TIMEOUT = "moex.connectTimeout";
    static final String REQUEST_TIMEOUT = "moex.requestTimeout";

    private static final URI DEFAULT_ENDPOINT = URI.create("https://iss.moex.com/iss");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    MoexProviderSettings {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!positive(connectTimeout) || !positive(requestTimeout)) {
            throw new IllegalArgumentException("MOEX timeouts must be positive");
        }
        validateEndpoint(endpoint);
    }

    static MoexProviderSettings from(ProviderContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> supplied = context.settings();
        for (String key : supplied.keySet()) {
            if (key.startsWith("moex.") && !key.equals(ENDPOINT)
                    && !key.equals(CONNECT_TIMEOUT) && !key.equals(REQUEST_TIMEOUT)) {
                throw new IllegalArgumentException("Unsupported MOEX provider setting: " + key);
            }
        }

        URI endpoint = supplied.containsKey(ENDPOINT)
                ? parseEndpoint(supplied.get(ENDPOINT)) : DEFAULT_ENDPOINT;
        Duration connectTimeout = supplied.containsKey(CONNECT_TIMEOUT)
                ? parseTimeout(CONNECT_TIMEOUT, supplied.get(CONNECT_TIMEOUT)) : DEFAULT_CONNECT_TIMEOUT;
        Duration requestTimeout = supplied.containsKey(REQUEST_TIMEOUT)
                ? parseTimeout(REQUEST_TIMEOUT, supplied.get(REQUEST_TIMEOUT)) : DEFAULT_REQUEST_TIMEOUT;
        return new MoexProviderSettings(endpoint, connectTimeout, requestTimeout);
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
