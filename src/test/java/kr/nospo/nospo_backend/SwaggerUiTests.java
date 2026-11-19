package kr.nospo.nospo_backend;

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
    void documentationIsAccessibleWithoutLogin() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("NOSPO API"))
                .andExpect(jsonPath("$.servers.length()").value(1))
                .andExpect(jsonPath("$.servers[0].url").value("https://nospo.syu-likelion.org"));
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
