package ru.onlychill.mbondsfx.integrations.moex;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.marketdata.model.BondMarketData;
import ru.onlychill.mbondsfx.integrations.moex.internal.MoexBondAdapter;
import ru.onlychill.mbondsfx.integrations.transport.Transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline contract checks for adapters; all network boundaries are replaced with fakes. */
class AdapterContractTests {
    private static final URI MOEX_FIXTURE = URI.create("https://fixture.invalid/iss");
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    @Test
    void independentPaginationAndRawValues() {
        PagingTransport transport = new PagingTransport();
        MoexBondAdapter adapter = new MoexBondAdapter(transport, MOEX_FIXTURE);
        BondMarketData data = adapter.bond(new SecurityId("SU26238RMFS4")).orElseThrow();

        assertEquals(41, data.coupons().size(), "all coupons paged");
        assertEquals(22, data.amortizations().size(), "all amortizations paged");
        assertEquals(21, data.offers().size(), "all offers paged");
        assertEquals("raw unknown value", data.description().get("FUTURE_FIELD"), "raw unknown description retained");
        assertEquals(Optional.empty(), data.coupons().getFirst().value(), "empty coupon value absent, not zero");
        assertEquals(Optional.empty(), data.coupons().getFirst().registryDate(), "absent record date");
        assertEquals(Optional.empty(), data.coupons().getFirst().valuePercent(), "empty coupon percent");
        assertEquals("amortization", data.amortizations().getFirst().source(), "raw amortization source");
        BondMarketData.OfferRow sentinelOffer = data.offers().stream()
                .filter(offer -> offer.offerType().equals(Optional.of("Оферта/Погашение")))
                .findFirst().orElseThrow();
        assertEquals(Optional.empty(), sentinelOffer.offerDate(), "sentinel offer date absent");
        assertEquals(Optional.of("Оферта"), data.offers().stream()
                .filter(offer -> offer.offerType().equals(Optional.of("Оферта")))
                .findFirst().orElseThrow().offerType(), "raw offer type retained");

        assertEquals(List.of(0, 20, 40, 41), transport.offsets("coupons"), "coupon offset follows actual count");
        assertEquals(List.of(0, 20, 22), transport.offsets("amortizations"), "amortization offset follows actual count");
        assertEquals(List.of(0, 20, 21), transport.offsets("offers"), "offer offset follows actual count");
        assertTrue(transport.requests.stream().filter(request -> request.uri().getPath().endsWith("bondization.xml"))
                .allMatch(request -> request.uri().getRawQuery().contains("limit=20")),
                "each schedule request explicitly asks for limit=20");
    }

    @Test
    void unknownMalformedRepeatedAndInjectionCases() {
        Transport unknown = request -> "<document><data id=\"description\"><rows/></data></document>";
        assertTrue(new MoexBondAdapter(unknown, MOEX_FIXTURE).bond(new SecurityId("ZZZZNOPE")).isEmpty(),
                "empty description rows identify an unknown security");

        Transport malformed = request -> "<document><data id='description'>";
        assertThrows(IllegalArgumentException.class,
                () -> new MoexBondAdapter(malformed, MOEX_FIXTURE).bond(new SecurityId("BAD")),
                "malformed XML is not interpreted as unknown");

        Transport missingBlock = request -> "<document><data id=\"boards\"><rows/></data></document>";
        assertThrows(IllegalStateException.class,
                () -> new MoexBondAdapter(missingBlock, MOEX_FIXTURE).bond(new SecurityId("BAD")),
                "missing description block is distinct from empty rows");

        AtomicInteger couponCalls = new AtomicInteger();
        Transport repeated = request -> {
            if (request.uri().getPath().endsWith("BAD.xml")) {
                return descriptionXml();
            }
            String block = query(request.uri(), "iss.only");
            if ("coupons".equals(block)) {
                couponCalls.incrementAndGet();
                return pageXml(block, List.of(couponRow(1, "1")));
            }
            throw new AssertionError("Adapter continued after repeated coupon page");
        };
        assertThrows(IllegalStateException.class,
                () -> new MoexBondAdapter(repeated, MOEX_FIXTURE).bond(new SecurityId("BAD")),
                "repeated server page fails instead of truncating or looping");
        assertEquals(2, couponCalls.get(), "repeat is checked on next page");

        AtomicInteger sends = new AtomicInteger();
        Transport noCall = request -> {
            sends.incrementAndGet();
            return "";
        };
        assertThrows(IllegalArgumentException.class,
                () -> new MoexBondAdapter(noCall, MOEX_FIXTURE)
                        .bond(new SecurityId("../securities/ATTACK.xml?iss.only=description")),
                "SECID cannot inject path or query parameters");
        assertEquals(0, sends.get(), "invalid SECID rejected before transport");
    }

    @Test
    void rejectsDoctypeAndExternalEntities() {
        String doctype = "<!DOCTYPE data [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<document><data id=\"description\"><rows><row name=\"x\" value=\"&xxe;\"/>"
                + "</rows></data></document>";
        assertThrows(IllegalArgumentException.class,
                () -> new MoexBondAdapter(request -> doctype, MOEX_FIXTURE)
                        .bond(new SecurityId("XXE")), "DOCTYPE and external entities rejected");
    }

    private static String descriptionXml() {
        return "<document><data id=\"description\"><rows>"
                + "<row name=\"SECID\" value=\"TEST\"/><row name=\"FUTURE_FIELD\" value=\"raw unknown value\"/>"
                + "</rows></data></document>";
    }

    private static String pageXml(String block, List<String> rows) {
        return "<document><data id=\"" + block + "\"><rows>" + String.join("", rows)
                + "</rows></data></document>";
    }

    private static String couponRow(int number, String value) {
        return "<row coupondate=\"" + START.plusDays(number) + "\" startdate=\"2025-12-01\""
                + (number == 1 ? "" : " recorddate=\"2025-12-02\"")
                + " facevalue=\"1000\" faceunit=\"RUB\" value=\"" + value
                + "\" valueprc=\"" + (number == 1 ? "" : "7.1")
                + "\" value_rub=\"" + (number == 1 ? "" : "34.04") + "\"" + (number == 1 ? " note=\"raw\"" : "")
                + "/>";
    }

    private static String amortizationRow(int number) {
        return "<row amortdate=\"" + START.plusDays(number) + "\" facevalue=\"1000\""
                + " initialfacevalue=\"1000\" faceunit=\"RUB\" valueprc=\"100\""
                + " value=\"1000\" value_rub=\"1000\" data_source=\""
                + (number == 1 ? "amortization" : "maturity") + "\"/>";
    }

    private static String offerRow(int number) {
        String date = number == 1 ? "0000-00-00" : START.plusDays(number).toString();
        return "<row offerdate=\"" + date + "\" offerdatestart=\"\" offerdateend=\"\""
                + " price=\"\" value=\"1000\" agent=\"Agent\" offertype=\""
                + (number == 1 ? "Оферта/Погашение" : "Оферта") + "\"/>";
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

    private static final class PagingTransport implements Transport {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public String send(Request request) {
            requests.add(request);
            String path = request.uri().getPath();
            if (path.endsWith(".xml") && !path.endsWith("bondization.xml")) {
                return descriptionXml();
            }
            String block = query(request.uri(), "iss.only");
            int offset = Integer.parseInt(query(request.uri(), "start"));
            int count = switch (block) {
                case "coupons" -> 41;
                case "amortizations" -> 22;
                case "offers" -> 21;
                default -> throw new AssertionError("Unexpected schedule block " + block);
            };
            List<String> rows = new ArrayList<>();
            int end = Math.min(offset + 20, count);
            for (int row = offset; row < end; row++) {
                int number = row + 1;
                rows.add(switch (block) {
                    case "coupons" -> couponRow(number, number == 1 ? "" : Integer.toString(number));
                    case "amortizations" -> amortizationRow(number);
                    case "offers" -> offerRow(number);
                    default -> throw new AssertionError("Unexpected block");
                });
            }
            return pageXml(block, rows);
        }

        List<Integer> offsets(String block) {
            return requests.stream()
                    .filter(request -> request.uri().getPath().endsWith("bondization.xml"))
                    .filter(request -> block.equals(query(request.uri(), "iss.only")))
                    .map(request -> Integer.parseInt(query(request.uri(), "start")))
                    .toList();
        }
    }


}
