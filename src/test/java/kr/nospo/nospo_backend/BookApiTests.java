package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.nospo.book.application.BookSearchProvider;
import kr.nospo.book.application.EditionMetadata;
import kr.nospo.book.application.WebNovelImport;
import kr.nospo.book.contract.SearchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:book_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookApiTests {
    private static final String BOOKS = "/api/v1/books";
    private static final String HISTORY = "/api/v1/me/search-history";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired JwtEncoder encoder;
    @Autowired WebNovelImport importer;
    @Autowired kr.nospo.book.contract.BookCatalog catalog;
    @MockitoBean BookSearchProvider provider;

    @BeforeEach
    void providerName() {
        when(provider.source()).thenReturn("TEST_PROVIDER");
        when(provider.search(anyString(), any(), nullable(String.class), anyInt()))
                .thenReturn(new BookSearchProvider.Results(List.of(), null));
    }

    @Test
    void transientProviderKeepsIdsStableWithoutRegisteringUnapprovedMetadata() throws Exception {
        when(provider.source()).thenReturn("KAKAO_DAUM");
        when(provider.transientResults()).thenReturn(true);
        var first = metadata("9780306406157", "Same title", "9780306406157");
        var second = metadata("9780140328721", "Same title", "9780140328721");
        when(provider.search("fixture", SearchType.TITLE, null, 50))
                .thenReturn(new BookSearchProvider.Results(List.of(first, second), "batch"));
        when(provider.search("fixture", SearchType.TITLE, "batch", 100))
                .thenReturn(new BookSearchProvider.Results(List.of(), "end"));
        when(provider.search("fixture", SearchType.TITLE, "end", 100))
                .thenThrow(new BookSearchProvider.InvalidContinuation());
        var items = data(mvc.perform(search("fixture", "TITLE", "BOOK").param("limit", "50"))
                .andExpect(status().isOk()));
        String firstId = items.path("items").get(0).path("bookId").asString();
        assertThat(items.path("items").get(1).path("bookId").asString()).isNotEqualTo(firstId);
        mvc.perform(search("fixture", "TITLE", "BOOK").param("limit", "50"))
                .andExpect(jsonPath("$.data.items[0].bookId").value(firstId));
        mvc.perform(get(BOOKS + "/" + firstId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("KAKAO_DAUM"))
                .andExpect(jsonPath("$.data.sourceItemId").value("9780306406157"));
        assertThat(catalog.find(UUID.fromString(firstId))).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_edition WHERE source='KAKAO_DAUM'", Integer.class)).isZero();
        String cursor = items.path("nextCursor").asString();
        error(search("fixture", "AUTHOR", "BOOK").param("cursor", cursor), 400, "INVALID_CURSOR");
        error(search("fixture", "TITLE", "WEB_NOVEL").param("cursor", cursor), 400, "INVALID_CURSOR");
        error(search("fixture", "TITLE", "BOOK").param("cursor", flip(cursor)), 400, "INVALID_CURSOR");
        var empty = data(mvc.perform(search("fixture", "TITLE", "BOOK").param("limit", "100").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(true)));
        error(search("fixture", "TITLE", "BOOK").param("limit", "100")
                .param("cursor", empty.path("nextCursor").asString()), 400, "INVALID_CURSOR");
    }

    @Test
    void externalPaginationPersistsStableDistinctEditionsAndDetails() throws Exception {
        String item = UUID.randomUUID().toString();
        var first = metadata(item, "Edition series", null);
        var second = metadata(item.toUpperCase(java.util.Locale.ROOT) + "A", "Edition series", null);
        when(provider.search("Edition", SearchType.TITLE, null, 1))
                .thenReturn(new BookSearchProvider.Results(List.of(first), "upstream"));
        when(provider.search("Edition", SearchType.TITLE, "upstream", 1))
                .thenReturn(new BookSearchProvider.Results(List.of(second), null));
        var request = search("Edition", "TITLE", "BOOK").param("limit", "1");
        var page = data(mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.meta.requestId").isString()));
        String firstId = page.path("items").get(0).path("bookId").asString();
        String cursor = page.path("nextCursor").asString();
        assertThat(UUID.fromString(firstId)).isNotEqualTo(UUID.fromString(item));
        assertThat(data(mvc.perform(request)).path("items").get(0).path("bookId").asString()).isEqualTo(firstId);
        var next = data(mvc.perform(search("Edition", "TITLE", "BOOK").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk()));
        assertThat(next.path("items").get(0).path("bookId").asString()).isNotEqualTo(firstId);
        assertThat(next.path("hasNext").asBoolean()).isFalse();
        mvc.perform(get(BOOKS + "/" + firstId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positionUnit").value("PAGE"))
                .andExpect(jsonPath("$.data.publicationDate").value("2020-01-02"))
                .andExpect(jsonPath("$.data.sourceItemId").value(item))
                .andExpect(jsonPath("$.data.authors[0]").value("Test Author"));
        error(get(BOOKS + "/" + firstId + "/structure"), 409, "BOOK_TYPE_MISMATCH");
        error(search("changed", "TITLE", "BOOK").param("cursor", cursor), 400, "INVALID_CURSOR");
        error(search("Edition", "TITLE", "BOOK").param("cursor", flip(cursor)), 400, "INVALID_CURSOR");
        error(get(BOOKS + "/" + firstId + "/structure").param("cursor", cursor), 400, "INVALID_CURSOR");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_edition WHERE source_item_id=?", Integer.class,
                item.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(1);
    }

    @Test
    void novelSearchAndImportUseOneEditionAndGloballyOrderedEpisodes() throws Exception {
        String key = UUID.randomUUID().toString();
        String title = "Synthetic " + key;
        UUID work = UUID.randomUUID();
        var novel = new WebNovelImport.Novel(work, metadata(key, title, "978-123"),
                List.of(new WebNovelImport.ChapterEpisode(3, "3", "v2", null),
                        new WebNovelImport.ChapterEpisode(1, "1", null, "c1")));
        UUID novelId = importer.importEdition(novel);
        var detail = data(mvc.perform(get(BOOKS + "/" + novelId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positionUnit").value("EPISODE"))
                .andExpect(jsonPath("$.data.workId").value(work.toString())));
        assertThat(detail.path("totalUnits").isNull()).isTrue();
        for (var request : List.of(search(title, "TITLE", "WEB_NOVEL"), search("Test Author", "AUTHOR", "WEB_NOVEL"),
                search("978123", "ISBN", "WEB_NOVEL"))) {
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].bookId").value(org.hamcrest.Matchers.hasItem(novelId.toString())));
        }
        String structure = BOOKS + "/" + novelId + "/structure";
        var first = data(mvc.perform(get(structure).param("limit", "1"))
                .andExpect(jsonPath("$.data.items[0].sequence").value(1)));
        assertThat(importer.importEdition(novel)).isEqualTo(novelId);
        mvc.perform(get(structure)).andExpect(jsonPath("$.data.items[0].episodeId")
                .value(first.path("items").get(0).path("episodeId").asString()));
        String cursor = first.path("nextCursor").asString();
        mvc.perform(get(structure).param("cursor", cursor))
                .andExpect(jsonPath("$.data.items[0].sequence").value(3))
                .andExpect(jsonPath("$.data.items[0].volumeLabel").value("v2"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        error(get(structure).param("cursor", flip(cursor)), 400, "INVALID_CURSOR");
        verifyNoInteractions(provider);
    }

    @Test
    void unfilteredSearchContinuesFromExternalToLocalAndExposesProviderFailures() throws Exception {
        String query = UUID.randomUUID().toString();
        importer.importEdition(new WebNovelImport.Novel(null, metadata(query, query, null), List.of()));
        when(provider.search(query, SearchType.TITLE, null, 1))
                .thenReturn(new BookSearchProvider.Results(List.of(metadata(query, query, null)), null));
        var first = data(mvc.perform(get(BOOKS + "/search").param("query", query).param("type", "TITLE").param("limit", "1"))
                .andExpect(jsonPath("$.data.items[0].bookType").value("BOOK")));
        mvc.perform(get(BOOKS + "/search").param("query", query).param("type", "TITLE")
                .param("cursor", first.path("nextCursor").asString()))
                .andExpect(jsonPath("$.data.items[0].bookType").value("WEB_NOVEL"));
        verify(provider, times(1)).search(eq(query), eq(SearchType.TITLE), isNull(), anyInt());
        when(provider.search(eq(query), eq(SearchType.TITLE), isNull(), eq(20)))
                .thenThrow(new BookSearchProvider.Unavailable());
        error(get(BOOKS + "/search").param("query", query).param("type", "TITLE"), 503, "BOOK_PROVIDER_UNAVAILABLE");
    }

    @Test
    void validatesRequestsAndSeparatesEmptySearchFromFailure() throws Exception {
        mvc.perform(search("q", "ISBN", "BOOK").param("limit", "100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.hasNext").value(false));
        verify(provider).search("q", SearchType.ISBN, null, 100);
        mvc.perform(search("가".repeat(100), "AUTHOR", "BOOK")).andExpect(status().isOk());
        verify(provider).search("가".repeat(100), SearchType.AUTHOR, null, 20);
        error(search("a".repeat(101), "TITLE", "BOOK"), 422, "VALIDATION_ERROR");
        error(search(" ", "TITLE", "BOOK"), 422, "VALIDATION_ERROR");
        error(search("q", "MISSING", "BOOK"), 400, "INVALID_REQUEST");
        error(search("q", "TITLE", "BAD"), 400, "INVALID_REQUEST");
        error(search("q", "TITLE", "BOOK").param("limit", "0"), 422, "VALIDATION_ERROR");
        error(search("q", "TITLE", "BOOK").param("limit", "101"), 422, "VALIDATION_ERROR");
        error(search("q", "TITLE", "BOOK").param("limit", "abc"), 400, "INVALID_REQUEST");
        error(get(BOOKS + "/search").param("query", "q"), 400, "INVALID_REQUEST");
        error(get(BOOKS + "/1-1-1-1-1"), 400, "INVALID_REQUEST");
        error(get(BOOKS + "/" + UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        error(get(BOOKS + "/" + UUID.randomUUID() + "/structure"), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(get(BOOKS + "/" + UUID.randomUUID() + "/wiki")).andExpect(status().isUnauthorized());
        mvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
    }

    @Test
    void historyIsOwnerBoundDeduplicatedPagedAndLimitedToLatestHundred() throws Exception {
        configureTerms();
        var owner = member();
        var other = member();
        mvc.perform(search("guest", "TITLE", "WEB_NOVEL")).andExpect(status().isOk());
        assertThat(data(mvc.perform(get(HISTORY).header("Authorization", owner.bearer())).andExpect(status().isOk()))
                .path("items").isEmpty()).isTrue();
        loggedSearch(owner, " É  Search ", "TITLE");
        var initial = firstHistory(owner);
        loggedSearch(owner, "e\u0301 search", "TITLE");
        var refreshed = firstHistory(owner);
        assertThat(refreshed.path("historyId").asString()).isEqualTo(initial.path("historyId").asString());
        assertThat(Instant.parse(refreshed.path("searchedAt").asString()))
                .isAfter(Instant.parse(initial.path("searchedAt").asString()));
        loggedSearch(owner, "é search", "AUTHOR");
        for (int index = 0; index < 99; index++) loggedSearch(owner, "history-" + index, "TITLE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_search_history WHERE member_id=?",
                Integer.class, id(owner.id()))).isEqualTo(100);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_search_history WHERE history_id=?",
                Integer.class, id(UUID.fromString(initial.path("historyId").asString())))).isZero();
        var page = data(mvc.perform(get(HISTORY).param("limit", "1").header("Authorization", owner.bearer()))
                .andExpect(header().string("Cache-Control", "no-store")));
        String historyId = page.path("items").get(0).path("historyId").asString();
        String cursor = page.path("nextCursor").asString();
        mvc.perform(get(HISTORY).param("cursor", cursor).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.data.items[0].query").value("history-97"));
        error(get(HISTORY).param("cursor", cursor).header("Authorization", other.bearer()), 400, "INVALID_CURSOR");
        error(delete(HISTORY + "/" + historyId).header("Authorization", other.bearer()), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(delete(HISTORY + "/" + historyId).header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        loggedSearch(other, "keep", "TITLE");
        mvc.perform(delete(HISTORY).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        mvc.perform(get(HISTORY).header("Authorization", owner.bearer())).andExpect(jsonPath("$.data.items").isEmpty());
        assertThat(firstHistory(other).path("query").asString()).isEqualTo("keep");
        db.update("DELETE FROM member WHERE member_id=?", id(other.id()));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM book_search_history WHERE member_id=?",
                Integer.class, id(other.id()))).isZero();
    }

    @Test
    void pendingReconsentRetainsPublicAccessButProtectsPrivateHistory() throws Exception {
        configureTerms();
        db.update("UPDATE term_version SET requires_reconsent=TRUE WHERE publication_status='PUBLISHED'");
        try {
            var owner = member(false);
            loggedSearch(owner, "public", "TITLE");
            error(get(HISTORY).header("Authorization", owner.bearer()), 403, "TERMS_RECONSENT_REQUIRED");
            error(delete(HISTORY).header("Authorization", owner.bearer()), 403, "TERMS_RECONSENT_REQUIRED");
        } finally {
            db.update("UPDATE term_version SET requires_reconsent=FALSE WHERE publication_status='PUBLISHED'");
        }
    }

    @Test
    void openApiExposesPublicAndPrivateBookOperations() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + BOOKS + "/search'].get.responses['503']").exists())
                .andExpect(jsonPath("$.paths['" + BOOKS + "/{bookId}/structure'].get.responses['409']").exists())
                .andExpect(jsonPath("$.paths['" + HISTORY + "'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + HISTORY + "/{historyId}'].delete.responses['204']").exists());
    }

    private void configureTerms() {
        for (String type : List.of("SERVICE", "PRIVACY")) {
            UUID termId = UUID.nameUUIDFromBytes(("book-test-" + type).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (db.queryForObject("SELECT COUNT(*) FROM term WHERE term_id=?", Integer.class, id(termId)) != 0) continue;
            db.update("INSERT INTO term(term_id,type,created_at) VALUES (?,?,CURRENT_TIMESTAMP)", id(termId), type);
            db.update("""
                    INSERT INTO term_version(term_version_id,term_id,version,title,content,required,
                        publication_status,effective_at,created_at)
                    VALUES (?,?,'test','test','test',TRUE,'PUBLISHED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, id(UUID.randomUUID()), id(termId));
        }
    }

    private Member member() {
        return member(true);
    }

    private Member member(boolean agreed) {
        UUID member = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        String label = member.toString().substring(0, 16);
        String email = label + "@example.test";
        Instant now = Instant.now();
        db.update("""
                INSERT INTO member(member_id,nickname,nickname_key,phone_number,created_at,updated_at)
                VALUES (?,?,?,'+821000000000',?,?)
                """, id(member), label, label, at(now), at(now));
        db.update("""
                INSERT INTO account(member_id,email,email_key,email_verified_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?)
                """, id(member), email, email, at(now), at(now), at(now));
        db.update("INSERT INTO auth_session(session_id,member_id,created_at,expires_at) VALUES (?,?,?,?)",
                id(session), id(member), at(now), at(now.plusSeconds(600)));
        if (agreed) {
            long order = 0;
            for (byte[] version : db.query("SELECT term_version_id FROM term_version WHERE required=TRUE",
                    (row, index) -> row.getBytes(1))) {
                db.update("""
                        INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at,event_order)
                        VALUES (?,?,?,TRUE,?,?)
                        """, id(UUID.randomUUID()), id(member), version, at(now), ++order);
            }
        }
        var claims = JwtClaimsSet.builder().issuer("nospo").subject(member.toString()).issuedAt(now)
                .expiresAt(now.plusSeconds(600)).claim("sid", session.toString()).build();
        var jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new Member(member, "Bearer " + jwt.getTokenValue());
    }

    private void loggedSearch(Member member, String query, String type) throws Exception {
        mvc.perform(search(query, type, "WEB_NOVEL").header("Authorization", member.bearer())).andExpect(status().isOk());
    }

    private JsonNode firstHistory(Member member) throws Exception {
        return data(mvc.perform(get(HISTORY).header("Authorization", member.bearer())).andExpect(status().isOk()))
                .path("items").get(0);
    }

    private JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private void error(MockHttpServletRequestBuilder request, int statusCode, String code) throws Exception {
        mvc.perform(request).andExpect(status().is(statusCode)).andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.meta.requestId").isString());
    }

    private static MockHttpServletRequestBuilder search(String query, String type, String bookType) {
        return get(BOOKS + "/search").param("query", query).param("type", type).param("bookType", bookType);
    }

    private static EditionMetadata metadata(String key, String title, String isbn) {
        return new EditionMetadata(key, title, List.of("Test Author"), null, null, isbn, null, null, null,
                LocalDate.of(2020, 1, 2));
    }

    private static String flip(String cursor) {
        return (cursor.startsWith("A") ? "B" : "A") + cursor.substring(1);
    }

    private record Member(UUID id, String bearer) {}
}
