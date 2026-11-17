# 30일 경과 개인정보 파기: 구현 전 검토

검토일: 2026-11-17. 최초 검토 후 사용자가 **기존 회원·지갑·잔액·가입보상 원장까지 최종 삭제**하는 정책을 확정했다. 아래 선택지 비교는 결정 근거를 보존한 것이며, 구현은 1번 정책을 따른다. 개발 DB 및 실제 카카오 계정에는 접근하거나 변경하지 않았다. 구현·운영 상태와 검증 결과는 `Withdrawal_Purge_Implementation.md`를 참고한다.

## 확정 정책과 최초 검토 근거

이번 요청의 30일 유예·복구 정책은 과거 명세의 즉시 삭제·복구 불가 정책보다 우선한다. 이 부분은 재확인할 필요가 없다.

기존 `NOSPO_Function_API_v2.1.md` 5장과 `NOSPO_Development_Conventions.md` 3.6은 최종 회원 행·개인 원장·식별 가능한 작업 이력 삭제를 규정한다. 현재 탈퇴·복구의 보존은 30일 유예기간에 적용하고, 기한 이후에는 회원 DELETE의 CASCADE로 지갑과 원장까지 삭제하는 것으로 사용자가 확정했다.

| 선택 | 최종 결과 | 영향 |
| --- | --- | --- |
| 기존 명세의 최종 삭제 적용 | 인증·보관 자료에 더해 기존 회원·지갑·가입보상 원장 삭제 | 이전 잔액과 원장도 소실된다. 재가입은 독립된 새 회원 및 5토큰 |
| 최소 회원·지갑·원장 보존 | 직접 식별자·인증·복구 자료 삭제, 기존 ID와 지갑·원장은 유지 | 잔액·원장 정합성은 유지하지만 회원 ID로 연결되는 기록이 남는다. 완전 익명화나 영구 보존의 법적 근거를 주장할 수 없다. 보존 목적·기한 정책이 별도로 필요 |

V1의 원장은 `CHECK(kind='SIGNUP' AND amount=5)`로 가입보상만 허용한다. 현재 구현에서 결제·청구·환불 테이블이나 유료 원장의 법정 보존 의무를 확인하지 못했다. 이를 근거로 법적 보존 의무가 없다고 단정하거나 임의 보존기간을 정하지 않는다. 향후 결제 데이터가 추가되면 이 검토 범위를 다시 확장해야 한다.

## 실제 소스 DDL의 FK 관계

현재 저장소 V1~V7의 CREATE/ALTER TABLE을 기준으로 확인했다. 개발 DB의 information_schema는 조회하지 않았다. 구현 후 격리 MySQL Testcontainers에서 실제 제약 및 고아 레코드를 추가 검증해야 한다.

| 자식 | 부모 | 삭제 동작 |
| --- | --- | --- |
| account | member | CASCADE |
| password_credential, auth_session | account | CASCADE |
| refresh_token | auth_session | CASCADE |
| refresh_token.replaced_by_token_id | refresh_token | SET NULL |
| member_profile_image, member_consent, idempotency_record | member | CASCADE |
| credit_wallet, credit_transaction | member | CASCADE |
| auth_challenge | account, auth_session | RESTRICT |
| auth_proof | auth_challenge, account, auth_session | RESTRICT |
| email_delivery | auth_challenge, member | RESTRICT |
| social_identity | account | RESTRICT |
| oauth_flow | account, auth_session, auth_proof(reauth_proof_id) | RESTRICT |
| oauth_exchange | oauth_flow | RESTRICT |
| social_signup_context | auth_proof, oauth_flow | RESTRICT |
| withdrawal_operation | member | RESTRICT |
| withdrawal_archive, withdrawal_identity, withdrawal_target | withdrawal_operation | CASCADE |
| auth_challenge.withdrawal_operation_id, auth_proof.withdrawal_operation_id (V7) | withdrawal_operation | RESTRICT |

member_consent는 term_version도 RESTRICT로 참조하지만, 공용 term/term_version은 파기 대상이 아니다.

`withdrawal_operation.session_id`는 auth_session FK가 아니다. 이 필드만 남겨도 이전 세션 식별자가 남으므로 최종 작업 기록 정리 범위에 포함해야 한다. operation의 request_fingerprint·idempotency_key도 무기한 보관하지 않는다.

소유 관계는 member_id뿐 아니라 해당 회원의 session_id, challenge_id, proof_id, withdrawal_operation_id와 연결된 OAuth flow/context까지 따라가야 한다. 최종 삭제에서는 현재 회차뿐 아니라 이전 RESTORED 회차의 복구 증명과 작업 기록에 대한 FK도 해소해야 한다. 같은 이메일/카카오를 사용하는 **새 회원의** 자료는 삭제하지 않는다. 식별자 문자열을 조건으로 과거·현재 데이터를 일괄 삭제하는 방식은 사용하지 않는다.

## 잠금·기한·상태 전환 제안

1. 대상 조회는 비잠금 조회로 만료 작업의 operation/member/generation만 가져온다. 대상 조회 자체는 삭제 권한이 아니다.
2. 각 처리 트랜잭션은 **member → withdrawal_operation → withdrawal_target** 순서로 잠근다. 기존 RecoveryService가 member를 먼저 잠그는 순서와 맞춘다.
3. 잠금을 얻은 뒤 새 현재 시각으로 `member.status='WITHDRAWN'`, 회원/작업 generation 일치, 양쪽 recoverable_until <= now, 작업 상태 RETAINED 또는 동일 작업의 PURGING을 재검증한다. ACTIVE·RESTORED·회차 불일치·기한 전은 수정하지 않는다.
4. RETAINED → PURGING 및 DB 정리 target 생성/임대 획득을 동일 트랜잭션으로 커밋한다. 기존 복구 코드는 RETAINED만 허용하므로 이후 복구를 허용하지 않는다. 정확히 기한에 도달하면 복구 조건 `> now`는 거짓이고 파기 조건 `<= now`는 참이다.
5. 실제 파기는 별도 트랜잭션에서 같은 순서로 재잠금하고 상태·회차·기한·lease_token·lease_until을 다시 검증한다. 이전 실행자가 보유한 임대가 만료되거나 교체되면 아무것도 삭제하지 않는다.
6. 성공 시 작업/대상 식별 자료도 최종 정리한다. 대상 작업이 이미 없으면 성공한 중복 처리로 간주하며 새 회원이나 다른 회차로 재대상을 지정하지 않는다.

복구가 먼저 완료되면 ACTIVE/RESTORED 재검증에서 파기가 중단된다. 파기 전환이 먼저 완료되면 복구의 RETAINED 조건이 실패한다. 기한 이후에는 어느 쪽이 잠금을 먼저 얻더라도 복구할 수 없다.

## FK를 만족하는 삭제 순서 제안

모든 순서는 동일 회원·세션·회차 소유 관계의 확정된 ID 집합에만 적용한다. 존재하지 않는 행 삭제는 성공으로 취급한다.

1. social_signup_context → oauth_exchange → oauth_flow. flow의 reauth_proof 참조를 auth_proof 삭제보다 먼저 해소한다.
2. email_delivery → auth_proof → auth_challenge. challenge/proof의 V7 operation 참조도 이 단계에서 해소한다.
3. refresh_token → auth_session → social_identity/password_credential → account.
4. member_profile_image, member_consent, idempotency_record 및 잔존 활성 개인정보.
5. withdrawal_archive(암호문+wrapped_data_key), withdrawal_identity(HMAC 예약), 처리 완료 withdrawal_target 및 withdrawal_operation. 외부 리소스 target이 존재한다면 실제 삭제 확인 전에 작업을 CASCADE 삭제하지 않는다.
6. 확정된 1번 정책에 따라 member와 연관 지갑·원장까지 삭제한다.

식별자 예약 해제는 해당 회원의 과거 인증/복구 자료 정리와 정합성을 갖는 커밋 경계에 둔다. 신규 가입 로직이나 보상 지급 로직은 변경하지 않는다. 재가입은 새 검증·새 ID이며 `(member_id,kind)` UNIQUE가 새 회원에 5토큰 1회 지급을 보장한다. 동일 요청 중복·동시 가입은 기존 가입 멱등성/식별자 잠금으로 검증한다. 이전 회원과 신규 회원 사이에 영구 식별자 차단 표식을 추가하지 않는다.

## 워커와 재시도 제안

기존 worker는 ConditionalOnProperty와 Scheduled(fixedDelay/fixedDelayString)를 사용한다. 신규 전용 account-lifecycle worker adapter를 두고 기본 60초 주기, 제한된 batch로 처리하는 구성이 기존 구조에 맞는다. 테스트에서는 새 파괴적 스케줄을 명시적으로 비활성화하고 격리 컨테이너에서 서비스 직접 호출로 검증해야 한다.

V6 withdrawal_target의 status·attempt_count·next_attempt_at·lease_token·lease_until은 영속 재시도에 재사용 가능하다. target 생성은 operation 잠금 아래 중복 검사하고 임대는 1회 처리별 고유 UUID로 구분한다. 실패한 삭제 트랜잭션은 전체 롤백하고, 별도 짧은 트랜잭션으로 같은 임대의 재시도 시각만 갱신한다. 프로세스가 중단되면 lease 만료 뒤 다른 worker가 재개한다. 영구 실패 상태로 조용히 전환하지 않고 상한이 있는 backoff로 재시도하며 식별자 없는 오류 코드/집계만 기록한다.

V7은 앞선 복구 기능에서 이미 사용 중이므로 덮어쓰지 않는다. 기존 V6 필드로 구현되면 신규 마이그레이션은 필요 없다. 별도 완료 상태·오류 코드 컬럼 등이 반드시 필요해지면 V7을 바꾸지 않고 V8 제안과 호환성 검토가 필요하다.

외부 객체 저장소 어댑터는 현재 없고 프로필 이미지는 DB BLOB이다. 알 수 없는 resource_type의 기존 target을 임의 완료 처리하거나 외부 리소스가 없다고 가정해 제거해서는 안 된다. 외부 target을 도입할 때 별도 삭제 어댑터와 독립된 암호화 locator 수명, 실패 재시도를 검증해야 한다.

## 개인정보·키·백업 보장 범위

- 현재 archive는 탈퇴 건별 AES-256-GCM 데이터키를 생성하고 공용 AUTH 키로 래핑한다. ciphertext와 wrapped_data_key가 같은 DB 행에 저장된다.
- 해당 행 삭제는 현재 DB의 보관 암호문과 건별 래핑키 사본을 제거한다. AUTH 키를 삭제/교체할 필요가 없으며 그렇게 해서는 안 된다.
- 독립 KMS의 건별 폐기 가능한 키 객체가 없다. 백업/binlog/스냅샷에 두 값이 남고 기존 AUTH 키가 있으면 복호화할 수 있다. 따라서 DB DELETE를 백업의 암호학적 완전 파기로 표현하면 안 된다.
- 백업 보존기간·복원 시 삭제 재적용·개별 키 관리 정책은 별도 결정 사항이다. 이미 발송된 이메일이나 외부 제공자 사본 회수도 현재 보장하지 못한다.
- 최소 회원 ID·지갑·원장을 남기는 선택은 비식별 완료를 뜻하지 않는다. 키워드 검색뿐 아니라 FK·ID·해시·암호문·토큰 소유 자료를 기준으로 잔존을 검사해야 한다.
- auth_guard/auth_rate_limit는 TTL이 있는 HMAC scope만 저장하며 member 소유 FK가 없다. 특정 회원과의 소유 관계를 추정해 다른 회원의 직렬화 잠금을 삭제하면 안 된다. 기존 만료 정리가 담당하는 별도 인프라 범위로 분류한다.

## 구현 후 필수 검증 계획

격리 MySQL Testcontainers에서 직전/정각 기한, 파기·동일 이메일/카카오 신규 가입·새 ID·5토큰 1회, 복구 완료/이전 회차 안전성, 복구-파기 및 가입-파기 경합, 중복 worker·중복 삭제·임대 교체, 각 삭제 단계 예외/롤백·재시작 재개, 오래된 인증/복구 증명과 Access/Refresh 거부, 실제 information_schema FK·고아 행·소유 개인정보 잔존을 검증한다. 새 회원과 관련 없는 타 회원 데이터를 대조 fixture로 보호한다.

최초 검토 단계에서는 실행 코드를 수정하거나 테스트를 실행하지 않았다. 이전 974건/MySQL 480건은 직전 복구 작업의 결과이며, 파기 워커의 실제 실행 결과는 별도 구현 문서에 기록한다.
