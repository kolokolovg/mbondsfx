package ru.onlychill.mbondsfx.core.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.money.Money;

/** Pure constructors for supported portfolio accounting operations. All cash amounts are totals. */
public final class BondOperations {
    private BondOperations() { }

    public static OperationResult opening(OperationId op, LocalDate date, PortfolioId portfolio,
                                          Currency currency, Money amount) {
        return cashOperation(OperationResult.OperationKind.OPENING, op, date, portfolio, currency, amount,
                AccountKind.CASH, AccountKind.EQUITY);
    }

    public static OperationResult deposit(OperationId op, LocalDate date, PortfolioId portfolio,
                                         Currency currency, Money amount) {
        return cashOperation(OperationResult.OperationKind.DEPOSIT, op, date, portfolio, currency, amount,
                AccountKind.CASH, AccountKind.EQUITY);
    }

    public static OperationResult withdrawal(OperationId op, LocalDate date, PortfolioId portfolio,
                                             Currency currency, Money amount) {
        return cashOperation(OperationResult.OperationKind.WITHDRAWAL, op, date, portfolio, currency, amount,
                AccountKind.EQUITY, AccountKind.CASH);
    }

    public static OperationResult buy(OperationId op, LocalDate settle, Position current,
                                      long quantity, Money cleanPricePerUnit, Money accruedPerUnit,
                                      Money facePerUnit, Money commission) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(settle, "settle");
        requireQuantity(quantity, "quantity");
        Currency currency = currencyOf(current);
        requireNonNegative(cleanPricePerUnit, currency, "cleanPricePerUnit");
        requireNonNegative(accruedPerUnit, currency, "accruedPerUnit");
        requirePositive(facePerUnit, currency, "facePerUnit");
        requireNonNegative(commission, currency, "commission");
        long newQuantity = Math.addExact(current.quantity(), quantity);
        Money cleanTotal = cleanPricePerUnit.multiply(quantity);
        Money accruedTotal = accruedPerUnit.multiply(quantity);
        Money faceTotal = facePerUnit.multiply(quantity);
        Money cashOut = cleanTotal.add(accruedTotal).add(commission);
        Position after = new Position(current.id(), newQuantity,
                current.carryingAmount().add(cleanTotal), current.faceAmount().add(faceTotal),
                current.purchasedAccrued().add(accruedTotal));

        List<Posting.Entry> entries = new ArrayList<>();
        debit(entries, account(current, AccountKind.SECURITIES), cleanTotal);
        debit(entries, account(current, AccountKind.ACCRUED_INTEREST), accruedTotal);
        debit(entries, flat(current, AccountKind.COMMISSION_EXPENSE), commission);
        credit(entries, flat(current, AccountKind.CASH), cashOut);
        return securityResult(OperationResult.OperationKind.PURCHASE, op, settle, entries, current, after);
    }

    public static OperationResult coupon(OperationId op, LocalDate payDate, Position current,
                                        Money grossCouponTotal, Optional<Money> taxWithheldTotal) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(payDate, "payDate");
        Objects.requireNonNull(taxWithheldTotal, "taxWithheldTotal");
        requireOpen(current);
        Currency currency = currencyOf(current);
        requireNonNegative(grossCouponTotal, currency, "grossCouponTotal");
        Money tax = taxWithheldTotal.orElseGet(() -> Money.zero(currency));
        requireNonNegative(tax, currency, "taxWithheldTotal");
        if (tax.compareTo(grossCouponTotal) > 0) {
            throw new IllegalArgumentException("Tax withheld cannot exceed gross coupon");
        }
        Money release = min(current.purchasedAccrued(), grossCouponTotal);
        Money income = grossCouponTotal.subtract(release);
        Money net = grossCouponTotal.subtract(tax);
        Position after = new Position(current.id(), current.quantity(), current.carryingAmount(),
                current.faceAmount(), current.purchasedAccrued().subtract(release));

        List<Posting.Entry> entries = new ArrayList<>();
        debit(entries, flat(current, AccountKind.CASH), net);
        debit(entries, flat(current, AccountKind.TAX_EXPENSE), tax);
        credit(entries, account(current, AccountKind.ACCRUED_INTEREST), release);
        credit(entries, flat(current, AccountKind.COUPON_INCOME), income);
        return securityResult(OperationResult.OperationKind.COUPON, op, payDate, entries, current, after);
    }

    public static OperationResult amortization(OperationId op, LocalDate payDate, Position current,
                                              Money faceReductionTotal, Money paymentTotal,
                                              Optional<Money> taxWithheldTotal) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(payDate, "payDate");
        Objects.requireNonNull(taxWithheldTotal, "taxWithheldTotal");
        requireOpen(current);
        Currency currency = currencyOf(current);
        requirePositive(faceReductionTotal, currency, "faceReductionTotal");
        requireNonNegative(paymentTotal, currency, "paymentTotal");
        Money tax = taxWithheldTotal.orElseGet(() -> Money.zero(currency));
        requireNonNegative(tax, currency, "taxWithheldTotal");
        if (tax.compareTo(paymentTotal) > 0) {
            throw new IllegalArgumentException("Tax cannot exceed the amortization payment");
        }
        if (faceReductionTotal.compareTo(current.faceAmount()) > 0) {
            throw new IllegalArgumentException("Face reduction exceeds the position face amount");
        }
        boolean fullPrincipalReduction = faceReductionTotal.equals(current.faceAmount());
        if (fullPrincipalReduction && !current.purchasedAccrued().isZero()) {
            throw new IllegalArgumentException("Cannot fully amortize while purchased accrued interest remains");
        }
        Money carryingRemoved = fullPrincipalReduction ? current.carryingAmount()
                : proportional(current.carryingAmount(), faceReductionTotal.amount(), current.faceAmount().amount());
        Money gain = paymentTotal.subtract(carryingRemoved);
        Money faceAfter = current.faceAmount().subtract(faceReductionTotal);
        Money carryingAfter = current.carryingAmount().subtract(carryingRemoved);
        Position after = new Position(current.id(), current.quantity(), carryingAfter,
                faceAfter, current.purchasedAccrued());

        List<Posting.Entry> entries = new ArrayList<>();
        debit(entries, flat(current, AccountKind.CASH), paymentTotal.subtract(tax));
        debit(entries, flat(current, AccountKind.TAX_EXPENSE), tax);
        credit(entries, account(current, AccountKind.SECURITIES), carryingRemoved);
        signed(entries, flat(current, AccountKind.AMORTIZATION_GAIN), gain.negate());
        return securityResult(OperationResult.OperationKind.AMORTIZATION, op, payDate, entries, current, after);
    }

    public static OperationResult sell(OperationId op, LocalDate settle, Position current, long quantity,
                                       Money cleanPricePerUnit, Money accruedReceivedPerUnit,
                                       Money commission, Optional<Money> taxOnGainTotal) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(settle, "settle");
        Objects.requireNonNull(taxOnGainTotal, "taxOnGainTotal");
        requireOpen(current);
        requireQuantity(quantity, "quantity");
        if (quantity > current.quantity()) {
            throw new IllegalArgumentException("Sale quantity exceeds position quantity");
        }
        Currency currency = currencyOf(current);
        requireNonNegative(cleanPricePerUnit, currency, "cleanPricePerUnit");
        requireNonNegative(accruedReceivedPerUnit, currency, "accruedReceivedPerUnit");
        requireNonNegative(commission, currency, "commission");
        Money tax = taxOnGainTotal.orElseGet(() -> Money.zero(currency));
        requireNonNegative(tax, currency, "taxOnGainTotal");

        boolean fullDisposal = quantity == current.quantity();
        Money cleanValue = cleanPricePerUnit.multiply(quantity);
        Money accruedReceived = accruedReceivedPerUnit.multiply(quantity);
        Money accruedRelease = fullDisposal ? current.purchasedAccrued()
                : proportional(current.purchasedAccrued(), BigDecimal.valueOf(quantity),
                        BigDecimal.valueOf(current.quantity()));
        Money carryingRemoved = fullDisposal ? current.carryingAmount()
                : proportional(current.carryingAmount(), BigDecimal.valueOf(quantity),
                        BigDecimal.valueOf(current.quantity()));
        Money faceRemoved = fullDisposal ? current.faceAmount()
                : proportional(current.faceAmount(), BigDecimal.valueOf(quantity),
                        BigDecimal.valueOf(current.quantity()));
        Money accruedIncome = accruedReceived.subtract(accruedRelease);
        Money realizedPnL = cleanValue.subtract(carryingRemoved);
        long remainingQuantity = current.quantity() - quantity;
        Position after = remainingQuantity == 0 ? Position.empty(current.id(), currency)
                : new Position(current.id(), remainingQuantity,
                        current.carryingAmount().subtract(carryingRemoved),
                        current.faceAmount().subtract(faceRemoved),
                        current.purchasedAccrued().subtract(accruedRelease));

        Money netCash = cleanValue.add(accruedReceived).subtract(commission).subtract(tax);
        List<Posting.Entry> entries = new ArrayList<>();
        signed(entries, flat(current, AccountKind.CASH), netCash);
        debit(entries, flat(current, AccountKind.COMMISSION_EXPENSE), commission);
        debit(entries, flat(current, AccountKind.TAX_EXPENSE), tax);
        credit(entries, account(current, AccountKind.SECURITIES), carryingRemoved);
        credit(entries, account(current, AccountKind.ACCRUED_INTEREST), accruedRelease);
        signed(entries, flat(current, AccountKind.COUPON_INCOME), accruedIncome.negate());
        signed(entries, flat(current, AccountKind.REALIZED_PNL), realizedPnL.negate());
        return securityResult(OperationResult.OperationKind.SALE, op, settle, entries, current, after);
    }

    private static OperationResult cashOperation(OperationResult.OperationKind kind, OperationId op,
                                                 LocalDate date, PortfolioId portfolio, Currency currency,
                                                 Money amount, AccountKind debitKind, AccountKind creditKind) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(currency, "currency");
        requirePositive(amount, currency, "amount");
        List<Posting.Entry> entries = new ArrayList<>();
        debit(entries, new Account(portfolio, debitKind, Optional.empty(), currency), amount);
        credit(entries, new Account(portfolio, creditKind, Optional.empty(), currency), amount);
        Posting posting = new Posting(op, date, entries);
        return new OperationResult(kind, op, date, List.of(posting), Optional.empty(), Optional.empty());
    }

    private static OperationResult securityResult(OperationResult.OperationKind kind, OperationId op,
                                                  LocalDate date, List<Posting.Entry> entries,
                                                  Position before, Position after) {
        Posting posting = new Posting(op, date, entries);
        return new OperationResult(kind, op, date, List.of(posting), Optional.of(before), Optional.of(after));
    }

    private static Account account(Position position, AccountKind kind) {
        return new Account(position.id().portfolio(), kind, Optional.of(position.id().security()),
                currencyOf(position));
    }

    private static Account flat(Position position, AccountKind kind) {
        return new Account(position.id().portfolio(), kind, Optional.empty(), currencyOf(position));
    }

    private static void debit(List<Posting.Entry> entries, Account account, Money amount) {
        signed(entries, account, amount);
    }

    private static void credit(List<Posting.Entry> entries, Account account, Money amount) {
        signed(entries, account, amount.negate());
    }

    private static void signed(List<Posting.Entry> entries, Account account, Money signedAmount) {
        if (!signedAmount.isZero()) {
            entries.add(new Posting.Entry(account, signedAmount));
        }
    }

    private static Money proportional(Money amount, BigDecimal numerator, BigDecimal denominator) {
        if (amount.isNegative() || denominator.signum() <= 0
                || numerator.signum() < 0 || numerator.compareTo(denominator) > 0) {
            throw new IllegalArgumentException("Invalid proportional removal");
        }
        int scale = Math.max(0, amount.currency().getDefaultFractionDigits());
        BigDecimal result = amount.amount().multiply(numerator)
                .divide(denominator, scale, RoundingMode.HALF_UP);
        // A sub-minor-unit balance can round up past the whole amount being removed.
        // Preserve HALF_UP unless that would overdraw the asset; then release its exact remainder.
        if (result.compareTo(amount.amount()) > 0) {
            result = amount.amount();
        }
        return new Money(result, amount.currency());
    }

    private static Money min(Money left, Money right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Currency currencyOf(Position position) {
        return position.carryingAmount().currency();
    }

    private static void requireOpen(Position position) {
        if (position.quantity() <= 0) {
            throw new IllegalArgumentException("Operation requires a non-empty position");
        }
    }

    private static void requireQuantity(long quantity, String name) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(Money amount, Currency currency, String name) {
        requireCurrency(amount, currency, name);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(Money amount, Currency currency, String name) {
        requireCurrency(amount, currency, name);
        if (amount.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireCurrency(Money amount, Currency currency, String name) {
        Objects.requireNonNull(amount, name);
        if (!currency.equals(amount.currency())) {
            throw new IllegalArgumentException(name + " currency does not match position/operation currency");
        }
    }
}
