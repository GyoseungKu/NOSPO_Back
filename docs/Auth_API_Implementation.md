# 이메일 Auth API

카카오를 제외한 `/api/v1/auth`의 이메일 가입, 로그인, 토큰 재발급, 로그아웃, 비밀번호 재설정을 구현했습니다. `/api/v1/me` 정보 변경과 비밀번호 재인증은 [내 정보 변경 문서](Member_Profile_API_Implementation.md)에 설명합니다. 탈퇴는 별도 범위입니다.

| Method | 경로 | 응답 |
| --- | --- | --- |
| POST | /api/v1/auth/login | 200 AuthResult, Refresh 쿠키 |
| POST | /api/v1/auth/token/refresh | 200 accessToken/tokenType/expiresIn, Refresh 쿠키 회전 |
| POST | /api/v1/auth/logout | 204, Refresh 쿠키 삭제 |
| POST | /api/v1/auth/password-resets | 202 verificationId/expiresAt/resendAvailableAt |
| POST | /api/v1/auth/password-resets/verify | 200 resetProof/expiresAt |
| POST | /api/v1/auth/password-resets/complete | 204 |

로그인은 email/password, 재설정 요청은 email, 인증번호 검증은 verificationId/code, 재설정 완료는 resetProof/newPassword를 JSON으로 받습니다. 재발급은 body 없이 Refresh 쿠키를 사용합니다. 로그아웃은 유효한 Bearer Access 또는 Refresh 쿠키로 현재 세션을 식별합니다. 만료 Access를 Authorization에 보내면 인증 필터가 거부하므로 Refresh 방식에서는 해당 헤더를 생략합니다.

Access는 15분, 세션은 14일이며 Refresh 회전으로 세션 만료가 연장되지는 않습니다. 사용했던 Refresh 토큰 재사용 시 해당 세션을 폐기합니다. 동시 재발급 요청도 재사용으로 처리되므로 클라이언트는 재발급을 직렬화해야 합니다.

쿠키는 HttpOnly, SameSite=Lax, Path=/api/v1/auth이며 Secure는 설정값을 따릅니다. 브라우저 쿠키 요청에는 허용된 Origin이 필요합니다. 로컬 HTTP 개발에서만 Secure=false를 사용합니다.

로그인은 IP당 15분 30회, 이메일당 15분 10회로 제한합니다. 가입은 IP당 15분 20회입니다. 재설정 요청은 IP당 시간당 30회, 이메일당 시간당 5회, 재발송 간격 60초입니다. 재설정 코드는 6자리, 10분 만료, 최대 5회 검증입니다. 코드와 증명은 일회용이며 재발송은 이전 코드·증명을 무효화합니다. 존재하지 않는 이메일도 동일한 202 형식으로 응답하지만 메일은 보내지 않습니다. 메일은 기존 암호화 outbox 워커를 사용합니다.

비밀번호 재설정 완료는 모든 기존 세션을 폐기합니다. 가입 재시도에서 원래 Access가 만료된 경우 409 SIGNUP_ALREADY_COMPLETED_LOGIN_REQUIRED를 반환하므로 로그인으로 이어가야 합니다.

프록시 환경의 IP 제한은 실제 클라이언트 IP가 신뢰 가능한 프록시 설정을 통해 복원되어야 합니다.

## 카카오 Auth

구현 범위는 로그인 수단 조회, 카카오 OAuth 로그인, 신규 회원가입입니다. 기존 계정 연결·카카오 재인증·이메일 로그인 추가는 미구현입니다.

### 로그인 수단 조회

| Method | 경로 | 인증 | 응답 |
| --- | --- | --- | --- |
| GET | /api/v1/me/login-methods | Bearer Access | 200 loginMethods,canAddEmail,canLinkKakao |

`loginMethods`는 `password_credential`과 `social_identity`의 존재로 계산하며 순서는 EMAIL, KAKAO입니다. 가입·로그인·내 정보 응답도 같은 `LoginMethods` 계약을 사용합니다.

| loginMethods | canAddEmail | canLinkKakao |
| --- | --- | --- |
| EMAIL | false | true |
| KAKAO | true | false |
| EMAIL, KAKAO | false | false |

두 플래그는 계정 상태에 따른 추가 가능 여부이며 해당 추가 API의 구현 여부를 뜻하지 않습니다. 카카오 전용 회원의 비밀번호 재설정은 미가입 이메일과 같은 202 응답을 반환하고 메일은 보내지 않습니다. 재설정 완료 시에도 이메일·회원·자격 증명을 재검사하며 자격 증명 UPDATE가 1행이 아니면 실패합니다.

### 카카오 로그인

| Method | 경로 | 요청 | 응답 |
| --- | --- | --- | --- |
| GET | /api/v1/auth/kakao/authorize | returnPath? | 302 카카오 인증 화면, nospoKakaoFlow 쿠키 |
| GET | /api/v1/auth/kakao/callback | state 및 code 또는 error | 302 고정 프론트 콜백, exchangeCode 또는 오류 |
| POST | /api/v1/auth/kakao/exchange | exchangeCode | 200 교환 결과 |

시작 API는 브라우저 페이지 이동으로 호출합니다. `returnPath`는 `KAKAO_RETURN_PATHS`의 정확히 일치하는 상대경로만 허용하며 기본값은 목록의 첫 경로입니다. 서버는 설정된 redirect URI를 사용하고 사용자 지정 redirect_uri·목적·회원 ID는 받지 않습니다. returnPath는 교환 응답으로 반환합니다.

콜백 URL에는 일회용 exchangeCode 또는 아래 오류만 전달합니다. JWT·카카오 토큰·가입 증명은 포함하지 않습니다.

```text
https://web.example/auth/kakao/callback?exchangeCode=<일회용 코드>
https://web.example/auth/kakao/callback?error=KAKAO_AUTH_CANCELLED
https://web.example/auth/kakao/callback?error=KAKAO_AUTH_FAILED
```

프론트는 코드를 추출하고 `history.replaceState`로 URL에서 제거한 뒤 교환합니다. Bearer 헤더를 붙여 시작한 흐름은 같은 회원·세션의 Bearer 헤더로 교환해야 합니다. 일반 페이지 이동은 Bearer 헤더를 전달하지 않으므로 익명 흐름으로 시작합니다.

```http
POST /api/v1/auth/kakao/exchange
Origin: https://web.example
Content-Type: application/json

{"exchangeCode":"<43자 일회용 코드>"}
```

모든 JSON 응답은 기존 `data`/`meta` 형식을 사용합니다. 교환 결과는 다음 세 가지입니다.

| result | 조건 | 반환 내용 |
| --- | --- | --- |
| LOGIN_SUCCESS | 카카오 제공자 식별자가 기존 회원에 연결됨 | auth:AuthResult, Refresh 쿠키 |
| SIGNUP_REQUIRED | 연결되지 않은 신규 카카오 | signupProof,expiresAt,signupInfo |
| EXISTING_ACCOUNT | 연결되지 않은 카카오의 검증된 이메일이 기존 계정과 중복 | nextAction:LOGIN_AND_LINK |

```json
{"data":{"result":"LOGIN_SUCCESS","auth":{"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":900,"member":{"memberId":"<UUID>","loginMethods":["EMAIL","KAKAO"]}},"returnPath":"/"},"meta":{"requestId":"<UUID>"}}
```

member는 축약 예시이며 실제 AuthResult에는 닉네임·서비스 이메일·전화번호·프로필·생성 시각도 포함합니다. 제공자 식별자로 로그인하며 카카오 이메일로 서비스 이메일을 덮어쓰지 않습니다.

```json
{"data":{"result":"SIGNUP_REQUIRED","signupProof":"<일회용 가입 증명>","expiresAt":"<발급 후 15분>","signupInfo":{"email":"reader@example.com","emailVerified":true,"nicknameSuggestion":"reader","requiredFields":["nickname","phoneNumber","consents"],"expiresAt":"<발급 후 15분>"},"returnPath":"/"},"meta":{"requestId":"<UUID>"}}
```

카카오의 이메일 유효·검증 플래그가 모두 true이고 형식이 유효할 때만 이메일을 사용합니다. 그 외에는 email=null, emailVerified=false이며 requiredFields에 emailProof가 추가됩니다. SIGNUP_REQUIRED 교환만으로 회원·동의·보상·세션을 생성하지 않습니다.

```json
{"data":{"result":"EXISTING_ACCOUNT","nextAction":"LOGIN_AND_LINK","returnPath":"/"},"meta":{"requestId":"<UUID>"}}
```

이메일이 같다는 이유로 자동 연결·로그인·계정 병합을 하지 않습니다.

### 카카오 신규 회원가입

| Method | 경로 | 요청 | 응답 |
| --- | --- | --- | --- |
| POST | /api/v1/auth/signup/kakao/context | signupProof | 200 email,emailVerified,nicknameSuggestion,requiredFields,expiresAt |
| POST | /api/v1/auth/signup/kakao | signupProof,nickname,phoneNumber,emailProof?,consents; Idempotency-Key(UUID) 필수 | 201 AuthResult, Refresh 쿠키 |

두 API는 가입 전 공개 API입니다. 컨텍스트 조회는 증명을 소비하지 않으며 제공자 식별자·토큰을 반환하지 않습니다. OAuth 흐름·교환 코드가 만료되어도 가입 증명의 15분 유효기간 동안 복원·가입할 수 있습니다.

```http
POST /api/v1/auth/signup/kakao/context
Content-Type: application/json

{"signupProof":"<결과 교환으로 받은 가입 증명>"}
```

```json
{"data":{"email":"reader@example.com","emailVerified":true,"nicknameSuggestion":"reader","requiredFields":["nickname","phoneNumber","consents"],"expiresAt":"<가입 증명 만료 시각>"},"meta":{"requestId":"<UUID>"}}
```

검증된 사용 가능 이메일이 있으면 emailProof를 보내지 않습니다. 이메일이 없거나 미검증이면 `/api/v1/auth/signup/email-verifications` → `/confirm`에서 소유권을 검증하고 emailProof를 함께 제출합니다. 이 인증으로 EMAIL 로그인 수단을 생성하지 않습니다.

```http
POST /api/v1/auth/signup/kakao
Idempotency-Key: <UUID>
Content-Type: application/json

{
  "signupProof":"<카카오 가입 증명>",
  "nickname":"새독자",
  "phoneNumber":"010-1234-5678",
  "consents":[
    {"termVersionId":"<현행 약관 버전 UUID>","agreed":true}
  ]
}
```

consents는 축약 예시입니다. 실제로는 현행 가입 약관 목록의 각 버전과 선택 여부를 제출합니다. nickname은 기존 2~20자 정규화·대소문자 무시 중복 검사를, phoneNumber는 E.164 정규화를 사용합니다. 필수 약관 누락·거부 시 가입할 수 없습니다.

```json
{"data":{"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":900,"member":{"memberId":"<UUID>","nickname":"새독자","email":"reader@example.com","phoneNumber":"+821012345678","phoneVerified":false,"profileImageUrl":null,"loginMethods":["KAKAO"],"createdAt":"<가입 시각>"}},"meta":{"requestId":"<UUID>"}}
```

최초 5토큰을 지급하고 기존 JWT·세션·Refresh 쿠키로 자동 로그인합니다. password_credential은 생성하지 않습니다.

### 오류 코드와 요청 제한

| 조건 | 응답 |
| --- | --- |
| 사용자 인증 취소 | 프론트 콜백 error=KAKAO_AUTH_CANCELLED |
| OAuth 검증·카카오 통신·사용자 식별 실패 | 프론트 콜백 error=KAKAO_AUTH_FAILED |
| Idempotency-Key 누락·형식 오류 | 400 INVALID_REQUEST |
| 교환 코드·가입 입력 형식 오류, 필요한 emailProof 누락·불필요한 emailProof 제출 | 422 VALIDATION_ERROR |
| 알 수 없거나 다른 목적의 증명·교환 코드 | 400 INVALID_PROOF |
| 만료·무효화된 가입/이메일 증명, 만료된 교환 코드 | 410 PROOF_EXPIRED |
| 이미 소비한 증명·교환 코드, 다른 키로 재가입 시도 | 409 PROOF_ALREADY_USED |
| 제공자 식별자 중복 | 409 SOCIAL_ACCOUNT_ALREADY_LINKED |
| 이메일·닉네임 중복 | 409 EMAIL_ALREADY_EXISTS / NICKNAME_ALREADY_EXISTS |
| 필수 약관 누락·거부 | 422 REQUIRED_CONSENT_MISSING |
| 현행이 아닌 약관 버전 | 409 TERMS_VERSION_CHANGED |
| 필수 약관 미설정 | 503 TERMS_NOT_CONFIGURED |
| 같은 멱등 키에 다른 요청 내용 | 409 IDEMPOTENCY_CONFLICT |
| 최초 가입 Access 만료 후 성공 재시도 | 409 SIGNUP_ALREADY_COMPLETED_LOGIN_REQUIRED |
| 허용되지 않은 Origin | 403 |
| 카카오 키 미설정 | 시작 API 503 KAKAO_NOT_CONFIGURED, 이메일 Auth는 계속 동작 |

정리 작업이 이미 삭제한 증명·교환 코드는 INVALID_PROOF입니다.

| 제한 대상 | 15분당 허용 횟수 |
| --- | --- |
| 카카오 로그인 시작 IP | 30회 |
| 결과 교환 IP | 60회 |
| 가입 IP — 이메일 가입과 공유 | 20회 |
| 가입 증명별 가입 시도 | 10회 |
| 가입 컨텍스트 조회 IP | 60회 |

### 증명·쿠키·저장 정책

| 자료 | 수명·처리 |
| --- | --- |
| state·흐름 쿠키 | 256비트 난수, 해시 저장, 흐름 5분 |
| 교환 코드 | 256비트 난수, 해시 저장, 콜백 성공 후 60초·일회용 |
| 카카오 가입 증명 | auth_proof의 KAKAO_SIGNUP 목적, 15분·일회용 |
| 가입 성공 멱등 결과 | 증명+HTTP method+path+key 범위, 24시간 암호화 보관 |

- `nospoKakaoFlow`는 HttpOnly·SameSite=Lax·Path=/api/v1/auth/kakao/callback이며 Secure 설정을 따릅니다. 새 흐름을 시작하면 이전 쿠키가 교체되고 콜백에서 삭제됩니다.
- state·쿠키·설정된 콜백 URL을 검증하고 중복 파라미터·임의 리다이렉트를 거부합니다. DB 잠금으로 콜백을 소비·커밋한 뒤 카카오 HTTP를 호출합니다. 외부 오류 시 새 로그인 흐름부터 시작합니다.
- 결과 교환의 소비와 가입 증명 또는 세션 생성은 한 트랜잭션입니다. 교환 성공 시 결과 암호문을 삭제합니다.
- 가입은 회원·계정·카카오 로그인 수단·동의·5토큰·증명 소비·세션·멱등 결과·사전 자료 귀속/정리를 한 READ_COMMITTED 트랜잭션으로 확정합니다. 마지막 저장까지 어느 단계든 실패하면 모두 롤백합니다.
- 성공 멱등 결과를 증명 검사보다 먼저 조회합니다. 동일 요청은 최초 결과를 재사용하고 새 보상·세션을 만들지 않습니다. 최초 Access의 15분이 지나면 다시 카카오 로그인해야 합니다.
- 같은 카카오 식별자는 DB guard와 유일 제약으로, 이메일은 기존 이메일 가입과 공통 guard·최종 유일성 검사로 직렬화합니다. 별도 emailProof가 필요하면 두 증명을 함께 검증·소비하며 실패 시 둘 다 유지합니다.
- 가입 정보 JSON은 AES-GCM으로 암호화합니다. 유효한 가입 증명이 참조하는 컨텍스트·부모는 OAuth 만료와 무관하게 보존하고 정리는 자식부터 수행합니다. 가입 성공 시 사용한 카카오 증명을 회원에 귀속하고 컨텍스트·교환·흐름을 삭제합니다. 이메일 인증·발송 자료도 회원에 귀속합니다.
- 카카오 Access/Refresh는 메모리에서만 사용합니다. 고정 HTTPS 호스트, 연결·읽기 타임아웃 각 5초, 응답 64 KiB 제한, HTTP 리다이렉트 금지를 적용합니다. 제공자 응답·예외 원문을 노출하지 않고 민감 DTO의 toString을 마스킹하며 HTTP·MVC 상세 로깅을 비활성화합니다.
- `KakaoOAuthCleanup`은 기본 1분마다 만료 자료를 정리합니다. `KAKAO_CLEANUP_ENABLED=false`로 중지할 수 있습니다.
- 카카오 로그인 성공과 가입 완료의 Refresh 쿠키는 기존 정책인 HttpOnly·SameSite=Lax·Path=/api/v1/auth와 Secure 설정을 사용합니다.

### 환경변수와 운영 설정

| 환경변수 | 값 |
| --- | --- |
| KAKAO_CLIENT_ID | 카카오 REST API 키 |
| KAKAO_CLIENT_SECRET | 해당 키의 Client Secret, 서버 비밀 저장소에서 주입 |
| KAKAO_REDIRECT_URI | https://api.example/api/v1/auth/kakao/callback |
| KAKAO_FRONTEND_CALLBACK | https://web.example/auth/kakao/callback |
| KAKAO_RETURN_PATHS | /,/shelf 등 허용 상대경로 |
| KAKAO_CLEANUP_ENABLED | 만료 정리 실행 여부, 기본 true |
| AUTH_ALLOWED_ORIGINS | https://web.example 등 기존 CORS 허용 Origin |
| AUTH_SECURE_COOKIE | 운영 true |

카카오 개발자 콘솔에서 로그인을 활성화하고 REST API 키의 Redirect URI에 KAKAO_REDIRECT_URI를 정확히 등록합니다. 같은 키의 Client Secret을 활성화·주입하고 필요한 이메일·닉네임 동의 항목을 설정합니다. 이메일 미제공도 처리합니다. 프론트 콜백과 카카오 Redirect URI는 별개입니다. 참고: [카카오 설정하기](https://developers.kakao.com/docs/ko/kakaologin/prerequisite), [REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api).

로컬 HTTP는 다음 값을 함께 설정합니다. HTTP는 localhost·127.0.0.1·IPv6 loopback에서만 허용하며, 운영 URI는 쿼리·fragment·userinfo가 없는 정규화된 HTTPS 주소여야 합니다.

```text
AUTH_SECURE_COOKIE=false
KAKAO_REDIRECT_URI=http://localhost:8080/api/v1/auth/kakao/callback
KAKAO_FRONTEND_CALLBACK=http://localhost:3000/auth/kakao/callback
```

프록시 뒤에서도 서버가 보는 scheme/host/port가 등록 URI와 같아야 합니다. 신뢰하는 프록시만 Forwarded 헤더를 재작성하고 Spring 전달 헤더 처리를 설정합니다. 프록시·APM은 콜백 쿼리, Authorization·Cookie, 요청·응답 본문을 기록하지 않아야 합니다. Nginx는 `$request` 대신 `$request_method $uri $status`처럼 쿼리를 제외하며 운영 비밀값으로 curl -v·HTTP wire logging을 사용하지 않습니다.

### 구현 위치와 테스트

| 역할 | 구현 |
| --- | --- |
| 로그인 수단 조회·내부 연결 저장 | LoginMethods, LoginMethodService, LoginMethodStore 및 JDBC 어댑터 |
| OAuth 조합·외부 통신·SQL | KakaoOAuthService, KakaoClient/KakaoHttpClient, JdbcKakaoOAuthStore |
| 가입 조합·identity 계약·SQL | account-lifecycle의 KakaoSignupService, identity의 KakaoSignupIdentity, JdbcKakaoSignupStore |
| 저장 구조 | V3 social_identity; V4 oauth_flow,oauth_exchange,social_signup_context |

다른 업무 모듈은 contract로 호출하며 SQL은 adapter-persistence, 카카오 HTTP는 adapter-integration에 둡니다. `LoginMethodService.recordVerifiedKakao`는 활성 회원 잠금과 저장을 한 트랜잭션으로 처리하는 내부 기능으로, 향후 연결 증명 검증·소비와 함께 호출해야 하며 HTTP로 노출하지 않습니다.

`EmailSignupTests`는 로그인 수단과 이메일 Auth 회귀를, `KakaoOAuthTests`는 로그인·가입·증명·멱등·동시성·전체 롤백·보안을 검증합니다. `KakaoHttpClientTests`는 로컬 모의 서버로 HTTP 계약·예외·로그 비노출을 검증합니다. Docker가 있으면 `MySqlEmailSignupTests`·`MySqlKakaoOAuthTests`도 실행합니다. 실제 카카오 서버 연동은 별도 확인이 필요합니다.

### 미구현 기능

- 기존 회원의 카카오 재인증과 카카오 계정 연결 API
- 카카오 전용 회원의 이메일 로그인 추가 API
- 회원 탈퇴 API

계정 연결 해제와 기존 회원 데이터 병합은 현재 기능 범위에 포함하지 않습니다.
