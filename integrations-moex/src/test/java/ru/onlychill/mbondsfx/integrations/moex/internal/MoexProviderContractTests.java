package ru.onlychill.mbondsfx.integrations.moex.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import ru.onlychill.mbondsfx.application.port.BondReference;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;
import ru.onlychill.mbondsfx.integrations.transport.Transport;

/** Offline whitebox checks for MOEX provider settings and sparse adaptation. */
public final class MoexProviderContractTests {
    private static int checks;

    private MoexProviderContractTests() {
    }

    public static void main(String[] args) {
        providerConstructionAndCreationAreLazy();
        settingsAreValidatedWithoutNormalizingValues();
        reportedValuesMapExactlyAndUnknownsStaySparse();
        incompletePairsAndUnknownSecuritiesStayAbsent();
        System.out.println("MoexProviderContractTests: " + checks + " checks passed");
    }

    private static void providerConstructionAndCreationAreLazy() {
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
        equal("moex", factory.providerId(), "provider ID is stable");
        equal(0, calls.get(), "factory construction and ID discovery do not call transport");
        ProviderContext context = new ProviderContext(Map.of(
                MoexProviderSettings.ENDPOINT, "https://fixture.invalid/base/iss/",
                MoexProviderSettings.CONNECT_TIMEOUT, "PT2S",
                MoexProviderSettings.REQUEST_TIMEOUT, "PT7S"));
        BondReferenceDataSource source = factory.create(context);
        equal(0, calls.get(), "capability construction does not call transport");

        BondReference result = source.reference(new SecurityId("TEST")).orElseThrow();
        check(calls.get() > 0, "network boundary is first crossed on a reference call");
        equal(Optional.of(new BondReference.ReportedValue(new BigDecimal("1000.2500"), "USD")),
                result.faceValuePerUnit(), "face value retains exact reported decimal and unit");
        equal(Optional.empty(), result.observedOn(), "unknown observation date remains absent");
        equal("moex", result.sourceId(), "provenance is retained");
        equal(Optional.empty(), result.nextCoupon(),
                "multiple schedule rows do not establish an explicit next coupon");
        check(requests.stream().allMatch(uri -> uri.getHost().equals("fixture.invalid")
                        && uri.getPath().startsWith("/base/iss/")),
                "configured endpoint is used for every request");
        equal("PT2S", context.settings().get(MoexProviderSettings.CONNECT_TIMEOUT),
                "provider context retains explicit timeout configuration");
    }

    private static void settingsAreValidatedWithoutNormalizingValues() {
        MoexProviderSettings settings = MoexProviderSettings.from(new ProviderContext(Map.of(
                MoexProviderSettings.ENDPOINT, "https://fixture.invalid/custom/iss",
                MoexProviderSettings.CONNECT_TIMEOUT, "PT2.5S",
                MoexProviderSettings.REQUEST_TIMEOUT, "PT8S",
                "cbr.endpoint", "https://other.invalid/")));
        equal(URI.create("https://fixture.invalid/custom/iss"), settings.endpoint(), "endpoint is retained");
        equal(Duration.ofMillis(2500), settings.connectTimeout(), "connect timeout parsed exactly");
        equal(Duration.ofSeconds(8), settings.requestTimeout(), "request timeout parsed exactly");

        expect(IllegalArgumentException.class, () -> settings(Map.of(MoexProviderSettings.ENDPOINT,
                "https://fixture.invalid/iss?query=1")), "endpoint query is rejected");
        expect(IllegalArgumentException.class, () -> settings(Map.of(MoexProviderSettings.ENDPOINT,
                "file:///tmp/moex")), "non-HTTP endpoint is rejected");
        expect(IllegalArgumentException.class, () -> settings(Map.of(
                MoexProviderSettings.CONNECT_TIMEOUT, "PT0S")), "zero timeout is rejected");
        expect(IllegalArgumentException.class, () -> settings(Map.of(
                MoexProviderSettings.REQUEST_TIMEOUT, "not-a-duration")), "malformed timeout is rejected");
        expect(IllegalArgumentException.class, () -> settings(Map.of("moex.unknown", "value")),
                "unknown MOEX-owned setting is rejected");

        AtomicInteger calls = new AtomicInteger();
        MoexProviderFactory factory = new MoexProviderFactory(request -> {
            calls.incrementAndGet();
            return "";
        });
        expect(IllegalArgumentException.class, () -> factory.create(new ProviderContext(Map.of(
                MoexProviderSettings.REQUEST_TIMEOUT, "PT0S"))), "invalid settings fail during create");
        equal(0, calls.get(), "settings validation does not call transport");
    }

    private static void reportedValuesMapExactlyAndUnknownsStaySparse() {
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
        equal(new BigDecimal("42.5000"), reference.faceValuePerUnit().orElseThrow().value(),
                "BigDecimal scale in face value is retained");
        equal("GBP", reference.faceValuePerUnit().orElseThrow().unit(), "reported face unit is retained");
        equal(Optional.empty(), reference.nextCoupon(),
                "historical/future coupons and an undated sentinel offer do not identify the next coupon");
        equal(Optional.empty(), reference.observedOn(), "arbitrary description facts are not dates by inference");
    }

    private static void incompletePairsAndUnknownSecuritiesStayAbsent() {
        SecurityId security = new SecurityId("PARTIAL");
        BondMarketData raw = new BondMarketData(Map.of("FACEVALUE", "100.00"),
                List.of(new BondMarketData.CouponRow(LocalDate.of(2027, 1, 10), Optional.empty(), Optional.empty(),
                        new BigDecimal("100"), "RUB", Optional.empty(), Optional.empty(), Optional.empty())),
                List.of(), List.of());
        BondReference reference = new MoexBondReferenceDataSource(requested -> Optional.of(raw))
                .reference(security).orElseThrow();
        equal(Optional.empty(), reference.faceValuePerUnit(), "face value without unit is absent as a whole");
        equal(Optional.empty(), reference.nextCoupon(), "coupon schedule alone does not identify a next coupon");

        BondReferenceDataSource absent = new MoexBondReferenceDataSource(requested -> Optional.empty());
        equal(Optional.empty(), absent.reference(security), "unknown security stays absent");
        expect(NullPointerException.class, () -> absent.reference(null), "null security is rejected");
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

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action, String message) {
        checks++;
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + ": expected " + expected.getSimpleName() + " but caught " + thrown,
                    thrown);
        }
        throw new AssertionError(message + ": expected " + expected.getSimpleName());
    }
}
