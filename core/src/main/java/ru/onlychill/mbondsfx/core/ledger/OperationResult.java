package ru.onlychill.mbondsfx.core.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.OperationId;

/** Immutable outcome created by the supported operations in {@link BondOperations}. */
public final class OperationResult {
    public enum OperationKind {
        OPENING, DEPOSIT, WITHDRAWAL, PURCHASE, COUPON, AMORTIZATION, SALE, TAX_CORRECTION
    }

    private final OperationKind kind;
    private final OperationId operation;
    private final LocalDate date;
    private final List<Posting> postings;
    private final Optional<Position> positionBefore;
    private final Optional<Position> positionAfter;

    OperationResult(OperationKind kind, OperationId operation, LocalDate date,
                    List<Posting> postings, Optional<Position> positionBefore,
                    Optional<Position> positionAfter) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.date = Objects.requireNonNull(date, "date");
        this.postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        this.positionBefore = Objects.requireNonNull(positionBefore, "positionBefore");
        this.positionAfter = Objects.requireNonNull(positionAfter, "positionAfter");
        if (this.postings.isEmpty()) {
            throw new IllegalArgumentException("An operation must contain at least one posting");
        }
        for (Posting posting : this.postings) {
            if (!operation.equals(posting.operation()) || !date.equals(posting.date())) {
                throw new IllegalArgumentException("Posting operation and date must match the result");
            }
            if ((kind == OperationKind.TAX_CORRECTION) != posting.adjustsOperation().isPresent()) {
                throw new IllegalArgumentException("Only tax correction postings may link to an adjusted operation");
            }
        }
        if (kind == OperationKind.TAX_CORRECTION && this.postings.size() != 1) {
            throw new IllegalArgumentException("A tax correction must contain exactly one posting");
        }
        boolean securityOperation = switch (kind) {
            case PURCHASE, COUPON, AMORTIZATION, SALE -> true;
            case OPENING, DEPOSIT, WITHDRAWAL, TAX_CORRECTION -> false;
        };
        if (securityOperation != (this.positionBefore.isPresent() && this.positionAfter.isPresent())) {
            throw new IllegalArgumentException("Position snapshots do not match operation kind");
        }
        if (!securityOperation && (this.positionBefore.isPresent() || this.positionAfter.isPresent())) {
            throw new IllegalArgumentException("Cash operations cannot contain position snapshots");
        }
        if (securityOperation) {
            Position before = this.positionBefore.orElseThrow();
            Position after = this.positionAfter.orElseThrow();
            if (!before.id().equals(after.id())
                    || !before.carryingAmount().currency().equals(after.carryingAmount().currency())) {
                throw new IllegalArgumentException("Position snapshots must have the same identity and currency");
            }
        }
    }

    public OperationKind kind() { return kind; }
    public OperationId operation() { return operation; }
    public LocalDate date() { return date; }
    public List<Posting> postings() { return postings; }
    public Optional<Position> positionBefore() { return positionBefore; }
    public Optional<Position> positionAfter() { return positionAfter; }
}
