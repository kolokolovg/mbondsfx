package ru.onlychill.mbondsfx.core.bond;

import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.Money;

/** Immutable bond terms. An absent coupon is a zero-coupon bond; absent maturity means perpetual. */
public record BondDefinition(SecurityId id, Currency currency, LocalDate issueDate,
                             Optional<LocalDate> maturityDate, Optional<Coupon> coupon,
                             Principal principal, RedemptionOptions options) implements Security {
    public BondDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency without a minor-unit scale is not supported");
        }
        Objects.requireNonNull(issueDate, "issueDate");
        Objects.requireNonNull(maturityDate, "maturityDate");
        Objects.requireNonNull(coupon, "coupon");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(options, "options");
        if (maturityDate.isPresent() && !issueDate.isBefore(maturityDate.orElseThrow())) {
            throw new IllegalArgumentException("maturityDate must be after issueDate");
        }
        requireCurrency(principal.initialFacePerUnit(), currency, "initial face");
        for (AmortizationEvent event : principal.events()) {
            requireAfterIssueAndByMaturity(event.amortDate(), "amortDate", issueDate, maturityDate);
            requireCurrency(event.faceReductionPerUnit(), currency, "amortization");
        }

        coupon.ifPresent(value -> {
            for (DatedPeriod period : value.periods()) {
                if (period.accrualStart().isBefore(issueDate)) {
                    throw new IllegalArgumentException("Coupon accrualStart must not precede issueDate");
                }
                requireByMaturity(period.accrualEnd(), "coupon accrualEnd", maturityDate);
                requireByMaturity(period.paymentDate(), "coupon paymentDate", maturityDate);
            }
            if (value instanceof KnownCoupon known) {
                for (KnownCoupon.Amount amount : known.amounts()) {
                    requireCurrency(amount.amountPerUnit(), currency, "known coupon amount");
                }
            }
        });

        for (OptionExercise option : options.callDates()) {
            validateOption(option, currency, issueDate, maturityDate);
        }
        for (OptionExercise option : options.putDates()) {
            validateOption(option, currency, issueDate, maturityDate);
        }
    }

    private static void validateOption(OptionExercise option, Currency currency, LocalDate issueDate,
                                      Optional<LocalDate> maturityDate) {
        if (option.date().isBefore(issueDate)) {
            throw new IllegalArgumentException("Option date must not precede issueDate");
        }
        requireByMaturity(option.date(), "option date", maturityDate);
        option.price().ifPresent(price -> requireCurrency(price, currency, "option price"));
    }

    private static void requireAfterIssueAndByMaturity(LocalDate date, String field, LocalDate issueDate,
                                                        Optional<LocalDate> maturityDate) {
        if (!date.isAfter(issueDate)) {
            throw new IllegalArgumentException(field + " must be after issueDate");
        }
        requireByMaturity(date, field, maturityDate);
    }

    private static void requireByMaturity(LocalDate date, String field, Optional<LocalDate> maturityDate) {
        if (maturityDate.isPresent() && date.isAfter(maturityDate.orElseThrow())) {
            throw new IllegalArgumentException(field + " must not be after maturityDate");
        }
    }

    private static void requireCurrency(Money money, Currency currency, String field) {
        if (!money.currency().equals(currency)) {
            throw new IllegalArgumentException(field + " currency must match bond currency");
        }
    }
}
