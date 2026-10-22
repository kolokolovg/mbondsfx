package ru.onlychill.mbondsfx.core.ledger;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identity for an account in a configured ledger chart. */
public record AccountCode(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public AccountCode {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid account code: " + value);
        }
    }

    public static AccountCode of(String value) {
        return new AccountCode(value);
    }
}
