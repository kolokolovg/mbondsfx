package ru.onlychill.mbondsfx.integrations.transport;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** JDK HTTP transport for the public, keyless market-data endpoints. */
public final class HttpTransport implements Transport {
    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpTransport(HttpClient client, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
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
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
}
