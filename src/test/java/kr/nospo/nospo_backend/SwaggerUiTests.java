package kr.nospo.nospo_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
class SwaggerUiTests {

    @Autowired MockMvc mvc;

    @Test
    void noticeDocumentationMatchesRequestAndResponseContracts() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andDo(result -> {
            if (result.getResolvedException() != null) throw result.getResolvedException();
        }).andExpect(status().isOk()).andReturn();
        var document = new tools.jackson.databind.ObjectMapper()
                .readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var paths = document.path("paths");
        var publicList = paths.path("/api/v1/notices").path("get");
        var publicRead = paths.path("/api/v1/notices/{noticeId}").path("get");
        var adminList = paths.path("/api/v1/admin/notices").path("get");
        var adminRead = paths.path("/api/v1/admin/notices/{noticeId}").path("get");
        var create = paths.path("/api/v1/admin/notices").path("post");
        var patch = paths.path("/api/v1/admin/notices/{noticeId}").path("patch");
        var delete = paths.path("/api/v1/admin/notices/{noticeId}").path("delete");
        var upload = paths.path("/api/v1/admin/notices/images").path("post");
        var operations = java.util.List.of(publicList, publicRead, adminList, adminRead, create, patch, delete, upload);
        var ids = java.util.List.of("listPublicNotices", "getPublicNotice", "listAdminNotices", "getAdminNotice",
                "createNotice", "patchNotice", "deleteNotice", "uploadNoticeImage");
        var statuses = java.util.List.of("200", "200", "200", "200", "201", "200", "204", "201");
        for (int i = 0; i < operations.size(); i++) {
            var op = operations.get(i);
            assertThat(op.path("operationId").asText()).isEqualTo(ids.get(i));
            assertThat(op.at("/tags/0").asText()).isEqualTo(i < 2 ? "공개 공지" : "관리자 공지");
            assertThat(op.path("security").toString().contains("bearerAuth")).isEqualTo(i >= 2);
            var success = op.path("responses").path(statuses.get(i));
            if (i == 6) {
                assertThat(success.has("content")).isFalse();
                assertThat(op.has("requestBody")).isFalse();
            } else {
                assertThat(success.at("/content/application~1json/example/meta/requestId").asText()).isNotBlank();
            }
            assertThat(op.at("/responses/400/content/application~1json/examples").isObject()).isTrue();
        }
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        for (var byId : java.util.List.of(publicRead, adminRead, patch, delete)) {
            assertThat(byId.at("/parameters/0/in").asText()).isEqualTo("path");
            assertThat(byId.at("/parameters/0/required").asBoolean()).isTrue();
            assertThat(byId.at("/parameters/0/schema/format").asText()).isEqualTo("uuid");
            assertThat(byId.path("responses").has("404")).isTrue();
        }
        for (var list : java.util.List.of(publicList, adminList)) {
            assertThat(list.at("/parameters/0/schema/default").asText()).isEqualTo("latest");
            assertThat(list.at("/parameters/0/schema/enum/1").asText()).isEqualTo("oldest");
            assertThat(list.at("/parameters/1/schema/default").asInt()).isEqualTo(20);
            assertThat(list.at("/parameters/1/schema/minimum").asInt()).isEqualTo(1);
            assertThat(list.at("/parameters/1/schema/maximum").asInt()).isEqualTo(100);
            assertThat(list.at("/parameters/2/required").asBoolean()).isFalse();
            assertThat(list.at("/responses/200/content/application~1json/example/data/nextCursor").isNull()).isTrue();
            assertNullable(list.at("/responses/200/content/application~1json/schema/properties/data/properties/nextCursor"));
        }
        var publishedAt = "/responses/200/content/application~1json/schema/properties/data/properties/publishedAt";
        assertNullable(adminRead.at(publishedAt));
        assertThat(publicRead.at(publishedAt).toString()).doesNotContain("null");
        assertThat(adminList.at("/responses/200/headers/X-CSRF-TOKEN").isObject()).isTrue();
        for (var write : java.util.List.of(create, patch, delete, upload)) {
            var parameters = write.path("parameters");
            var csrf = parameters.get(parameters.size() - 1);
            assertThat(csrf.path("name").asText()).isEqualTo("X-CSRF-TOKEN");
            assertThat(csrf.path("in").asText()).isEqualTo("header");
            assertThat(csrf.path("required").asBoolean()).isTrue();
            assertThat(write.at("/responses/403/content/application~1json/examples").has("TERMS_RECONSENT_REQUIRED")).isTrue();
        }
        assertThat(create.at("/requestBody/content/application~1json/schema/required").size()).isEqualTo(5);
        assertThat(patch.at("/requestBody/content/application~1json/schema/minProperties").asInt()).isEqualTo(1);
        var uploadSchema = upload.at("/requestBody/content/multipart~1form-data/schema");
        assertThat(uploadSchema.at("/required/0").asText()).isEqualTo("file");
        assertThat(uploadSchema.at("/properties/file/format").asText()).isEqualTo("binary");
        assertThat(uploadSchema.at("/properties/file/type").toString()).contains("\"string\"");
        assertThat(upload.at("/responses/201/content/application~1json/example/data").size()).isEqualTo(1);
        for (var status : java.util.List.of("401", "403", "409", "413", "422", "500", "503")) {
            assertThat(upload.path("responses").has(status)).isTrue();
        }
        var error = upload.at("/responses/422/content/application~1json/examples/INVALID_IMAGE/value/error");
        assertThat(error.path("message").asText()).isEqualTo("요청을 처리할 수 없습니다.");
        assertThat(error.path("details").isArray()).isTrue();
    }

    private static void assertNullable(tools.jackson.databind.JsonNode schema) {
        assertThat(schema.path("nullable").asBoolean()
                || schema.path("type").toString().contains("\"null\"")).isTrue();
    }

    @Test
    void documentationIsAccessibleWithoutLogin() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("NOSPO API"))
                .andExpect(jsonPath("$.servers.length()").value(2))
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.servers[1].url").value("https://nospo.syu-likelion.org"));
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }

    @Test
    void otherRequestsStillRequireAuthentication() throws Exception {
        mvc.perform(get("/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wikiAndAiDocumentsMatchMethodsHeadersAndGraphSchemas() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var doc = new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        var paths = doc.path("paths");
        var graph = paths.path("/api/v1/ai/graphs").path("post");
        assertThat(graph.at("/requestBody/content/application~1json/schema/$ref").asString())
                .endsWith("/GraphRequest");
        assertThat(doc.at("/components/schemas/GraphRequest/properties").has("bookId")).isTrue();
        assertThat(doc.at("/components/schemas/GraphRequest/properties").has("termVersionId")).isFalse();
        assertThat(doc.at("/components/schemas/GraphAccepted/properties").has("questionId")).isFalse();
        assertThat(graph.path("responses").has("202")).isTrue();

        String root = "/api/v1/wiki/drafts";
        var draft = paths.path(root + "/{draftId}");
        assertThat(paths.path(root).at("/post/responses/201/headers").has("ETag")).isTrue();
        assertThat(draft.at("/get/responses/200/headers").has("ETag")).isTrue();
        assertThat(draft.at("/delete/responses/204").has("content")).isFalse();
        var patch = draft.path("patch");
        assertThat(patch.path("parameters").values().stream()
                .anyMatch(p -> "If-Match".equals(p.path("name").asString()) && p.path("required").asBoolean())).isTrue();
        assertThat(patch.path("responses").has("412")).isTrue();
        assertThat(patch.path("responses").has("428")).isTrue();
        var upload = paths.path(root + "/{draftId}/assets").path("post");
        assertThat(upload.at("/requestBody/content/multipart~1form-data/schema/properties/file/format").asString())
                .isEqualTo("binary");
        assertThat(paths.path("/api/v1/wiki/submissions/{submissionId}/revision-drafts").path("post")
                .path("responses").propertyNames()).contains("200", "201");
        assertThat(paths.path("/api/v1/admin/terms/versions").path("post").path("responses").has("201")).isTrue();
    }

    @Test
    void everyApiOperationHasDocumentation() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var document = new tools.jackson.databind.ObjectMapper()
                .readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        int operations = 0;
        for (var path : document.path("paths").properties()) {
            if (!path.getKey().startsWith("/api/v1/")) continue;
            for (String method : java.util.List.of("get", "post", "put", "patch", "delete")) {
                var operation = path.getValue().get(method);
                if (operation == null) continue;
                String endpoint = method + " " + path.getKey();
                org.junit.jupiter.api.Assertions.assertFalse(operation.path("summary").asText().isBlank(), endpoint);
                org.junit.jupiter.api.Assertions.assertFalse(operation.path("description").asText().isBlank(), endpoint);
                org.junit.jupiter.api.Assertions.assertFalse(operation.path("tags").isEmpty(), endpoint);
                if (path.getKey().equals("/api/v1/me") || path.getKey().startsWith("/api/v1/me/")) {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            operation.path("security").toString().contains("bearerAuth"), endpoint);
                }
                operations++;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(operations > 0);
        org.junit.jupiter.api.Assertions.assertTrue(document.at("/paths/~1api~1v1~1auth~1signup/post/responses").has("201"));
        org.junit.jupiter.api.Assertions.assertTrue(document.at("/paths/~1api~1v1~1auth~1kakao~1authorize/get/responses").has("302"));
        org.junit.jupiter.api.Assertions.assertTrue(document.at("/paths/~1api~1v1~1me~1withdrawal/post/responses").has("204"));
    }
}
