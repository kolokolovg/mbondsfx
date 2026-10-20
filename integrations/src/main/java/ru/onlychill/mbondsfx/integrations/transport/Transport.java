package ru.onlychill.mbondsfx.integrations.transport;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Transport boundary; request and response bodies are UTF-8 text. */
public interface Transport {
    String send(Request request);

    record Request(String method, URI uri, Map<String, String> headers, String body) {
        public Request {
            Objects.requireNonNull(method, "method");
            if (method.isBlank()) {
                throw new IllegalArgumentException("method must not be blank");
            }
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(headers, "headers");
            Objects.requireNonNull(body, "body");
            Map<String, String> copy = new LinkedHashMap<>();
            headers.forEach((name, value) -> copy.put(
                    Objects.requireNonNull(name, "header name"),
                    Objects.requireNonNull(value, "header value")));
            headers = Collections.unmodifiableMap(copy);
        }

        public static Request get(URI uri) {
            return new Request("GET", uri, Map.of(), "");
        }

        public static Request post(URI uri, String body) {
            return new Request("POST", uri,
                    Map.of("Content-Type", "text/xml; charset=utf-8"), body);
        }
    }
}
