package ru.onlychill.mbondsfx.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.SecurityId;

/** Sparse facts explicitly reported by one named source, not an inferred BondDefinition.
 * observedOn is present only when that source explicitly reports its observation date.
 */
public record BondReference(SecurityId security, String sourceId, Optional<LocalDate> observedOn,
                            Optional<ReportedValue> faceValuePerUnit, Optional<NextCoupon> nextCoupon) {
    public BondReference {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        Objects.requireNonNull(observedOn, "observedOn");
        Objects.requireNonNull(faceValuePerUnit, "faceValuePerUnit");
        Objects.requireNonNull(nextCoupon, "nextCoupon");
    }

    /** A numeric fact paired with the exact nonblank unit label reported by its source. */
    public record ReportedValue(BigDecimal value, String unit) {
        public ReportedValue {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(unit, "unit");
            if (unit.isBlank()) {
                throw new IllegalArgumentException("unit must not be blank");
            }
        }
    }

    /** Sparse reported next-coupon facts; an absent date or amount is not inferred. */
    public record NextCoupon(Optional<LocalDate> paymentDate, Optional<ReportedValue> amount) {
        public NextCoupon {
            Objects.requireNonNull(paymentDate, "paymentDate");
            Objects.requireNonNull(amount, "amount");
            if (paymentDate.isEmpty() && amount.isEmpty()) {
                throw new IllegalArgumentException("Next coupon must contain at least one reported fact");
            }
        }
    }
}
