package kr.nospo.book.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.book.contract.BookType;
import kr.nospo.book.contract.PositionContext;
import kr.nospo.book.contract.ReadingRecord;
import kr.nospo.book.contract.ShelfSort;

public interface ShelfStore {
    record Progress(int current, int highest, Instant lastProgressAt, long revision) {}
    record Entry(UUID bookId, Progress progress, PositionContext context, Instant addedAt, String title) {}
    record Boundary(UUID bookId, String sortKey) {}
    record RecordBoundary(Instant recordedAt, UUID recordId) {}

    Optional<Progress> progress(UUID owner, UUID bookId);
    void insertProgress(UUID owner, UUID bookId, Progress progress);
    void updateProgress(UUID owner, UUID bookId, Progress progress);
    void attach(UUID owner, UUID bookId, Instant addedAt);
    boolean detach(UUID owner, UUID bookId);
    Optional<Entry> find(UUID owner, UUID bookId);
    List<Entry> list(UUID owner, BookType type, ShelfSort sort, Boundary after, int fetch);
    UUID record(UUID owner, UUID bookId, int from, int to, int addedUnits, Instant recordedAt);
    List<ReadingRecord> records(UUID owner, UUID bookId, Instant start, Instant end, RecordBoundary after, int fetch);
}
