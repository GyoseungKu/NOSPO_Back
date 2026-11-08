package kr.nospo.nospo_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	@Bean
	OpenAPI nospoOpenApi() {
		return new OpenAPI().info(new Info()
			.title("NOSPO API")
			.version("v2.1")
			.description("NOSPO 서비스 API. 구현된 컨트롤러를 기준으로 생성됩니다."));
	}
}
