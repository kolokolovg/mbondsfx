package ru.onlychill.mbondsfx.application.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable provider-specific settings without a concrete storage dependency. */
public record RepositoryConfig(Map<String, String> settings) {
    public RepositoryConfig {
        Objects.requireNonNull(settings, "settings");
        Map<String, String> copy = new LinkedHashMap<>();
        settings.forEach((key, value) -> copy.put(
                requireText(key, "setting key"), Objects.requireNonNull(value, "setting value")));
        settings = Collections.unmodifiableMap(copy);
    }

    public static RepositoryConfig empty() {
        return new RepositoryConfig(Map.of());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
