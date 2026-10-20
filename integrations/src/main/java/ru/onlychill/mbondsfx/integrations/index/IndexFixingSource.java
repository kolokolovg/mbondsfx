package ru.onlychill.mbondsfx.integrations.index;

import java.time.LocalDate;
import ru.onlychill.mbondsfx.core.id.IndexId;

/** Exact-date fixing lookup only; implementations do not interpolate or select a latest value. */
@FunctionalInterface
public interface IndexFixingSource {
    IndexFixing fixing(IndexId index, LocalDate date);
}
