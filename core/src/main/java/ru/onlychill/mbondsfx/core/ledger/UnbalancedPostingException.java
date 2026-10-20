package ru.onlychill.mbondsfx.core.ledger;

public final class UnbalancedPostingException extends IllegalArgumentException {
    public UnbalancedPostingException(String message) {
        super(message);
    }
}
