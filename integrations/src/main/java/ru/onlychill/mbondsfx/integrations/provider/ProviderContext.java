package ru.onlychill.mbondsfx.integrations.provider;

import java.util.Map;
import java.util.Objects;

/** Immutable, provider-owned string settings supplied by the composition root. */
public record ProviderContext(Map<String, String> settings) {
    public ProviderContext {
        Objects.requireNonNull(settings, "settings");
        settings.forEach((key, value) -> {
            Objects.requireNonNull(key, "settings key");
            Objects.requireNonNull(value, "settings value");
            if (key.isBlank()) {
                throw new IllegalArgumentException("settings keys must not be blank");
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("settings values must not be blank");
            }
        });
        settings = Map.copyOf(settings);
    }
}
