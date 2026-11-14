# NOSPO_Back
NOSPO Web/App Back-End Project

## API 문서

애플리케이션 실행 후 다음 주소에서 확인할 수 있습니다(기본 포트 8080).

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

문서·약관·가입 API는 인증 없이 접근 가능합니다. `/api/v1/me`는 가입 후 받은 Bearer JWT로 호출합니다.
Swagger에는 구현된 컨트롤러만 표시됩니다.

## 이메일 회원가입

이메일 회원가입·로그인·Refresh 회전·로그아웃·비밀번호 재설정을 제공합니다. 가입과 로그인 시 Access JWT(15분)와 Refresh 쿠키(14일 세션)를 발급합니다. 상세 계약과 제한은 [Auth API 구현 문서](docs/Auth_API_Implementation.md)를 참고하세요. 닉네임·연락처·프로필·이메일·비밀번호 변경과 비밀번호 재인증도 구현했습니다. [내 정보 변경 문서](docs/Member_Profile_API_Implementation.md)를 참고하세요. 카카오와 탈퇴 API는 후속 구현 대상입니다.

| 순서 | API | 동작 |
| --- | --- | --- |
| 1 | `GET /api/v1/terms` | 현재 시행 중인 가입 약관 조회 |
| 2 | `GET /api/v1/auth/availability/nickname?nickname=...` | 닉네임 중복 확인 |
| 3 | `GET /api/v1/auth/availability/email?email=...` | 이메일 중복 확인 |
| 4 | `POST /api/v1/auth/signup/email-verifications` | `{ "email": "reader@example.com" }`, 202 발송 작업 접수 |
| 5 | `POST /api/v1/auth/signup/email-verifications/confirm` | `{ "token": "메일 링크의 토큰" }`, emailProof 발급 |
| 6 | `POST /api/v1/auth/signup` | 아래 요청, `Idempotency-Key` UUID 필수 |
| 7 | `GET /api/v1/me` | `Authorization: Bearer {accessToken}`으로 가입 결과 확인 |

```json
{
  "nickname": "책읽는새",
  "emailProof": "검증 API에서 받은 증명",
  "password": "사용자가 입력한 비밀번호",
  "phoneNumber": "010-1234-5678",
  "consents": [
    { "termVersionId": "약관 조회에서 받은 UUID", "agreed": true }
  ]
}
```

`consents`에는 조회한 현행 약관 전체의 동의/미동의를 명시합니다. 필수 약관은 true여야 합니다. 회원·동의·5토큰·증명 소비·세션·멱등 성공 결과는 같은 트랜잭션으로 저장하며, 충돌하면 모두 롤백합니다. 같은 키·같은 요청을 재시도하면 최초 결과를 반환합니다.

인증 메일 링크는 `AUTH_EMAIL_CONFIRM_URL#token=...` 형태입니다. 프론트는 fragment의 토큰을 읽어 검증 API에 POST한 뒤 주소에서 제거해야 합니다. 링크 GET만으로 검증하지 않습니다. 링크/가입 증명은 각각 발급 후 10분, 재발송 간격은 60초입니다. 재클릭은 `409 CHALLENGE_ALREADY_VERIFIED`이며 기존 증명은 유지합니다.

메일은 DB outbox에 암호화 저장하고 워커가 전송합니다. 제공자 접수는 ACCEPTED로 기록하며 받은편지함 도착을 뜻하지 않습니다. 최대 5회, 인증 만료 안에서 같은 링크로 재시도합니다. 접수 확인·최종 실패·취소 시 발송 암호문을 지우고, 만료 자료는 주기적으로 정리합니다. 가입 전 요청 제한은 이메일당 시간당 5회, IP당 시간당 30회입니다. 카운터는 TTL이 있는 DB 테이블에 저장합니다. 사용자 입력 `X-Forwarded-For`는 신뢰하지 않습니다. 프록시 배포 시 신뢰할 수 있는 프록시에서만 클라이언트 IP를 복원해야 합니다.

## 실행 설정

Java 21, MySQL 8.4 기준입니다. IntelliJ 실행 설정 또는 OS 환경변수에 다음 값을 지정하고 `./gradlew bootRun`을 실행합니다. `.env` 파일은 자동으로 읽지 않습니다.

| 환경변수 | 의미 |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL JDBC URL·계정. URL에 UTC 연결 설정 권장 |
| `AUTH_ENCRYPTION_KEY` | 메일/멱등 응답 암호화용 32바이트 Base64 키 |
| `AUTH_HMAC_KEY` | 요청 지문·제한 카운터용 별도의 32바이트 Base64 키 |
| `AUTH_JWT_KEY` | JWT 서명용 별도의 32바이트 Base64 키 |
| `AUTH_ALLOWED_ORIGINS` | 허용 Origin을 쉼표로 구분. 기본 localhost:3000·localhost:8080 |
| `AUTH_EMAIL_CONFIRM_URL` | 프론트 인증 확인 페이지 URL, query·fragment 없이 설정 |
| `AUTH_SECURE_COOKIE` | 기본 true. 로컬 HTTP 테스트에서만 false 사용 가능 |
| `SMTP_HOST`, `SMTP_PORT` | SMTP 호스트·포트. 로컬 기본 localhost:1025 |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | SMTP 인증 정보 |
| `SMTP_AUTH`, `SMTP_STARTTLS` | SMTP 인증/TLS 활성화 여부, 기본 false |
| `MAIL_FROM` | 실제 발신 주소 |

키는 안전한 난수 생성기로 각각 생성하고 재시작 후에도 같은 값을 사용해야 대기 메일·멱등 응답을 복원할 수 있습니다. 테스트 키를 운영에 사용하지 않습니다. 키를 지정하지 않으면 서버가 시작되지 않습니다.

Flyway가 테이블을 생성합니다. 실제 약관 본문은 포함하지 않았으므로 승인된 SERVICE·PRIVACY 약관을 `term`, `term_version`에 별도 마이그레이션으로 등록해야 합니다. `PUBLISHED`, 시행 시각, required=true가 필요합니다. 약관이 준비되지 않으면 가입은 `503 TERMS_NOT_CONFIGURED`로 거부됩니다. 약관 발행 시에는 대상 `term` 행을 잠그고 신규 버전을 저장해야 가입의 최종 약관 검증과 직렬화됩니다.

회원가입에 필요한 업무별 contract/application/adapter 모듈을 분리했습니다. 외부 업무는 contract로 호출하며, DB 어댑터는 잠금·원자 갱신이 명시적인 JDBC를 사용합니다. 구현된 스키마는 `src/main/resources/db/migration`이 기준이며, ERD의 카카오·탈퇴 작업 테이블은 해당 기능 구현 시 별도 마이그레이션으로 추가합니다. `auth_guard`, `auth_rate_limit`은 경합·TTL 제한을 위한 기술 테이블이며 credit 지갑/가입 원장은 별도 업무 테이블입니다.

## 검증

- DB/Docker 없는 빠른 검증: `./gradlew :test --tests '*EmailSignupTests' --tests '*SwaggerUiTests' --tests '*NospoBackendApplicationTests' -PskipMysql`
- 실제 MySQL 검증: Docker 실행 후 `./gradlew :test --tests '*MySqlEmailSignupTests'`
- 전체 검증: `./gradlew test bootJar`

MySQL 테스트는 Docker가 없으면 건너뜁니다. 테스트 메일은 대체 발송기로 검증하며 실제 SMTP 수신함 도착을 검증하지 않습니다.
