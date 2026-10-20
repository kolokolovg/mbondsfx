package ru.onlychill.mbondsfx.integrations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import ru.onlychill.mbondsfx.application.port.BondReference;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;

/** Offline contracts for shared typed provider SPI and provider configuration. */
public final class ProviderContractTests {
    private ProviderContractTests() {
    }

    public static void main(String[] args) {
        providerContextCopiesAndFreezesSettings();
        providerContextRejectsInvalidSettings();
        typedProviderContractsReturnTheirCapabilities();
        System.out.println("ProviderContractTests: passed");
    }

    private static void providerContextCopiesAndFreezesSettings() {
        Map<String, String> supplied = new HashMap<>();
        supplied.put("moex.endpoint", "https://fixture.invalid/iss");
        ProviderContext context = new ProviderContext(supplied);
        supplied.put("moex.endpoint", "https://changed.invalid");
        supplied.put("new", "value");

        equal("https://fixture.invalid/iss", context.settings().get("moex.endpoint"),
                "settings are copied at construction");
        check(!context.settings().containsKey("new"), "later additions are not visible");
        expect(UnsupportedOperationException.class,
                () -> context.settings().put("x", "y"), "settings map is immutable");
        equal(Map.of(), new ProviderContext(Map.of()).settings(), "empty settings are valid");

        ProviderContext opaque = new ProviderContext(Map.of(" vendor.key ", " value "));
        equal(" value ", opaque.settings().get(" vendor.key "), "nonblank setting strings are not normalized");
    }

    private static void providerContextRejectsInvalidSettings() {
        expect(NullPointerException.class, () -> new ProviderContext(null), "null settings map rejected");
        expect(IllegalArgumentException.class,
                () -> new ProviderContext(Map.of(" ", "value")), "blank setting key rejected");
        expect(IllegalArgumentException.class,
                () -> new ProviderContext(Map.of("key", "\t")), "blank setting value rejected");

        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        expect(NullPointerException.class, () -> new ProviderContext(nullKey), "null setting key rejected");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);
        expect(NullPointerException.class, () -> new ProviderContext(nullValue), "null setting value rejected");
    }

    private static void typedProviderContractsReturnTheirCapabilities() {
        ProviderContext context = new ProviderContext(Map.of("test.mode", "fixture"));
        IndexId index = new IndexId("CPI");
        LocalDate date = LocalDate.of(2026, 10, 20);
        IndexValueSource indexValues = (requestedIndex, requestedDate) -> {
            check(index.equals(requestedIndex), "index capability receives requested index");
            check(date.equals(requestedDate), "index capability receives requested date");
            return new BigDecimal("1.2300");
        };
        IndexValueSourceProvider indexProvider = new IndexValueSourceProvider() {
            @Override
            public String providerId() {
                return "fixture-index";
            }

            @Override
            public IndexValueSource create(ProviderContext suppliedContext) {
                check(context == suppliedContext, "index provider receives shared context");
                return indexValues;
            }
        };
        equal("fixture-index", indexProvider.providerId(), "index provider has an ID");
        equal(new BigDecimal("1.2300"), indexProvider.create(context).value(index, date),
                "index SPI returns the core capability");

        SecurityId security = new SecurityId("TEST");
        BondReference reference = new BondReference(security, "fixture-bond", Optional.empty(),
                Optional.empty(), Optional.empty());
        BondReferenceDataSource source = requested -> {
            check(security.equals(requested), "bond capability receives requested security");
            return Optional.of(reference);
        };
        BondReferenceDataSourceProvider bondProvider = new BondReferenceDataSourceProvider() {
            @Override
            public String providerId() {
                return "fixture-bond";
            }

            @Override
            public BondReferenceDataSource create(ProviderContext suppliedContext) {
                check(context == suppliedContext, "bond provider receives shared context");
                return source;
            }
        };
        equal("fixture-bond", bondProvider.providerId(), "bond provider has an ID");
        equal(Optional.of(reference), bondProvider.create(context).reference(security),
                "bond SPI returns the application capability");
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

    private static void expect(Class<? extends Throwable> expected, Runnable action, String message) {
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
