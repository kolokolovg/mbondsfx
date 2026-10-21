package ru.onlychill.mbondsfx.application.port;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** Immutable account metadata registered independently from the core ledger. */
public record InvestmentAccount(PortfolioId portfolio, InvestmentAccountType type,
                               Optional<Iis3Terms> iis3Terms) {
    public InvestmentAccount {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(iis3Terms, "iis3Terms");
        if ((type == InvestmentAccountType.IIS_3) != iis3Terms.isPresent()) {
            throw new IllegalArgumentException("IIS_3 requires terms and BROKERAGE must not have IIS-3 terms");
        }
    }

    public static InvestmentAccount brokerage(PortfolioId portfolio) {
        return new InvestmentAccount(portfolio, InvestmentAccountType.BROKERAGE, Optional.empty());
    }

    public static InvestmentAccount iis3(PortfolioId portfolio, Iis3Terms terms) {
        return new InvestmentAccount(portfolio, InvestmentAccountType.IIS_3, Optional.of(terms));
    }

    /**
     * Creates new IIS-3 metadata using the minimum holding term in Tax Code 219.2(4) for its opening year.
     * This selects a term only; it does not establish entitlement to any tax benefit.
     */
    public static InvestmentAccount newIis3WithMinimumTermForOpeningDate(
            PortfolioId portfolio, LocalDate openingDate, Optional<LocalDate> contractualCloseDate) {
        Objects.requireNonNull(openingDate, "openingDate");
        Objects.requireNonNull(contractualCloseDate, "contractualCloseDate");
        int year = openingDate.getYear();
        if (year < 2024) {
            throw new IllegalArgumentException("The statutory IIS-3 minimum-term schedule starts in 2024");
        }
        int minimumYears = year <= 2026 ? 5 : Math.min(10, year - 2021);
        return iis3(portfolio, new Iis3Terms(openingDate, Period.ofYears(minimumYears), contractualCloseDate));
    }

    public Optional<LocalDate> openingDate() {
        return iis3Terms.map(Iis3Terms::openingDate);
    }

    public Optional<LocalDate> earliestEligibleCloseDate() {
        return iis3Terms.map(Iis3Terms::earliestEligibleCloseDate);
    }

    public Optional<LocalDate> contractualCloseDate() {
        return iis3Terms.flatMap(Iis3Terms::contractualCloseDate);
    }
}
