package ru.onlychill.mbondsfx.application.service;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.application.port.DuplicateOperationException;
import ru.onlychill.mbondsfx.application.port.LedgerCommit;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.money.Money;

/** Appends an explicit correction to the cumulative withholding for an already-posted operation. */
public final class TaxCorrectionService {
    private final LedgerRepository repository;

    public TaxCorrectionService(LedgerRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Corrects the total withholding recorded for an original operation. The corrected total is a caller-supplied
     * fact, not a tax calculation. A successful correction is appended atomically; it never rewrites the original
     * operation or retries a repository conflict.
     */
    public LedgerSnapshot correct(PortfolioId portfolio, OperationId correctionOperation,
                                  LocalDate correctionDate, OperationId originalOperation,
                                  Money correctedTotal, boolean allowNegativeCash) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(correctionOperation, "correctionOperation");
        Objects.requireNonNull(correctionDate, "correctionDate");
        Objects.requireNonNull(originalOperation, "originalOperation");
        Objects.requireNonNull(correctedTotal, "correctedTotal");
        if (correctedTotal.isNegative()) {
            throw new IllegalArgumentException("correctedTotal must not be negative");
        }

        Optional<LedgerSnapshot> loaded = Objects.requireNonNull(repository.load(portfolio),
                "repository.load result");
        LedgerSnapshot snapshot = loaded.orElseThrow(() -> new IllegalStateException("Portfolio ledger is unknown"));
        if (!portfolio.equals(snapshot.portfolio())) {
            throw new IllegalStateException("Repository returned a snapshot for a different portfolio");
        }
        if (allowNegativeCash != snapshot.state().allowNegativeCash()) {
            throw new IllegalStateException("Cash policy differs from the persisted ledger policy");
        }

        Ledger before = Ledger.restore(snapshot.state());
        if (before.operationIds().contains(correctionOperation)) {
            throw new DuplicateOperationException(portfolio, correctionOperation);
        }

        Money currentTotal = before.taxWithheldForOperation(originalOperation);
        if (!correctedTotal.currency().equals(currentTotal.currency())) {
            throw new IllegalArgumentException("correctedTotal currency differs from original withholding currency");
        }
        Money adjustment = correctedTotal.subtract(currentTotal);
        if (adjustment.isZero()) {
            throw new IllegalArgumentException("correctedTotal does not change the recorded withholding");
        }

        var operation = BondOperations.taxCorrection(correctionOperation, correctionDate, portfolio,
                originalOperation, adjustment);
        Ledger after = before.apply(operation);
        return repository.commit(new LedgerCommit(snapshot.version(), before.state(), operation, after.state()));
    }
}
