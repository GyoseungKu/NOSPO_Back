package kr.nospo.book.contract;

import java.time.Instant;
import java.util.UUID;

public record SearchHistory(UUID historyId, String query, SearchType type, Instant searchedAt) {}
