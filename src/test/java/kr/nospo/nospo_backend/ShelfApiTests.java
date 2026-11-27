package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.nospo.book.application.BookSearchProvider;
import kr.nospo.book.application.EditionMetadata;
import kr.nospo.book.application.KakaoShelfStoragePolicy;
import kr.nospo.book.contract.SearchType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = {NospoBackendApplication.class, ShelfApiTests.TimeConfig.class},
        properties = "spring.datasource.url=jdbc:h2:mem:shelf_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShelfApiTests {
    private static final String SHELF = "/api/v1/me/shelf";
    private static final String BOOKS = "/api/v1/books";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired FixtureClock time;
    @MockitoBean BookSearchProvider provider;
    @MockitoSpyBean KakaoShelfStoragePolicy kakaoPolicy;
    @MockitoSpyBean kr.nospo.book.application.ShelfStore shelfStore;

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary FixtureClock shelfClock() { return new FixtureClock(); }
    }

    static class FixtureClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    @BeforeEach
    void configure() {
        time.now.set(Instant.now());
        for (String type : List.of("SERVICE", "PRIVACY")) {
            if (db.queryForObject("SELECT COUNT(*) FROM term WHERE type=?", Integer.class, type) != 0) continue;
            UUID term = UUID.randomUUID();
            var earlier = at(time.instant().minusSeconds(120));
            db.update("INSERT INTO term(term_id,type,created_at) VALUES (?,?,?)", id(term), type, earlier);
            db.update("""
                    INSERT INTO term_version(term_version_id,term_id,version,title,content,required,
                        publication_status,effective_at,created_at)
                    VALUES (?,?,'shelf','test','test',TRUE,'PUBLISHED',?,?)
                    """, id(UUID.randomUUID()), id(term), earlier, earlier);
        }
    }

    @Test
    void registersTwoEditionsSeparatesOwnersAndNeverCountsTheInitialPosition() throws Exception {
        var owner = member(true);
        var other = member(true);
        UUID book = edition("BOOK", "같은 제목", 80);
        UUID otherEdition = edition("BOOK", "같은 제목", null);
        mvc.perform(get(SHELF).header("Authorization", owner.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(add(owner, book, "PAGE", 20)).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.data.book.bookId").value(book.toString()))
                .andExpect(jsonPath("$.data.book.authors[0]").value("Test Author"))
                .andExpect(jsonPath("$.data.currentPosition.value").value(20))
                .andExpect(jsonPath("$.data.highestPosition.value").value(20))
                .andExpect(jsonPath("$.data.progressPercent").value(25))
                .andExpect(jsonPath("$.data.lastProgressAt").hasJsonPath())
                .andExpect(jsonPath("$.data.positionContext.episodeLabel").hasJsonPath());
        error(add(owner, book, "PAGE", 0), 409, "SHELF_ALREADY_EXISTS");
        mvc.perform(add(owner, otherEdition, "PAGE", 6)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.progressPercent").hasJsonPath())
                .andExpect(jsonPath("$.data.progressPercent").value(org.hamcrest.Matchers.nullValue()));
        error(detail(other, book), 404, "RESOURCE_NOT_FOUND");
        error(remove(other, book), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(add(other, book, "PAGE", 7)).andExpect(status().isCreated());
        assertThat(records(owner, book)).isZero();
        mvc.perform(remove(owner, book)).andExpect(status().isNoContent()).andExpect(content().string(""));
        error(remove(owner, book), 404, "RESOURCE_NOT_FOUND");
        error(detail(owner, book), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(detail(other, book)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentPosition.value").value(7));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_edition WHERE book_id=?", Integer.class, id(book))).isOne();
    }

    @Test
    void validatesInputAndBuildsTheWebNovelEpisodeContext() throws Exception {
        var owner = member(true);
        UUID book = edition("WEB_NOVEL", "회차", 5);
        db.update("""
                INSERT INTO book_episode(episode_id,book_id,sequence_no,episode_label,volume_label,chapter_label)
                VALUES (?,?,2,'2화','1권','구간 A')
                """, id(UUID.randomUUID()), id(book));
        error(add(owner, UUID.randomUUID(), "PAGE", 0), 404, "RESOURCE_NOT_FOUND");
        error(input(owner, "short", "{\"unit\":\"PAGE\",\"value\":0}"), 400, "INVALID_REQUEST");
        for (String position : List.of("null", "2", "{\"unit\":\"EPISODE\"}",
                "{\"unit\":\"PAGE\",\"value\":2}", "{\"unit\":\"EPISODE\",\"value\":6}",
                "{\"unit\":\"EPISODE\",\"value\":-1}", "{\"unit\":\"EPISODE\",\"value\":2.5}",
                "{\"unit\":\"EPISODE\",\"value\":\"2\"}", "{\"unit\":\"WRONG\",\"value\":1}")) {
            error(input(owner, book.toString(), position), 422, "INVALID_POSITION");
        }
        mvc.perform(add(owner, book, "EPISODE", 2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.positionContext.volumeLabel").value("1권"))
                .andExpect(jsonPath("$.data.positionContext.chapterLabel").value("구간 A"))
                .andExpect(jsonPath("$.data.positionContext.episodeLabel").value("2화"));
        mvc.perform(detail(owner, book)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentPosition.unit").value("EPISODE"))
                .andExpect(jsonPath("$.data.currentPosition.value").value(2));
    }

    @Test
    void listsByAllSortsWithScopedExpiringKeysetsAndFilters() throws Exception {
        var owner = member(true);
        var other = member(true);
        UUID first = edition("BOOK", "가", 100);
        UUID second = edition("WEB_NOVEL", "나", 100);
        UUID tie = edition("BOOK", "가", 100);
        mvc.perform(add(owner, first, "PAGE", 0)).andExpect(status().isCreated());
        mvc.perform(add(owner, tie, "PAGE", 0)).andExpect(status().isCreated());
        time.now.updateAndGet(t -> t.plusSeconds(1));
        mvc.perform(add(owner, second, "EPISODE", 5)).andExpect(status().isCreated());
        db.update("""
                UPDATE book_progress SET last_progress_at=? WHERE member_id=? AND book_id=?
                """, LocalDateTime.ofInstant(time.instant().plusSeconds(1), ZoneOffset.UTC), id(owner.id()), id(first));
        mvc.perform(list(owner).param("sort", "RECENT_ACTIVITY")).andExpect(jsonPath("$.data.items[0].book.bookId")
                .value(first.toString()));
        mvc.perform(list(owner).param("sort", "RECENT_ADDED")).andExpect(jsonPath("$.data.items[0].book.bookId")
                .value(second.toString()));
        mvc.perform(list(owner).param("bookType", "WEB_NOVEL"))
                .andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.items[0].book.bookId")
                .value(second.toString()));
        var page1 = data(mvc.perform(list(owner).param("sort", "TITLE").param("limit", "1")).andExpect(status().isOk()));
        String cursor = page1.path("nextCursor").asString();
        var page2 = data(mvc.perform(list(owner).param("sort", "TITLE").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk()));
        String firstId = page1.path("items").get(0).path("book").path("bookId").asString();
        String secondId = page2.path("items").get(0).path("book").path("bookId").asString();
        assertThat(List.of(firstId, secondId)).containsExactlyElementsOf(
                List.of(first.toString(), tie.toString()).stream().sorted().toList());
        mvc.perform(list(owner).param("sort", "TITLE").param("limit", "1").param("cursor", page2.path("nextCursor").asString()))
                .andExpect(jsonPath("$.data.items[0].book.bookId").value(second.toString()))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        for (var bad : List.of(
                list(owner).param("sort", "TITLE").param("limit", "2").param("cursor", cursor),
                list(owner).param("sort", "TITLE").param("limit", "1").param("bookType", "BOOK").param("cursor", cursor),
                list(owner).param("sort", "RECENT_ADDED").param("limit", "1").param("cursor", cursor),
                list(owner).param("sort", "TITLE").param("limit", "1").param("cursor", flip(cursor)),
                list(other).param("sort", "TITLE").param("limit", "1").param("cursor", cursor),
                get(BOOKS + "/search").param("query", "가").param("type", "TITLE").param("bookType", "WEB_NOVEL")
                        .param("cursor", cursor))) {
            error(bad, 400, "INVALID_CURSOR");
        }
        mvc.perform(list(owner).param("limit", "100")).andExpect(status().isOk());
        error(list(owner).param("limit", "0"), 422, "VALIDATION_ERROR");
        error(list(owner).param("limit", "101"), 422, "VALIDATION_ERROR");
        error(list(owner).param("sort", "OTHER"), 400, "INVALID_REQUEST");
        time.now.updateAndGet(t -> t.plusSeconds(901));
        error(list(owner).param("sort", "TITLE").param("limit", "1").param("cursor", cursor), 400, "INVALID_CURSOR");
    }

    @Test
    void reattachmentPreservesHighWaterMarkAndPersistsOnlyNewReading() throws Exception {
        var owner = member(true);
        UUID book = edition("BOOK", "독서량", 100);
        mvc.perform(add(owner, book, "PAGE", 40)).andExpect(status().isCreated());
        for (int position : new int[] {30, 40, 45}) {
            time.now.updateAndGet(t -> t.plusSeconds(1));
            mvc.perform(remove(owner, book)).andExpect(status().isNoContent());
            mvc.perform(add(owner, book, "PAGE", position)).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.currentPosition.value").value(position))
                    .andExpect(jsonPath("$.data.highestPosition.value").value(Math.max(40, position)));
            assertThat(records(owner, book)).isEqualTo(position == 45 ? 1 : 0);
        }
        var record = db.queryForMap("""
                SELECT from_value,to_value,added_units,recorded_at FROM book_reading_record
                WHERE member_id=? AND book_id=?
                """, id(owner.id()), id(book));
        assertThat(record).containsEntry("from_value", 40).containsEntry("to_value", 45).containsEntry("added_units", 5);
        assertThat(db.queryForObject("SELECT revision FROM book_progress WHERE member_id=? AND book_id=?", Long.class,
                id(owner.id()), id(book))).isEqualTo(3);
        mvc.perform(remove(owner, book)).andExpect(status().isNoContent());
        assertThat(records(owner, book)).isOne();
        mvc.perform(add(owner, book, "PAGE", 20)).andExpect(status().isCreated());
        db.update("UPDATE member SET status='WITHDRAWN' WHERE member_id=?", id(owner.id()));
        error(detail(owner, book), 401, "INVALID_CREDENTIALS");
        assertThat(records(owner, book)).isOne();
        db.update("UPDATE member SET status='ACTIVE' WHERE member_id=?", id(owner.id()));
        mvc.perform(detail(owner, book)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.highestPosition.value").value(45));
        db.update("DELETE FROM member WHERE member_id=?", id(owner.id()));
        for (String table : List.of("book_progress", "book_shelf", "book_reading_record")) {
            assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE member_id=?", Integer.class,
                    id(owner.id()))).isZero();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_edition WHERE book_id=?", Integer.class, id(book))).isOne();
    }

    @Test
    void concurrentRegistrationAndReattachmentCreateOneLinkAndOneIncrement() throws Exception {
        var owner = member(true);
        UUID book = edition("BOOK", "경합", 100);
        assertThat(race(() -> response(add(owner, book, "PAGE", 30)), () -> response(add(owner, book, "PAGE", 30))))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(records(owner, book)).isZero();
        mvc.perform(remove(owner, book)).andExpect(status().isNoContent());
        assertThat(race(() -> response(add(owner, book, "PAGE", 40)), () -> response(add(owner, book, "PAGE", 40))))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(records(owner, book)).isOne();
        var crossing = race(() -> response(remove(owner, book)), () -> response(add(owner, book, "PAGE", 50)));
        assertThat(crossing.getFirst()).isEqualTo(204);
        assertThat(crossing.getLast()).isIn(201, 409);
        assertThat(records(owner, book)).isEqualTo(crossing.getLast() == 201 ? 2 : 1);
    }

    @Test
    void shelfChangeAndWithdrawalHaveAConsistentOrder() throws Exception {
        var owner = member(true);
        UUID book = edition("BOOK", "탈퇴 경합", 100);
        var statuses = race(() -> response(add(owner, book, "PAGE", 5)),
                () -> db.update("UPDATE member SET status='WITHDRAWN' WHERE member_id=?", id(owner.id())));
        assertThat(statuses.getFirst()).isIn(201, 401);
        assertThat(statuses.getLast()).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_shelf WHERE member_id=? AND book_id=?",
                Integer.class, id(owner.id()), id(book))).isEqualTo(statuses.getFirst() == 201 ? 1 : 0);
        error(list(owner), 401, "INVALID_CREDENTIALS");
        error(add(owner, book, "PAGE", 6), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void onlyResolvesLiveTemporaryIdToAnExistingExactSourceEdition() throws Exception {
        var owner = member(true);
        String isbn = "9780306406157";
        when(provider.source()).thenReturn("KAKAO_DAUM");
        when(provider.transientResults()).thenReturn(true);
        var edition = new EditionMetadata(isbn, "Remote fixture", List.of(), null, null, isbn, null, null, null, null);
        when(provider.search("fixture", SearchType.TITLE, null, 20))
                .thenReturn(new BookSearchProvider.Results(List.of(edition), null));
        var data = data(mvc.perform(get(BOOKS + "/search").param("query", "fixture").param("type", "TITLE")
                .param("bookType", "BOOK")).andExpect(status().isOk()));
        UUID temp = UUID.fromString(data.path("items").get(0).path("bookId").asString());
        error(add(owner, temp, "PAGE", 0), 404, "RESOURCE_NOT_FOUND");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_edition WHERE book_id=?", Integer.class, id(temp))).isZero();
        UUID permanent = edition("BOOK", "Approved", null);
        db.update("UPDATE book_edition SET source='KAKAO_DAUM',source_item_id=?,isbn=? WHERE book_id=?",
                isbn.getBytes(StandardCharsets.UTF_8), isbn, id(permanent));
        mvc.perform(add(owner, temp, "PAGE", 0)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.book.bookId").value(permanent.toString()));
        error(add(owner, permanent, "PAGE", 0), 409, "SHELF_ALREADY_EXISTS");
        error(detail(owner, temp), 404, "RESOURCE_NOT_FOUND");
        error(progressGet(owner, temp), 404, "RESOURCE_NOT_FOUND");
        time.now.updateAndGet(t -> t.plusSeconds(301));
        error(add(member(true), temp, "PAGE", 0), 404, "RESOURCE_NOT_FOUND");
        var missingFromCache = new UUID(0x0000000000008000L, 0x8000000000000000L);
        error(add(owner, missingFromCache, "PAGE", 0), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void rejectsInvalidIdentityAndConflictingApprovedMetadataEvenWhenCreationIsAllowed() throws Exception {
        var owner = member(true);
        doReturn(true).when(kakaoPolicy).allowsSharedEditionCreation();
        String isbn = "9780804429573";
        UUID conflicting = edition("BOOK", "Conflicting ISBN", null);
        db.update("UPDATE book_edition SET source='KAKAO_DAUM',source_item_id=?,isbn=? WHERE book_id=?",
                isbn.getBytes(StandardCharsets.UTF_8), "9780306406157", id(conflicting));
        for (var candidate : List.of(
                new EditionMetadata(isbn, "Conflict", List.of(), null, null, isbn, null, null, null, null),
                new EditionMetadata("9780306406158", "Bad checksum", List.of(), null, null, "9780306406158",
                        null, null, null, null),
                new EditionMetadata("missing", "No ISBN", List.of(), null, null, null, null, null, null, null),
                new EditionMetadata("mismatch", "Wrong source key", List.of(), null, null, isbn, null, null, null, null))) {
            error(add(owner, cachedEdition(candidate), "PAGE", 0), 404, "RESOURCE_NOT_FOUND");
            assertThat(catalogRows(candidate.sourceItemId())).isEqualTo(candidate.sourceItemId().equals(isbn) ? 1 : 0);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_progress WHERE member_id=?", Integer.class, id(owner.id())))
                .isZero();
    }

    @Test
    void testOnlyApprovalCreatesOneMinimalEditionAndKeepsItAcrossRequests() throws Exception {
        var owner = member(true);
        var other = member(true);
        var failed = member(true);
        String isbn = "9780140328721";
        var external = new EditionMetadata(isbn, "Selected title", List.of("External author"), "External press",
                "External label", isbn, "https://example.test/cover", "External synopsis", 123, LocalDate.of(2020, 1, 1));
        UUID temporary = cachedEdition(external);
        error(add(owner, temporary, "PAGE", 20), 404, "RESOURCE_NOT_FOUND");
        assertThat(catalogRows(isbn)).isZero();
        doReturn(true).when(kakaoPolicy).allowsSharedEditionCreation();
        error(add(failed, temporary, "EPISODE", 20), 422, "INVALID_POSITION");
        assertThat(catalogRows(isbn)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_progress WHERE member_id=?", Integer.class, id(failed.id())))
                .isZero();

        assertThat(race(() -> response(add(owner, temporary, "PAGE", 20)),
                () -> response(add(other, temporary, "PAGE", 20)))).containsExactly(201, 201);
        assertThat(catalogRows(isbn)).isOne();
        UUID permanent = uuid(db.queryForObject("SELECT book_id FROM book_edition WHERE source='KAKAO_DAUM' AND source_item_id=?",
                byte[].class, isbn.getBytes(StandardCharsets.UTF_8)));
        assertThat(permanent).isNotEqualTo(temporary);
        mvc.perform(detail(owner, permanent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.book.bookId").value(permanent.toString()))
                .andExpect(jsonPath("$.data.book.authors").isEmpty());
        mvc.perform(detail(other, permanent)).andExpect(status().isOk());
        assertThat(records(owner, permanent)).isZero();
        assertThat(db.queryForMap("""
                SELECT description,cover_url,publication_date,publisher,edition_label,total_units,work_id
                FROM book_edition WHERE book_id=?
                """, id(permanent)).values()).allMatch(java.util.Objects::isNull);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_edition WHERE book_id=?", Integer.class, id(temporary))).isZero();
        var changed = new EditionMetadata(isbn, "Updated remote title", List.of(), null, null, "0140328726",
                null, null, null, null);
        assertThat(cachedEdition(changed)).isEqualTo(temporary);
        error(add(owner, temporary, "PAGE", 25), 409, "SHELF_ALREADY_EXISTS");
        mvc.perform(remove(owner, permanent)).andExpect(status().isNoContent());
        mvc.perform(add(owner, temporary, "PAGE", 25)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.book.bookId").value(permanent.toString()))
                .andExpect(jsonPath("$.data.book.title").value("Selected title"))
                .andExpect(jsonPath("$.data.highestPosition.value").value(25));
        assertThat(records(owner, permanent)).isOne();
        String differentIsbn = "9783161484100";
        UUID different = cachedEdition(new EditionMetadata(differentIsbn, "Selected title", List.of(),
                null, null, differentIsbn, null, null, null, null));
        var added = data(mvc.perform(add(owner, different, "PAGE", 20)).andExpect(status().isCreated()));
        String differentPermanent = added.path("book").path("bookId").asString();
        assertThat(differentPermanent).isNotEqualTo(permanent.toString()).isNotEqualTo(different.toString());
        assertThat(catalogRows(differentIsbn)).isOne();
    }

    @Test
    void anEvictedTemporaryIdCannotCreateAnEdition() throws Exception {
        var owner = member(true);
        String isbn = "9790000000001";
        UUID temporary = cachedEdition(new EditionMetadata(isbn, "Evicted", List.of(), null, null, isbn,
                null, null, null, null));
        doReturn(true).when(kakaoPolicy).allowsSharedEditionCreation();
        for (int batch = 0; batch < 11; batch++) {
            String query = "eviction-" + batch;
            var rows = java.util.stream.IntStream.range(batch * 100, (batch + 1) * 100)
                    .mapToObj(n -> new EditionMetadata("eviction-" + n, "Fixture", List.of(), null, null,
                            null, null, null, null, null)).toList();
            when(provider.search(query, SearchType.TITLE, null, 100))
                    .thenReturn(new BookSearchProvider.Results(rows, null));
            mvc.perform(get(BOOKS + "/search").param("query", query).param("type", "TITLE")
                    .param("bookType", "BOOK").param("limit", "100")).andExpect(status().isOk());
        }
        error(add(owner, temporary, "PAGE", 0), 404, "RESOURCE_NOT_FOUND");
        assertThat(catalogRows(isbn)).isZero();
    }

    private UUID cachedEdition(EditionMetadata edition) throws Exception {
        when(provider.source()).thenReturn("KAKAO_DAUM");
        when(provider.transientResults()).thenReturn(true);
        when(provider.search("selected", SearchType.TITLE, null, 20))
                .thenReturn(new BookSearchProvider.Results(List.of(edition), null));
        var search = data(mvc.perform(get(BOOKS + "/search").param("query", "selected").param("type", "TITLE")
                .param("bookType", "BOOK")).andExpect(status().isOk()));
        return UUID.fromString(search.path("items").get(0).path("bookId").asString());
    }

    private int catalogRows(String sourceItemId) {
        return db.queryForObject("SELECT COUNT(*) FROM book_edition WHERE source='KAKAO_DAUM' AND source_item_id=?",
                Integer.class, sourceItemId.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void requiresActiveJwtAndConsentAndDerivesOwnerFromJwt() throws Exception {
        UUID book = edition("BOOK", "보안", 100);
        var owner = member(true);
        var other = member(true);
        var pending = member(false);
        error(get(SHELF), 401, "INVALID_CREDENTIALS");
        error(list(new Member(owner.id(), owner.session(), token(owner.id(), owner.session(), Instant.now().minusSeconds(90)))),
                401, "INVALID_CREDENTIALS");
        error(add(pending, book, "PAGE", 0), 403, "TERMS_RECONSENT_REQUIRED");
        mvc.perform(post(SHELF).header("Authorization", owner.bearer()).contentType("application/json").content("""
                {"bookId":"%s","memberId":"%s","position":{"unit":"PAGE","value":0}}
                """.formatted(book, other.id()))).andExpect(status().isCreated());
        error(detail(other, book), 404, "RESOURCE_NOT_FOUND");
        db.update("UPDATE auth_session SET revoked_at=? WHERE session_id=?", at(time.instant()), id(owner.session()));
        error(list(owner), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void progressTracksRevisionHighWaterMarkAndEveryActualMove() throws Exception {
        var owner = member(true);
        UUID book = edition("BOOK", "Progress fixture", 100);
        mvc.perform(add(owner, book, "PAGE", 40)).andExpect(status().isCreated());
        String tag0 = progressTag(owner, book, 0);
        var backward = move(owner, book, tag0, "PAGE", 30)
                .andExpect(jsonPath("$.data.addedUnits").value(0))
                .andExpect(jsonPath("$.data.recordId").isString())
                .andExpect(jsonPath("$.data.highestPosition.value").value(40));
        String tag1 = etag(backward);
        var forward = move(owner, book, tag1, "PAGE", 45)
                .andExpect(jsonPath("$.data.highestPosition.value").value(45))
                .andExpect(jsonPath("$.data.currentPosition.value").value(45))
                .andExpect(jsonPath("$.data.progressPercent").value(45))
                .andExpect(jsonPath("$.data.addedUnits").value(5));
        var recordId = data(forward).path("recordId").asString();
        String tag2 = etag(forward);
        assertThat(tag2).startsWith("\"progress-2-").isNotEqualTo(tag1);
        time.now.updateAndGet(now -> now.plusSeconds(2));
        move(owner, book, tag2, "PAGE", 45).andExpect(header().string("ETag", tag2))
                .andExpect(jsonPath("$.data.addedUnits").value(0))
                .andExpect(jsonPath("$.data.recordId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.updatedAt").value(data(forward).path("updatedAt").asString()));
        String tag3 = etag(move(owner, book, tag2, "PAGE", 20));
        move(owner, book, tag3, "PAGE", 40).andExpect(jsonPath("$.data.addedUnits").value(0))
                .andExpect(jsonPath("$.data.recordId").isString())
                .andExpect(jsonPath("$.data.highestPosition.value").value(45));
        assertThat(records(owner, book)).isEqualTo(4);
        var row = db.queryForMap("SELECT from_value,to_value,added_units FROM book_reading_record WHERE record_id=?",
                id(UUID.fromString(recordId)));
        assertThat(row).containsEntry("from_value", 30).containsEntry("to_value", 45).containsEntry("added_units", 5);
    }

    @Test
    void progressValidatesEtagsAndPositionsWithoutChangingStateOnFailure() throws Exception {
        var owner = member(true);
        var other = member(true);
        UUID book = edition("BOOK", "ETag", 50);
        UUID otherBook = edition("BOOK", "Other edition", 50);
        mvc.perform(add(owner, book, "PAGE", 10)).andExpect(status().isCreated());
        mvc.perform(add(owner, otherBook, "PAGE", 10)).andExpect(status().isCreated());
        mvc.perform(add(other, book, "PAGE", 10)).andExpect(status().isCreated());
        String tag = progressTag(owner, book, 0);
        String body = "{\"position\":{\"unit\":\"PAGE\",\"value\":15}}";
        error(progressPatch(owner, book, body), 428, "PRECONDITION_REQUIRED");
        for (String invalid : List.of("*", "W/" + tag, "\"progress-0\"", tag + ", " + tag, "")) {
            error(progressPatch(owner, book, body).header("If-Match", invalid), 400, "INVALID_REQUEST");
        }
        error(progressPatch(owner, book, body).header("If-Match", tag, tag), 400, "INVALID_REQUEST");
        for (String wrong : List.of(progressTag(owner, otherBook, 0), progressTag(other, book, 0),
                tag.replaceFirst("progress-0-(.)", tag.charAt(12) == 'A' ? "progress-0-B" : "progress-0-A"))) {
            error(progressPatch(owner, book, body).header("If-Match", wrong), 412, "VERSION_MISMATCH");
        }
        for (String invalid : List.of("null", "{\"unit\":null,\"value\":5}", "{\"unit\":\"PAGE\",\"value\":null}",
                "{\"unit\":\"PAGE\",\"value\":2.5}", "{\"unit\":\"PAGE\",\"value\":\"5\"}",
                "{\"unit\":\"PAGE\",\"value\":-1}", "{\"unit\":\"PAGE\",\"value\":51}",
                "{\"unit\":\"EPISODE\",\"value\":5}")) {
            error(progressPatch(owner, book, "{\"position\":" + invalid + "}").header("If-Match", tag), 422,
                    "INVALID_POSITION");
        }
        assertThat(progressTag(owner, book, 0)).isEqualTo(tag);
        assertThat(records(owner, book)).isZero();
        var updated = progressPatch(owner, book, body).header("if-match", tag);
        mvc.perform(updated).andExpect(status().isOk());
        error(progressPatch(owner, book, body).header("If-Match", tag), 412, "VERSION_MISMATCH");
        assertThat(records(owner, book)).isOne();
    }

    @Test
    void progressSerializesConcurrentPatchesAndUsesCurrentEpisodeAfterReattachment() throws Exception {
        var owner = member(true);
        UUID book = edition("WEB_NOVEL", "Episode progress", null);
        db.update("""
                INSERT INTO book_episode(episode_id,book_id,sequence_no,episode_label,volume_label,chapter_label)
                VALUES (?,?,3,'3화','2권','구간 B')
                """, id(UUID.randomUUID()), id(book));
        mvc.perform(add(owner, book, "EPISODE", 1)).andExpect(status().isCreated());
        String first = progressTag(owner, book, 0);
        String body = "{\"position\":{\"unit\":\"EPISODE\",\"value\":3}}";
        assertThat(race(() -> response(progressPatch(owner, book, body).header("If-Match", first)),
                () -> response(progressPatch(owner, book, body).header("If-Match", first))))
                .containsExactlyInAnyOrder(200, 412);
        assertThat(records(owner, book)).isOne();
        mvc.perform(progressGet(owner, book)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positionContext.volumeLabel").value("2권"))
                .andExpect(jsonPath("$.data.positionContext.chapterLabel").value("구간 B"))
                .andExpect(jsonPath("$.data.positionContext.episodeLabel").value("3화"))
                .andExpect(jsonPath("$.data.progressPercent").value(org.hamcrest.Matchers.nullValue()));
        String previous = progressTag(owner, book, 1);
        mvc.perform(remove(owner, book)).andExpect(status().isNoContent());
        error(progressGet(owner, book), 404, "RESOURCE_NOT_FOUND");
        error(progressPatch(owner, book, body).header("If-Match", previous), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(add(owner, book, "EPISODE", 4)).andExpect(status().isCreated());
        error(progressPatch(owner, book, body).header("If-Match", previous), 412, "VERSION_MISMATCH");
        move(owner, book, progressTag(owner, book, 2), "EPISODE", 5)
                .andExpect(jsonPath("$.data.addedUnits").value(1));
        assertThat(records(owner, book)).isEqualTo(3);
        mvc.perform(recordList(owner).param("bookId", book.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].toPosition.unit", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("EPISODE"))));
    }

    @Test
    void progressWriteRollsBackWhenRecordInsertFails() throws Exception {
        var owner = member(true);
        UUID book = edition("BOOK", "Rollback", 100);
        mvc.perform(add(owner, book, "PAGE", 5)).andExpect(status().isCreated());
        String tag = progressTag(owner, book, 0);
        org.mockito.Mockito.doThrow(new kr.nospo.support.ApiException(409, "REQUEST_IN_PROGRESS"))
                .when(shelfStore).record(org.mockito.ArgumentMatchers.eq(owner.id()), org.mockito.ArgumentMatchers.eq(book),
                        org.mockito.ArgumentMatchers.eq(5), org.mockito.ArgumentMatchers.eq(10),
                        org.mockito.ArgumentMatchers.eq(5), org.mockito.ArgumentMatchers.any());
        error(progressPatch(owner, book, "{\"position\":{\"unit\":\"PAGE\",\"value\":10}}").header("If-Match", tag),
                409, "REQUEST_IN_PROGRESS");
        mvc.perform(progressGet(owner, book)).andExpect(header().string("ETag", tag))
                .andExpect(jsonPath("$.data.currentPosition.value").value(5))
                .andExpect(jsonPath("$.data.highestPosition.value").value(5));
        assertThat(records(owner, book)).isZero();
    }

    @Test
    void readingRecordsUseKoreanDateScopesAndStableUuidTieBreakersAfterRemoval() throws Exception {
        var owner = member(true);
        var other = member(true);
        UUID book = edition("BOOK", "Reading records", 100);
        UUID novel = edition("WEB_NOVEL", "Other records", 100);
        mvc.perform(add(owner, book, "PAGE", 0)).andExpect(status().isCreated());
        mvc.perform(add(owner, novel, "EPISODE", 0)).andExpect(status().isCreated());
        mvc.perform(add(other, book, "PAGE", 0)).andExpect(status().isCreated());
        Instant boundary = Instant.parse("2026-11-25T15:00:00Z");
        UUID low = new UUID(1, 0);
        UUID high = new UUID(Long.MIN_VALUE, 0);
        insertRecord(owner, book, new UUID(2, 0), boundary.minusNanos(1000));
        insertRecord(owner, book, low, boundary);
        insertRecord(owner, book, high, boundary);
        insertRecord(owner, novel, new UUID(3, 0), boundary);
        insertRecord(other, book, new UUID(4, 0), boundary);
        mvc.perform(remove(owner, book)).andExpect(status().isNoContent());
        var page1 = data(mvc.perform(datedRecords(owner, book).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].recordId").value(high.toString()))
                .andExpect(jsonPath("$.data.items[0].fromPosition.unit").value("PAGE"))
                .andExpect(jsonPath("$.data.items[0].recordedAt").value(boundary.toString())));
        String cursor = page1.path("nextCursor").asString();
        mvc.perform(datedRecords(owner, book).param("limit", "1").param("cursor", cursor))
                .andExpect(jsonPath("$.data.items[0].recordId").value(low.toString()))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(recordList(owner).param("to", "2026-11-25"))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(recordList(owner).param("from", "2026-11-26").param("limit", "100"))
                .andExpect(jsonPath("$.data.items.length()").value(3));
        error(datedRecords(other, book).param("limit", "1").param("cursor", cursor), 400, "INVALID_CURSOR");
        error(datedRecords(owner, novel).param("limit", "1").param("cursor", cursor), 400, "INVALID_CURSOR");
        error(recordList(owner).param("limit", "1").param("cursor", cursor), 400, "INVALID_CURSOR");
        error(datedRecords(owner, book).param("limit", "1").param("cursor", flip(cursor)), 400, "INVALID_CURSOR");
        error(recordList(owner).param("from", "2026-02-30"), 400, "INVALID_REQUEST");
        error(recordList(owner).param("from", "2025-01-01").param("to", "2026-11-26"), 422, "VALIDATION_ERROR");
        error(recordList(owner).param("limit", "101"), 422, "VALIDATION_ERROR");
        time.now.updateAndGet(now -> now.plusSeconds(901));
        error(datedRecords(owner, book).param("limit", "1").param("cursor", cursor), 400, "INVALID_CURSOR");
        mvc.perform(recordList(member(true))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void readingUsesExistingAuthorizationAndOnlyProgressCorsAllowsIfMatch() throws Exception {
        var owner = member(true);
        var other = member(true);
        var pending = member(false);
        UUID book = edition("BOOK", "Reading security", 100);
        mvc.perform(add(owner, book, "PAGE", 0)).andExpect(status().isCreated());
        String progress = SHELF + "/" + book + "/progress";
        error(get(progress), 401, "INVALID_CREDENTIALS");
        error(get("/api/v1/me/reading/records"), 401, "INVALID_CREDENTIALS");
        error(progressGet(pending, book), 403, "TERMS_RECONSENT_REQUIRED");
        error(recordList(pending), 403, "TERMS_RECONSENT_REQUIRED");
        error(progressGet(other, book), 404, "RESOURCE_NOT_FOUND");
        error(progressGet(owner, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        String origin = "http://localhost:3000";
        mvc.perform(options(progress).header("Origin", origin).header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "Authorization, Content-Type, If-Match"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("If-Match")));
        mvc.perform(progressGet(owner, book).header("Origin", origin))
                .andExpect(header().string("Access-Control-Expose-Headers", "ETag"));
        mvc.perform(options(SHELF).header("Origin", origin).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "If-Match")).andExpect(status().isForbidden());
    }

    private void insertRecord(Member owner, UUID book, UUID recordId, Instant time) {
        db.update("""
                INSERT INTO book_reading_record(record_id,member_id,book_id,from_value,to_value,added_units,recorded_at)
                VALUES (?,?,?,0,1,1,?)
                """, id(recordId), id(owner.id()), id(book), LocalDateTime.ofInstant(time, ZoneOffset.UTC));
    }

    private static MockHttpServletRequestBuilder datedRecords(Member owner, UUID book) {
        return recordList(owner).param("bookId", book.toString()).param("from", "2026-11-26").param("to", "2026-11-26");
    }

    private static MockHttpServletRequestBuilder recordList(Member owner) {
        return get("/api/v1/me/reading/records").header("Authorization", owner.bearer());
    }

    private static MockHttpServletRequestBuilder progressGet(Member owner, UUID book) {
        return get(SHELF + "/" + book + "/progress").header("Authorization", owner.bearer());
    }

    private static MockHttpServletRequestBuilder progressPatch(Member owner, UUID book, String body) {
        return patch(SHELF + "/" + book + "/progress").header("Authorization", owner.bearer())
                .contentType("application/json").content(body);
    }

    private ResultActions move(Member owner, UUID book, String tag, String unit, int value) throws Exception {
        return mvc.perform(progressPatch(owner, book, """
                {"position":{"unit":"%s","value":%d}}
                """.formatted(unit, value)).header("If-Match", tag)).andExpect(status().isOk());
    }

    private String progressTag(Member owner, UUID book, int revision) throws Exception {
        return etag(mvc.perform(progressGet(owner, book)).andExpect(status().isOk())
                .andExpect(header().string("ETag", org.hamcrest.Matchers.startsWith("\"progress-" + revision + "-"))));
    }

    private static String etag(ResultActions actions) {
        return actions.andReturn().getResponse().getHeader("ETag");
    }

    @Test
    void exposesShelfMethodsAndBearerInOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SHELF + "'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + SHELF + "'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['" + SHELF + "'].post.requestBody.content['application/json'].examples.WEB_NOVEL").exists())
                .andExpect(jsonPath("$.paths['" + SHELF + "/{bookId}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['" + SHELF + "/{bookId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['" + SHELF + "/{bookId}/progress'].get.responses['200'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['" + SHELF + "/{bookId}/progress'].patch.responses['428']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/reading/records'].get.security[0].bearerAuth").exists());
    }

    private int records(Member owner, UUID book) {
        return db.queryForObject("SELECT COUNT(*) FROM book_reading_record WHERE member_id=? AND book_id=?", Integer.class,
                id(owner.id()), id(book));
    }

    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private int response(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private UUID edition(String type, String title, Integer total) {
        UUID book = UUID.randomUUID();
        db.update("""
                INSERT INTO book_edition(book_id,book_type,title,source,source_item_id,total_units)
                VALUES (?,?,?,'SHELF_FIXTURE',?,?)
                """, id(book), type, title, id(book), total);
        db.update("INSERT INTO book_author(book_id,author_order,author_name) VALUES (?,0,'Test Author')", id(book));
        return book;
    }

    private Member member(boolean agreed) {
        UUID owner = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        String nickname = owner.toString().substring(0, 16);
        String email = nickname + "@example.test";
        Instant now = time.instant();
        db.update("""
                INSERT INTO member(member_id,nickname,nickname_key,phone_number,created_at,updated_at)
                VALUES (?,?,?,'+821000000000',?,?)
                """, id(owner), nickname, nickname, at(now), at(now));
        db.update("""
                INSERT INTO account(member_id,email,email_key,email_verified_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?)
                """, id(owner), email, email, at(now), at(now), at(now));
        db.update("INSERT INTO auth_session(session_id,member_id,created_at,expires_at) VALUES (?,?,?,?)",
                id(sid), id(owner), at(now), at(now.plusSeconds(3600)));
        if (agreed) {
            int order = 0;
            for (byte[] term : db.query("SELECT term_version_id FROM term_version WHERE required=TRUE", (row,n) -> row.getBytes(1))) {
                db.update("""
                        INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at,event_order)
                        VALUES (?,?,?,TRUE,?,?)
                        """, id(UUID.randomUUID()), id(owner), term, at(now), ++order);
            }
        }
        return new Member(owner, sid, token(owner, sid, Instant.now().plusSeconds(1800)));
    }

    private String token(UUID owner, UUID sid, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer("nospo").subject(owner.toString())
                .issuedAt(expiry.minusSeconds(1800)).expiresAt(expiry).claim("sid", sid.toString()).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private void error(MockHttpServletRequestBuilder request, int statusCode, String code) throws Exception {
        mvc.perform(request).andExpect(status().is(statusCode)).andExpect(jsonPath("$.error.code").value(code));
    }

    private static MockHttpServletRequestBuilder input(Member member, String book, String position) {
        return post(SHELF).header("Authorization", member.bearer()).contentType("application/json")
                .content("{\"bookId\":\"" + book + "\",\"position\":" + position + "}");
    }

    private static MockHttpServletRequestBuilder add(Member member, UUID book, String unit, int value) {
        return input(member, book.toString(), "{\"unit\":\"" + unit + "\",\"value\":" + value + "}");
    }

    private static MockHttpServletRequestBuilder detail(Member member, UUID book) {
        return get(SHELF + "/" + book).header("Authorization", member.bearer());
    }

    private static MockHttpServletRequestBuilder list(Member member) {
        return get(SHELF).header("Authorization", member.bearer());
    }

    private static MockHttpServletRequestBuilder remove(Member member, UUID book) {
        return delete(SHELF + "/" + book).header("Authorization", member.bearer());
    }

    private static String flip(String cursor) {
        return (cursor.startsWith("A") ? "B" : "A") + cursor.substring(1);
    }

    private record Member(UUID id, UUID session, String bearer) {}
}
