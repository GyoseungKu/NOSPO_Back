package kr.nospo.book.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Progress(UUID bookId, Position currentPosition, Position highestPosition, PositionContext positionContext,
        BigDecimal progressPercent, Instant updatedAt) {}
