package ru.onlychill.mbondsfx.core.ledger;

import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.SecurityId;

/** Account identity includes portfolio, configured code, optional security, and currency subaccount. */
public record Account(PortfolioId portfolio, AccountCode code,
                      Optional<SecurityId> security, Currency currency) {
    public Account {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Pseudo-currency has no minor-unit scale: "
                    + currency.getCurrencyCode());
        }
    }
}
