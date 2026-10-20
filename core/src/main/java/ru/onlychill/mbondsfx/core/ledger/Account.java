package ru.onlychill.mbondsfx.core.ledger;

import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.SecurityId;

public record Account(PortfolioId portfolio, AccountKind kind,
                      Optional<SecurityId> security, Currency currency) {
    public Account {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Pseudo-currency has no minor-unit scale: "
                    + currency.getCurrencyCode());
        }
        if (kind.requiresSecurity() != security.isPresent()) {
            throw new IllegalArgumentException(kind + (kind.requiresSecurity()
                    ? " requires a security" : " is not security-scoped"));
        }
    }
}
