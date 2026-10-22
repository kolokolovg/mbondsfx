package ru.onlychill.mbondsfx.application.service;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.application.port.InvestmentAccount;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountRepository;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.ledger.BondAccountingPolicy;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.money.Money;

/** Confirms and commits an actual coupon receipt; it never processes scheduled payments. */
public final class CouponReceiptService {
    private final InvestmentAccountRepository accounts;
    private final LedgerRepository ledgers;
    private final LedgerApplicationService ledgerService;
    private final BondOperations operations;

    public CouponReceiptService(InvestmentAccountRepository accounts, LedgerRepository ledgers,
                                BondAccountingPolicy policy) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.ledgers = Objects.requireNonNull(ledgers, "ledgers");
        Objects.requireNonNull(policy, "policy");
        this.ledgerService = new LedgerApplicationService(ledgers, policy);
        this.operations = new BondOperations(policy);
    }

    /** All three amounts are mandatory known facts, including an explicitly known zero tax. */
    public CouponReceipt confirm(InvestmentAccount account, Position position, OperationId operation,
                                 LocalDate actualDate, Money grossTotal, Money taxWithheldTotal,
                                 Money netReceivedTotal, boolean allowNegativeCash) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(actualDate, "actualDate");
        requireNonNegative(grossTotal, "grossTotal");
        requireNonNegative(taxWithheldTotal, "taxWithheldTotal");
        requireNonNegative(netReceivedTotal, "netReceivedTotal");
        requireSameCurrency(grossTotal, taxWithheldTotal, netReceivedTotal);
        if (grossTotal.compareTo(taxWithheldTotal.add(netReceivedTotal)) != 0) {
            throw new IllegalArgumentException("grossTotal must equal taxWithheldTotal + netReceivedTotal");
        }
        if (!account.portfolio().equals(position.id().portfolio())) {
            throw new IllegalArgumentException("Position belongs to a different investment-account portfolio");
        }
        if (position.quantity() <= 0) {
            throw new IllegalArgumentException("A received coupon requires an open position");
        }
        InvestmentAccount registered = accounts.account(account.portfolio())
                .orElseThrow(() -> new IllegalStateException("Investment-account metadata is unknown"));
        if (!registered.equals(account)) {
            throw new IllegalArgumentException("Supplied investment-account metadata differs from registry");
        }
        account.openingDate().ifPresent(opening -> {
            if (actualDate.isBefore(opening)) {
                throw new IllegalArgumentException("Coupon actualDate must not precede IIS-3 openingDate");
            }
        });

        Optional<LedgerSnapshot> loaded = Objects.requireNonNull(ledgers.load(account.portfolio()),
                "repository.load result");
        LedgerSnapshot current = loaded.orElseThrow(() -> new IllegalStateException("Portfolio ledger is unknown"));
        Position storedPosition = current.state().positions().get(position.id().security());
        if (!position.equals(storedPosition)) {
            throw new IllegalArgumentException("Position does not match the current portfolio ledger");
        }

        var operationResult = operations.coupon(operation, actualDate, position, grossTotal,
                Optional.of(taxWithheldTotal));
        LedgerSnapshot committed = ledgerService.apply(account.portfolio(), allowNegativeCash, operationResult);
        return new CouponReceipt(account, position.id(), operation, actualDate, grossTotal,
                taxWithheldTotal, netReceivedTotal, committed);
    }

    private static void requireNonNegative(Money amount, String name) {
        Objects.requireNonNull(amount, name);
        if (amount.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireSameCurrency(Money gross, Money tax, Money net) {
        if (!gross.currency().equals(tax.currency()) || !gross.currency().equals(net.currency())) {
            throw new IllegalArgumentException("Coupon receipt amounts must use one currency");
        }
    }
}
