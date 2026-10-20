module ru.onlychill.mbondsfx.persistence.memory {
    requires ru.onlychill.mbondsfx.application;

    provides ru.onlychill.mbondsfx.application.port.LedgerRepositoryFactory
            with ru.onlychill.mbondsfx.persistence.memory.InMemoryRepositoryFactory;
}
