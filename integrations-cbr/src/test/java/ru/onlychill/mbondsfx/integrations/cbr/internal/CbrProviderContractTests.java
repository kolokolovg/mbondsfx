package ru.onlychill.mbondsfx.integrations.cbr.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.integrations.index.IndexFixing;
import ru.onlychill.mbondsfx.integrations.index.MissingFixingException;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;
import ru.onlychill.mbondsfx.integrations.transport.HttpStatusException;
import ru.onlychill.mbondsfx.integrations.transport.Transport;
import ru.onlychill.mbondsfx.integrations.transport.TransportException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline contracts for the CBR adapter, provider factory, and its settings. */
class CbrProviderContractTests {
    private static final URI FIXTURE_ENDPOINT = URI.create("https://fixture.invalid/DailyInfo.asmx");
    private static final LocalDate KEY_DATE = LocalDate.of(2026, 10, 1);

    @Test
    void soapSeriesAndExactFixings() {
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
        assertEquals(0, new BigDecimal("0.14").compareTo(key.value()),
                "key-rate percent converts to decimal fraction");
        assertEquals(CbrIndexAdapter.RU_KEY_RATE, key.index(), "key-rate index metadata retained");
        assertEquals(KEY_DATE, key.date(), "key-rate requested date retained");
        IndexFixing ruonia = adapter.fixing(CbrIndexAdapter.RUONIA, LocalDate.of(2026, 10, 2));
        assertEquals(0, new BigDecimal("0.1384").compareTo(ruonia.value()),
                "RUONIA percent converts to decimal fraction");

        assertTrue(requests.stream().allMatch(request -> "POST".equals(request.method())), "CBR uses SOAP POST");
        assertTrue(requests.stream().allMatch(request -> "text/xml; charset=utf-8"
                .equals(request.headers().get("Content-Type"))), "CBR sends UTF-8 XML content type");
        assertEquals(FIXTURE_ENDPOINT, requests.getFirst().uri(), "request uses configured endpoint");
        assertEquals("\"http://web.cbr.ru/KeyRateXML\"", requests.get(0).headers().get("SOAPAction"),
                "KeyRate SOAPAction");
        assertEquals("\"http://web.cbr.ru/RuoniaXML\"", requests.get(1).headers().get("SOAPAction"),
                "RUONIA SOAPAction");
        assertTrue(requests.get(0).body().contains("<fromDate>2026-10-01</fromDate><ToDate>2026-10-01</ToDate>"),
                "exact fixing sends explicit inclusive single-day range");
        assertTrue(requests.get(0).body().contains("<KeyRateXML xmlns=\"http://web.cbr.ru/\">"),
                "key-rate SOAP method namespace");
        assertTrue(requests.get(1).body().contains("<RuoniaXML xmlns=\"http://web.cbr.ru/\">"),
                "RUONIA SOAP method namespace");

        List<IndexFixing> keySeries = adapter.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE, KEY_DATE);
        assertEquals(KEY_DATE, keySeries.getFirst().date(), "series API returns exact fixing points");
        assertThrows(UnsupportedOperationException.class, () -> keySeries.add(key), "series result is immutable");
        MissingFixingException missing = assertThrows(MissingFixingException.class,
                () -> adapter.fixing(CbrIndexAdapter.RU_KEY_RATE, LocalDate.of(2026, 10, 4)),
                "missing exact date is not forward-filled");
        assertEquals(CbrIndexAdapter.RU_KEY_RATE, missing.index(), "missing fixing reports requested index");
        assertEquals(LocalDate.of(2026, 10, 4), missing.date(), "missing fixing reports requested date");
        assertThrows(IllegalArgumentException.class,
                () -> adapter.fixing(new IndexId("CPI"), KEY_DATE), "unsupported CBR index fails clearly");
        assertThrows(IllegalArgumentException.class,
                () -> adapter.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE.plusDays(1), KEY_DATE),
                "reversed CBR date range is rejected");
    }

    @Test
    void malformedXmlAndMissingSoapResultAreRejected() {
        CbrIndexAdapter malformed = new CbrIndexAdapter(request -> "<soap:Envelope><broken>", FIXTURE_ENDPOINT);
        assertThrows(IllegalArgumentException.class,
                () -> malformed.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "malformed SOAP XML fails");

        CbrIndexAdapter noResult = new CbrIndexAdapter(request -> "<Envelope><Body/></Envelope>", FIXTURE_ENDPOINT);
        assertThrows(IllegalStateException.class,
                () -> noResult.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "missing SOAP result is distinct");
    }

    @Test
    void soapFaultAndDoctypeExternalEntityAreRejected() {
        CbrIndexAdapter fault = new CbrIndexAdapter(request -> "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>Unavailable</faultstring>"
                + "</soap:Fault></soap:Body></soap:Envelope>", FIXTURE_ENDPOINT);
        assertThrows(CbrIndexAdapter.SoapFaultException.class,
                () -> fault.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "SOAP Fault is recognized");

        String doctype = "<!DOCTYPE soap:Envelope [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<soap:Fault><faultstring>&xxe;</faultstring></soap:Fault></soap:Body></soap:Envelope>";
        CbrIndexAdapter unsafe = new CbrIndexAdapter(request -> doctype, FIXTURE_ENDPOINT);
        assertThrows(IllegalArgumentException.class,
                () -> unsafe.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "DTD/external entity is rejected");
    }

    @Test
    void invalidFixingDateAndRateAreRejected() {
        CbrIndexAdapter invalidDate = new CbrIndexAdapter(request -> soapResult("KeyRateXML",
                "<KeyRate xmlns=\"\"><KR><DT>not-a-date</DT><Rate>14</Rate></KR></KeyRate>"), FIXTURE_ENDPOINT);
        assertThrows(IllegalArgumentException.class,
                () -> invalidDate.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "invalid CBR date is rejected");
        CbrIndexAdapter invalidRate = new CbrIndexAdapter(request -> soapResult("KeyRateXML",
                "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>unknown</Rate></KR></KeyRate>"), FIXTURE_ENDPOINT);
        assertThrows(IllegalArgumentException.class,
                () -> invalidRate.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE), "invalid CBR decimal is rejected");
    }

    @Test
    void http500SoapFaultUsesSharedStatusContract() {
        String faultBody = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>Rejected</faultstring>"
                + "</soap:Fault></soap:Body></soap:Envelope>";
        HttpStatusException status = new HttpStatusException(500, FIXTURE_ENDPOINT, faultBody);
        TransportException wrapped = new TransportException("HTTP status", status);
        CbrIndexAdapter httpFault = new CbrIndexAdapter(request -> { throw wrapped; }, FIXTURE_ENDPOINT);
        CbrIndexAdapter.SoapFaultException recognized = assertThrows(CbrIndexAdapter.SoapFaultException.class,
                () -> httpFault.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "HTTP 500 SOAP Fault remains recognizable through shared status type");
        assertTrue(recognized.getMessage().contains("Rejected"), "HTTP SOAP fault body is retained for parsing");

        TransportException nonSoapStatus = new TransportException("HTTP status",
                new HttpStatusException(503, FIXTURE_ENDPOINT, "not XML"));
        CbrIndexAdapter ordinaryHttpFailure = new CbrIndexAdapter(request -> { throw nonSoapStatus; }, FIXTURE_ENDPOINT);
        assertSame(nonSoapStatus, assertThrows(TransportException.class,
                () -> ordinaryHttpFailure.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "non-SOAP HTTP failure propagates"), "original transport error is preserved");

        TransportException htmlStatus = new TransportException("HTTP status",
                new HttpStatusException(500, FIXTURE_ENDPOINT,
                        "<!doctype html><html><body><p>Unavailable<br><p>Try later"));
        CbrIndexAdapter htmlFailure = new CbrIndexAdapter(request -> { throw htmlStatus; }, FIXTURE_ENDPOINT);
        assertSame(htmlStatus, assertThrows(TransportException.class,
                () -> htmlFailure.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "HTTP 500 HTML response remains the original HTTP error"));

        TransportException malformedSoapStatus = new TransportException("HTTP status",
                new HttpStatusException(500, FIXTURE_ENDPOINT,
                        "<soap:Envelope><soap:Body><soap:Fault><faultstring>Rejected"));
        CbrIndexAdapter malformedSoapFailure = new CbrIndexAdapter(
                request -> { throw malformedSoapStatus; }, FIXTURE_ENDPOINT);
        assertSame(malformedSoapStatus, assertThrows(TransportException.class,
                () -> malformedSoapFailure.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "malformed SOAP on HTTP 500 preserves the original HTTP error"));

        String tooDeepFaultBody = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><soap:Fault><faultstring>Rejected</faultstring></soap:Fault></soap:Body></soap:Envelope>";
        HttpStatusException tooDeepStatus = new HttpStatusException(500, FIXTURE_ENDPOINT, tooDeepFaultBody);
        TransportException tooDeepWrapped = new TransportException("HTTP status", tooDeepStatus);
        CbrProviderFactory.Configuration shallowConfig = CbrProviderFactory.Configuration.from(
                new ProviderContext(Map.of(CbrProviderFactory.MAX_XML_DEPTH_SETTING, "2")));
        CbrIndexAdapter tooDeepHttpFault = new CbrIndexAdapter(request -> { throw tooDeepWrapped; },
                shallowConfig.endpoint(), shallowConfig.maxResponseBytes(), shallowConfig.xmlLimits(),
                shallowConfig.maxSeriesRows());
        TransportException depthRejected = assertThrows(TransportException.class,
                () -> tooDeepHttpFault.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "HTTP 500 XML parse-limit failure preserves the original HTTP error");
        assertSame(tooDeepWrapped, depthRejected, "original transport status wrapper is retained");
        assertSame(tooDeepStatus, depthRejected.getCause(), "original HTTP status cause is retained");

        String overlongFaultBody = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><soap:Fault><faultstring>" + "x".repeat(1025)
                + "</faultstring></soap:Fault></soap:Body></soap:Envelope>";
        TransportException overlongFaultStatus = new TransportException("HTTP status",
                new HttpStatusException(500, FIXTURE_ENDPOINT, overlongFaultBody));
        CbrIndexAdapter overlongFault = new CbrIndexAdapter(request -> { throw overlongFaultStatus; }, FIXTURE_ENDPOINT);
        RuntimeException faultLimitFailure = assertThrows(RuntimeException.class,
                () -> overlongFault.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "HTTP SOAP Fault resource-limit failures are not swallowed as ordinary HTTP errors");
        assertTrue(faultLimitFailure instanceof IllegalArgumentException,
                "well-formed oversized SOAP Fault text is explicitly rejected");
        assertTrue(!(faultLimitFailure instanceof MissingFixingException),
                "SOAP Fault resource-limit failures are not reported as missing fixings");
    }

    @Test
    void providerFactoryIsLazyAndUsesConfiguredEndpoint() {
        List<Transport.Request> requests = new ArrayList<>();
        Transport fakeTransport = request -> {
            requests.add(request);
            return soapResult("KeyRateXML", "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT>"
                    + "<Rate>14.00</Rate></KR></KeyRate>");
        };
        CbrProviderFactory factory = new CbrProviderFactory(fakeTransport);
        assertEquals("cbr", factory.providerId(), "stable CBR provider ID");
        ProviderContext context = new ProviderContext(Map.of(
                CbrProviderFactory.ENDPOINT_SETTING, "https://fixture.invalid/custom/cbr",
                CbrProviderFactory.CONNECT_TIMEOUT_SETTING, "PT3S",
                CbrProviderFactory.REQUEST_TIMEOUT_SETTING, "PT8S"));
        IndexValueSource source = factory.create(context);
        assertEquals(0, requests.size(), "factory create does not perform network I/O");
        assertEquals(0, new BigDecimal("0.14").compareTo(source.value(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE)),
                "factory returns the shared bridge over CBR fixings");
        assertEquals(1, requests.size(), "network is used only when the capability is called");
        assertEquals(URI.create("https://fixture.invalid/custom/cbr"), requests.getFirst().uri(),
                "provider endpoint setting is applied");
        assertNotNull(source, "provider returns domain IndexValueSource capability");

        IndexValueSource productionSource = new CbrProviderFactory().create(new ProviderContext(Map.of()));
        assertNotNull(productionSource, "no-argument factory creates the production capability without fetching");
    }

    @Test
    void configurationDefaultsAndValidation() {
        CbrProviderFactory.Configuration defaults = CbrProviderFactory.Configuration.from(
                new ProviderContext(Map.of()));
        assertEquals(CbrIndexAdapter.DEFAULT_ENDPOINT, defaults.endpoint(), "default official CBR endpoint");
        assertEquals(Duration.ofSeconds(10), defaults.connectTimeout(), "default connect timeout");
        assertEquals(Duration.ofSeconds(30), defaults.requestTimeout(), "default request timeout");
        assertEquals(CbrProviderFactory.DEFAULT_MAX_RESPONSE_BYTES, defaults.maxResponseBytes(),
                "default response byte budget");
        assertEquals(CbrProviderFactory.DEFAULT_MAX_XML_DEPTH, defaults.maxXmlDepth(), "default XML depth");
        assertEquals(CbrProviderFactory.DEFAULT_MAX_XML_ATTRIBUTES, defaults.maxXmlAttributes(),
                "default XML attribute count");
        assertEquals(CbrProviderFactory.DEFAULT_MAX_XML_ATTRIBUTE_LENGTH, defaults.maxXmlAttributeLength(),
                "default XML attribute length");
        assertEquals(CbrProviderFactory.DEFAULT_MAX_XML_TEXT_LENGTH, defaults.maxXmlTextLength(),
                "default XML text length");
        assertEquals(CbrProviderFactory.DEFAULT_MAX_SERIES_ROWS, defaults.maxSeriesRows(),
                "default fixing row count");

        CbrProviderFactory.Configuration custom = CbrProviderFactory.Configuration.from(new ProviderContext(Map.of(
                CbrProviderFactory.ENDPOINT_SETTING, "http://fixture.invalid/soap",
                CbrProviderFactory.CONNECT_TIMEOUT_SETTING, "PT2.5S",
                CbrProviderFactory.REQUEST_TIMEOUT_SETTING, "PT12S")));
        assertEquals(URI.create("http://fixture.invalid/soap"), custom.endpoint(), "custom endpoint parsed");
        assertEquals(Duration.ofMillis(2500), custom.connectTimeout(), "custom connect timeout parsed");
        assertEquals(Duration.ofSeconds(12), custom.requestTimeout(), "custom request timeout parsed");

        CbrProviderFactory.Configuration customLimits = CbrProviderFactory.Configuration.from(new ProviderContext(
                Map.of(CbrProviderFactory.MAX_RESPONSE_BYTES_SETTING, "4096",
                        CbrProviderFactory.MAX_XML_DEPTH_SETTING, "24",
                        CbrProviderFactory.MAX_XML_ATTRIBUTES_SETTING, "12",
                        CbrProviderFactory.MAX_XML_ATTRIBUTE_LENGTH_SETTING, "512",
                        CbrProviderFactory.MAX_XML_TEXT_LENGTH_SETTING, "768",
                        CbrProviderFactory.MAX_SERIES_ROWS_SETTING, "1234")));
        assertEquals(4096, customLimits.maxResponseBytes(), "custom byte limit parsed");
        assertEquals(24, customLimits.maxXmlDepth(), "custom XML depth parsed");
        assertEquals(12, customLimits.maxXmlAttributes(), "custom XML attribute count parsed");
        assertEquals(512, customLimits.maxXmlAttributeLength(), "custom XML attribute length parsed");
        assertEquals(768, customLimits.maxXmlTextLength(), "custom XML text length parsed");
        assertEquals(1234, customLimits.maxSeriesRows(), "custom series row limit parsed");

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
        invalidSetting(Map.of(CbrProviderFactory.MAX_RESPONSE_BYTES_SETTING, "0"),
                "zero response byte limit rejected");
        invalidSetting(Map.of(CbrProviderFactory.MAX_RESPONSE_BYTES_SETTING, "9223372036854775808"),
                "overflowing response byte limit rejected");
        invalidSetting(Map.of(CbrProviderFactory.MAX_XML_DEPTH_SETTING, "-1"),
                "negative XML depth rejected");
        invalidSetting(Map.of(CbrProviderFactory.MAX_XML_DEPTH_SETTING, "2147483648"),
                "overflowing XML depth rejected");
        invalidSetting(Map.of(CbrProviderFactory.MAX_XML_ATTRIBUTES_SETTING, "not-a-number"),
                "non-numeric XML attribute count rejected");
        invalidSetting(Map.of(CbrProviderFactory.MAX_XML_ATTRIBUTE_LENGTH_SETTING, "0"),
                "zero XML attribute length rejected");
        invalidSetting(Map.of(CbrProviderFactory.MAX_XML_TEXT_LENGTH_SETTING, "-2"),
                "negative XML text length rejected");
        invalidSetting(Map.of(CbrProviderFactory.MAX_SERIES_ROWS_SETTING, "0"),
                "zero series row limit rejected");
        assertThrows(NullPointerException.class, () -> CbrProviderFactory.Configuration.from(null),
                "null provider context rejected");
    }

    @Test
    void fakeTransportResponseByteBudgetCountsUtf8Bytes() {
        String body = soapResultUnescaped("KeyRateXML", "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT>"
                + "<Rate>14</Rate><note>é</note></KR></KeyRate>");
        long bytes = body.getBytes(StandardCharsets.UTF_8).length;
        CbrIndexAdapter exactLimit = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_RESPONSE_BYTES_SETTING, Long.toString(bytes)), body);
        assertEquals(0, new BigDecimal("0.14").compareTo(exactLimit.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE)
                .value()), "response exactly at UTF-8 byte budget is accepted");

        CbrIndexAdapter belowLimit = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_RESPONSE_BYTES_SETTING, Long.toString(bytes - 1)), body);
        assertThrows(IllegalArgumentException.class,
                () -> belowLimit.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "fake transport response over UTF-8 byte budget is rejected before parsing");
    }

    @Test
    void xmlLimitsCoverDepthAttributesTextAndCdata() {
        String validRow = "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>14</Rate></KR></KeyRate>";
        CbrIndexAdapter exactDepth = configuredAdapter(Map.of(CbrProviderFactory.MAX_XML_DEPTH_SETTING, "7"),
                soapResultUnescaped("KeyRateXML", validRow));
        assertEquals(0, new BigDecimal("0.14").compareTo(
                exactDepth.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE).value()), "exact XML depth limit is accepted");
        CbrIndexAdapter depthLimited = configuredAdapter(Map.of(CbrProviderFactory.MAX_XML_DEPTH_SETTING, "5"),
                soapResultUnescaped("KeyRateXML", validRow));
        assertNotFixingFailure(() -> depthLimited.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "deep SOAP data must not become a missing fixing");

        CbrIndexAdapter attributeLimited = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_XML_ATTRIBUTES_SETTING, "1"),
                soapResultUnescaped("KeyRateXML", "<KeyRate xmlns=\"\"><KR first=\"1\" second=\"2\">"
                        + "<DT>2026-10-01</DT><Rate>14</Rate></KR></KeyRate>"));
        assertNotFixingFailure(() -> attributeLimited.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "attribute count over limit is explicit");
        CbrIndexAdapter exactAttributes = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_XML_ATTRIBUTES_SETTING, "2"),
                soapResultUnescaped("KeyRateXML", "<KeyRate xmlns=\"\"><KR first=\"1\" second=\"2\">"
                        + "<DT>2026-10-01</DT><Rate>14</Rate></KR></KeyRate>"));
        assertEquals(0, new BigDecimal("0.14").compareTo(
                exactAttributes.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE).value()),
                "exact XML attribute count limit is accepted");

        CbrIndexAdapter attributeLengthLimited = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_XML_ATTRIBUTE_LENGTH_SETTING, "50"),
                soapResultUnescaped("KeyRateXML", "<KeyRate xmlns=\"\"><KR ignored=\""
                        + "x".repeat(51) + "\"><DT>2026-10-01</DT><Rate>14</Rate></KR></KeyRate>"));
        assertNotFixingFailure(() -> attributeLengthLimited.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "ignored attribute over limit is explicit");
        CbrIndexAdapter exactAttributeLength = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_XML_ATTRIBUTE_LENGTH_SETTING, "50"),
                soapResultUnescaped("KeyRateXML", "<KeyRate xmlns=\"\"><KR ignored=\""
                        + "x".repeat(50) + "\"><DT>2026-10-01</DT><Rate>14</Rate></KR></KeyRate>"));
        assertEquals(0, new BigDecimal("0.14").compareTo(
                exactAttributeLength.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE).value()),
                "exact XML attribute value length is accepted");

        String cdataRow = "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>14</Rate>"
                + "<ignored><![CDATA[12345678901]]></ignored></KR></KeyRate>";
        CbrIndexAdapter textLimited = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_XML_TEXT_LENGTH_SETTING, "10"),
                soapResultUnescaped("KeyRateXML", cdataRow));
        assertNotFixingFailure(() -> textLimited.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "ignored CDATA over limit is explicit");
        String exactTextRow = "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>14</Rate>"
                + "<ignored><![CDATA[1234567890]]></ignored></KR></KeyRate>";
        CbrIndexAdapter exactText = configuredAdapter(
                Map.of(CbrProviderFactory.MAX_XML_TEXT_LENGTH_SETTING, "10"),
                soapResultUnescaped("KeyRateXML", exactTextRow));
        assertEquals(0, new BigDecimal("0.14").compareTo(
                exactText.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE).value()),
                "exact text and CDATA length limits are accepted");
    }

    @Test
    void seriesRowLimitRejectsOneOverAndAcceptsExactMaximum() {
        String one = "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>14</Rate></KR></KeyRate>";
        CbrIndexAdapter exact = configuredAdapter(Map.of(CbrProviderFactory.MAX_SERIES_ROWS_SETTING, "1"),
                soapResultUnescaped("KeyRateXML", one));
        assertEquals(1, exact.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE, KEY_DATE).size(),
                "exact series row limit is accepted");

        String two = "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>14</Rate></KR>"
                + "<KR><DT>2026-10-02</DT><Rate>14</Rate></KR></KeyRate>";
        CbrIndexAdapter over = configuredAdapter(Map.of(CbrProviderFactory.MAX_SERIES_ROWS_SETTING, "1"),
                soapResultUnescaped("KeyRateXML", two));
        assertNotFixingFailure(() -> over.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE, KEY_DATE.plusDays(1)),
                "series row limit exceeded is an explicit resource failure");
    }

    @Test
    void escapedAndInlineResultsAllowLargeAggregateTextButLimitIndividualFields() {
        String padding = "x".repeat(900);
        String inner = "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>14</Rate><ignored>" + padding
                + "</ignored></KR><KR><DT>2026-10-02</DT><Rate>14</Rate><ignored>" + padding
                + "</ignored></KR></KeyRate>";
        assertTrue(inner.length() > CbrProviderFactory.DEFAULT_MAX_XML_TEXT_LENGTH,
                "fixture has aggregate serialized text larger than the field budget");

        CbrIndexAdapter inline = new CbrIndexAdapter(request -> soapResultUnescaped("KeyRateXML", inner),
                FIXTURE_ENDPOINT);
        assertEquals(2, inline.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE, KEY_DATE.plusDays(1)).size(),
                "inline result validates individual text fields");

        CbrIndexAdapter escaped = new CbrIndexAdapter(request -> soapResult("KeyRateXML", inner), FIXTURE_ENDPOINT);
        assertEquals(2, escaped.series(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE, KEY_DATE.plusDays(1)).size(),
                "escaped embedded XML can exceed a single text-field budget");

        String tooLongField = "<KeyRate xmlns=\"\"><KR><DT>2026-10-01</DT><Rate>14</Rate><ignored>"
                + "x".repeat(CbrProviderFactory.DEFAULT_MAX_XML_TEXT_LENGTH + 1)
                + "</ignored></KR></KeyRate>";
        CbrIndexAdapter nestedOverLimit = new CbrIndexAdapter(
                request -> soapResult("KeyRateXML", tooLongField), FIXTURE_ENDPOINT);
        assertNotFixingFailure(() -> nestedOverLimit.fixing(CbrIndexAdapter.RU_KEY_RATE, KEY_DATE),
                "nested XML overlong text is rejected explicitly");
    }

    private static void assertNotFixingFailure(Runnable action, String message) {
        RuntimeException failure = assertThrows(RuntimeException.class, action::run, message);
        assertTrue(!(failure instanceof MissingFixingException), "resource limits are not missing fixings");
    }

    private static CbrIndexAdapter configuredAdapter(Map<String, String> settings, String response) {
        CbrProviderFactory.Configuration config = CbrProviderFactory.Configuration.from(new ProviderContext(settings));
        return new CbrIndexAdapter(request -> response, config.endpoint(), config.maxResponseBytes(),
                config.xmlLimits(), config.maxSeriesRows());
    }

    private static void invalidSetting(Map<String, String> settings, String message) {
        assertThrows(IllegalArgumentException.class,
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

}
