package ru.onlychill.mbondsfx.application.port;

import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.SecurityId;

/** Reads sparse, source-neutral facts about a bond; it does not construct domain issue terms
 * or infer a missing observation date.
 */
@FunctionalInterface
public interface BondReferenceDataSource {
    Optional<BondReference> reference(SecurityId security);
}
