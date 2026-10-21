package ru.onlychill.mbondsfx.application.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import ru.onlychill.mbondsfx.application.port.PenaltyInterval;
import ru.onlychill.mbondsfx.core.money.Money;

/**
 * Computes a scenario estimate for caller-confirmed personal tax debt intervals using 1/300 of key rate.
 * It does not determine legal liability, debt principal, key rates, or interval dates.
 */
public final class PenaltyEstimateService {
    private static final MathContext INTERMEDIATE_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal DAYS_DIVISOR = BigDecimal.valueOf(300);
    private static final Currency RUB = Currency.getInstance("RUB");

    /**
     * Estimates one chronological debt series. Empty is an explicit known zero; only supplied intervals
     * contribute, so gaps are not filled. Intervals may be adjacent but must be sorted and non-overlapping.
     */
    public PenaltyEstimate estimate(List<PenaltyInterval> intervals) {
        Objects.requireNonNull(intervals, "intervals");
        List<PenaltyInterval> covered = List.copyOf(intervals);
        validateSeries(covered);

        BigDecimal total = BigDecimal.ZERO;
        for (PenaltyInterval interval : covered) {
            long days = ChronoUnit.DAYS.between(interval.fromInclusive(), interval.toExclusive());
            BigDecimal intervalAmount = interval.applicableDebt().amount()
                    .multiply(interval.annualKeyRate(), INTERMEDIATE_CONTEXT)
                    .multiply(BigDecimal.valueOf(days), INTERMEDIATE_CONTEXT)
                    .divide(DAYS_DIVISOR, INTERMEDIATE_CONTEXT);
            total = total.add(intervalAmount, INTERMEDIATE_CONTEXT);
        }
        Money rounded = new Money(total.setScale(2, RoundingMode.HALF_UP), RUB);
        return new PenaltyEstimate(covered, rounded);
    }

    private static void validateSeries(List<PenaltyInterval> intervals) {
        PenaltyInterval previous = null;
        for (PenaltyInterval interval : intervals) {
            Objects.requireNonNull(interval, "interval");
            if (!RUB.equals(interval.applicableDebt().currency())) {
                throw new IllegalArgumentException("Tax penalty estimate supports RUB debt only");
            }
            if (previous != null) {
                if (interval.fromInclusive().isBefore(previous.fromInclusive())) {
                    throw new IllegalArgumentException("Penalty intervals must be sorted by fromInclusive");
                }
                if (interval.fromInclusive().isBefore(previous.toExclusive())) {
                    throw new IllegalArgumentException("Penalty intervals in one debt series must not overlap");
                }
            }
            previous = interval;
        }
    }
}
