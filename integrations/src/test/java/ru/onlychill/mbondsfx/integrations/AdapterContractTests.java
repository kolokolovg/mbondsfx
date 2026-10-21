package ru.onlychill.mbondsfx.integrations;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.integrations.adapter.StaticFixings;
import ru.onlychill.mbondsfx.integrations.index.IndexFixing;
import ru.onlychill.mbondsfx.integrations.index.MissingFixingException;
import ru.onlychill.mbondsfx.integrations.transport.HttpStatusException;
import ru.onlychill.mbondsfx.integrations.transport.HttpTransport;
import ru.onlychill.mbondsfx.integrations.transport.Transport;
import ru.onlychill.mbondsfx.integrations.transport.TransportException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline contract checks for adapters; all network boundaries are replaced with fakes. */
class AdapterContractTests {
    @Test
    void staticFixingsAreImmutableAndExact() {
        IndexId cpi = new IndexId("CPI");
        LocalDate date = LocalDate.of(2026, 1, 1);
        Map<LocalDate, BigDecimal> dates = new LinkedHashMap<>();
        dates.put(date, new BigDecimal("1.023"));
        Map<IndexId, Map<LocalDate, BigDecimal>> table = new LinkedHashMap<>();
        table.put(cpi, dates);
        StaticFixings fixings = StaticFixings.of(table);
        dates.put(date.plusDays(1), BigDecimal.TEN);
        table.clear();
        assertEquals(new BigDecimal("1.023"), fixings.fixing(cpi, date).value(), "copy protects static data");
        assertThrows(MissingFixingException.class,
                () -> fixings.fixing(cpi, date.plusDays(1)), "no forward fill in static fixings");
        StaticFixings direct = StaticFixings.of(new IndexFixing(cpi, date, new BigDecimal("0.1023")));
        assertEquals(new BigDecimal("0.1023"), direct.fixing(cpi, date).value(),
                "varargs values remain decimal fractions");
    }

    @Test
    void httpTransportUsesUtf8TimeoutAndStatusHandling() {
        RecordingHttpClient client = new RecordingHttpClient(200, "Привет", null);
        HttpTransport transport = new HttpTransport(client, Duration.ofSeconds(7));
        String body = transport.send(new Transport.Request("POST", URI.create("https://fixture.invalid/soap"),
                Map.of("Content-Type", "text/xml; charset=utf-8", "SOAPAction", "\"action\""), "Привет"));
        assertEquals("Привет", body, "response decoded as UTF-8");
        assertEquals(Duration.ofSeconds(7), client.request.timeout().orElseThrow(), "explicit request timeout");
        assertEquals("Привет", new String(requestBodyBytes(client.request), StandardCharsets.UTF_8),
                "request encoded as UTF-8");
        assertEquals("mbondsfx/1.0 (public market-data client)", client.request.headers()
                .firstValue("User-Agent").orElseThrow(), "polite user agent");

        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransport(client, Duration.ofSeconds(1), 0),
                "response byte limit must be positive");
        RecordingHttpClient exactSize = new RecordingHttpClient(200, "1234", null, Map.of(), 2);
        assertEquals("1234", new HttpTransport(exactSize, Duration.ofSeconds(1), 4)
                .send(Transport.Request.get(URI.create("https://fixture.invalid/exact"))),
                "response at byte limit succeeds without a Content-Length header");
        assertEquals(2, exactSize.deliveredChunks, "exact-size response was delivered in multiple chunks");
        RecordingHttpClient oversizedResponse = new RecordingHttpClient(200, "12345", null, Map.of(), 2);
        TransportException tooLarge = assertThrows(TransportException.class,
                () -> new HttpTransport(oversizedResponse, Duration.ofSeconds(1), 4)
                        .send(Transport.Request.get(URI.create("https://fixture.invalid/too-large"))),
                "response above byte limit fails");
        assertTrue(tooLarge.getCause() instanceof IOException, "response limit failure retains I/O cause");
        assertTrue(oversizedResponse.cancelled, "cumulative multi-chunk overflow cancels body consumption");
        assertEquals(3, oversizedResponse.deliveredChunks, "overflow is detected across the third chunk");
        assertEquals("plain", new HttpTransport(new RecordingHttpClient(200, "plain", null,
                Map.of("Content-Encoding", List.of("identity"))), Duration.ofSeconds(1), 5)
                .send(Transport.Request.get(URI.create("https://fixture.invalid/identity"))),
                "identity content encoding remains supported");
        assertThrows(TransportException.class,
                () -> new HttpTransport(new RecordingHttpClient(200, "compressed", null,
                        Map.of("Content-Encoding", List.of("gzip"))), Duration.ofSeconds(1), 100)
                        .send(Transport.Request.get(URI.create("https://fixture.invalid/encoded"))),
                "unsupported content encoding is rejected");
        RecordingHttpClient oversizedEncoded = new RecordingHttpClient(200, "12345", null,
                Map.of("Content-Encoding", List.of("gzip")));
        assertThrows(TransportException.class,
                () -> new HttpTransport(oversizedEncoded, Duration.ofSeconds(1), 4)
                        .send(Transport.Request.get(URI.create("https://fixture.invalid/encoded-too-large"))),
                "unsupported encoding is rejected under the response byte cap");
        assertTrue(oversizedEncoded.cancelled, "oversized unsupported representation cancels body consumption");

        TransportException statusFailure = assertThrows(TransportException.class,
                () -> new HttpTransport(new RecordingHttpClient(503, "status body", null), Duration.ofSeconds(1))
                        .send(Transport.Request.get(URI.create("https://fixture.invalid/fail"))),
                "non-2xx HTTP status fails");
        assertTrue(statusFailure.getCause() instanceof HttpStatusException,
                "non-2xx failure exposes the transport status contract");
        HttpStatusException status = (HttpStatusException) statusFailure.getCause();
        assertEquals(503, status.statusCode(), "status contract retains response code");
        assertEquals(URI.create("https://fixture.invalid/fail"), status.uri(), "status contract retains request URI");
        assertEquals("status body", status.responseBody(), "status contract retains response body");

        RecordingHttpClient oversizedStatus = new RecordingHttpClient(503, "12345", null, Map.of(), 2);
        TransportException oversizedStatusFailure = assertThrows(TransportException.class,
                () -> new HttpTransport(oversizedStatus, Duration.ofSeconds(1), 4)
                        .send(Transport.Request.get(URI.create("https://fixture.invalid/status-too-large"))),
                "oversized non-2xx body fails at the response cap");
        assertTrue(oversizedStatusFailure.getCause() instanceof IOException,
                "oversized status body retains the I/O limit failure");
        assertTrue(!(oversizedStatusFailure.getCause() instanceof HttpStatusException),
                "oversized status body is not reported as a complete HTTP status failure");
        assertTrue(oversizedStatus.cancelled, "oversized status body consumption is cancelled");

        RecordingHttpClient interrupted = new RecordingHttpClient(200, "", new InterruptedException("test"));
        HttpTransport interruptedTransport = new HttpTransport(interrupted, Duration.ofSeconds(1));
        assertThrows(TransportException.class,
                () -> interruptedTransport.send(Transport.Request.get(
                        URI.create("https://fixture.invalid/interrupted"))));
        try {
            assertTrue(Thread.currentThread().isInterrupted(), "interruption flag restored");
            Thread.interrupted();
        } finally {
            Thread.interrupted();
        }
    }

    private static byte[] requestBodyBytes(HttpRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            @Override public void onError(Throwable failure) { completed.completeExceptionally(failure); }
            @Override public void onComplete() { completed.complete(null); }
        });
        completed.join();
        return output.toByteArray();
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final int status;
        private final String responseBody;
        private final InterruptedException interruption;
        private final Map<String, List<String>> responseHeaders;
        private final int responseChunkSize;
        private HttpRequest request;
        private boolean cancelled;
        private int deliveredChunks;

        RecordingHttpClient(int status, String responseBody, InterruptedException interruption) {
            this(status, responseBody, interruption, Map.of(), Integer.MAX_VALUE);
        }

        RecordingHttpClient(int status, String responseBody, InterruptedException interruption,
                Map<String, List<String>> responseHeaders) {
            this(status, responseBody, interruption, responseHeaders, Integer.MAX_VALUE);
        }

        RecordingHttpClient(int status, String responseBody, InterruptedException interruption,
                Map<String, List<String>> responseHeaders, int responseChunkSize) {
            this.status = status;
            this.responseBody = responseBody;
            this.interruption = interruption;
            this.responseHeaders = responseHeaders;
            if (responseChunkSize <= 0) {
                throw new IllegalArgumentException("responseChunkSize must be positive");
            }
            this.responseChunkSize = responseChunkSize;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override
        public SSLContext sslContext() { return null; }
        @Override
        public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override
        public Version version() { return Version.HTTP_1_1; }
        @Override
        public Optional<Executor> executor() { return Optional.empty(); }
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            this.request = request;
            if (interruption != null) {
                throw interruption;
            }
            HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
                @Override public int statusCode() { return status; }
                @Override public HttpHeaders headers() { return HttpHeaders.of(responseHeaders, (a, b) -> true); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { cancelled = true; }
            });
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < bytes.length && !cancelled; offset += responseChunkSize) {
                int length = Math.min(responseChunkSize, bytes.length - offset);
                subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(bytes, offset, length)));
                deliveredChunks++;
            }
            if (!cancelled) {
                subscriber.onComplete();
            }
            T body;
            try {
                body = subscriber.getBody().toCompletableFuture().join();
            } catch (java.util.concurrent.CompletionException failure) {
                throw new IOException(failure);
            }
            return new HttpResponse<>() {
                @Override public int statusCode() { return status; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(responseHeaders, (a, b) -> true); }
                @Override public T body() { return body; }
                @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override
        public WebSocket.Builder newWebSocketBuilder() { throw new UnsupportedOperationException(); }
    }
}
