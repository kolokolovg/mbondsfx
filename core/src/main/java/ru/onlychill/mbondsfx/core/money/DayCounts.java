package ru.onlychill.mbondsfx.core.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class DayCounts {
    private static final MathContext DECIMAL128 = MathContext.DECIMAL128;
    private static final DayCount ACT_365F = fixedBasis(365);
    private static final DayCount ACT_360 = fixedBasis(360);
    private static final DayCount ACT_ACT_ISDA = new ActActIsda();
    private static final DayCount THIRTY_360 = new Thirty360();
    private static final DayCount THIRTY_E_360 = new ThirtyE360();
    private static final DayCount THIRTY_E_PLUS_360 = new ThirtyEPlus360();

    private DayCounts() {
    }

    public static DayCount of(String code) {
        return switch (Objects.requireNonNull(code, "code")) {
            case "ACT/365F" -> act365F();
            case "ACT/360" -> act360();
            case "ACT/ACT.ISDA" -> actActIsda();
            case "30/360" -> thirty360();
            case "30E/360" -> thirtyE360();
            case "30E+/360" -> thirtyEPlus360();
            default -> throw new IllegalArgumentException("Unknown day-count code: " + code);
        };
    }

    public static DayCount act365F() {
        return ACT_365F;
    }

    public static DayCount act360() {
        return ACT_360;
    }

    public static DayCount actActIsda() {
        return ACT_ACT_ISDA;
    }

    /**
     * 30/360 (bond basis). Each call returns the same instance; {@code BondCalculations} exempts this instance, compared
     * by identity, from the positive-fraction check on a coupon or accrued segment.
     */
    public static DayCount thirty360() {
        return THIRTY_360;
    }

    /** 30E/360 (Eurobond basis). Each call returns the same instance, compared by identity elsewhere. */
    public static DayCount thirtyE360() {
        return THIRTY_E_360;
    }

    /** 30E+/360: the 31st at the end of an interval is read as the 1st of the following month. */
    public static DayCount thirtyEPlus360() {
        return THIRTY_E_PLUS_360;
    }

    private static DayCount fixedBasis(int basis) {
        return new DayCount() {
            @Override
            public BigDecimal yearFraction(LocalDate start, LocalDate end) {
                return BigDecimal.valueOf(days(start, end))
                        .divide(BigDecimal.valueOf(basis), DECIMAL128);
            }

            @Override
            public long days(LocalDate start, LocalDate end) {
                return ChronoUnit.DAYS.between(Objects.requireNonNull(start, "start"),
                        Objects.requireNonNull(end, "end"));
            }
        };
    }

    private static final class ActActIsda implements DayCount {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (start.isAfter(end)) {
                return yearFraction(end, start).negate();
            }

            BigDecimal fraction = BigDecimal.ZERO;
            LocalDate segmentStart = start;
            while (segmentStart.isBefore(end)) {
                LocalDate nextYear = LocalDate.of(segmentStart.getYear() + 1, 1, 1);
                LocalDate segmentEnd = nextYear.isBefore(end) ? nextYear : end;
                long segmentDays = ChronoUnit.DAYS.between(segmentStart, segmentEnd);
                int yearLength = segmentStart.isLeapYear() ? 366 : 365;
                fraction = fraction.add(BigDecimal.valueOf(segmentDays)
                        .divide(BigDecimal.valueOf(yearLength), DECIMAL128));
                segmentStart = segmentEnd;
            }
            return fraction.round(DECIMAL128);
        }

        @Override
        public long days(LocalDate start, LocalDate end) {
            return ChronoUnit.DAYS.between(Objects.requireNonNull(start, "start"),
                    Objects.requireNonNull(end, "end"));
        }
    }

    private static final class ThirtyE360 implements DayCount {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            return BigDecimal.valueOf(days(start, end)).divide(BigDecimal.valueOf(360), DECIMAL128);
        }

        @Override
        public long days(LocalDate start, LocalDate end) {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            int startDay = Math.min(start.getDayOfMonth(), 30);
            int endDay = Math.min(end.getDayOfMonth(), 30);
            return 360L * (end.getYear() - start.getYear())
                    + 30L * (end.getMonthValue() - start.getMonthValue())
                    + endDay - startDay;
        }
    }

    /**
     * 30/360 by formula (1): a start on the 31st becomes the 30th; an end on the 31st becomes the 30th only when the
     * unadjusted start day is 30 or 31. A start on day 29 or earlier leaves an end on the 31st unchanged.
     * A reversed interval is the negated result of the forward interval, as in the specification.
     */
    private static final class Thirty360 implements DayCount {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            return BigDecimal.valueOf(days(start, end)).divide(BigDecimal.valueOf(360), DECIMAL128);
        }

        @Override
        public long days(LocalDate start, LocalDate end) {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (start.isAfter(end)) {
                return -days(end, start);
            }
            int startDay = Math.min(start.getDayOfMonth(), 30);
            int endDay = end.getDayOfMonth() == 31 && start.getDayOfMonth() >= 30 ? 30 : end.getDayOfMonth();
            return 360L * (end.getYear() - start.getYear())
                    + 30L * (end.getMonthValue() - start.getMonthValue())
                    + endDay - startDay;
        }
    }

    /**
     * 30E+/360 by formula (1): a start on the 31st becomes the 30th; an end on the 31st becomes the 1st of the
     * following month, with the year carried over after December. A reversed interval is the negated result of the
     * forward interval, as in the specification.
     */
    private static final class ThirtyEPlus360 implements DayCount {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            return BigDecimal.valueOf(days(start, end)).divide(BigDecimal.valueOf(360), DECIMAL128);
        }

        @Override
        public long days(LocalDate start, LocalDate end) {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (start.isAfter(end)) {
                return -days(end, start);
            }
            int startDay = Math.min(start.getDayOfMonth(), 30);
            YearMonth endMonth = YearMonth.from(end);
            int endDay = end.getDayOfMonth();
            if (endDay == 31) {
                endMonth = endMonth.plusMonths(1);
                endDay = 1;
            }
            return 360L * (endMonth.getYear() - start.getYear())
                    + 30L * (endMonth.getMonthValue() - start.getMonthValue())
                    + endDay - startDay;
        }
    }
}
