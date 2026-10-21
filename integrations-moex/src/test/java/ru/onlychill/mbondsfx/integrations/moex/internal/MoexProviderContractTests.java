package ru.onlychill.mbondsfx.integrations.moex.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.application.port.BondReference;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;
import ru.onlychill.mbondsfx.integrations.transport.Transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline whitebox checks for MOEX provider settings and sparse adaptation. */
class MoexProviderContractTests {
    @Test
    void providerConstructionAndCreationAreLazy() {
        AtomicInteger calls = new AtomicInteger();
        java.util.ArrayList<URI> requests = new java.util.ArrayList<>();
        Transport fixture = request -> {
            calls.incrementAndGet();
            requests.add(request.uri());
            if (request.uri().getPath().endsWith(".xml")
                    && !request.uri().getPath().endsWith("bondization.xml")) {
                return "<m:document xmlns:m=\"urn:moex\"><m:data id=\"description\"><m:rows>"
                        + "<m:row name=\"FACEVALUE\" value=\"1000.2500\"/>"
                        + "<m:row name=\"FACEUNIT\" value=\"USD\"/>"
                        + "<m:row name=\"UNRECOGNIZED\" value=\"2026-10-01\"/>"
                        + "</m:rows></m:data></m:document>";
            }
            String block = query(request.uri(), "iss.only");
            if ("coupons".equals(block) && "0".equals(query(request.uri(), "start"))) {
                return "<m:document xmlns:m=\"urn:moex\"><m:data id=\"coupons\"><m:rows>"
                        + coupon("2026-12-15", "9.9900", "RUB")
                        + coupon("2026-11-15", "3.141590", "EUR")
                        + "</m:rows></m:data></m:document>";
            }
            if ("offers".equals(block) && "0".equals(query(request.uri(), "start"))) {
                return "<m:document xmlns:m=\"urn:moex\"><m:data id=\"offers\"><m:rows>"
                        + "<m:row offerdate=\"0000-00-00\" offerdatestart=\"\" offerdateend=\"\""
                        + " price=\"\" value=\"\" agent=\"\" offertype=\"Оферта/Погашение\"/>"
                        + "</m:rows></m:data></m:document>";
            }
            return "<m:document xmlns:m=\"urn:moex\"><m:data id=\"" + block
                    + "\"><m:rows/></m:data></m:document>";
        };

        MoexProviderFactory factory = new MoexProviderFactory(fixture);
        assertEquals("moex", factory.providerId(), "provider ID is stable");
        assertEquals(0, calls.get(), "factory construction and ID discovery do not call transport");
        ProviderContext context = new ProviderContext(Map.of(
                MoexProviderSettings.ENDPOINT, "https://fixture.invalid/base/iss/",
                MoexProviderSettings.CONNECT_TIMEOUT, "PT2S",
                MoexProviderSettings.REQUEST_TIMEOUT, "PT7S"));
        BondReferenceDataSource source = factory.create(context);
        assertEquals(0, calls.get(), "capability construction does not call transport");

        BondReference result = source.reference(new SecurityId("TEST")).orElseThrow();
        assertTrue(calls.get() > 0, "network boundary is first crossed on a reference call");
        assertEquals(Optional.of(new BondReference.ReportedValue(new BigDecimal("1000.2500"), "USD")),
                result.faceValuePerUnit(), "face value retains exact reported decimal and unit");
        assertEquals(Optional.empty(), result.observedOn(), "unknown observation date remains absent");
        assertEquals("moex", result.sourceId(), "provenance is retained");
        assertEquals(Optional.empty(), result.nextCoupon(),
                "multiple schedule rows do not establish an explicit next coupon");
        assertTrue(requests.stream().allMatch(uri -> uri.getHost().equals("fixture.invalid")
                        && uri.getPath().startsWith("/base/iss/")),
                "configured endpoint is used for every request");
        assertEquals("PT2S", context.settings().get(MoexProviderSettings.CONNECT_TIMEOUT),
                "provider context retains explicit timeout configuration");
    }

    @Test
    void settingsAreValidatedWithoutNormalizingValues() {
        MoexProviderSettings settings = MoexProviderSettings.from(new ProviderContext(Map.of(
                MoexProviderSettings.ENDPOINT, "https://fixture.invalid/custom/iss",
                MoexProviderSettings.CONNECT_TIMEOUT, "PT2.5S",
                MoexProviderSettings.REQUEST_TIMEOUT, "PT8S",
                "cbr.endpoint", "https://other.invalid/")));
        assertEquals(URI.create("https://fixture.invalid/custom/iss"), settings.endpoint(), "endpoint is retained");
        assertEquals(Duration.ofMillis(2500), settings.connectTimeout(), "connect timeout parsed exactly");
        assertEquals(Duration.ofSeconds(8), settings.requestTimeout(), "request timeout parsed exactly");

        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(MoexProviderSettings.ENDPOINT,
                "https://fixture.invalid/iss?query=1")), "endpoint query is rejected");
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(MoexProviderSettings.ENDPOINT,
                "file:///tmp/moex")), "non-HTTP endpoint is rejected");
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(
                MoexProviderSettings.CONNECT_TIMEOUT, "PT0S")), "zero timeout is rejected");
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(
                MoexProviderSettings.REQUEST_TIMEOUT, "not-a-duration")), "malformed timeout is rejected");
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of("moex.unknown", "value")),
                "unknown MOEX-owned setting is rejected");

        AtomicInteger calls = new AtomicInteger();
        MoexProviderFactory factory = new MoexProviderFactory(request -> {
            calls.incrementAndGet();
            return "";
        });
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ProviderContext(Map.of(
                MoexProviderSettings.REQUEST_TIMEOUT, "PT0S"))), "invalid settings fail during create");
        assertEquals(0, calls.get(), "settings validation does not call transport");
    }

    @Test
    void defaultSettingsUseHttpEndpoint() {
        assertEquals(URI.create("http://iss.moex.com/iss"), settings(Map.of()).endpoint(),
                "the default endpoint uses HTTP");
    }

    @Test
    void responseLimitSettingsHaveFiniteDefaultsAndAcceptPositiveOverrides() {
        MoexProviderSettings defaults = settings(Map.of());
        assertEquals(512L * 1024, defaults.maxResponseBytes(), "default response byte budget");
        assertEquals(8, defaults.maxXmlDepth(), "default XML depth budget");
        assertEquals(32, defaults.maxXmlAttributes(), "default attributes-per-element budget");
        assertEquals(8192, defaults.maxXmlAttributeLength(), "default decoded attribute length budget");
        assertEquals(1024, defaults.maxXmlTextLength(), "default decoded text length budget");
        assertEquals(256, defaults.maxDescriptionRows(), "default description row budget");
        assertEquals(128, defaults.maxPageRows(), "default schedule page row budget");

        MoexProviderSettings custom = settings(Map.of(
                MoexProviderSettings.MAX_RESPONSE_BYTES, "4096",
                MoexProviderSettings.MAX_XML_DEPTH, "5",
                MoexProviderSettings.MAX_XML_ATTRIBUTES, "6",
                MoexProviderSettings.MAX_XML_ATTRIBUTE_LENGTH, "700",
                MoexProviderSettings.MAX_XML_TEXT_LENGTH, "80",
                MoexProviderSettings.MAX_DESCRIPTION_ROWS, "7",
                MoexProviderSettings.MAX_PAGE_ROWS, "9"));
        assertEquals(4096L, custom.maxResponseBytes(), "custom byte budget retained");
        assertEquals(5, custom.maxXmlDepth(), "custom XML depth retained");
        assertEquals(6, custom.maxXmlAttributes(), "custom attribute count retained");
        assertEquals(700, custom.maxXmlAttributeLength(), "custom attribute length retained");
        assertEquals(80, custom.maxXmlTextLength(), "custom text length retained");
        assertEquals(7, custom.maxDescriptionRows(), "custom description row count retained");
        assertEquals(9, custom.maxPageRows(), "custom page row count retained");

        Map<String, String> limitKeys = Map.of(
                MoexProviderSettings.MAX_RESPONSE_BYTES, "0",
                MoexProviderSettings.MAX_XML_DEPTH, "0",
                MoexProviderSettings.MAX_XML_ATTRIBUTES, "0",
                MoexProviderSettings.MAX_XML_ATTRIBUTE_LENGTH, "0",
                MoexProviderSettings.MAX_XML_TEXT_LENGTH, "0",
                MoexProviderSettings.MAX_DESCRIPTION_ROWS, "0",
                MoexProviderSettings.MAX_PAGE_ROWS, "0");
        limitKeys.forEach((key, value) -> assertThrows(IllegalArgumentException.class,
                () -> settings(Map.of(key, value)), key + " must reject nonpositive limits"));

        AtomicInteger requests = new AtomicInteger();
        MoexProviderFactory factory = new MoexProviderFactory(request -> {
            requests.incrementAndGet();
            return "";
        });
        limitKeys.keySet().forEach(key -> assertThrows(IllegalArgumentException.class,
                () -> factory.create(new ProviderContext(Map.of(key, "0"))),
                key + " is validated before provider creation completes"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new ProviderContext(Map.of(
                        MoexProviderSettings.MAX_RESPONSE_BYTES, "9223372036854775808"))),
                "byte limit overflow is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new ProviderContext(Map.of(MoexProviderSettings.MAX_XML_DEPTH, "2147483648"))),
                "XML limit overflow is rejected");
        assertEquals(0, requests.get(), "invalid limit settings fail before any transport request");
    }

    @Test
    void customLimitsReachAdapterWhenFakeTransportBypassesHttpGuard() {
        AtomicInteger calls = new AtomicInteger();
        MoexProviderFactory factory = new MoexProviderFactory(request -> {
            calls.incrementAndGet();
            return "<document><data id=\"description\"><rows>"
                    + "<row name=\"ONE\" value=\"1\"/><row name=\"TWO\" value=\"2\"/>"
                    + "</rows></data></document>";
        });
        BondReferenceDataSource source = factory.create(new ProviderContext(Map.of(
                MoexProviderSettings.MAX_RESPONSE_BYTES, "4096",
                MoexProviderSettings.MAX_DESCRIPTION_ROWS, "1")));

        assertEquals(0, calls.get(), "custom limits are configured without sending a request");
        assertThrows(IllegalArgumentException.class,
                () -> source.reference(new SecurityId("LIMITED")),
                "the configured row maximum is applied to a fake transport response");
        assertEquals(1, calls.get(), "the overflowing description is rejected on its first response");

        AtomicInteger byteCalls = new AtomicInteger();
        BondReferenceDataSource byteLimitedSource = new MoexProviderFactory(request -> {
            byteCalls.incrementAndGet();
            return "larger than one byte";
        }).create(new ProviderContext(Map.of(MoexProviderSettings.MAX_RESPONSE_BYTES, "1")));
        assertThrows(IllegalArgumentException.class,
                () -> byteLimitedSource.reference(new SecurityId("BYTE")),
                "the adapter enforces configured byte limits even for a fake Transport");
        assertEquals(1, byteCalls.get(), "fake transport is called once before its response is rejected");

        String depthLimitedXml = "<document><data id=\"description\"><rows>"
                + "<row name=\"FIELD\" value=\"1\"/></rows></data></document>";
        BondReferenceDataSource depthLimitedSource = new MoexProviderFactory(request -> depthLimitedXml)
                .create(new ProviderContext(Map.of(MoexProviderSettings.MAX_RESPONSE_BYTES, "4096",
                        MoexProviderSettings.MAX_XML_DEPTH, "3")));
        assertThrows(IllegalArgumentException.class,
                () -> depthLimitedSource.reference(new SecurityId("DEPTH")),
                "the configured XML depth is passed to parsing for a fake transport response");
    }

    @Test
    void reportedValuesMapExactlyAndUnknownsStaySparse() {
        BondMarketData raw = new BondMarketData(
                Map.of("FACEVALUE", "42.5000", "FACEUNIT", "GBP", "FUTURE_FIELD", "preserved"),
                List.of(
                        couponRow("2020-01-10", "0.0100"),
                        couponRow("2026-10-25", "0.012300"),
                        couponRow("2030-01-10", "0.0200")),
                List.of(), List.of(new BondMarketData.OfferRow(Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("Оферта/Погашение"))));
        BondReference reference = new MoexBondReferenceDataSource(security -> Optional.of(raw))
                .reference(new SecurityId("RAW")).orElseThrow();
        assertEquals(new BigDecimal("42.5000"), reference.faceValuePerUnit().orElseThrow().value(),
                "BigDecimal scale in face value is retained");
        assertEquals("GBP", reference.faceValuePerUnit().orElseThrow().unit(), "reported face unit is retained");
        assertEquals(Optional.empty(), reference.nextCoupon(),
                "historical/future coupons and an undated sentinel offer do not identify the next coupon");
        assertEquals(Optional.empty(), reference.observedOn(), "arbitrary description facts are not dates by inference");
    }

    @Test
    void incompletePairsAndUnknownSecuritiesStayAbsent() {
        SecurityId security = new SecurityId("PARTIAL");
        BondMarketData raw = new BondMarketData(Map.of("FACEVALUE", "100.00"),
                List.of(new BondMarketData.CouponRow(LocalDate.of(2027, 1, 10), Optional.empty(), Optional.empty(),
                        new BigDecimal("100"), "RUB", Optional.empty(), Optional.empty(), Optional.empty())),
                List.of(), List.of());
        BondReference reference = new MoexBondReferenceDataSource(requested -> Optional.of(raw))
                .reference(security).orElseThrow();
        assertEquals(Optional.empty(), reference.faceValuePerUnit(), "face value without unit is absent as a whole");
        assertEquals(Optional.empty(), reference.nextCoupon(), "coupon schedule alone does not identify a next coupon");

        BondReferenceDataSource absent = new MoexBondReferenceDataSource(requested -> Optional.empty());
        assertEquals(Optional.empty(), absent.reference(security), "unknown security stays absent");
        assertThrows(NullPointerException.class, () -> absent.reference(null), "null security is rejected");
    }

    private static MoexProviderSettings settings(Map<String, String> values) {
        return MoexProviderSettings.from(new ProviderContext(values));
    }

    private static String coupon(String date, String value, String unit) {
        return "<m:row coupondate=\"" + date + "\" facevalue=\"1000\" faceunit=\"" + unit
                + "\" value=\"" + value + "\"/>";
    }

    private static BondMarketData.CouponRow couponRow(String date, String value) {
        return new BondMarketData.CouponRow(LocalDate.parse(date), Optional.empty(), Optional.empty(),
                new BigDecimal("1000"), "GBP", Optional.of(new BigDecimal(value)), Optional.empty(), Optional.empty());
    }

    private static String query(URI uri, String key) {
        for (String param : uri.getRawQuery().split("&")) {
            String[] pair = param.split("=", 2);
            if (pair[0].equals(key)) {
                return pair.length == 1 ? "" : pair[1];
            }
        }
        return "";
    }

}
