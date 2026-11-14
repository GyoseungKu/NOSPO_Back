package kr.nospo.nospo_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	@Bean
	OpenAPI nospoOpenApi() {
		return new OpenAPI().addServersItem(new Server().url("https://nospo.syu-likelion.org").description("NOSPO HTTPS API"))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))).info(new Info()
			.title("NOSPO API")
			.version("v2.1")
			.description("NOSPO 서비스 API. 구현된 컨트롤러를 기준으로 생성됩니다."));
	}

    @Bean
    OpenApiCustomizer authenticatedOperations() {
        return api -> {
            var me = api.getPaths().get("/api/v1/me");
            if (me != null && me.getGet() != null) {
                me.getGet().addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
            }
        };
    }
}
