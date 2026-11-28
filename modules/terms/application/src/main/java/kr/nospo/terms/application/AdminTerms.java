package kr.nospo.terms.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import kr.nospo.support.ApiException;
import kr.nospo.member.contract.MemberAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminTerms {
    public record TermVersionCreate(UUID termId, String type, String version, String title, String content, Boolean required) {}
    public record TermPublication(Instant effectiveAt, Boolean requiresReconsent) {}
    public record TermVersionView(UUID termVersionId, UUID termId, String type, String version, String title, boolean required,
                       Instant effectiveAt, boolean requiresReconsent, String publicationStatus) {}
    public record TermVersionDetail(UUID termVersionId, UUID termId, String type, String version, String title, boolean required,
                         Instant effectiveAt, boolean requiresReconsent, String publicationStatus, String content) {
        TermVersionView summary() {
            return new TermVersionView(termVersionId, termId, type, version, title, required, effectiveAt, requiresReconsent,
                    publicationStatus);
        }
    }
    public record TermVersionPage(List<TermVersionView> items, String nextCursor, boolean hasNext) {}
    public record Cursor(Instant createdAt, UUID versionId) {}
    public record Row(TermVersionView version, Instant createdAt) {}

    public interface Store {
        Optional<String> termType(UUID termId);
        void insertTerm(UUID termId, String type, Instant now);
        void insertVersion(UUID id, TermVersionCreate input, Instant now);
        Optional<TermVersionDetail> adminDetail(UUID id);
        List<Row> adminVersions(UUID termId, Cursor after, int limit);
        boolean schedule(UUID id, TermPublication publication, Instant now);
        void audit(UUID id, UUID actor, String action, String reason, Instant now);
    }

    private final Store store;
    private final TermStore terms;
    private final Clock clock;
    private final MemberAccess members;

    public AdminTerms(Store store, TermStore terms, Clock clock, MemberAccess members) {
        this.store = store;
        this.terms = terms;
        this.clock = clock;
        this.members = members;
    }

    public TermVersionPage list(UUID termId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw invalid();
        var rows = store.adminVersions(termId, decode(termId, cursor), limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(Row::version).toList();
        String next = null;
        if (more) {
            var last = rows.get(limit - 1);
            String payload = termId + "," + last.createdAt() + "," + last.version().termVersionId();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        }
        return new TermVersionPage(items, next, more);
    }

    public TermVersionDetail detail(UUID id) {
        return store.adminDetail(id).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    }

    @Transactional
    public TermVersionView create(UUID actor, TermVersionCreate input) {
        if (input.termId() == null || input.type() == null
                || !Set.of("SERVICE", "PRIVACY", "OPTIONAL").contains(input.type())
                || !text(input.version(), 32) || !text(input.title(), 200) || !text(input.content(), Integer.MAX_VALUE)
                || input.required() == null) throw invalid();

        lockAdministrator(actor);
        terms.lock();
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        try {
            var existing = store.termType(input.termId());
            if (existing.isPresent() && !existing.get().equals(input.type())) throw invalid();
            if (existing.isEmpty()) store.insertTerm(input.termId(), input.type(), now);
            store.insertVersion(id, input, now);
            store.audit(id, actor, "CREATE", "약관 버전 초안 등록", now);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(409, "TERM_VERSION_CONFLICT");
        }
        return detail(id).summary();
    }

    @Transactional
    public TermVersionView publish(UUID actor, UUID id, TermPublication input) {
        if (input.effectiveAt() == null || input.requiresReconsent() == null) throw invalid();
        lockAdministrator(actor);
        terms.lock();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var current = detail(id);
        if (current.effectiveAt() != null && !current.effectiveAt().isAfter(now))
            throw new ApiException(409, "TERM_ALREADY_EFFECTIVE");
        Instant effectiveAt = input.effectiveAt().truncatedTo(ChronoUnit.MICROS);
        if (effectiveAt.isBefore(now) || effectiveAt.isAfter(Instant.parse("9999-12-31T00:00:00Z"))) throw invalid();
        try {
            if (!store.schedule(id, new TermPublication(effectiveAt, input.requiresReconsent()), now))
                throw new ApiException(409, "TERM_ALREADY_EFFECTIVE");
            store.audit(id, actor, "PUBLICATION", "약관 시행일과 재동의 설정", now);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(409, "TERM_VERSION_CONFLICT");
        }
        return detail(id).summary();
    }

    private void lockAdministrator(UUID actor) {
        if (members.lockState(actor).filter(MemberAccess.State::administrator).isEmpty())
            throw new ApiException(403, "FORBIDDEN");
    }

    private static Cursor decode(UUID termId, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 160) throw new IllegalArgumentException();
            var fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(",", -1);
            if (fields.length != 3 || !String.valueOf(termId).equals(fields[0])) throw new IllegalArgumentException();
            Instant at = Instant.parse(fields[1]);
            if (at.isBefore(Instant.EPOCH) || at.isAfter(Instant.parse("9999-12-31T00:00:00Z")))
                throw new IllegalArgumentException();
            return new Cursor(at, UUID.fromString(fields[2]));
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            throw new ApiException(400, "INVALID_CURSOR");
        }
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }

    private static ApiException invalid() { return new ApiException(422, "VALIDATION_ERROR"); }
}
