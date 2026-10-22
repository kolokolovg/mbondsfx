package ru.onlychill.mbondsfx.core.bond.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.money.DayCount;
import ru.onlychill.mbondsfx.core.money.DayCounts;
import ru.onlychill.mbondsfx.core.money.Money;
import ru.onlychill.mbondsfx.core.bond.AmortizationEvent;
import ru.onlychill.mbondsfx.core.bond.BondDefinition;
import ru.onlychill.mbondsfx.core.bond.Coupon;
import ru.onlychill.mbondsfx.core.bond.DatedPeriod;
import ru.onlychill.mbondsfx.core.bond.FixedCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingCoupon;
import ru.onlychill.mbondsfx.core.bond.FloatingRateFormula;
import ru.onlychill.mbondsfx.core.bond.IndexationRule;
import ru.onlychill.mbondsfx.core.bond.IndexLinkedCoupon;
import ru.onlychill.mbondsfx.core.bond.KnownCoupon;
import ru.onlychill.mbondsfx.core.bond.Principal;

/** Static bond calculations. Each formula is explicit and uses BigDecimal only. */
public final class BondCalculations {
    private static final MathContext DECIMAL128 = MathContext.DECIMAL128;

    private BondCalculations() {
    }

    /** Outstanding per-unit face on {@code on}; amortization events on the date are applied. */
    public static Money facePerUnit(BondDefinition bond, LocalDate on, IndexValueSource fixings) {
        Objects.requireNonNull(bond, "bond");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(fixings, "fixings");
        validateCalculationDate(bond, on);

        BigDecimal nominal = nominalAt(bond.principal(), on);
        Optional<IndexationRule> indexation = bond.principal().indexation();
        if (indexation.isEmpty()) {
            return new Money(nominal, bond.currency());
        }

        IndexationRule rule = indexation.orElseThrow();
        LocalDate observationDate = rule.observationDate(on);
        BigDecimal indexValue = fixingValue(fixings, rule.index(), observationDate);
        BigDecimal coefficient = indexValue.divide(rule.baseIndexValue(), DECIMAL128);
        BigDecimal indexedFace = nominal.multiply(coefficient);
        if (indexedFace.signum() < 0) {
            throw new IllegalArgumentException("Indexation must not produce negative outstanding face");
        }
        return new Money(indexedFace, bond.currency()).multiply(BigDecimal.ONE);
    }

    /**
     * Exact authoritative amount for KnownCoupon, otherwise a formula amount. Floating coupons
     * use the day-count embedded in FloatingRateFormula; the parameter is used for fixed/linked coupons.
     */
    public static Money couponAmount(BondDefinition bond, DatedPeriod period,
                                     DayCount dayCount, IndexValueSource fixings) {
        Objects.requireNonNull(bond, "bond");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(fixings, "fixings");
        Coupon coupon = bond.coupon().orElseThrow(() ->
                new IllegalArgumentException("Bond has no coupon schedule"));
        requireDeclaredPeriod(coupon, period);

        if (coupon instanceof KnownCoupon known) {
            return knownAmount(known, period).amountPerUnit();
        }
        rejectFormulaOnIndexedPrincipal(bond, coupon);

        BigDecimal rate;
        BigDecimal indexCoefficient = BigDecimal.ONE;
        DayCount calculationDayCount = dayCount;
        if (coupon instanceof FixedCoupon fixed) {
            rate = fixed.annualRate();
        } else if (coupon instanceof FloatingCoupon floating) {
            FloatingRateFormula formula = floating.formula();
            rate = floatingRate(formula, period.accrualStart(), fixings);
            calculationDayCount = formula.dayCount();
        } else if (coupon instanceof IndexLinkedCoupon linked) {
            rate = linked.annualRate();
            LocalDate reference = referenceDate(linked.referenceDate(), period, period.accrualEnd());
            indexCoefficient = indexCoefficient(linked.indexation(), reference, fixings);
        } else {
            throw new UnsupportedCalculationException("Unsupported coupon type: " + coupon.getClass().getName());
        }

        BigDecimal unrounded = segmentedInterest(bond.principal(), period.accrualStart(), period.accrualEnd(),
                rate, indexCoefficient, calculationDayCount);
        return new Money(unrounded, bond.currency()).multiply(BigDecimal.ONE);
    }

    /**
     * Accrued amount for the half-open elapsed interval [period start, settle). KnownCoupon accrual
     * is an explicit linear year-fraction proration, not a universal issuer-specific NCD formula.
     * On the start date of a period the elapsed share is zero for every day-count, and the day-count is not
     * consulted for that zero-length interval (docs/specs/day-count/v1.0.1.md, points 1-2).
     */
    public static AccruedInterest accrued(BondDefinition bond, LocalDate settle,
                                           DayCount dayCount, IndexValueSource fixings) {
        Objects.requireNonNull(bond, "bond");
        Objects.requireNonNull(settle, "settle");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(fixings, "fixings");
        validateCalculationDate(bond, settle);

        if (bond.coupon().isEmpty()) {
            return new AccruedInterest(Money.zero(bond.currency()), Optional.empty(), dayCount);
        }

        Coupon coupon = bond.coupon().orElseThrow();
        DayCount calculationDayCount = coupon instanceof FloatingCoupon floating
                ? floating.formula().dayCount() : dayCount;
        if (!(coupon instanceof KnownCoupon)) {
            rejectFormulaOnIndexedPrincipal(bond, coupon);
        }
        Optional<DatedPeriod> matching = coupon.periods().stream()
                .filter(period -> !settle.isBefore(period.accrualStart())
                        && settle.isBefore(period.accrualEnd()))
                .findFirst();
        if (matching.isEmpty()) {
            return new AccruedInterest(Money.zero(bond.currency()), Optional.empty(), calculationDayCount);
        }

        DatedPeriod period = matching.orElseThrow();
        if (coupon instanceof KnownCoupon known) {
            KnownCoupon.Amount amount = knownAmount(known, period);
            BigDecimal elapsed = settle.equals(period.accrualStart())
                    ? BigDecimal.ZERO
                    : segmentYearFraction(dayCount, period.accrualStart(), settle);
            BigDecimal full = positiveYearFraction(dayCount, period.accrualStart(), period.accrualEnd());
            BigDecimal ratio = elapsed.divide(full, DECIMAL128);
            return new AccruedInterest(amount.amountPerUnit().multiply(ratio), matching, dayCount);
        }

        BigDecimal rate;
        BigDecimal indexCoefficient = BigDecimal.ONE;
        if (coupon instanceof FixedCoupon fixed) {
            rate = fixed.annualRate();
        } else if (coupon instanceof FloatingCoupon floating) {
            FloatingRateFormula formula = floating.formula();
            LocalDate fixingDate = fixingDate(formula, period.accrualStart());
            if (fixingDate.isAfter(settle)) {
                throw new MissingIndexValueException(formula.index(), fixingDate);
            }
            rate = floatingRate(formula, period.accrualStart(), fixings);
            calculationDayCount = formula.dayCount();
        } else if (coupon instanceof IndexLinkedCoupon linked) {
            rate = linked.annualRate();
            LocalDate reference = referenceDate(linked.referenceDate(), period, settle);
            LocalDate observationDate = linked.indexation().observationDate(reference);
            if (observationDate.isAfter(settle)) {
                throw new MissingIndexValueException(linked.indexation().index(), observationDate);
            }
            indexCoefficient = indexCoefficient(linked.indexation(), reference, fixings);
        } else {
            throw new UnsupportedCalculationException("Unsupported coupon type: " + coupon.getClass().getName());
        }

        BigDecimal unrounded = segmentedInterest(bond.principal(), period.accrualStart(), settle,
                rate, indexCoefficient, calculationDayCount);
        Money rounded = new Money(unrounded, bond.currency()).multiply(BigDecimal.ONE);
        return new AccruedInterest(rounded, matching, calculationDayCount);
    }

    private static void validateCalculationDate(BondDefinition bond, LocalDate date) {
        if (date.isBefore(bond.issueDate())) {
            throw new IllegalArgumentException("Calculation date must not precede issueDate");
        }
        if (bond.maturityDate().isPresent() && date.isAfter(bond.maturityDate().orElseThrow())) {
            throw new IllegalArgumentException("Calculation date must not be after maturityDate");
        }
    }

    private static void requireDeclaredPeriod(Coupon coupon, DatedPeriod period) {
        if (!coupon.periods().contains(period)) {
            throw new IllegalArgumentException("Period is not declared by the bond coupon");
        }
    }

    private static KnownCoupon.Amount knownAmount(KnownCoupon coupon, DatedPeriod period) {
        return coupon.amounts().stream()
                .filter(amount -> amount.period().equals(period))
                .findFirst()
                .orElseThrow(() -> new UnsupportedCalculationException(
                        "No announced amount for coupon period " + period));
    }

    private static void rejectFormulaOnIndexedPrincipal(BondDefinition bond, Coupon coupon) {
        if (bond.principal().indexation().isPresent()
                && (coupon instanceof FixedCoupon || coupon instanceof FloatingCoupon
                || coupon instanceof IndexLinkedCoupon)) {
            throw new UnsupportedCalculationException(
                    "Formula-based coupon/accrued calculations with indexed principal require KnownCoupon amounts");
        }
    }

    private static BigDecimal nominalAt(Principal principal, LocalDate on) {
        BigDecimal nominal = principal.initialFacePerUnit().amount();
        for (AmortizationEvent event : principal.events()) {
            if (event.amortDate().isAfter(on)) {
                break;
            }
            nominal = nominal.subtract(event.faceReductionPerUnit().amount());
        }
        return nominal;
    }

    private static BigDecimal segmentedInterest(Principal principal, LocalDate start, LocalDate end,
                                                BigDecimal annualRate, BigDecimal indexCoefficient,
                                                DayCount dayCount) {
        if (start.equals(end)) {
            return BigDecimal.ZERO;
        }
        List<LocalDate> boundaries = new ArrayList<>();
        for (AmortizationEvent event : principal.events()) {
            if (event.amortDate().isAfter(start) && event.amortDate().isBefore(end)) {
                if (boundaries.isEmpty() || !boundaries.get(boundaries.size() - 1).equals(event.amortDate())) {
                    boundaries.add(event.amortDate());
                }
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        LocalDate segmentStart = start;
        for (LocalDate boundary : boundaries) {
            total = total.add(segmentInterest(principal, segmentStart, boundary,
                    annualRate, indexCoefficient, dayCount));
            segmentStart = boundary;
        }
        total = total.add(segmentInterest(principal, segmentStart, end,
                annualRate, indexCoefficient, dayCount));
        return total;
    }

    private static BigDecimal segmentInterest(Principal principal, LocalDate start, LocalDate end,
                                              BigDecimal annualRate, BigDecimal indexCoefficient,
                                              DayCount dayCount) {
        BigDecimal fraction = segmentYearFraction(dayCount, start, end);
        BigDecimal nominal = nominalAt(principal, start);
        return nominal.multiply(annualRate).multiply(indexCoefficient).multiply(fraction);
    }

    /**
     * 30/360 and 30E/360 cap the day of month at 30, so a positive interval such as 30 Jan to 31 Jan legitimately
     * has a zero year fraction, and that segment contributes nothing to the coupon or accrued interest. Only these
     * two instances, compared by identity, may yield zero. Every other day-count, including a lookalike instance of
     * either convention and 30E+/360, must yield a positive fraction for a positive interval. A negative fraction is
     * rejected in every case. This check also governs the elapsed share of a KnownCoupon accrual after its start; the
     * full period of a KnownCoupon is checked separately by positiveYearFraction and stays strict.
     */
    private static BigDecimal segmentYearFraction(DayCount dayCount, LocalDate start, LocalDate end) {
        if (dayCount == DayCounts.thirty360() || dayCount == DayCounts.thirtyE360()) {
            return nonNegativeYearFraction(dayCount, start, end);
        }
        return positiveYearFraction(dayCount, start, end);
    }

    private static BigDecimal positiveYearFraction(DayCount dayCount, LocalDate start, LocalDate end) {
        BigDecimal fraction = Objects.requireNonNull(dayCount.yearFraction(start, end), "yearFraction");
        if (fraction.signum() <= 0) {
            throw new IllegalArgumentException("DayCount must return a positive fraction for a positive interval");
        }
        return fraction;
    }

    private static BigDecimal nonNegativeYearFraction(DayCount dayCount, LocalDate start, LocalDate end) {
        BigDecimal fraction = Objects.requireNonNull(dayCount.yearFraction(start, end), "yearFraction");
        if (fraction.signum() < 0) {
            throw new IllegalArgumentException("DayCount must not return a negative fraction");
        }
        return fraction;
    }

    private static BigDecimal floatingRate(FloatingRateFormula formula, LocalDate accrualStart,
                                           IndexValueSource fixings) {
        LocalDate fixingDate = fixingDate(formula, accrualStart);
        BigDecimal rate = fixingValue(fixings, formula.index(), fixingDate).add(formula.spread());
        if (formula.floor().isPresent() && rate.compareTo(formula.floor().orElseThrow()) < 0) {
            rate = formula.floor().orElseThrow();
        }
        if (formula.cap().isPresent() && rate.compareTo(formula.cap().orElseThrow()) > 0) {
            rate = formula.cap().orElseThrow();
        }
        return rate;
    }

    private static LocalDate fixingDate(FloatingRateFormula formula, LocalDate accrualStart) {
        return accrualStart.plusDays(formula.fixingOffsetDays());
    }

    private static LocalDate referenceDate(IndexLinkedCoupon.ReferenceDate policy,
                                           DatedPeriod period, LocalDate calculationDate) {
        return switch (policy) {
            case PERIOD_START -> period.accrualStart();
            case PERIOD_END -> period.accrualEnd();
            case CALCULATION_DATE -> calculationDate;
        };
    }

    private static BigDecimal indexCoefficient(IndexationRule rule, LocalDate reference,
                                               IndexValueSource fixings) {
        LocalDate observationDate = rule.observationDate(reference);
        BigDecimal coefficient = fixingValue(fixings, rule.index(), observationDate)
                .divide(rule.baseIndexValue(), DECIMAL128);
        if (coefficient.signum() < 0) {
            throw new IllegalArgumentException("Indexation must not produce negative index coefficient");
        }
        return coefficient;
    }

    private static BigDecimal fixingValue(IndexValueSource values, IndexId index, LocalDate date) {
        BigDecimal value;
        try {
            value = values.value(index, date);
        } catch (MissingIndexValueException missing) {
            if (index.equals(missing.index()) && date.equals(missing.date())) {
                throw missing;
            }
            throw new MissingIndexValueException(index, date, missing);
        }
        if (value == null) {
            throw new MissingIndexValueException(index, date);
        }
        return value;
    }
}
