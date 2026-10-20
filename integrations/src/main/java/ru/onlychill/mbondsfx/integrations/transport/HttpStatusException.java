package ru.onlychill.mbondsfx.integrations.transport;

import java.net.URI;
import java.util.Objects;

/** HTTP response status failure contract, including the response body for protocol adapters. */
public final class HttpStatusException extends RuntimeException {
    private final int statusCode;
    private final URI uri;
    private final String responseBody;

    public HttpStatusException(int statusCode, URI uri, String responseBody) {
        super("HTTP " + statusCode + " from " + Objects.requireNonNull(uri, "uri"));
        this.statusCode = statusCode;
        this.uri = uri;
        this.responseBody = Objects.requireNonNull(responseBody, "responseBody");
    }

    public int statusCode() {
        return statusCode;
    }

    public URI uri() {
        return uri;
    }

    public String responseBody() {
        return responseBody;
    }
}
