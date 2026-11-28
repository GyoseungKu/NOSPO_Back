package kr.nospo.nospo_backend.wiki;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.identity.application.KakaoLinkAccess;
import kr.nospo.wiki.application.WikiBookAccess;
import kr.nospo.wiki.application.WikiStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.flyway.target=17",
        "spring.datasource.url=jdbc:h2:mem:wiki_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "nospo.auth.email-confirm-url=https://app.example.test/auth/confirm",
        "nospo.auth.allowed-origins=https://app.example.test",
        "nospo.auth.kakao.redirect-uri=https://api.example.test/api/v1/auth/kakao/callback",
        "nospo.auth.kakao.frontend-callback=https://app.example.test/oauth/callback",
        "nospo.auth.kakao.return-paths=/"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WikiDraftApiTests {
    private static final String DRAFTS = "/api/v1/wiki/drafts";
    private final UUID owner = UUID.randomUUID();
    private final UUID book = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired WikiStore store;
    @MockitoBean WikiBookAccess books;
    @MockitoBean KakaoLinkAccess consent;

    @BeforeEach
    void fixtures() {
        var now = at(Instant.now());
        db.update("""
                INSERT INTO member(member_id,nickname,nickname_key,phone_number,created_at,updated_at)
                VALUES (?,?,?,?,?,?)
                """, id(owner), owner.toString().substring(0, 12), owner.toString().substring(0, 12), "+821012345678", now, now);
        db.update("""
                INSERT INTO member(member_id,nickname,nickname_key,phone_number,role,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, id(admin), admin.toString().substring(0, 12), admin.toString().substring(0, 12),
                "+821012345678", "ADMIN", now, now);
        when(books.find(book)).thenReturn(Optional.of(new WikiBookAccess.Edition(WikiBookAccess.Unit.EPISODE, 100)));
        when(books.currentPosition(owner, book))
                .thenReturn(Optional.of(new WikiBookAccess.Position(WikiBookAccess.Unit.EPISODE, 40)));
    }

    @Test
    void onlyOwnerCanEditAndEveryWriteUsesTheDraftRevision() throws Exception {
        String initial = json.writeValueAsString(Map.of("bookId", book, "mode", "CREATE", "position", position(30)));
        mvc.perform(post(DRAFTS).contentType("application/json").content(initial)).andExpect(status().isUnauthorized());
        var created = mvc.perform(post(DRAFTS).with(member(owner)).contentType("application/json").content(initial))
                .andExpect(status().isCreated()).andReturn();
        String path = DRAFTS + "/" + data(created).path("draftId").asString();
        String tag = created.getResponse().getHeader("ETag");
        mvc.perform(get(path).with(member(UUID.randomUUID()))).andExpect(status().isNotFound());

        String input = json.writeValueAsString(Map.of("firstRevealAt", position(20), "profile", profile(20, "하린")));
        mvc.perform(post(path + "/characters").with(member(owner)).contentType("application/json").content(input))
                .andExpect(status().is(428));
        var added = mvc.perform(post(path + "/characters").with(member(owner)).header("If-Match", tag)
                        .contentType("application/json").content(input))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.profiles[0].name").value("하린")).andReturn();
        String entityId = data(added).path("entityId").asString();
        mvc.perform(post(path + "/characters").with(member(owner)).header("If-Match", tag)
                        .contentType("application/json").content(input)).andExpect(status().isPreconditionFailed());

        String fresh = added.getResponse().getHeader("ETag");
        mvc.perform(patch(path).with(member(owner)).header("If-Match", fresh).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("position", position(19)))))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("DRAFT_POSITION_CONFLICT"))
                .andExpect(jsonPath("$.error.details[0].ids[0]").value(entityId));
        var changed = mvc.perform(post(path + "/characters/" + entityId + "/profiles").with(member(owner))
                        .header("If-Match", fresh).contentType("application/json")
                        .content(json.writeValueAsString(profile(25, "하린 관장"))))
                .andExpect(status().isCreated()).andReturn();
        mvc.perform(get("/api/v1/me/wiki/drafts").with(member(owner)).param("bookId", book.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].revision").value(2));
        mvc.perform(delete(path).with(member(owner)).header("If-Match", changed.getResponse().getHeader("ETag")))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).with(member(owner))).andExpect(status().isNotFound());
    }

    @Test
    void updateCopiesTheRequestedVersionWithoutFutureProfilesOrPublicIds() throws Exception {
        UUID wiki = UUID.randomUUID();
        UUID character = UUID.randomUUID();
        store.insertWiki(wiki, book);
        var first = json.valueToTree(profile(10, "공개 이름"));
        var future = json.valueToTree(profile(35, "미래 이름"));
        var content = json.writeValueAsString(Map.of("characters", List.of(Map.of(
                "entityId", character, "kind", "CHARACTER", "firstRevealAt", position(10),
                "profiles", List.of(first, future), "entries", List.of()))));
        store.insertVersion(new WikiStore.PublishedVersion(wiki, book, 1, content, Instant.now()));
        store.insertVersion(new WikiStore.PublishedVersion(wiki, book, 2, "{}", Instant.now()));

        var result = mvc.perform(post(DRAFTS).with(member(owner)).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("bookId", book, "mode", "UPDATE", "position", position(20),
                                "wikiId", wiki, "baseWikiVersion", 1))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.baseWikiVersion").value(1))
                .andExpect(jsonPath("$.data.characters[0].profiles.length()").value(1))
                .andExpect(jsonPath("$.data.characters[0].profiles[0].name").value("공개 이름")).andReturn();
        assertThat(data(result).path("characters").get(0).path("entityId").asString()).isNotEqualTo(character.toString());
        String path = DRAFTS + "/" + data(result).path("draftId").asString();
        String copied = data(result).path("characters").get(0).path("entityId").asString();
        UUID submitKey = UUID.randomUUID();
        String submission = data(submit(path, tag(result), submitKey)).path("submissionId").asString();
        when(books.currentPosition(owner, book))
                .thenReturn(Optional.of(new WikiBookAccess.Position(WikiBookAccess.Unit.EPISODE, 5)));
        mvc.perform(get(path).with(member(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.characters").isEmpty());
        mvc.perform(get(path + "/characters/" + copied).with(member(owner))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/wiki/submissions/" + submission).with(member(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.characters").isEmpty());
        mvc.perform(post(path + "/submissions").with(member(owner)).header("If-Match", tag(result))
                        .header("Idempotency-Key", submitKey))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("POSITION_EXCEEDS_CURRENT"));
    }

    @Test
    void zeroRewardApprovalPublishesOnceAndHidesFutureRelationsAndImages() throws Exception {
        String publicPath = "/api/v1/books/" + book + "/wiki";
        mvc.perform(get(publicPath).with(member(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(false));
        var draft = postJson(DRAFTS, owner, null, Map.of("bookId", book, "mode", "CREATE", "position", position(35)));
        String path = DRAFTS + "/" + data(draft).path("draftId").asString();
        var first = postJson(path + "/characters", owner, tag(draft),
                Map.of("firstRevealAt", position(10), "profile", profile(10, "하린")));
        var organization = postJson(path + "/organizations", owner, tag(first),
                Map.of("firstRevealAt", position(10), "profile", profile(10, "도서관")));
        byte[] png;
        try (var bytes = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB),
                    "png", bytes);
            png = bytes.toByteArray();
        }
        var upload = mvc.perform(multipart(path + "/assets").file("file", png).with(member(owner))
                        .header("If-Match", tag(organization)))
                .andExpect(status().isCreated()).andReturn();
        String asset = data(upload).path("assetId").asString();
        String assetPath = "/api/v1/wiki/assets/" + asset;
        String entity = data(first).path("entityId").asString();
        var lateProfile = postJson(path + "/characters/" + entity + "/profiles", owner, tag(upload),
                Map.of("name", "하린 관장", "description", "추가 정보", "revealAt", position(30), "imageAssetId", asset));
        var relation = postJson(path + "/relations", owner, tag(lateProfile),
                Map.of("from", Map.of("type", "CHARACTER", "id", entity), "to",
                        Map.of("type", "ORGANIZATION", "id", data(organization).path("entityId").asString()),
                        "type", "AFFILIATION", "description", "가입", "revealAt", position(15),
                        "sourcePosition", position(15), "events", List.of(Map.of("kind", "END",
                                "description", "탈퇴", "revealAt", position(32), "sourcePosition", position(32)))));
        mvc.perform(get(assetPath).with(member(UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(get(assetPath).with(member(owner))).andExpect(status().isOk());
        UUID submitKey = UUID.randomUUID();
        var submission = submit(path, tag(relation), submitKey);
        assertThat(data(submit(path, tag(relation), submitKey)).path("submissionId")).isEqualTo(data(submission).path("submissionId"));
        mvc.perform(delete(path).with(member(owner)).header("If-Match", tag(relation)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRAFT_NOT_EDITABLE"));
        String reviewId = data(submission).path("submissionId").asString();
        String review = "/api/v1/admin/wiki/submissions/" + reviewId;
        mvc.perform(get(review).with(member(owner))).andExpect(status().isForbidden());
        String reviewTag = tag(mvc.perform(get(review).with(member(admin))).andExpect(status().isOk()).andReturn());
        UUID approvalKey = UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("rewardAmount", 0, "rewardReason", "검수 완료", "expectedWikiVersion", 0));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(review + "/approve").with(member(admin)).header("If-Match", reviewTag)
                            .header("Idempotency-Key", approvalKey).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.publishedWikiVersion").value(1));
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM wiki_version v JOIN wiki w ON v.wiki_id=w.wiki_id WHERE w.book_id=?",
                Integer.class, id(book))).isOne();
        mvc.perform(get(publicPath + "/relations").with(member(owner)).param("positionValue", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].type").value("AFFILIATION"));
        mvc.perform(get(publicPath + "/relations").with(member(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());

        UUID reader = UUID.randomUUID();
        when(books.currentPosition(reader, book))
                .thenReturn(Optional.of(new WikiBookAccess.Position(WikiBookAccess.Unit.EPISODE, 20)));
        mvc.perform(get(assetPath).with(member(owner)).param("positionValue", "20")).andExpect(status().isNotFound());
        mvc.perform(get(assetPath).with(member(reader))).andExpect(status().isNotFound());
        when(books.currentPosition(reader, book))
                .thenReturn(Optional.of(new WikiBookAccess.Position(WikiBookAccess.Unit.EPISODE, 35)));
        mvc.perform(get(assetPath).with(member(reader))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/wiki/review-summary").with(member(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.approved").value(1));
    }

    @Test
    void createsOneRevisionDraftAndAllowsResubmittingWithoutEditingOriginal() throws Exception {
        var draft = postJson(DRAFTS, owner, null, Map.of("bookId", book, "mode", "CREATE", "position", position(30)));
        String path = DRAFTS + "/" + data(draft).path("draftId").asString();
        var character = postJson(path + "/characters", owner, tag(draft),
                Map.of("firstRevealAt", position(20), "profile", profile(20, "하린")));
        var submission = submit(path, tag(character), UUID.randomUUID());
        String parent = data(submission).path("submissionId").asString();
        String adminPath = "/api/v1/admin/wiki/submissions/" + parent;
        var review = mvc.perform(get(adminPath).with(member(admin))).andExpect(status().isOk()).andReturn();
        mvc.perform(post(adminPath + "/request-changes").with(member(admin)).header("If-Match", tag(review))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("comment", "설명 보완"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"));
        String revision = "/api/v1/wiki/submissions/" + parent + "/revision-drafts";
        var created = mvc.perform(post(revision).with(member(owner))).andExpect(status().isCreated()).andReturn();
        var replay = mvc.perform(post(revision).with(member(owner))).andExpect(status().isOk()).andReturn();
        assertThat(data(created).path("draftId")).isEqualTo(data(replay).path("draftId"));
        var resubmitted = submit(DRAFTS + "/" + data(created).path("draftId").asString(), tag(created), UUID.randomUUID());
        assertThat(data(resubmitted).path("parentSubmissionId").asString()).isEqualTo(parent);
    }

    private MvcResult postJson(String path, UUID actor, String tag, Object body) throws Exception {
        var request = post(path).with(member(actor)).contentType("application/json").content(json.writeValueAsString(body));
        if (tag != null) request.header("If-Match", tag);
        return mvc.perform(request).andExpect(status().isCreated()).andReturn();
    }

    private MvcResult submit(String path, String tag, UUID key) throws Exception {
        return mvc.perform(post(path + "/submissions").with(member(owner)).header("If-Match", tag)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn();
    }

    private static String tag(MvcResult result) {
        return result.getResponse().getHeader("ETag");
    }

    private static Map<String, Object> position(int value) {
        return Map.of("unit", "EPISODE", "value", value);
    }

    private static Map<String, Object> profile(int at, String name) {
        return Map.of("name", name, "description", "", "revealAt", position(at));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID id) {
        return jwt().jwt(token -> token.subject(id.toString()).claim("sid", UUID.randomUUID().toString()));
    }

    private JsonNode data(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
}
