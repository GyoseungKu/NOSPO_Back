# 30일 경과 계정 파기 워커

## 적용 정책과 범위

사용자가 확정한 최종 삭제 범위는 기존 회원·개인정보·인증/복구 자료·지갑·잔액·가입보상 원장이다. 신규 가입은 새 회원 ID·새 지갑으로 기존 가입 정책의 5토큰을 1회 지급한다. 기존 가입·복구 API 계약, 보안 검증, 지갑/보상 지급 코드는 변경하지 않는다.

V1~V7 및 원본 ERD 변경은 없다. V7은 기존 복구 기능의 마이그레이션으로 보존했다. 신규 DB 변경도 없다. FK 그래프 검토 근거는 [사전 검토](Withdrawal_Purge_Design_Review.md)에 있다.

이 작업에서 개발 DB·실제 카카오 계정·Docker/MySQL 서버 설정·백업·binlog·AUTH 키는 변경하지 않는다. 파기 테스트는 Docker 사용이 필수인 격리 MySQL Testcontainers에만 추가했다.

## 실행 및 비활성 기본값

- 신규 `account-lifecycle:adapter-worker`가 기존 ConditionalOnProperty/Scheduled 컨벤션을 따른다.
- **기본 비활성**이다. 운영자가 명시적으로 `nospo.withdrawal.purge-enabled=true`를 설정해야 동작한다. 이 작업에서는 개발/운영 워커를 활성화하지 않았다.
- `nospo.withdrawal.purge-poll-ms` 기본 60000ms. 첫 실행도 동일 시간만큼 지연하며, 이전 실행이 끝난 후 fixed delay로 재실행한다.
- 한 poll에 최대 50개 계정을 처리한다. 여러 애플리케이션 인스턴스가 동시에 실행해도 DB 회원 잠금과 영속 임대로 직렬화한다.
- 테스트 프로필은 purge 스케줄을 명시적으로 비활성화한다. 테스트에서 서비스 호출로만 격리 DB를 파기한다.

## 기한·복구·회차 보호

대상 조회는 후보 수집일 뿐 삭제 권한이 아니다. 처리마다 member → withdrawal_operation → withdrawal_target 순서로 잠근다. 회원 잠금을 얻은 **후** 현재 시각을 다시 읽고 다음을 모두 검증한다.

1. 회원이 WITHDRAWN이고 작업이 RETAINED 또는 PURGING이다.
2. 후보 member/operation/generation이 현재 회원과 작업에 모두 일치한다.
3. 회원과 작업의 recoverable_until이 모두 현재 시각 이하이다.

기존 복구는 RETAINED와 기한 `> now`만 허용한다. 따라서 정확히 30일 도달 시 복구는 거부되고 파기 대상으로 인정된다. 워커가 지연돼도 기한 이후 복구할 수 없다.

임대 획득과 RETAINED → PURGING 전환은 하나의 짧은 트랜잭션으로 커밋한다. PURGING에서 RETAINED로 되돌리지 않는다. 복구가 먼저 완료된 ACTIVE/RESTORED 회원, 이전 탈퇴 회차, 이미 삭제된 회원은 수정하지 않는다.

## 삭제와 장애 재시도

V6 withdrawal_target에 resource_type=`DATABASE`인 DB 파기 작업을 영속 저장한다. 별도 외부 위치가 필요 없으므로 locator_ciphertext는 빈 바이트이고 operation FK가 대상이다. 외부 locator나 개인 데이터 키를 복호화하지 않고 삭제한다.

임대 기간은 120초이며 각 획득에 새 UUID를 사용한다. 삭제 트랜잭션은 회원·회차·기한·PURGING·target·lease_token·유효 임대를 재확인한다. 만료/교체된 임대의 이전 프로세스는 삭제할 수 없다. 삭제 중에는 회원 잠금이 유지되므로 다른 worker가 해당 계정을 동시에 재선점할 수 없다.

다음 삭제를 하나의 트랜잭션으로 수행한다.

| 순서 | 범위 및 이유 |
| --- | --- |
| 1 | 회원/세션/증명/가입 컨텍스트 소유 OAuth context → exchange → flow. reauth_proof FK를 증명 삭제 전에 해소 |
| 2 | 회원/세션/탈퇴 회차/증명 소유 email_delivery → auth_proof → auth_challenge. V7의 작업 FK도 해소 |
| 3 | Refresh → 세션 → 카카오 연결·비밀번호 인증정보 → account |
| 4 | 약관 동의 및 멱등 응답, 모든 과거/현재 withdrawal_operation. archive·건별 래핑키·HMAC 예약·target·locator·임대도 제거 |
| 5 | 같은 WITHDRAWN 회원과 generation 조건으로 최종 member DELETE. 프로필 이미지·지갑·잔액·가입보상 원장은 기존 CASCADE로 제거 |

어느 단계든 실패하면 계정·인증 자료·보관본·지갑·원장 삭제가 전부 롤백된다. 별도 트랜잭션에서 **동일 임대 소유자만** PENDING 및 next_attempt_at을 갱신한다. 60초부터 지수 backoff, 최대 3600초로 제한하며 횟수 상한으로 영구 포기하지 않는다. 프로세스가 재시도 기록 전에 종료돼도 PROCESSING 임대가 만료되면 다른 인스턴스가 재개한다.

성공 시 작업 자체까지 삭제하므로 식별 가능한 완료 이력을 새로 영구 보존하지 않는다. 이미 삭제된 대상의 중복 실행은 변경 없이 종료한다. 예상하지 못한 외부 resource_type이나 다른 회원을 가리키는 논리적으로 불일치한 소유 참조는 성공으로 처리하지 않고 롤백·재시도한다. 이 경우 운영자가 원인을 조사해야 하며 임의 삭제하지 않는다.

로그에는 식별자·토큰·키·DB 오류 원문 대신 고정 코드 `WITHDRAWAL_PURGE_RETRY_REQUIRED`만 기록한다. attempt_count·next_attempt_at·status·임대는 DB 작업행에서 확인할 수 있다. 별도의 모니터링/경보 연동은 운영 미완료 항목이다.

## 신규 가입과의 경합

삭제는 이메일/카카오 문자열 검색이 아니라 **이전 회원의 소유 FK/ID**만 사용한다. 식별자 예약 해제는 전체 삭제 성공 커밋과 함께 반영된다. 이전 worker가 지연 완료돼도 새 회원으로 대상을 다시 지정하지 않는다.

해제 직전의 가입 인증 요청은 기존 `EMAIL_ALREADY_EXISTS`가 될 수 있으며, 파기 커밋 후 정상 인증을 다시 진행할 수 있다. 신규 가입 및 멱등 재요청의 지갑/5토큰 1회 지급은 기존 구현과 유일 제약을 그대로 사용한다. 영구 재가입 차단용 이메일·카카오 해시를 남기지 않는다.

## 개인정보 잔존 검사 범위와 한계

현재 구현된 계정 소유 테이블의 평문·암호문·키 래핑 사본·HMAC 식별 인덱스·비밀번호 해시·세션/Refresh/증명 자료를 삭제한다. 현재 프로필은 DB BLOB이고 외부 객체 저장소 어댑터는 없다. 알 수 없는 외부 target을 무시하고 완료하지 않는다.

회원에 귀속되지 않은 다른 사람의 가입/OAuth 흐름은 이메일 일치만으로 삭제하지 않는다. 익명 인증 흐름의 만료 정리는 기존 정리 worker가 담당한다. auth_guard/auth_rate_limit는 회원 FK가 없는 TTL HMAC 인프라 자료이며 기존 만료 정리 대상이다. 다른 회원의 잠금을 훼손하는 추정 삭제를 하지 않는다. 이미 SMTP 제공자에게 보낸 메일이나 외부 제공자 사본 회수는 보장하지 않는다.

## 백업·binlog·키 관리: 별도 운영 미완료

**아래는 초기 검토안 및 운영 과제이며 이번에 적용한 설정이 아니다.**

- 전체 백업: 하루 1회, 7일 보존을 초기 운영 검토안으로 기록한다.
- binlog: 7일을 초기 검토안으로 기록하되, 복제 지연·장애 복구·시점 복구(PITR) 요구사항을 확인하기 전에는 적용하지 않는다.
- 백업 암호화, 최소 권한/접근 통제 및 접근 감사, 보존기간 자동 만료, 복원 검증 절차와 책임자를 정해야 한다.
- MySQL 전체 백업·binlog·스냅샷에는 삭제 전 개인정보와 복구 자료가 남을 수 있다. 현재 DB DELETE는 해당 매체의 물리적 삭제를 보장하지 않는다.
- 보장 범위는 성공 커밋 후 애플리케이션이 조회하는 논리 행의 삭제다. InnoDB undo/free page, 복제본, 스토리지 사본, JVM의 모든 메모리 사본에 대한 포렌식 수준의 즉시 소거를 보장하지 않는다.
- 건별 AES 데이터키는 공용 AUTH 키로 래핑되어 DB에 저장된다. 백업에 암호문과 wrapped_data_key가 있고 AUTH 키에 접근 가능하면 복호화할 수 있다. **현재 구조로 백업 사본의 암호학적 파기를 보장할 수 없다.** AUTH 키는 삭제·재생성·교체하지 않는다.
- 향후 별도 키 관리 체계 및 탈퇴 건별 독립적인 키 폐기 가능성을 검토한다. 백업 매체와 키 사본까지 포함한 보장이 확인되기 전에는 완전 파기로 표현하지 않는다.

### 복원 후 재활성화 방지 절차 제안

1. 백업/PITR 복원은 격리 환경에서 수행하고 인증 API·메일·일반 background 쓰기·외부 트래픽을 막는다. 복원 직후 서비스를 열지 않는다.
2. 복원 시각 이후에 탈퇴/복구/파기된 계정은 과거 백업에서 ACTIVE로 되돌아올 수 있다. 최신 신뢰 가능한 삭제/탈퇴 상태와 대조해야 한다. 현재 영구 식별자 삭제 이력을 보존하지 않는 구조만으로 이 대조 자료를 제공할 수 없으므로 **별도 보안 통제된 복원용 삭제 재적용 체계의 보존범위·기간·접근권한 정책**을 먼저 결정해야 한다. 이 워커에 영구 tombstone을 임의 추가하지 않았다.
3. 백업에 WITHDRAWN/PURGING으로 남은 기한 만료 계정은 현재 실제 시각으로 worker를 재실행하고, FK·인증 차단·개인정보 잔존 검사를 반복한다. 과거 ACTIVE 상태로 복원된 계정은 worker가 자동 추정해서 삭제할 수 없으므로 2번의 대조가 필수다.
4. 복원본의 기존 세션/토큰 재사용 차단 및 중간 탈퇴 계정의 접근 차단을 확인한다. 오래된 인증 상태를 외부에 열지 않는 별도 복구 runbook이 필요하다. AUTH 키 교체를 대안으로 사용하지 않는다.
5. 삭제 재적용·인증 차단·FK 무결성·백업 범위 대조 결과를 승인한 뒤에만 서비스 트래픽을 재개한다. 이 절차는 아직 실환경에서 실행/검증하지 않았다.

## 변경 파일과 이유

| 파일/영역 | 이유 |
| --- | --- |
| account-lifecycle/application/PurgeStore.java, PurgeService.java | 후보·회차·기한·임대 및 트랜잭션 오케스트레이션 |
| account-lifecycle/adapter-persistence/JdbcPurgeStore.java | V6 작업/임대 재사용, 잠금 및 원자적 상태 전환·재시도 |
| account-lifecycle/adapter-worker/build.gradle, PurgeWorker.java | 기존 멀티모듈 구조의 선택적 주기 실행 adapter |
| identity/contract/PurgeIdentity.java, adapter-persistence/JdbcPurgeIdentity.java | identity 소유 FK 그래프의 자식 우선 삭제와 교차 회원 보호 |
| member/contract/PurgeMembers.java, adapter-persistence/JdbcPurgeMembers.java | 최종 조건부 회원 삭제와 기존 지갑/원장 CASCADE |
| build.gradle, settings.gradle | 새 worker adapter만 등록 |
| src/test/resources/application-test.properties | 테스트 중 예약 파기 실행 방지 |
| MySqlPurgeTests.java | 필수 격리 MySQL 파기·경합·재시도·FK 테스트 및 상속된 인증/복구 회귀 |
| Withdrawal_Purge_Design_Review.md, 본 문서 | 확정 정책·설계·운영 한계·실제 검증 결과 기록 |

기존 복구 코드와 미커밋 변경은 보존했다. V1~V7·ERD·인증 API/보안 설정·가입보상 로직·DB/백업 인프라 설정 변경은 없다.

## 실제 검증 결과

2026-11-17 최종 실행: `./gradlew.bat test --console=plain`, **BUILD SUCCESSFUL, 17분 8초**.

| 범위 | 실행/통과 | 실패 | 오류 | 스킵 |
| --- | ---: | ---: | ---: | ---: |
| 전체 회귀 | 1086 | 0 | 0 | 0 |
| MySQL Testcontainers | 592 | 0 | 0 | 0 |
| MySqlPurgeTests (상속 회귀 포함) | 112 | 0 | 0 | 0 |

MySqlPurgeTests는 신규 파기 시나리오 16건과 기존 인증/복구 회귀 96건이다. 30일 직전 1µs/정각, 지연된 PURGING에서 복구 거부, EMAIL/KAKAO 재가입의 새 회원 ID와 보상 1회, 복구 완료 ACTIVE 및 이전 회차 보호, 중복 worker/중복 완료, 만료 임대 및 새 서비스 인스턴스 재시작 모사, 최종 회원 삭제 직후 예외의 전체 롤백/재시도, 복구-파기 및 가입-지연 파기 경합, 가입 인증-예약 해제 경합, 타 회원/공용 약관 보호, information_schema 전체 FK의 고아 행 검사, 잔존 OAuth/메일/프로필 자료의 삭제, 미지원 외부 target과 교차 회원 참조의 안전한 실패를 검증했다. 기존 Access/Refresh 및 복구 증명 재사용 거부도 확인했다.

HTML 결과: `build/reports/tests/test/index.html`. XML 집계: `build/test-results/test/TEST-*.xml`. 파괴적 시나리오는 모두 필수 MySQL Testcontainers에서 실행했으며, 기존 테스트의 삭제·스킵·보안 검증 완화는 없다.

실제 OS 프로세스 강제 종료/재부팅은 실행하지 않았다. 재시작 검증은 영속 DB 작업행을 남긴 채 서비스 인스턴스를 새로 생성하고 임대를 회수하는 모사다. 실제 운영 스케줄 활성화, 외부 SMTP/카카오 실연동, 외부 객체 삭제, 백업/binlog 설정 적용·복원/PITR·물리 소거·독립 키 폐기는 미실행/미검증이다. 과거 복구 작업의 974건/MySQL 480건을 이번 워커의 결과로 재사용하지 않았다.
