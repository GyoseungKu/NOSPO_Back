package kr.nospo.book.contract;

import java.math.BigDecimal;
import java.time.Instant;

public record ShelfItem(BookSummary book, Position currentPosition, Position highestPosition,
        PositionContext positionContext, BigDecimal progressPercent, Instant addedAt, Instant lastProgressAt) {}
