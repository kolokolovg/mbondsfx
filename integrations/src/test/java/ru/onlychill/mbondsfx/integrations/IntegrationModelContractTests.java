package ru.onlychill.mbondsfx.integrations;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationModelContractTests {
    @Test
    void indexFixingContracts() {
        IndexId index = new IndexId("RU_KEY_RATE");
        LocalDate fixingDate = LocalDate.of(2024, 6, 14);
        IndexFixing expected = new IndexFixing(index, fixingDate, new BigDecimal("0.14"));
        IndexFixingSource exactDateSource = (requestedIndex, date) -> {
            if (expected.index().equals(requestedIndex) && expected.date().equals(date)) {
                return expected;
            }
            throw new MissingFixingException(requestedIndex, date);
        };
        assertEquals(expected, exactDateSource.fixing(index, fixingDate), "exact-date index fixing lookup");
        MissingFixingException missing = assertThrows(MissingFixingException.class,
                () -> exactDateSource.fixing(index, fixingDate.plusDays(1)), "missing exact-date fixing throws");
        assertEquals(index, missing.index(), "missing fixing exposes index");
        assertEquals(fixingDate.plusDays(1), missing.date(), "missing fixing exposes date");
        assertThrows(NullPointerException.class, () -> new IndexFixing(index, fixingDate, null),
                "null fixing value rejected");
    }

    @Test
    void indexValueSourceBridgePreservesMetadataAndPrecision() {
        IndexId requestedIndex = new IndexId("CPI");
        LocalDate requestedDate = LocalDate.of(2024, 6, 14);
        BigDecimal precise = new BigDecimal("0.123400");
        IndexFixing validFixing = new IndexFixing(requestedIndex, requestedDate, precise);
        IndexFixingSource recordingSource = (index, date) -> {
            assertEquals(requestedIndex, index, "bridge delegates the requested index");
            assertEquals(requestedDate, date, "bridge delegates the requested date");
            return validFixing;
        };
        BigDecimal value = new IndexValueSourceAdapter(recordingSource).value(requestedIndex, requestedDate);
        assertEquals(precise, value, "bridge preserves precise decimal value and scale");
        assertThrows(NullPointerException.class, () -> new IndexValueSourceAdapter(null),
                "null fixing source rejected");

        MissingIndexValueException nullResult = assertThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> null).value(requestedIndex, requestedDate),
                "null fixing result becomes missing domain value");
        assertEquals(requestedIndex, nullResult.index(), "null result error identifies requested index");
        assertEquals(requestedDate, nullResult.date(), "null result error identifies requested date");

        MissingIndexValueException wrongIndex = assertThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> new IndexFixing(
                        new IndexId("OTHER"), date, BigDecimal.ONE)).value(requestedIndex, requestedDate),
                "wrong fixing index becomes missing domain value");
        assertEquals(requestedIndex, wrongIndex.index(), "wrong-index error identifies requested index");
        assertEquals(requestedDate, wrongIndex.date(), "wrong-index error identifies requested date");

        MissingIndexValueException wrongDate = assertThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> new IndexFixing(
                        index, date.plusDays(1), BigDecimal.ONE)).value(requestedIndex, requestedDate),
                "wrong fixing date becomes missing domain value");
        assertEquals(requestedIndex, wrongDate.index(), "wrong-date error identifies requested index");
        assertEquals(requestedDate, wrongDate.date(), "wrong-date error identifies requested date");
    }

    @Test
    void indexValueSourceBridgeTranslatesMissingValuesAndPropagatesOtherFailures() {
        IndexId requestedIndex = new IndexId("CPI");
        LocalDate requestedDate = LocalDate.of(2024, 6, 14);

        MissingFixingException matchingMissing = new MissingFixingException(requestedIndex, requestedDate);
        MissingIndexValueException translatedMatching = assertThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw matchingMissing; })
                        .value(requestedIndex, requestedDate), "external missing fixing is translated");
        assertEquals(requestedIndex, translatedMatching.index(), "translated missing fixing index");
        assertEquals(requestedDate, translatedMatching.date(), "translated missing fixing date");
        assertSame(matchingMissing, translatedMatching.getCause(), "translated missing fixing retains cause");

        MissingFixingException mismatchedMissing = new MissingFixingException(
                new IndexId("OTHER"), requestedDate.plusDays(2));
        MissingIndexValueException translatedMismatched = assertThrows(MissingIndexValueException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw mismatchedMissing; })
                        .value(requestedIndex, requestedDate), "external missing metadata is normalized");
        assertEquals(requestedIndex, translatedMismatched.index(),
                "mismatched external missing metadata reports requested index");
        assertEquals(requestedDate, translatedMismatched.date(),
                "mismatched external missing metadata reports requested date");
        assertSame(mismatchedMissing, translatedMismatched.getCause(),
                "mismatched external missing metadata retains cause");

        IllegalArgumentException unsupported = new IllegalArgumentException("unsupported index");
        assertSame(unsupported, assertThrows(IllegalArgumentException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw unsupported; })
                        .value(requestedIndex, requestedDate), "unsupported index failure propagates"),
                "unsupported-index exception is not translated");
        TransportException transport = new TransportException("transport failure");
        assertSame(transport, assertThrows(TransportException.class,
                () -> new IndexValueSourceAdapter((index, date) -> { throw transport; })
                        .value(requestedIndex, requestedDate), "transport failure propagates"),
                "transport exception is not translated");
    }

    @Test
    void bridgeBackedCalculationUsesExactFixing() {
        Currency rub = Currency.getInstance("RUB");
        IndexId index = new IndexId("CPI");
        LocalDate issue = LocalDate.of(2024, 1, 1);
        Principal principal = new Principal(new Money(new BigDecimal("1000"), rub), List.of(),
                Optional.of(new IndexationRule(index, new BigDecimal("100"), issue, 0)));
        BondDefinition bond = new BondDefinition(new SecurityId("INDEXED"), rub, issue,
                Optional.of(issue.plusYears(1)), Optional.empty(), principal, RedemptionOptions.none());
        IndexValueSource values = new IndexValueSourceAdapter(StaticFixings.of(
                new IndexFixing(index, issue, new BigDecimal("105.0000"))));
        assertEquals(new Money(new BigDecimal("1050"), rub), BondCalculations.facePerUnit(bond, issue, values),
                "indexed calculation works through StaticFixings and the explicit bridge");

        MissingIndexValueException wrongIndex = assertThrows(MissingIndexValueException.class,
                () -> BondCalculations.facePerUnit(bond, issue, new IndexValueSourceAdapter(
                        (requestedIndex, date) -> new IndexFixing(new IndexId("WRONG"), date,
                                new BigDecimal("105")))),
                "indexed calculation rejects a fixing for another index");
        assertEquals(index, wrongIndex.index(), "indexed calculation reports requested index for wrong DTO");
        assertEquals(issue, wrongIndex.date(), "indexed calculation reports requested date for wrong DTO");
    }

    @Test
    void transportRequestFactoriesAndValidation() {
        URI uri = URI.create("https://example.invalid/data");
        Transport.Request get = Transport.Request.get(uri);
        assertEquals("GET", get.method(), "GET factory method");
        assertEquals(uri, get.uri(), "GET factory URI");
        assertTrue(get.headers().isEmpty() && get.body().isEmpty(), "GET factory has empty headers and body");

        String xml = "<request>Привет</request>";
        Transport.Request post = Transport.Request.post(uri, xml);
        assertEquals("POST", post.method(), "POST factory method");
        assertEquals("text/xml; charset=utf-8", post.headers().get("Content-Type"), "POST UTF-8 XML content type");
        assertEquals(xml, post.body(), "POST preserves UTF-8 text body");

        Map<String, String> suppliedHeaders = new java.util.HashMap<>();
        suppliedHeaders.put("X-Test", "before");
        Transport.Request copied = new Transport.Request("PUT", uri, suppliedHeaders, "body");
        suppliedHeaders.put("X-Test", "after");
        assertEquals("before", copied.headers().get("X-Test"), "request defensively copies headers");
        assertThrows(UnsupportedOperationException.class,
                () -> copied.headers().put("X-Other", "value"), "request headers are immutable");
        assertThrows(NullPointerException.class, () -> new Transport.Request("GET", uri, null, ""),
                "null request headers rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new Transport.Request(" ", uri, Map.of(), ""), "blank request method rejected");
    }
}
