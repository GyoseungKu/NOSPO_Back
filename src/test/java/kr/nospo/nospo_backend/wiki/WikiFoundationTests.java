package kr.nospo.nospo_backend.wiki;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.support.ApiException;
import kr.nospo.wiki.application.WikiBookAccess;
import kr.nospo.wiki.application.WikiReadingScope;
import kr.nospo.wiki.persistence.JdbcWikiStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static kr.nospo.support.SqlIds.*;
import static kr.nospo.wiki.application.WikiBookAccess.*;
import static kr.nospo.wiki.application.WikiStore.*;
import static org.assertj.core.api.Assertions.*;

class WikiFoundationTests {
    @Test
    void resolvesOnlyTheCurrentReadingScopeOfAPermanentEdition() {
        UUID member = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        var scope = new WikiReadingScope(new WikiBookAccess() {
            public Optional<Edition> find(UUID id) {
                return book.equals(id) ? Optional.of(new Edition(Unit.EPISODE, 100)) : Optional.empty();
            }

            public Optional<Position> currentPosition(UUID owner, UUID id) {
                return member.equals(owner) && book.equals(id)
                        ? Optional.of(new Position(Unit.EPISODE, 30)) : Optional.empty();
            }
        });

        assertThat(scope.resolve(member, book, null)).isEqualTo(new Position(Unit.EPISODE, 30));
        var earlier = new Position(Unit.EPISODE, 20);
        assertThat(scope.resolve(member, book, earlier)).isEqualTo(earlier);
        assertThatThrownBy(() -> scope.resolve(member, book, new Position(Unit.EPISODE, 31)))
                .isInstanceOf(ApiException.class).hasMessage("POSITION_EXCEEDS_CURRENT");
        assertThatThrownBy(() -> scope.resolve(member, book, new Position(Unit.PAGE, 20)))
                .isInstanceOf(ApiException.class).hasMessage("INVALID_POSITION");
        assertThatThrownBy(() -> scope.resolve(UUID.randomUUID(), book, null))
                .isInstanceOf(ApiException.class).hasMessage("SHELF_REQUIRED");
        assertThatThrownBy(() -> scope.resolve(member, UUID.randomUUID(), null))
                .isInstanceOf(ApiException.class).hasMessage("RESOURCE_NOT_FOUND");
    }

    @Test
    void storesPrivateSnapshotsSeparatelyFromEditionScopedPublicVersions() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:wiki_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        var db = new JdbcTemplate(source);
        var store = new JdbcWikiStore(db);
        UUID member = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        UUID wiki = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        var now = Instant.parse("2026-11-27T00:00:00Z");
        db.update("""
                INSERT INTO member (member_id,nickname,nickname_key,phone_number,created_at,updated_at)
                VALUES (?,?,?,?,?,?)
                """, id(member), "reader", "reader", "+821012345678", at(now), at(now));

        store.insertWiki(wiki, book);
        var first = new PublishedVersion(wiki, book, 1, "{\"characters\":[]}", now);
        store.insertVersion(first);
        var draft = new Draft(draftId, member, book, Mode.UPDATE, DraftStatus.SUBMITTED, new Position(Unit.PAGE, 20),
                wiki, 1, null, 2, "{\"characters\":[{}]}", now);
        store.insertDraft(draft);
        var submission = new Submission(UUID.randomUUID(), draftId, ReviewStatus.PENDING, draft.snapshot(), now, null);
        store.insertSubmission(submission);

        assertThat(store.findDraft(member, draftId)).contains(draft);
        assertThat(store.findDraft(UUID.randomUUID(), draftId)).isEmpty();
        assertThat(store.findSubmission(submission.id())).contains(submission);
        assertThat(store.latestVersion(book)).contains(first);
        assertThatThrownBy(() -> store.insertDraft(new Draft(UUID.randomUUID(), member, UUID.randomUUID(),
                Mode.UPDATE, DraftStatus.DRAFT, draft.position(), wiki, 1, null, 0, draft.snapshot(), now)))
                .isInstanceOf(DataIntegrityViolationException.class);

        db.update("DELETE FROM member WHERE member_id=?", id(member));
        assertThat(store.findDraft(member, draftId)).isEmpty();
        assertThat(store.findSubmission(submission.id())).isEmpty();
        assertThat(store.latestVersion(book)).contains(first);
    }
}
