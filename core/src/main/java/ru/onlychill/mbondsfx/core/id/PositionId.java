package ru.onlychill.mbondsfx.core.id;

import java.util.Objects;

public record PositionId(PortfolioId portfolio, SecurityId security) {
    public PositionId {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(security, "security");
    }
}
