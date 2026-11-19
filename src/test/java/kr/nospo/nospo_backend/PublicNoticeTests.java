package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.nospo.identity.application.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = {NospoBackendApplication.class, PublicNoticeTests.TestClock.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicNoticeTests {
    protected static final Instant NOW = Instant.parse("2026-11-19T12:00:00.123456Z");
    private static final String PATH = "/api/v1/notices";
    private static final String MARKDOWN = "**notice** <script>fixture</script>";

    @TestConfiguration
    static class TestClock {
        @Bean @Primary
        Clock noticeTestClock() {
            return Clock.fixed(NOW.plusNanos(500), ZoneOffset.UTC);
        }
    }

    @MockitoBean EmailSender sender;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clearNotices() {
        db.update("DELETE FROM notice");
    }

    UUID insert(UUID noticeId, boolean pinned, boolean published, Instant publishedAt) {
        db.update("""
                INSERT INTO notice(notice_id,title,summary,content,pinned,published,published_at,created_at,updated_at)
                VALUES (?, 'fixture title', '', ?, ?, ?, ?, ?, ?)
                """, id(noticeId), MARKDOWN, pinned, published, publishedAt == null ? null : utc(publishedAt), utc(NOW), utc(NOW));
        return noticeId;
    }

    @Test
    void publicReadsOnlyPublishedRowsAtOrBeforeServerTime() throws Exception {
        UUID visible = insert(UUID.randomUUID(), false, true, NOW);
        UUID future = insert(UUID.randomUUID(), true, true, NOW.plusNanos(1000));
        UUID draft = insert(UUID.randomUUID(), true, false, null);
        UUID withdrawn = insert(UUID.randomUUID(), false, false, NOW.minusSeconds(1));
        var response = mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.data.items[0].noticeId").value(visible.toString()))
                .andExpect(jsonPath("$.data.items[0].content").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.items[0].published").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.items[0].createdAt").doesNotHaveJsonPath())
                .andReturn();
        var data = mapper.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(data.path("items").size()).isEqualTo(1);
        assertThat(data.path("nextCursor").isNull()).isTrue();
        assertThat(data.path("hasNext").asBoolean()).isFalse();
        mvc.perform(get(PATH + "/{id}", visible)).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.data.noticeId").value(visible.toString()))
                .andExpect(jsonPath("$.data.publishedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.data.content").value(MARKDOWN))
                .andExpect(jsonPath("$.data.published").doesNotHaveJsonPath());
        for (UUID hidden : List.of(future, draft, withdrawn, UUID.randomUUID())) {
            mvc.perform(get(PATH + "/{id}", hidden)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.meta.requestId").isString());
        }
    }

    @Test
    void keysetHonorsPinnedTimeAndUnsignedBinaryUuidAcrossPages() throws Exception {
        UUID low = insert(new UUID(1, 1), true, true, NOW.minusSeconds(10));
        UUID high = insert(new UUID(Long.MIN_VALUE, 1), true, true, NOW.minusSeconds(10));
        UUID latest = insert(new UUID(0, 1), true, true, NOW);
        UUID normalOld = insert(new UUID(0, 2), false, true, NOW.minusSeconds(4));
        UUID normalNew = insert(new UUID(0, 3), false, true, NOW);
        var seen = new ArrayList<String>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            JsonNode result = page("2", cursor);
            result.path("items").forEach(item -> seen.add(item.path("noticeId").asString()));
            assertThat(result.path("hasNext").asBoolean()).isEqualTo(page < 2);
            if (page < 2) {
                cursor = result.path("nextCursor").asString();
                assertThat(cursor).isNotBlank();
            } else {
                assertThat(result.path("nextCursor").isNull()).isTrue();
            }
        }
        assertThat(seen).containsExactlyElementsOf(
                List.of(latest, high, low, normalNew, normalOld).stream().map(UUID::toString).toList());
    }

    @Test
    void emptyAndLimitBoundariesFollowCommonContract() throws Exception {
        var empty = page(null, null);
        assertThat(empty.path("items").size()).isZero();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
        assertThat(empty.path("hasNext").asBoolean()).isFalse();
        for (int i = 1; i <= 101; i++) insert(new UUID(0, i), false, true, NOW);
        assertThat(page(null, null).path("items").size()).isEqualTo(20);
        assertThat(page("1", null).path("items").size()).isEqualTo(1);
        for (int limit : List.of(10, 20, 30)) {
            assertThat(page(Integer.toString(limit), null).path("items").size()).isEqualTo(limit);
        }
        var max = page("100", null);
        assertThat(max.path("items").size()).isEqualTo(100);
        assertThat(page("100", max.path("nextCursor").asString()).path("items").size()).isEqualTo(1);
        for (String limit : List.of("0", "101", "-1")) {
            mvc.perform(get(PATH).param("limit", limit)).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        for (String invalid : List.of("1.5", "1 0", "")) {
            mvc.perform(get(PATH).param("limit", invalid)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void rejectsMalformedIdsAndTamperedCursors() throws Exception {
        insert(new UUID(0, 1), false, true, NOW);
        insert(new UUID(0, 2), false, true, NOW);
        String cursor = page("1", null).path("nextCursor").asString();
        String tampered = (cursor.startsWith("A") ? "B" : "A") + cursor.substring(1);
        for (String invalid : List.of("", "abc", tampered, cursor + "=", "x".repeat(257))) {
            mvc.perform(get(PATH).param("cursor", invalid)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
                    .andExpect(jsonPath("$.meta.requestId").isString());
        }
        for (String invalid : List.of("1-2-3-4-5", "not-a-uuid", "' OR 1=1 --")) {
            mvc.perform(get(PATH + "/{id}", invalid)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void otherMethodsAndAdminPathsRemainProtected() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isForbidden());
        mvc.perform(patch(PATH + "/{id}", UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(delete(PATH + "/{id}", UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(patch(PATH + "/{id}", UUID.randomUUID()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + "/{id}", UUID.randomUUID()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(head(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/notices")).andExpect(status().isUnauthorized());
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Test
    void oldestKeepsPinnedFirstAndFiltersDeletedRowsAcrossPages() throws Exception {
        UUID low = insert(new UUID(1, 0), true, true, NOW.minusSeconds(5));
        UUID high = insert(new UUID(Long.MIN_VALUE, 0), true, true, NOW.minusSeconds(5));
        UUID pinnedNew = insert(new UUID(0, 2), true, true, NOW);
        UUID normal = insert(new UUID(0, 3), false, true, NOW.minusSeconds(10));
        var first = mvc.perform(get(PATH).param("sort", "oldest").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].noticeId").value(low.toString()))
                .andReturn();
        String cursor = mapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("nextCursor").asString();
        mvc.perform(get(PATH).param("cursor", cursor).param("sort", "latest"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        String latest = page("1", null).path("nextCursor").asString();
        mvc.perform(get(PATH).param("sort", "oldest").param("cursor", latest))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        jdbcDeleteNotice(high);
        mvc.perform(get(PATH).param("sort", "oldest").param("cursor", cursor).param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].noticeId").value(pinnedNew.toString()))
                .andExpect(jsonPath("$.data.items[1].noticeId").value(normal.toString()))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(PATH + "/{id}", high)).andExpect(status().isNotFound());
        mvc.perform(get(PATH).param("sort", "newest")).andExpect(status().isBadRequest());
    }

    private void jdbcDeleteNotice(UUID notice) {
        db.update("UPDATE notice SET deleted_at=? WHERE notice_id=?", utc(NOW), id(notice));
    }

    private JsonNode page(String limit, String cursor) throws Exception {
        var request = get(PATH);
        if (limit != null) request.param("limit", limit);
        if (cursor != null) request.param("cursor", cursor);
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).path("data");
    }
}
