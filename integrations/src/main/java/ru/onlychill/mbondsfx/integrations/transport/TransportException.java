package ru.onlychill.mbondsfx.integrations.transport;

public final class TransportException extends RuntimeException {
    public TransportException() {
    }

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransportException(Throwable cause) {
        super(cause);
    }
}
