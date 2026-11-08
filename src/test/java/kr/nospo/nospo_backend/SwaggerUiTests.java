package kr.nospo.nospo_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
class SwaggerUiTests {

	@Autowired
	MockMvc mvc;

	@Test
	void documentationIsAccessibleWithoutLogin() throws Exception {
		mvc.perform(get("/swagger-ui.html"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/swagger-ui/index.html"));
		mvc.perform(get("/swagger-ui/index.html"))
			.andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.title").value("NOSPO API"));
		mvc.perform(get("/v3/api-docs/swagger-config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("/v3/api-docs"));
	}

	@Test
	void otherRequestsStillRequireAuthentication() throws Exception {
		mvc.perform(get("/me").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isUnauthorized());
	}
}
