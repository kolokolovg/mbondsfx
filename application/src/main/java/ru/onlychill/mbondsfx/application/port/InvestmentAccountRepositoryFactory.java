package ru.onlychill.mbondsfx.application.port;

/** Explicit provider SPI for investment-account metadata storage. */
public interface InvestmentAccountRepositoryFactory {
    /** Stable, nonblank ID coordinated with the selected ledger repository provider. */
    String providerId();

    InvestmentAccountRepository createInvestmentAccountRepository(RepositoryConfig config);
}
