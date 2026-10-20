package ru.onlychill.mbondsfx.integrations.cbr.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.integrations.index.IndexFixing;
import ru.onlychill.mbondsfx.integrations.index.MissingFixingException;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;
import ru.onlychill.mbondsfx.integrations.transport.HttpStatusException;
import ru.onlychill.mbondsfx.integrations.transport.Transport;
import ru.onlychill.mbondsfx.integrations.transport.TransportException;

/** Offline contracts for the CBR adapter, provider factory, and its settings. */
public final class CbrProviderContractTests {
    private static final URI FIXTURE_ENDPOINT = URI.create("https://fixture.invalid/DailyInfo.asmx");
    private static final LocalDate KEY_DATE = LocalDate.of(2026, 10, 1);

    private CbrProviderContractTests() {
    }

    public static void main(String[] args) {
        cbrSoapSeriesAndExactFixings();
        cbrXmlFailuresAndSecurity();
        http500SoapFaultUsesSharedStatusContract();
        providerFactoryIsLazyAndUsesConfiguredEndpoint();
        configurationDefaultsAndValidation();
        System.out.println("CbrProviderContractTests: passed");
    }

    private static void cbrSoapSeriesAndExactFixings() {
        List<Transport.Request> requests = new ArrayList<>();
        Transport transport = request -> {
            requests.add(request);
            if (request.body().contains("<KeyRateXML ")) {
                return soapResult("KeyRateXML", "<KeyRate xmlns=\"\"><KR><DT>2026-10-01T00:00:00+03:00</DT>"
                        + "<Rate>14.00</Rate></KR></KeyRate>");
            }
            if (request.body().contains("<RuoniaXML ")) {
                return soapResultUnescaped("RuoniaXML", "<Ruonia xmlns=\"\"><ro><D0>2026-10-02T00:00:00+03:00</D0>"
                        + "<ruo>13.8400</ruo><vol>1</vol></ro></Ruonia>");
            }
            throw new AssertionError("Unexpected SOAP request");
        };
        CbrIndexAdapter adapter = new CbrIndexAdapter(transport, FIXTURE_ENDPOINT);
        IndexFixing key = adapter.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE);
        decimalEqual(new BigDecimal("0.14"), key.value(), "key-rate percent converts to decimal fraction");
        equal(CbrIndexAdapter.RU_KEY_RATE, key.index(), "key-rate index metadata retained");
        equal(KEY_DATE, key.date(), "key-rate requested date retained");
        IndexFixing ruonia = adapter.fixing(CbrIndexAdapter.RUONIA, LocalDate.of(2026, 10, 2));
        decimalEqual(new BigDecimal("0.1384"), ruonia.value(), "RUONIA percent converts to decimal fraction");

        check(requests.stream().allMatch(request -> "POST".equals(request.method())), "CBR uses SOAP POST");
        check(requests.stream().allMatch(request -> "text/xml; charset=utf-8"
                .equals(request.headers().get("Content-Type"))), "CBR sends UTF-8 XML content type");
        equal(FIXTURE_ENDPOINT, requests.getFirst().uri(), "request uses configured endpoint");
        equal("\"http://web.cbr.ru/KeyRateXML\"", requests.get(0).headers().get("SOAPAction"),
                "KeyRate SOAPAction");
        equal("\"http://web.cbr.ru/RuoniaXML\"", requests.get(1).headers().get("SOAPAction"),
                "RUONIA SOAPAction");
        check(requests.get(0).body().contains("<fromDate>2026-10-01</fromDate><ToDate>2026-10-01</ToDate>"),
                "exact fixing sends explicit inclusive single-day range");
        check(requests.get(0).body().contains("<KeyRateXML xmlns=\"http://web.cbr.ru/\">"),
                "key-rate SOAP method namespace");
        check(requests.get(1).body().contains("<RuoniaXML xmlns=\"http://web.cbr.ru/\">"),
                "RUONIA SOAP method namespace");

        List<IndexFixing> keySeries = adapter.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE, KEY_DATE);
        equal(KEY_DATE, keySeries.getFirst().date(), "series API returns exact fixing points");
        expect(UnsupportedOperationException.class, () -> keySeries.add(key), "series result is immutable");
        MissingFixingException missing = expect(MissingFixingException.class,
                () -> adapter.fixing(CbrIndexAdapter.RU_KEY_RATE, LocalDate.of(2026, 10, 4)),
                "missing exact date is not forward-filled");
        equal(CbrIndexAdapter.RU_KEY_RATE, missing.index(), "missing fixing reports requested index");
        equal(LocalDate.of(2026, 10, 4), missing.date(), "missing fixing reports requested date");
        expect(IllegalArgumentException.class,
                () -> adapter.fixing(new IndexId("CPI"), KEY_DATE), "unsupported CBR index fails clearly");
        expect(IllegalArgumentException.class,
                () -> adapter.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE.plusDays(1), KEY_DATE),
                "reversed CBR date range is rejected");
    }

    private static void cbrXmlFailuresAndSecurity() {
        CbrIndexAdapter malformed = new CbrIndexAdapter(request -> "<soap:Envelope><broken>", FIXTURE_ENDPOINT);
        expect(IllegalArgumentException.class,
                () -> malformed.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "malformed SOAP XML fails");

        CbrIndexAdapter fault = new CbrIndexAdapter(request -> "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>Unavailable</faultstring>"
                + "</soap:Fault></soap:Body></soap:Envelope>", FIXTURE_ENDPOINT);
        expect(CbrIndexAdapter.SoapFaultException.class,
                () -> fault.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "SOAP Fault is recognized");

        CbrIndexAdapter noResult = new CbrIndexAdapter(request -> "<Envelope><Body/></Envelope>", FIXTURE_ENDPOINT);
        expect(IllegalStateException.class,
                () -> noResult.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "missing SOAP result is distinct");

        String doctype = "<!DOCTYPE soap:Envelope [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<soap:Fault><faultstring>&xxe;</faultstring></soap:Fault></soap:Body></soap:Envelope>";
        CbrIndexAdapter unsafe = new CbrIndexAdapter(request -> doctype, FIXTURE_ENDPOINT);
        expect(IllegalArgumentException.class,
                () -> unsafe.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "DTD/external entity is rejected");

        CbrIndexAdapter invalidDate = new CbrIndexAdapter(request -> soapResult("KeyRateXML",
                "<KeyRate xmlns=\"\"><KR><DT>not-a-date</DT><Rate>14</Rate></KR></KeyRate>"), FIXTURE_ENDPOINT);
        expect(IllegalArgumentException.class,
                () -> invalidDate.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "invalid CBR date is rejected");
        CbrIndexAdapter invalidRate = new CbrIndexAdapter(request -> soapResult("KeyRateXML",
                "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>unknown</Rate></KR></KeyRate>"), FIXTURE_ENDPOINT);
        expect(IllegalArgumentException.class,
                () -> invalidRate.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "invalid CBR decimal is rejected");
    }

    private static void http500SoapFaultUsesSharedStatusContract() {
        String faultBody = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>Rejected</faultstring>"
                + "</soap:Fault></soap:Body></soap:Envelope>";
        HttpStatusException status = new HttpStatusException(500, FIXTURE_ENDPOINT, faultBody);
        TransportException wrapped = new TransportException("HTTP status", status);
        CbrIndexAdapter httpFault = new CbrIndexAdapter(request -> { throw wrapped; }, FIXTURE_ENDPOINT);
        CbrIndexAdapter.SoapFaultException recognized = expect(CbrIndexAdapter.SoapFaultException.class,
                () -> httpFault.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "HTTP 500 SOAP Fault remains recognizable through shared status type");
        check(recognized.getMessage().contains("Rejected"), "HTTP SOAP fault body is retained for parsing");

        TransportException nonSoapStatus = new TransportException("HTTP status",
                new HttpStatusException(503, FIXTURE_ENDPOINT, "not XML"));
        CbrIndexAdapter ordinaryHttpFailure = new CbrIndexAdapter(request -> { throw nonSoapStatus; }, FIXTURE_ENDPOINT);
        check(expect(TransportException.class,
                () -> ordinaryHttpFailure.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "non-SOAP HTTP failure propagates") == nonSoapStatus, "original transport error is preserved");
    }

    private static void providerFactoryIsLazyAndUsesConfiguredEndpoint() {
        List<Transport.Request> requests = new ArrayList<>();
        Transport fakeTransport = request -> {
            requests.add(request);
            return soapResult("KeyRateXML", "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT>"
                    + "<Rate>14.00</Rate></KR></KeyRate>");
        };
        CbrProviderFactory factory = new CbrProviderFactory(fakeTransport);
        equal("cbr", factory.providerId(), "stable CBR provider ID");
        ProviderContext context = new ProviderContext(Map.of(
                CbrProviderFactory.ENDPOINT_SETTING, "https://fixture.invalid/custom/cbr",
                CbrProviderFactory.CONNECT_TIMEOUT_SETTING, "PT3S",
                CbrProviderFactory.REQUEST_TIMEOUT_SETTING, "PT8S"));
        IndexValueSource source = factory.create(context);
        equal(0, requests.size(), "factory create does not perform network I/O");
        decimalEqual(new BigDecimal("0.14"), source.value(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "factory returns the shared bridge over CBR fixings");
        equal(1, requests.size(), "network is used only when the capability is called");
        equal(URI.create("https://fixture.invalid/custom/cbr"), requests.getFirst().uri(),
                "provider endpoint setting is applied");
        check(source != null, "provider returns domain IndexValueSource capability");

        IndexValueSource productionSource = new CbrProviderFactory().create(new ProviderContext(Map.of()));
        check(productionSource != null, "no-argument factory creates the production capability without fetching");
    }

    private static void configurationDefaultsAndValidation() {
        CbrProviderFactory.Configuration defaults = CbrProviderFactory.Configuration.from(
                new ProviderContext(Map.of()));
        equal(CbrIndexAdapter.DEFAULT_ENDPOINT, defaults.endpoint(), "default official CBR endpoint");
        equal(Duration.ofSeconds(10), defaults.connectTimeout(), "default connect timeout");
        equal(Duration.ofSeconds(30), defaults.requestTimeout(), "default request timeout");

        CbrProviderFactory.Configuration custom = CbrProviderFactory.Configuration.from(new ProviderContext(Map.of(
                CbrProviderFactory.ENDPOINT_SETTING, "http://fixture.invalid/soap",
                CbrProviderFactory.CONNECT_TIMEOUT_SETTING, "PT2.5S",
                CbrProviderFactory.REQUEST_TIMEOUT_SETTING, "PT12S")));
        equal(URI.create("http://fixture.invalid/soap"), custom.endpoint(), "custom endpoint parsed");
        equal(Duration.ofMillis(2500), custom.connectTimeout(), "custom connect timeout parsed");
        equal(Duration.ofSeconds(12), custom.requestTimeout(), "custom request timeout parsed");

        invalidSetting(Map.of("unexpected", "x"), "unknown provider setting rejected");
        invalidSetting(Map.of(CbrProviderFactory.ENDPOINT_SETTING, "not a uri"), "malformed endpoint rejected");
        invalidSetting(Map.of(CbrProviderFactory.ENDPOINT_SETTING, "ftp://fixture.invalid/cbr"),
                "non-HTTP endpoint rejected");
        invalidSetting(Map.of(CbrProviderFactory.ENDPOINT_SETTING, "https://fixture.invalid/cbr?x=1"),
                "endpoint query rejected");
        invalidSetting(Map.of(CbrProviderFactory.ENDPOINT_SETTING, "https://fixture.invalid/cbr#fragment"),
                "endpoint fragment rejected");
        invalidSetting(Map.of(CbrProviderFactory.CONNECT_TIMEOUT_SETTING, "3 seconds"),
                "non-ISO connect timeout rejected");
        invalidSetting(Map.of(CbrProviderFactory.REQUEST_TIMEOUT_SETTING, "PT0S"),
                "zero request timeout rejected");
        invalidSetting(Map.of(CbrProviderFactory.REQUEST_TIMEOUT_SETTING, "PT-1S"),
                "negative request timeout rejected");
        expect(NullPointerException.class, () -> CbrProviderFactory.Configuration.from(null),
                "null provider context rejected");
    }

    private static void invalidSetting(Map<String, String> settings, String message) {
        expect(IllegalArgumentException.class,
                () -> CbrProviderFactory.Configuration.from(new ProviderContext(settings)), message);
    }

    private static String soapResult(String method, String inner) {
        String escaped = inner.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<" + method + "Response xmlns=\"http://web.cbr.ru/\"><" + method + "Result>"
                + escaped + "</" + method + "Result></" + method + "Response></soap:Body></soap:Envelope>";
    }

    private static String soapResultUnescaped(String method, String inner) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<" + method + "Response xmlns=\"http://web.cbr.ru/\"><" + method + "Result>"
                + inner + "</" + method + "Result></" + method + "Response></soap:Body></soap:Envelope>";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void decimalEqual(BigDecimal expected, BigDecimal actual, String message) {
        if (expected.compareTo(actual) != 0) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static <T extends Throwable> T expect(Class<T> expected, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return expected.cast(thrown);
            }
            throw new AssertionError(message + ": expected " + expected.getSimpleName() + " but caught " + thrown,
                    thrown);
        }
        throw new AssertionError(message + ": expected " + expected.getSimpleName());
    }
}
