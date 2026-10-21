package ru.onlychill.mbondsfx.application.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import ru.onlychill.mbondsfx.application.port.InvestmentAccount;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountType;
import ru.onlychill.mbondsfx.core.money.Money;

/** Partial early-closure assessment; it does not determine legal tax liability or penalty periods. */
public record EarlyClosureAssessment(InvestmentAccount account, LocalDate closureDate,
                                     Optional<Money> returnedDeductions,
                                     Optional<Money> additionalTax,
                                     Optional<Money> penalties) {
    private static final Currency RUB = Currency.getInstance("RUB");

    public EarlyClosureAssessment {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(closureDate, "closureDate");
        Objects.requireNonNull(returnedDeductions, "returnedDeductions");
        Objects.requireNonNull(additionalTax, "additionalTax");
        Objects.requireNonNull(penalties, "penalties");
        if (account.type() != InvestmentAccountType.IIS_3) {
            throw new IllegalArgumentException("Early closure assessment requires an IIS_3 account");
        }
        LocalDate openingDate = account.openingDate().orElseThrow();
        if (closureDate.isBefore(openingDate)) {
            throw new IllegalArgumentException("closureDate must not precede openingDate");
        }
        validateComponents(returnedDeductions, additionalTax, penalties);
    }

    /**
     * Creates a partial estimate for ordinary premature closure from explicitly known refund, penalty,
     * and additional-tax inputs. This factory does not determine recoverability, exceptions, or legal entitlement.
     */
    public static EarlyClosureAssessment estimateOrdinaryEarlyClosure(
            InvestmentAccount account, LocalDate closureDate,
            Optional<List<Money>> actuallyReceivedTaxRefunds,
            Optional<PenaltyEstimate> penaltyEstimate,
            Optional<Money> suppliedAdditionalTax) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(closureDate, "closureDate");
        Objects.requireNonNull(actuallyReceivedTaxRefunds, "actuallyReceivedTaxRefunds");
        Objects.requireNonNull(penaltyEstimate, "penaltyEstimate");
        Objects.requireNonNull(suppliedAdditionalTax, "suppliedAdditionalTax");

        Optional<Money> refundTotal = actuallyReceivedTaxRefunds.map(refunds -> {
            Money total = Money.zero(RUB);
            for (Money refund : List.copyOf(refunds)) {
                requireKnownRubAmount(refund, "actuallyReceivedTaxRefund");
                total = total.add(refund);
            }
            return total;
        });
        Optional<Money> penalties = penaltyEstimate.map(PenaltyEstimate::amount);
        penaltyEstimate.ifPresent(estimate -> requireKnownRubAmount(estimate.amount(), "penaltyEstimate"));
        suppliedAdditionalTax.ifPresent(tax -> requireKnownRubAmount(tax, "suppliedAdditionalTax"));

        EarlyClosureAssessment assessment = new EarlyClosureAssessment(account, closureDate,
                refundTotal, suppliedAdditionalTax, penalties);
        if (assessment.minimumTermReached()) {
            throw new IllegalArgumentException("Ordinary early-closure estimate requires closureDate before "
                    + "earliestEligibleCloseDate " + account.earliestEligibleCloseDate().orElseThrow());
        }
        return assessment;
    }

    public boolean minimumTermReached() {
        return !closureDate.isBefore(account.earliestEligibleCloseDate().orElseThrow());
    }

    public long daysEarly() {
        return minimumTermReached() ? 0L
                : ChronoUnit.DAYS.between(closureDate, account.earliestEligibleCloseDate().orElseThrow());
    }

    /** Present only when every component is explicitly known; no estimate is substituted for unknowns. */
    public Optional<Money> totalKnownComponents() {
        if (returnedDeductions.isEmpty() || additionalTax.isEmpty() || penalties.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(returnedDeductions.orElseThrow()
                .add(additionalTax.orElseThrow()).add(penalties.orElseThrow()));
    }

    private static void requireKnownRubAmount(Money amount, String name) {
        Objects.requireNonNull(amount, name);
        if (!RUB.equals(amount.currency())) {
            throw new IllegalArgumentException(name + " must be denominated in RUB");
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void validateComponents(Optional<Money> returned, Optional<Money> tax,
                                           Optional<Money> penalties) {
        List<Money> amounts = Stream.of(returned, tax, penalties).flatMap(Optional::stream).toList();
        for (Money amount : amounts) {
            if (amount.isNegative()) {
                throw new IllegalArgumentException("Closure components must not be negative");
            }
        }
        if (!amounts.isEmpty() && amounts.stream()
                .anyMatch(amount -> !amount.currency().equals(amounts.getFirst().currency()))) {
            throw new IllegalArgumentException("Closure components must use one currency");
        }
    }
}
