package ru.onlychill.mbondsfx.application.port;

import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** A portfolio journal already contains this operation ID. */
public final class DuplicateOperationException extends RuntimeException {
    private final PortfolioId portfolio;
    private final OperationId operation;

    public DuplicateOperationException(PortfolioId portfolio, OperationId operation) {
        super("Duplicate operation " + Objects.requireNonNull(operation, "operation")
                + " for portfolio " + Objects.requireNonNull(portfolio, "portfolio"));
        this.portfolio = portfolio;
        this.operation = operation;
    }

    public PortfolioId portfolio() { return portfolio; }
    public OperationId operation() { return operation; }
}
