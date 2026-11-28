# NOSPO Back-End

NOSPO의 Spring Boot API 서버입니다. 이메일·카카오 계정과 약관, 도서 검색·서재·독서 기록, 판본별 Wiki, AI 질문·관계도·토큰, 공지사항을 제공합니다.

- [기능·API 명세 v2.1](docs/NOSPO_Function_API_v2.1.md)
- [API 요약표](docs/NOSPO_API_Summary.xlsx)
- [모듈 구조](docs/NOSPO_Multi_Module_Study_Guide.md)

## 로컬 실행

Java 21과 MySQL 8.4가 필요합니다. MySQL에 로컬용 빈 데이터베이스를 만들고, 아래 변수를 프로젝트 루트의 Git 제외 파일 `env.properties` 또는 OS 환경변수로 설정합니다. `env.properties`에는 `KEY=value` 형식을 사용합니다.

| 설정 | 용도 |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 접속. 예시 URL은 `jdbc:mysql://localhost:3306/nospo?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `AUTH_ENCRYPTION_KEY`, `AUTH_HMAC_KEY`, `AUTH_JWT_KEY` | 각각 별도로 생성한 32바이트 난수의 Base64 값. 재시작 후에도 같은 키를 사용 |
| `AUTH_ALLOWED_ORIGINS` | 실제 프론트 Origin을 쉼표로 구분. 로컬 예시는 `http://localhost:3000` |
| `AUTH_EMAIL_CONFIRM_URL` | 이메일 인증용 프론트 페이지. query·fragment 없이 설정 |
| `AUTH_SECURE_COOKIE` | HTTPS는 `true`. 로컬 HTTP에 한해 `false` |
| `KAKAO_REDIRECT_URI`, `KAKAO_FRONTEND_CALLBACK`, `KAKAO_RETURN_PATHS` | 백엔드 `/api/v1/auth/kakao/callback`의 전체 URL, 프론트 콜백 URL, 쉼표로 구분한 허용 상대 경로. 로컬 HTTP 콜백은 루프백 주소와 `AUTH_SECURE_COOKIE=false` 필요 |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | 카카오 로그인에 사용하는 REST API 키와 Secret. 책 검색은 동일 REST API 키 사용 |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` | 메일 전송 설정. 로컬 메일 서버를 쓴다면 호스트와 포트를 직접 지정 |
| `SMTP_AUTH`, `SMTP_STARTTLS`, `SMTP_STARTTLS_REQUIRED`, `MAIL_WORKER_ENABLED` | SMTP 인증·TLS와 메일 발송 워커 설정 |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | 실제 AI 질문에 사용. 모델 기본값은 `gpt-5-mini`. Wiki와 관계도에는 모델 키가 필요 없음 |

키 생성 예시입니다. 각 키에 대해 별도로 실행합니다.

```sh
openssl rand -base64 32
sh gradlew bootRun
```

Windows에서는 `gradlew.bat bootRun`을 사용합니다. 기본 포트는 8080입니다. Flyway가 스키마를 적용합니다. 번호는 **Book V12·V13 → Wiki V14 → AI V15·V16 → Wiki 검수 V17 → Book 참조 V18 → 관리자 약관 감사 V19** 순서입니다. 공유 DB에 적용된 기존 Flyway 이력은 병합본을 실행하기 전에 확인하세요.

첫 가입 전에 관리자 약관 API나 운영용 초기 마이그레이션을 통해 승인된 **필수 SERVICE·PRIVACY 약관**을 발행해야 합니다. 관리자 API는 이미 활성화된 관리자 계정이 필요합니다. 현행 약관이 없으면 가입은 `503 TERMS_NOT_CONFIGURED`를 반환합니다.

## API 문서

- [Swagger UI](http://localhost:8080/swagger-ui.html)에서 로컬 서버를 열고 **Authorize**에 액세스 토큰을 입력합니다.
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)을 도구에 가져올 수 있습니다.
- API 기본 경로는 `/api/v1`입니다. 본인·관리자 요청은 `Authorization: Bearer <accessToken>`을 사용합니다.

## 주요 흐름

| 기능 | API 사용 순서 |
| --- | --- |
| 가입·로그인 | `GET /terms` → 이메일 인증 발송·링크 확인 → `POST /auth/signup`. 가입과 로그인은 Access JWT와 Refresh 쿠키를 발급합니다. [인증 계약](docs/Auth_API_Implementation.md) |
| 약관 관리 | `POST /admin/terms/versions`로 버전 초안을 저장한 뒤 `PUT /admin/terms/versions/{termVersionId}/publication`에서 시행일과 재동의 여부를 지정합니다. 시행 전에는 관리자에게만 보입니다. |
| Book·서재 | `GET /books/search` → `POST /me/shelf` → 응답의 영구 `data.book.bookId`로 교체. 진도 변경에 GET 응답의 `ETag`를 `If-Match`로 보냅니다. [Book 개발 문서](docs/NOSPO_Book_Development.md) |
| Wiki | 같은 도서를 서재에 등록 → `POST /wiki/drafts` → 인물·단체·프로필·항목·관계·이미지 편집 → 제출 → 관리자 승인. 공개 위키는 현재 독서 진도 범위로 조회합니다. |
| AI 질문 | `POST /ai/questions` → 응답의 `eventsUrl`로 SSE 연결 → `GET /ai/jobs/{jobId}` 또는 질문 결과 조회. 재연결은 마지막 이벤트 ID를 `Last-Event-ID`로 보냅니다. |
| AI 관계도 | `POST /ai/graphs` → `GET /ai/jobs/{jobId}` → 응답의 `result.graphId`로 `GET /ai/graphs/{graphId}`. 재생성은 이전 관계도 ID를 지정합니다. |
| 토큰 | 가입 5개 지급. 질문·관계도는 1개 예약, 성공 시 차감, 실패 시 해제합니다. 회원 거래 목록·상세, 관리자 지급·회수·전액 환불, 위키 승인 보상을 지원합니다. [AI·토큰 상세](docs/AI_Credit_API_Implementation.md) |
| 탈퇴 | `GET /me/withdrawal-info`에서 현재 서버 정책을 확인합니다. 현재는 탈퇴 후 30일 복구 정책을 사용하며, 이 부분은 v2.1의 복구 없는 삭제 정책과 차이가 있습니다. [탈퇴 구현](docs/Withdrawal_API_Implementation.md) · [계정 복구](docs/Recovery_API_Implementation.md) |
| 공지 | 공개 목록·상세와 관리자 등록·수정·삭제·이미지 업로드를 제공합니다. [공지 API 및 프론트 연동](docs/NOSPO_Notice_Development.md) |

Wiki 편집 시 마지막 응답의 `ETag`를 다음 `If-Match`에 사용합니다. 이미지 업로드는 multipart 필드 `file`로 요청하고, 반환된 `assetId`를 프로필의 `imageAssetId`에 저장합니다. 검수 제출·결정, AI 생성, 토큰 조정·환불에는 UUID `Idempotency-Key`를 전달합니다. 첫 Wiki 승인 예시는 `{"rewardAmount":0,"rewardReason":"공개 범위 검수 완료","expectedWikiVersion":0}`입니다.

### AI 로컬 샘플

실제 데이터로 검증할 때는 기본 프로필에서 Book의 영구 판본, 독서 진도와 승인 Wiki를 사용합니다. 샘플만 실행할 때는 로컬 DB에서 `ai-local` 프로필을 사용합니다.

```sh
sh gradlew bootRun --args='--spring.profiles.active=ai-local'
```

샘플 도서 ID는 `00000000-0000-0000-0000-000000000101`이고 현재 진도는 40화입니다. `POST /api/v1/ai/questions`에 `{"bookId":"00000000-0000-0000-0000-000000000101","position":{"unit":"EPISODE","value":10},"question":"하린은 누구인가요?"}`를 보낼 수 있습니다. 실제 키를 사용하면 외부 API 호출 비용이 발생할 수 있습니다.

## 테스트

```sh
# Docker 없이 전체 회귀
sh gradlew test -PskipMysql

# Docker를 실행한 뒤 MySQL Testcontainers까지 포함
sh gradlew test bootJar

# Swagger와 주요 API 선별 검증
sh gradlew :test --tests '*SwaggerUiTests' --tests '*AdminTermsApiTests' --tests '*WikiDraftApiTests' --tests '*AiCreditsAndQuestionsTests'
```

별도의 빈 MySQL에서 Book·Wiki·AI 통합을 검증하려면 `NOSPO_WIKI_TEST_MYSQL_URL`, `NOSPO_WIKI_TEST_MYSQL_USER`, `NOSPO_WIKI_TEST_MYSQL_PASSWORD`를 설정하고 `sh gradlew :test --tests 'kr.nospo.nospo_backend.wiki.WikiBookAiIntegrationTests'`를 실행합니다. 이 테스트는 마이그레이션을 적용하고 테스트 데이터를 씁니다. 질문과 메일 테스트는 대체 응답을 사용합니다.
