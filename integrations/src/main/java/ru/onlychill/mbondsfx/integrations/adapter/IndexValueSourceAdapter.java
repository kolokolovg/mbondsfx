package ru.onlychill.mbondsfx.integrations.adapter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.bond.calculation.MissingIndexValueException;
import ru.onlychill.mbondsfx.core.bond.calculation.port.IndexValueSource;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.integrations.index.IndexFixing;
import ru.onlychill.mbondsfx.integrations.index.IndexFixingSource;
import ru.onlychill.mbondsfx.integrations.index.MissingFixingException;

/** Explicitly adapts external fixing records to the domain's exact index-value port. */
public final class IndexValueSourceAdapter implements IndexValueSource {
    private final IndexFixingSource fixings;

    public IndexValueSourceAdapter(IndexFixingSource fixings) {
        this.fixings = Objects.requireNonNull(fixings, "fixings");
    }

    @Override
    public BigDecimal value(IndexId index, LocalDate date) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(date, "date");
        IndexFixing fixing;
        try {
            fixing = fixings.fixing(index, date);
        } catch (MissingFixingException missing) {
            throw new MissingIndexValueException(index, date, missing);
        }
        if (fixing == null || !index.equals(fixing.index()) || !date.equals(fixing.date())) {
            throw new MissingIndexValueException(index, date);
        }
        return fixing.value();
    }
}
