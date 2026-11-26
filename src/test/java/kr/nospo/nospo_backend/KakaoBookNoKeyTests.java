package kr.nospo.nospo_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "KAKAO_CLIENT_ID=",
        "spring.datasource.url=jdbc:h2:mem:kakao_book_no_key;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KakaoBookNoKeyTests {
    @Autowired MockMvc mvc;

    @Test
    void missingRestApiKeyLeavesNovelSearchAvailable() throws Exception {
        String path = "/api/v1/books/search";
        for (String filter : new String[] {"BOOK", ""}) {
            var request = get(path).param("query", "fixture").param("type", "TITLE");
            if (!filter.isEmpty()) request.param("bookType", filter);
            mvc.perform(request).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("BOOK_PROVIDER_UNAVAILABLE"))
                    .andExpect(jsonPath("$.meta.requestId").isString());
        }
        mvc.perform(get(path).param("query", "fixture").param("type", "TITLE").param("bookType", "WEB_NOVEL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }
}
