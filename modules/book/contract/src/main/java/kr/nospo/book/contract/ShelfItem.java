package kr.nospo.book.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public record ShelfItem(BookSummary book, Position currentPosition, Position highestPosition,
        PositionContext positionContext, @Schema(nullable = true) BigDecimal progressPercent, Instant addedAt,
        @Schema(nullable = true) Instant lastProgressAt) {}
