package kr.nospo.book.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Progress(UUID bookId, Position currentPosition, Position highestPosition, PositionContext positionContext,
        @Schema(nullable = true) BigDecimal progressPercent, Instant updatedAt) {}
