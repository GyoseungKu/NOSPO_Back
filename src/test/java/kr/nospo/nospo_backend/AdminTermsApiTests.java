package kr.nospo.nospo_backend;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import kr.nospo.identity.application.KakaoLinkAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin_terms;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailSignupTests.TimeConfig.class)
class AdminTermsApiTests {
    private static final String ADMIN = "/api/v1/admin/terms/versions";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired EmailSignupTests.MutableClock clock;
    @MockitoBean KakaoLinkAccess consent;

    @Test
    void createsAndSchedulesAnImmutableVersionForAdministratorsOnly() throws Exception {
        UUID admin = insertMember("ADMIN");
        UUID reader = insertMember("USER");
        UUID term = UUID.randomUUID();
        String input = json.writeValueAsString(Map.of("termId", term, "type", "SERVICE", "version", "1.0",
                "title", "서비스 이용약관", "content", "약관 본문", "required", true));
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN).with(as(reader))).andExpect(status().isForbidden());
        mvc.perform(post(ADMIN).with(as(reader)).contentType("application/json").content(input))
                .andExpect(status().isForbidden());

        var result = mvc.perform(post(ADMIN).with(as(admin)).contentType("application/json").content(input))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.publicationStatus").value("DRAFT")).andReturn();
        String version = json.readTree(result.getResponse().getContentAsByteArray()).path("data")
                .path("termVersionId").asString();
        mvc.perform(get(ADMIN + "/" + version).with(as(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("약관 본문"));
        mvc.perform(get(ADMIN).with(as(admin)).param("termId", term.toString()).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].termVersionId").value(version));
        mvc.perform(post(ADMIN).with(as(admin)).contentType("application/json").content(input))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERM_VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/terms/" + version)).andExpect(status().isNotFound());

        String publication = ADMIN + "/" + version + "/publication";
        for (int seconds : new int[] {60, 120}) {
            mvc.perform(put(publication).with(as(admin)).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("effectiveAt", clock.instant().plusSeconds(seconds),
                                    "requiresReconsent", true))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.requiresReconsent").value(true));
        }
        mvc.perform(get("/api/v1/terms/" + version)).andExpect(status().isNotFound());
        clock.advance(121);
        mvc.perform(get("/api/v1/terms/" + version)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termVersionId").value(version));
        mvc.perform(put(publication).with(as(admin)).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("effectiveAt", clock.instant().plusSeconds(60),
                                "requiresReconsent", false))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERM_ALREADY_EFFECTIVE"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM term_admin_audit WHERE term_version_id=? AND actor_id=?",
                Integer.class, id(UUID.fromString(version)), id(admin))).isEqualTo(3);
    }

    @Test
    void withdrawalInformationRequiresAuthenticationAndReportsTheCurrentPolicy() throws Exception {
        String path = "/api/v1/me/withdrawal-info";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(as(insertMember("USER")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoverable").value(true))
                .andExpect(jsonPath("$.data.deletes").isArray())
                .andExpect(jsonPath("$.data.retains").isArray());
    }

    private UUID insertMember(String role) {
        UUID member = UUID.randomUUID();
        String name = member.toString().substring(0, 12);
        Instant now = clock.instant();
        db.update("""
                INSERT INTO member(member_id,nickname,nickname_key,phone_number,role,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, id(member), name, name, "+821012345678", role, at(now), at(now));
        return member;
    }

    private static RequestPostProcessor as(UUID member) {
        return jwt().jwt(token -> token.subject(member.toString()).claim("sid", UUID.randomUUID().toString()));
    }
}
