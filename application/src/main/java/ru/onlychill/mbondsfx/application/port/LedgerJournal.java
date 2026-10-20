package ru.onlychill.mbondsfx.application.port;

import java.util.List;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** Read-only operation history in ascending (date, sequence) order. */
public interface LedgerJournal {
    List<OperationRecord> operations(PortfolioId portfolio);
}
