package ru.onlychill.mbondsfx.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.ledger.Posting;

/** Read model of one domain operation, ordered by the repository-assigned sequence. */
public record OperationRecord(OperationResult.OperationKind kind, OperationId operation, LocalDate date,
                              List<Posting> postings, Optional<Position> positionBefore,
                              Optional<Position> positionAfter, long sequence) {
    public OperationRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(date, "date");
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        Objects.requireNonNull(positionBefore, "positionBefore");
        Objects.requireNonNull(positionAfter, "positionAfter");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (postings.isEmpty()) {
            throw new IllegalArgumentException("Operation record must contain at least one posting");
        }
        for (Posting posting : postings) {
            if (!operation.equals(posting.operation()) || !date.equals(posting.date())) {
                throw new IllegalArgumentException("Posting identity/date differs from operation record");
            }
        }
        boolean securityOperation = switch (kind) {
            case PURCHASE, COUPON, AMORTIZATION, SALE -> true;
            case OPENING, DEPOSIT, WITHDRAWAL, TAX_CORRECTION -> false;
        };
        if (securityOperation != (positionBefore.isPresent() && positionAfter.isPresent())
                || (!securityOperation && (positionBefore.isPresent() || positionAfter.isPresent()))) {
            throw new IllegalArgumentException("Position snapshots do not match operation kind");
        }
    }

    public static OperationRecord from(OperationResult operation, long sequence) {
        Objects.requireNonNull(operation, "operation");
        return new OperationRecord(operation.kind(), operation.operation(), operation.date(), operation.postings(),
                operation.positionBefore(), operation.positionAfter(), sequence);
    }
}
