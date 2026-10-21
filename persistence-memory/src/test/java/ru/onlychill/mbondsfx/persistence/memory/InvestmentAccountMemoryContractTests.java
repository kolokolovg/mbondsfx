package ru.onlychill.mbondsfx.persistence.memory;

import java.time.LocalDate;
import java.time.Period;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.application.port.Iis3Terms;
import ru.onlychill.mbondsfx.application.port.InvestmentAccount;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountConflictException;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountRepository;
import ru.onlychill.mbondsfx.application.port.RepositoryConfig;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestmentAccountMemoryContractTests {
    @Test
    void registrationIsUnknownUntilExplicitAndExactRepeatsAreIdempotent() {
        InvestmentAccountRepository repository = new InMemoryRepositoryFactory()
                .createInvestmentAccountRepository(RepositoryConfig.empty());
        PortfolioId portfolio = new PortfolioId("account-registry");
        assertTrue(repository.account(portfolio).isEmpty(), "absence means unknown, not BROKERAGE");

        InvestmentAccount account = InvestmentAccount.iis3(portfolio,
                new Iis3Terms(LocalDate.of(2024, 3, 1), Period.ofYears(4), Optional.empty()));
        InvestmentAccount registered = repository.register(account);
        assertEquals(account, registered);
        assertEquals(Optional.of(account), repository.account(portfolio));
        assertSame(registered, repository.register(account), "exact repeated registration is idempotent");
    }

    @Test
    void conflictingTypeOrTermsAreRejectedWithoutReplacingMetadata() {
        InvestmentAccountRepository repository = new InMemoryRepositoryFactory()
                .createInvestmentAccountRepository(RepositoryConfig.empty());
        PortfolioId portfolio = new PortfolioId("account-conflict");
        InvestmentAccount account = InvestmentAccount.brokerage(portfolio);
        repository.register(account);

        assertThrows(InvestmentAccountConflictException.class,
                () -> repository.register(InvestmentAccount.iis3(portfolio,
                        new Iis3Terms(LocalDate.of(2024, 1, 1), Period.ofYears(3), Optional.empty()))));
        assertEquals(Optional.of(account), repository.account(portfolio));

        InvestmentAccountRepository iisRepository = new InMemoryRepositoryFactory()
                .createInvestmentAccountRepository(RepositoryConfig.empty());
        InvestmentAccount iis = InvestmentAccount.iis3(portfolio,
                new Iis3Terms(LocalDate.of(2024, 1, 1), Period.ofYears(3), Optional.empty()));
        iisRepository.register(iis);
        InvestmentAccount differentTerms = InvestmentAccount.iis3(portfolio,
                new Iis3Terms(LocalDate.of(2024, 1, 1), Period.ofYears(4), Optional.empty()));
        assertThrows(InvestmentAccountConflictException.class, () -> iisRepository.register(differentTerms));
        assertEquals(Optional.of(iis), iisRepository.account(portfolio));
    }

    @Test
    void registryIsIsolatedByPortfolioAndFactoryInstances() {
        InMemoryRepositoryFactory factory = new InMemoryRepositoryFactory();
        InvestmentAccountRepository first = factory.createInvestmentAccountRepository(RepositoryConfig.empty());
        InvestmentAccountRepository second = factory.createInvestmentAccountRepository(RepositoryConfig.empty());
        PortfolioId firstPortfolio = new PortfolioId("isolation-one");
        PortfolioId secondPortfolio = new PortfolioId("isolation-two");
        InvestmentAccount brokerage = InvestmentAccount.brokerage(firstPortfolio);
        InvestmentAccount iis = InvestmentAccount.iis3(secondPortfolio,
                new Iis3Terms(LocalDate.of(2020, 1, 1), Period.ofYears(3), Optional.empty()));

        first.register(brokerage);
        first.register(iis);
        assertEquals(Optional.of(brokerage), first.account(firstPortfolio));
        assertEquals(Optional.of(iis), first.account(secondPortfolio));
        assertTrue(second.account(firstPortfolio).isEmpty());
        assertTrue(second.account(secondPortfolio).isEmpty());
    }
}
