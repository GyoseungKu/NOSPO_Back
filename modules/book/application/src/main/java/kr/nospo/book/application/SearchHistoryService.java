package kr.nospo.book.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.book.contract.Page;
import kr.nospo.book.contract.SearchHistory;
import kr.nospo.book.contract.SearchType;
import kr.nospo.support.ApiException;
import kr.nospo.support.Secrets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchHistoryService {
    private final SearchHistoryStore store;
    private final BookCursor cursors;
    private final Secrets secrets;
    private final Clock clock;
    private final SignupIdentity identity;

    SearchHistoryService(SearchHistoryStore store, BookCursor cursors, Secrets secrets, Clock clock,
            SignupIdentity identity) {
        this.store = store;
        this.cursors = cursors;
        this.secrets = secrets;
        this.clock = clock;
        this.identity = identity;
    }

    @Transactional
    public void record(UUID owner, UUID sessionId, String query, SearchType type) {
        if (!store.lockActiveMember(owner) || !identity.sessionActive(owner, sessionId, clock.instant())) return;
        String key = type == SearchType.ISBN ? BookNames.isbn(query) : BookNames.key(query);
        store.record(owner, query, secrets.hash(key), type, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    public Page<SearchHistory> list(UUID owner, String cursor, int limit) {
        BookService.checkLimit(limit);
        String scope = "history:" + owner;
        String position = cursors.decode(scope, cursor);
        String[] before = position == null ? null : position.split("\\|");
        var rows = store.list(owner, before == null ? null : Instant.parse(before[0]),
                before == null ? null : UUID.fromString(before[1]), limit + 1);
        if (rows.size() <= limit) return Page.of(rows, null);
        var last = rows.get(limit - 1);
        return Page.of(rows.subList(0, limit), cursors.encode(scope, last.searchedAt() + "|" + last.historyId()));
    }

    @Transactional
    public void delete(UUID owner, UUID historyId) {
        if (!store.lockActiveMember(owner) || !store.delete(owner, historyId))
            throw new ApiException(404, "RESOURCE_NOT_FOUND");
    }

    @Transactional
    public void deleteAll(UUID owner) {
        if (store.lockActiveMember(owner)) store.deleteAll(owner);
    }
}
