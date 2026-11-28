package kr.nospo.book.contract;

import java.time.Instant;
import java.util.UUID;

public record ReadingRecord(UUID recordId, UUID bookId, Position fromPosition, Position toPosition,
        int addedUnits, Instant recordedAt) {}
