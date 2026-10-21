package ru.onlychill.mbondsfx.application.service;

import java.time.LocalDate;
import java.util.Objects;
import ru.onlychill.mbondsfx.application.port.InvestmentAccount;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.money.Money;

/** Successfully committed user confirmation of an actually received coupon. */
public record CouponReceipt(InvestmentAccount account, PositionId position, OperationId operation,
                           LocalDate actualDate, Money grossTotal, Money taxWithheldTotal,
                           Money netReceivedTotal, LedgerSnapshot ledgerSnapshot) {
    public CouponReceipt {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(actualDate, "actualDate");
        Objects.requireNonNull(grossTotal, "grossTotal");
        Objects.requireNonNull(taxWithheldTotal, "taxWithheldTotal");
        Objects.requireNonNull(netReceivedTotal, "netReceivedTotal");
        Objects.requireNonNull(ledgerSnapshot, "ledgerSnapshot");
        if (!account.portfolio().equals(position.portfolio())
                || !account.portfolio().equals(ledgerSnapshot.portfolio())) {
            throw new IllegalArgumentException("Receipt account, position, and ledger must share a portfolio");
        }
        if (grossTotal.isNegative() || taxWithheldTotal.isNegative() || netReceivedTotal.isNegative()) {
            throw new IllegalArgumentException("Receipt totals must not be negative");
        }
        if (!grossTotal.currency().equals(taxWithheldTotal.currency())
                || !grossTotal.currency().equals(netReceivedTotal.currency())
                || grossTotal.compareTo(taxWithheldTotal.add(netReceivedTotal)) != 0) {
            throw new IllegalArgumentException("Receipt totals must share a currency and gross = tax + net");
        }
        account.openingDate().ifPresent(opening -> {
            if (actualDate.isBefore(opening)) {
                throw new IllegalArgumentException("Receipt actualDate must not precede IIS-3 openingDate");
            }
        });
        if (!ledgerSnapshot.state().operationIds().contains(operation)) {
            throw new IllegalArgumentException("Receipt operation is not present in the committed ledger");
        }
    }
}
