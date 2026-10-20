package ru.onlychill.mbondsfx.application.port;

/** Provider identity is selected explicitly by the composition root, not by the domain service. */
public interface LedgerRepositoryFactory {
    /** Stable, nonblank ID unique among the configured repository factories. */
    String providerId();

    LedgerRepository create(RepositoryConfig config);
}
