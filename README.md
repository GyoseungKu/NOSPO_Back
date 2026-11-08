# NOSPO_Back
NOSPO Web/App Back-End Project

## API 문서

애플리케이션 실행 후 다음 주소에서 확인할 수 있습니다(기본 포트 8080).

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

문서 경로는 인증 없이 접근 가능합니다. 나머지 요청은 인증이 필요합니다.
문서는 구현된 컨트롤러로부터 자동 생성되므로 아직 구현하지 않은 Auth API는 표시되지 않습니다.
실행에는 기존 MySQL 연결 설정이 필요합니다.

Swagger 설정 검증(DB/Docker 불필요): `./gradlew test --tests '*SwaggerUiTests'`
