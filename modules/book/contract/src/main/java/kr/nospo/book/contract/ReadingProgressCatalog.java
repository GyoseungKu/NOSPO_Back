package kr.nospo.book.contract;

import java.util.Optional;
import java.util.UUID;

/** Read view for trusted modules. Callers must authorize the member before reading. */
public interface ReadingProgressCatalog {
    record Snapshot(Progress progress, long revision) {}

    /** Returns a snapshot for an active member's active shelf link. Use currentPosition to bound spoilers. */
    Optional<Snapshot> activeProgress(UUID memberId, UUID bookId);
}
