package ru.onlychill.mbondsfx.integrations.transport;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** JDK HTTP transport for the public, keyless market-data endpoints. */
public final class HttpTransport implements Transport {
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024 * 1024;

    private final HttpClient client;
    private final Duration requestTimeout;
    private final long maxResponseBytes;

    public HttpTransport(HttpClient client, Duration requestTimeout) {
        this(client, requestTimeout, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public HttpTransport(HttpClient client, Duration requestTimeout, long maxResponseBytes) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String send(Request request) {
        Objects.requireNonNull(request, "request");
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(requestTimeout)
                .header("User-Agent", "mbondsfx/1.0 (public market-data client)");
        request.headers().forEach(builder::header);
        String method = request.method().toUpperCase(java.util.Locale.ROOT);
        switch (method) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(
                    request.body(), StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + request.method());
        }

        try {
            HttpResponse<String> response = client.send(builder.build(),
                    responseInfo -> {
                        if (!hasSupportedContentEncoding(responseInfo.headers().allValues("Content-Encoding"))) {
                            // Discard unsupported representations while applying the same byte cap.
                            return HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.<String>replacing(null), maxResponseBytes)
                                    .apply(responseInfo);
                        }
                        return HttpResponse.BodyHandlers.limiting(
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), maxResponseBytes)
                                .apply(responseInfo);
                    });
            if (!hasSupportedContentEncoding(response.headers().allValues("Content-Encoding"))) {
                throw new TransportException("Unsupported Content-Encoding for " + request.uri());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                HttpStatusException statusFailure = new HttpStatusException(
                        response.statusCode(), request.uri(), response.body());
                throw new TransportException(statusFailure.getMessage(), statusFailure);
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted while requesting " + request.uri(), interrupted);
        } catch (IOException failure) {
            throw new TransportException("HTTP request failed for " + request.uri(), failure);
        }
    }

    private static boolean hasSupportedContentEncoding(List<String> encodings) {
        for (String header : encodings) {
            for (String encoding : header.split(",", -1)) {
                if (!"identity".equalsIgnoreCase(encoding.trim())) {
                    return false;
                }
            }
        }
        return true;
    }
}
