package ru.onlychill.mbondsfx.integrations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.application.port.BondReference;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Offline contracts for shared typed provider SPI and provider configuration. */
class ProviderContractTests {
    @Test
    void providerContextCopiesAndFreezesSettings() {
        Map<String, String> supplied = new HashMap<>();
        supplied.put("moex.endpoint", "https://fixture.invalid/iss");
        ProviderContext context = new ProviderContext(supplied);
        supplied.put("moex.endpoint", "https://changed.invalid");
        supplied.put("new", "value");

        assertEquals("https://fixture.invalid/iss", context.settings().get("moex.endpoint"),
                "settings are copied at construction");
        assertFalse(context.settings().containsKey("new"), "later additions are not visible");
        assertThrows(UnsupportedOperationException.class,
                () -> context.settings().put("x", "y"), "settings map is immutable");
        assertEquals(Map.of(), new ProviderContext(Map.of()).settings(), "empty settings are valid");

        ProviderContext opaque = new ProviderContext(Map.of(" vendor.key ", " value "));
        assertEquals(" value ", opaque.settings().get(" vendor.key "),
                "nonblank setting strings are not normalized");
    }

    @Test
    void providerContextRejectsInvalidSettings() {
        assertThrows(NullPointerException.class, () -> new ProviderContext(null), "null settings map rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderContext(Map.of(" ", "value")), "blank setting key rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderContext(Map.of("key", "\t")), "blank setting value rejected");

        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        assertThrows(NullPointerException.class, () -> new ProviderContext(nullKey), "null setting key rejected");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);
        assertThrows(NullPointerException.class, () -> new ProviderContext(nullValue), "null setting value rejected");
    }

    @Test
    void typedProviderContractsReturnTheirCapabilities() {
        ProviderContext context = new ProviderContext(Map.of("test.mode", "fixture"));
        IndexId index = new IndexId("CPI");
        LocalDate date = LocalDate.of(2026, 10, 20);
        IndexValueSource indexValues = (requestedIndex, requestedDate) -> {
            assertEquals(index, requestedIndex, "index capability receives requested index");
            assertEquals(date, requestedDate, "index capability receives requested date");
            return new BigDecimal("1.2300");
        };
        IndexValueSourceProvider indexProvider = new IndexValueSourceProvider() {
            @Override
            public String providerId() {
                return "fixture-index";
            }

            @Override
            public IndexValueSource create(ProviderContext suppliedContext) {
                assertSame(context, suppliedContext, "index provider receives shared context");
                return indexValues;
            }
        };
        assertEquals("fixture-index", indexProvider.providerId(), "index provider has an ID");
        assertEquals(new BigDecimal("1.2300"), indexProvider.create(context).value(index, date),
                "index SPI returns the core capability");

        SecurityId security = new SecurityId("TEST");
        BondReference reference = new BondReference(security, "fixture-bond", Optional.empty(),
                Optional.empty(), Optional.empty());
        BondReferenceDataSource source = requested -> {
            assertEquals(security, requested, "bond capability receives requested security");
            return Optional.of(reference);
        };
        BondReferenceDataSourceProvider bondProvider = new BondReferenceDataSourceProvider() {
            @Override
            public String providerId() {
                return "fixture-bond";
            }

            @Override
            public BondReferenceDataSource create(ProviderContext suppliedContext) {
                assertSame(context, suppliedContext, "bond provider receives shared context");
                return source;
            }
        };
        assertEquals("fixture-bond", bondProvider.providerId(), "bond provider has an ID");
        assertEquals(Optional.of(reference), bondProvider.create(context).reference(security),
                "bond SPI returns the application capability");
    }
}
