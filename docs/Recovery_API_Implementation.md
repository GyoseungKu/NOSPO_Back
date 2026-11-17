# 30일 이내 계정 복구

기존 회원 ID를 그대로 활성화한다. 탈퇴 회차의 `recoverable_until`보다 현재 시각이 **작을 때만** 복구할 수 있다. 정확히 30일이 되는 시각부터 거부한다. 클라이언트가 회원 ID 또는 탈퇴 회차를 지정하지 않으며, 서버가 검증된 증명에서 두 값을 결정한다.

## API

| 요청 | 입력 | 응답 |
| --- | --- | --- |
| `POST /api/v1/auth/recovery/email-verifications` | `{ "email": "..." }` | 202, `verificationId`, `expiresAt`, `resendAvailableAt` |
| `POST /api/v1/auth/recovery/email-verifications/confirm` | `{ "token": "메일의 원본 토큰" }` | 200, `recoveryProof`, `expiresAt` |
| 기존 카카오 authorize → callback → exchange | 기존 state·브라우저 쿠키·exchangeCode 절차 | 탈퇴 계정이면 `result=RECOVERY_REQUIRED`, `recoveryProof`, `expiresAt`, `returnPath` |
| `POST /api/v1/auth/recovery/complete` | `{ "recoveryProof": "...", "newPassword": "...", "consents": [{ "termVersionId": "...", "agreed": true }] }` | 200, 기존 인증 응답 형식과 새 HttpOnly Refresh 쿠키 |

이메일 경로에서는 새 비밀번호가 필수이며 기존 비밀번호 검증 규칙(10~128자)을 사용한다. 카카오 경로에서는 `newPassword`를 생략하며 기존 로그인 수단을 복원한다. 이메일 복구를 선택한 카카오 전용 계정은 새 비밀번호와 함께 이메일 로그인도 사용할 수 있다. 이메일은 탈퇴 당시 검증된 계정 이메일에만 보낸다. 카카오는 이메일 일치가 아닌 기존 provider subject로 확인한다.

메일은 기존 암호화 outbox·발송 worker·SMTP 어댑터를 사용한다. 프런트엔드 확인 URL의 fragment는 `#purpose=ACCOUNT_RECOVERY&token=...`이다. 프런트엔드는 이를 복구 confirm API로 POST해야 한다. 토큰을 query string이나 로그에 넣지 않는다. 실제 SMTP 구축이나 인증 우회 경로는 추가하지 않았다.

존재하지 않거나 활성 상태이거나 기한이 지난 이메일에도 동일한 202 응답 구조를 반환하고 메일은 보내지 않는다. IP·이메일 요청 제한과 60초 재발송 제한을 적용한다. 재발송은 이전 이메일 challenge와 미사용 이메일 복구 증명을 무효화한다. 증명은 최대 10분 및 탈퇴 기한 중 더 이른 시점까지 유효하다.

## 원자성과 보존

- 회원 행 잠금으로 복구·탈퇴를 직렬화한다. 증명에는 회원 및 특정 withdrawal operation FK가 연결되고, 현재 회원 탈퇴 generation과 대조한다.
- 보관본 AES-GCM 검증, 증명 소비, 프로필·이미지·계정·로그인 수단·기존 약관 이력 복원, 최신 약관 동의 기록, 새 세션 발급을 한 트랜잭션에서 수행한다.
- 모든 최신 약관 항목과 필수 항목의 동의를 기존 Terms 계약으로 검증한다. 실패하면 증명 소비와 복원이 모두 롤백된다.
- 회원 생성 및 가입 보상 지급 코드를 호출하지 않는다. 기존 지갑·잔액·원장과 최초 가입 시각은 유지한다.
- 기존 세션·Refresh·재인증 증명은 복구하지 않는다. 성공 후 해당 회차의 보관본과 식별자 예약은 제거하며 operation은 `RESTORED`로 보존한다.
- 중복 요청과 다른 증명의 동시 요청은 하나만 성공한다. 성공 응답의 토큰을 재전달하는 멱등 재생은 지원하지 않는다. 응답 유실 시 정상 로그인한다.

## 오류와 제한

`ACCOUNT_NOT_RECOVERABLE`(409): 복구 완료, 현재 탈퇴 회차 불일치, 기한 도달 등. `INVALID_PROOF`(400): 만료·소비·무효화·목적 불일치 증명. 약관 오류는 기존 `TERMS_VERSION_CHANGED`·`REQUIRED_CONSENT_MISSING` 등을 사용한다.

탈퇴 후 다른 회원이 동일 닉네임을 사용한 경우 `RECOVERY_PROFILE_CONFLICT`(409)로 거부한다. 다른 회원을 수정하거나 기존 프로필을 임의로 변경하지 않으며, 증명과 보관본은 롤백으로 유지된다. 별도 닉네임 변경 복구 UX는 포함하지 않았다. 식별자 충돌도 `RECOVERY_IDENTITY_CONFLICT`로 거부한다.

기존 V1~V6와 원본 ERD는 수정하지 않고 V7에 challenge/proof의 탈퇴 회차 FK 및 인덱스만 추가한다. 30일 이후 개인정보 실제 파기 worker는 기존 단계와 마찬가지로 별도 작업이다. 복구 경로는 기한 이후 보관본을 사용하지 않는다.

## 검증

`./gradlew.bat test --console=plain`으로 H2와 MySQL Testcontainers 회귀 테스트를 실행한다. 테스트 프로필의 고정 fixture AUTH 키를 사용하므로 로컬 DB·운영 AUTH 키·실제 SMTP·실제 카카오 회원은 필요하지 않다. MySQL 테스트의 `disabledWithoutDocker=true` 때문에 Docker가 없는 실행에서는 skip 여부를 반드시 확인한다.

복구 전용 테스트는 `RecoveryTests`, `MySqlRecoveryTests`, V6→V7 보존 검증은 `MySqlRecoveryMigrationTests`에 있다. 기존 가입·카카오 로그인 테스트도 전체 실행에 포함된다.

2026-11-17 최종 단독 실행 결과: **974건 통과, 실패 0, 오류 0, 스킵 0**. 이 중 MySQL은 **480건**이다. `RecoveryTests`와 `MySqlRecoveryTests`는 각각 상속된 회귀 테스트 포함 96건, `MySqlRecoveryMigrationTests`는 1건 통과했다. 실행 시간은 14분 47초이며 HTML 보고서는 `build/reports/tests/test/index.html`에 있다. 종료 단계에 Hikari/MySQL 연결 경고가 출력됐으나 테스트 실패는 없었다.

초기 검증은 실행 중인 테스트와 JAR 재컴파일이 겹쳐 읽기/결과 파일 오류가 발생했으므로 결과에서 제외했다. 위 수치는 컴파일 병행 없이 최종 코드를 단독 실행한 XML 보고서의 집계다.

## 변경 파일 목록

| 영역 | 파일 |
| --- | --- |
| account-lifecycle/application | `RecoveryService.java`, `RecoveryStore.java` (신규) |
| account-lifecycle/adapter-persistence | `JdbcRecoveryStore.java` (신규) |
| account-lifecycle/adapter-web | `RecoveryController.java` (신규) |
| identity/contract | `RecoveryIdentity.java` (신규), `WithdrawalIdentity.java` |
| identity/application | `RecoveryIdentityService.java`, `RecoveryEvidenceStore.java` (신규), `KakaoOAuthService.java` |
| identity/adapter-persistence | `JdbcRecoveryEvidenceStore.java` (신규), `JdbcIdentityStore.java`, `JdbcWithdrawalIdentity.java` |
| identity/adapter-web | `RecoveryEmailController.java` (신규) |
| identity/adapter-integration | `SmtpEmailSender.java` |
| member | `WithdrawalMembers.java`, `JdbcWithdrawalMembers.java` |
| terms | `WithdrawalConsents.java`, `JdbcWithdrawalConsents.java` |
| support | `ArchivedRow.java` (신규) |
| 루트 설정 | `SecurityConfig.java` |
| 마이그레이션 | `V7__account_recovery.sql` (신규) |
| 테스트 | `RecoveryTests.java`, `MySqlRecoveryTests.java`, `MySqlRecoveryMigrationTests.java` (신규), `EmailSignupTests.java`, `WithdrawalTests.java`, `SmtpEmailSenderTests.java` |
| 문서 | `docs/Recovery_API_Implementation.md` (신규) |

테스트 초기화에서는 V7 FK를 준수하도록 `withdrawal_operation` 삭제 순서만 이동했다. 기존 탈퇴 카카오 테스트는 로그인 거부 대신 복구 증명 반환을 기대하도록 갱신했다.
