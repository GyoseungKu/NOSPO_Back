package kr.nospo.book.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.nospo.book.contract.SearchHistory;
import kr.nospo.book.contract.SearchType;

public interface SearchHistoryStore {
    boolean lockActiveMember(UUID memberId);

    void record(UUID memberId, String query, byte[] queryKey, SearchType type, Instant now);

    List<SearchHistory> list(UUID memberId, Instant before, UUID beforeId, int fetch);

    boolean delete(UUID memberId, UUID historyId);

    void deleteAll(UUID memberId);
}
