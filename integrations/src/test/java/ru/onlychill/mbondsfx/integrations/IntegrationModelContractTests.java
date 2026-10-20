package ru.onlychill.mbondsfx.integrations;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.bond.BondDefinition;
import ru.onlychill.mbondsfx.core.bond.IndexationRule;
import ru.onlychill.mbondsfx.core.bond.Principal;
import ru.onlychill.mbondsfx.core.bond.RedemptionOptions;
import ru.onlychill.mbondsfx.core.bond.calculation.BondCalculations;
import ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.Money;
import ru.onlychill.mbondsfx.integrations.adapter.IndexValueSourceAdapter;
import ru.onlychill.mbondsfx.integrations.adapter.StaticFixings;
import ru.onlychill.mbondsfx.integrations.index.IndexFixing;
import ru.onlychill.mbondsfx.integrations.index.IndexFixingSource;
import ru.onlychill.mbondsfx.integrations.index.MissingFixingException;
import ru.onlychill.mbondsfx.integrations.transport.Transport;
import ru.onlychill.mbondsfx.integrations.transport.TransportException;

public final class IntegrationModelContractTests {
    private static int checks;

    private IntegrationModelContractTests() {
    }

    public static void main(String[] args) {
        testIndexFixingContracts();
        testIndexValueSourceBridge();
        testBridgeBackedCalculation();
        testTransport();
        System.out.println("IntegrationModelContractTests: " + checks + " checks passed");
    }

    private static void testIndexFixingContracts() {
        IndexId index = new IndexId("RU_KEY_RATE");
        LocalDate fixingDate = LocalDate.of(2024, 6, 14);
        IndexFixing expected = new IndexFixing(index, fixingDate, new BigDecimal("0.14"));
        IndexFixingSource exactDateSource = (requestedIndex, date) -> {
            if (expected.index().equals(requestedIndex) && expected.date().equals(date)) {
                return expected;
            }
            throw new MissingFixingException(requestedIndex, date);
        };
        check(exactDateSource.fixing(index, fixingDate).equals(expected), "exact-date index fixing lookup");
        MissingFixingException missing = expectThrows(MissingFixingException.class,
                () -> exactDateSource.fixing(index, fixingDate.plusDays(1)), "missing exact-date fixing throws");
        check(missing.index().equals(index), "missing fixing exposes index");
        check(missing.date().equals(fixingDate.plusDays(1)), "missing fixing exposes date");
        expectThrows(NullPointerException.class, () -> new IndexFixing(index, fixingDate, null),
                "null fixing value rejected");
    }

    private static void testIndexValueSourceBridge() {
        IndexId requestedIndex = new IndexId("CPI");
        LocalDate requestedDate = LocalDate.of(2024, 6, 14);
        BigDecimal precise = new BigDecimal("0.123400");
        IndexFixing validFixing = new IndexFixing(requestedIndex, requestedDate, precise);
        IndexFixingSource recordingSource = (index, date) -> {
            check(index.equals(requestedIndex), "bridge delegates the requested index");
            check(date.equals(requestedDate), "bridge delegates the requested date");
            return validFixing;
        };
        BigDecimal value = new IndexValueSourceAdapter(recordingSource).value(requestedIndex, requestedDate);
        check(value.equals(precise), "bridge preserves precise decimal value and scale");
        expectThrows(NullPointerException.class, () -> new IndexValueSourceAdapter(null),
                "null fixing source rejected");

        MissingIndexValueException nullResult = expectThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> null).value(requestedIndex, requestedDate),
                "null fixing result becomes missing domain value");
        check(nullResult.index().equals(requestedIndex) && nullResult.date().equals(requestedDate),
                "null result error identifies requested index/date");

        MissingIndexValueException wrongIndex = expectThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> new IndexFixing(
                        new IndexId("OTHER"), date, BigDecimal.ONE)).value(requestedIndex, requestedDate),
                "wrong fixing index becomes missing domain value");
        check(wrongIndex.index().equals(requestedIndex) && wrongIndex.date().equals(requestedDate),
                "wrong-index error identifies requested index/date");
        MissingIndexValueException wrongDate = expectThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> new IndexFixing(
                        index, date.plusDays(1), BigDecimal.ONE)).value(requestedIndex, requestedDate),
                "wrong fixing date becomes missing domain value");
        check(wrongDate.index().equals(requestedIndex) && wrongDate.date().equals(requestedDate),
                "wrong-date error identifies requested index/date");

        MissingFixingException matchingMissing = new MissingFixingException(requestedIndex, requestedDate);
        MissingIndexValueException translatedMatching = expectThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw matchingMissing; })
                        .value(requestedIndex, requestedDate), "external missing fixing is translated");
        check(translatedMatching.index().equals(requestedIndex) && translatedMatching.date().equals(requestedDate),
                "translated missing fixing identifies requested index/date");
        check(translatedMatching.getCause() == matchingMissing, "translated missing fixing retains cause");

        MissingFixingException mismatchedMissing = new MissingFixingException(
                new IndexId("OTHER"), requestedDate.plusDays(2));
        MissingIndexValueException translatedMismatched = expectThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw mismatchedMissing; })
                        .value(requestedIndex, requestedDate), "external missing metadata is normalized");
        check(translatedMismatched.index().equals(requestedIndex)
                        && translatedMismatched.date().equals(requestedDate),
                "mismatched external missing metadata reports requested index/date");
        check(translatedMismatched.getCause() == mismatchedMissing,
                "mismatched external missing metadata retains cause");

        IllegalArgumentException unsupported = new IllegalArgumentException("unsupported index");
        check(expectThrows(IllegalArgumentException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw unsupported; })
                        .value(requestedIndex, requestedDate), "unsupported index failure propagates") == unsupported,
                "unsupported-index exception is not translated");
        TransportException transport = new TransportException("transport failure");
        check(expectThrows(TransportException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw transport; })
                        .value(requestedIndex, requestedDate), "transport failure propagates") == transport,
                "transport exception is not translated");
    }

    private static void testBridgeBackedCalculation() {
        Currency rub = Currency.getInstance("RUB");
        IndexId index = new IndexId("CPI");
        LocalDate issue = LocalDate.of(2024, 1, 1);
        Principal principal = new Principal(new Money(new BigDecimal("1000"), rub), List.of(),
                Optional.of(new IndexationRule(index, new BigDecimal("100"), issue, 0)));
        BondDefinition bond = new BondDefinition(new SecurityId("INDEXED"), rub, issue,
                Optional.of(issue.plusYears(1)), Optional.empty(), principal, RedemptionOptions.none());
        IndexValueSource values = new IndexValueSourceAdapter(StaticFixings.of(
                new IndexFixing(index, issue, new BigDecimal("105.0000"))));
        check(BondCalculations.facePerUnit(bond, issue, values).equals(new Money(new BigDecimal("1050"), rub)),
                "indexed calculation works through StaticFixings and the explicit bridge");

        MissingIndexValueException wrongIndex = expectThrows(MissingIndexValueException.class,
                () -> BondCalculations.facePerUnit(bond, issue, new IndexValueSourceAdapter(
                        (requestedIndex, date) -> new IndexFixing(new IndexId("WRONG"), date,
                                new BigDecimal("105")))),
                "indexed calculation rejects a fixing for another index");
        check(wrongIndex.index().equals(index) && wrongIndex.date().equals(issue),
                "indexed calculation reports the requested index/date for a wrong DTO");
    }

    private static void testTransport() {
        URI uri = URI.create("https://example.invalid/data");
        Transport.Request get = Transport.Request.get(uri);
        check(get.method().equals("GET"), "GET factory method");
        check(get.uri().equals(uri), "GET factory URI");
        check(get.headers().isEmpty() && get.body().isEmpty(), "GET factory has empty headers and body");

        String xml = "<request>Привет</request>";
        Transport.Request post = Transport.Request.post(uri, xml);
        check(post.method().equals("POST"), "POST factory method");
        check(post.headers().get("Content-Type").equals("text/xml; charset=utf-8"), "POST UTF-8 XML content type");
        check(post.body().equals(xml), "POST preserves UTF-8 text body");

        Map<String, String> suppliedHeaders = new java.util.HashMap<>();
        suppliedHeaders.put("X-Test", "before");
        Transport.Request copied = new Transport.Request("PUT", uri, suppliedHeaders, "body");
        suppliedHeaders.put("X-Test", "after");
        check(copied.headers().get("X-Test").equals("before"), "request defensively copies headers");
        expectThrows(UnsupportedOperationException.class,
                () -> copied.headers().put("X-Other", "value"), "request headers are immutable");
        expectThrows(NullPointerException.class, () -> new Transport.Request("GET", uri, null, ""),
                "null request headers rejected");
        expectThrows(IllegalArgumentException.class,
                () -> new Transport.Request(" ", uri, Map.of(), ""), "blank request method rejected");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> T expectThrows(Class<T> expectedType, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expectedType.isInstance(thrown)) {
                checks++;
                return expectedType.cast(thrown);
            }
            throw new AssertionError(message + ": expected " + expectedType.getName()
                    + " but got " + thrown.getClass().getName(), thrown);
        }
        throw new AssertionError(message + ": expected " + expectedType.getName());
    }
}
