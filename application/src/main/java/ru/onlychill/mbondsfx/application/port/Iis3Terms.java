package ru.onlychill.mbondsfx.application.port;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;
import java.util.Optional;

/** Explicitly supplied IIS-3 terms; these do not assert eligibility for any tax benefit. */
public record Iis3Terms(LocalDate openingDate, Period minimumTerm,
                        Optional<LocalDate> contractualCloseDate) {
    public Iis3Terms {
        Objects.requireNonNull(openingDate, "openingDate");
        Objects.requireNonNull(minimumTerm, "minimumTerm");
        Objects.requireNonNull(contractualCloseDate, "contractualCloseDate");
        if (minimumTerm.getYears() <= 0 || minimumTerm.getMonths() != 0 || minimumTerm.getDays() != 0) {
            throw new IllegalArgumentException("minimumTerm must be a positive whole number of years");
        }
        contractualCloseDate.ifPresent(date -> {
            if (date.isBefore(openingDate)) {
                throw new IllegalArgumentException("contractualCloseDate must not precede openingDate");
            }
        });
        // Validate the derived date at construction, including LocalDate range overflow.
        openingDate.plus(minimumTerm);
    }

    /** Calendar date at which the explicitly supplied minimum term is reached. */
    public LocalDate earliestEligibleCloseDate() {
        return openingDate.plus(minimumTerm);
    }
}
