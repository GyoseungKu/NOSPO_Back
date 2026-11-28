# AI 토큰·도서 질문·관계도

API 기본 경로는 `/api/v1`입니다. 정책 조회는 공개이며, 잔액·거래·질문·대화·관계도 API는 Bearer Access 토큰이 필요합니다. 관리자 API는 관리자 권한이 필요합니다.

AI 토큰은 서비스 지갑의 정수 단위입니다. 이메일·카카오 신규 가입 시 5개를 지급하고, 도서 질문과 관계도 생성·재생성은 접수 시 1개를 예약해 성공 시 차감합니다. OpenAI의 모델 토큰 사용량과 별도로 계산합니다.

## 토큰 API

| Method | 경로 | 권한 | 요청 | 성공 |
| --- | --- | --- | --- | --- |
| GET | /ai/credit-policy | 공개 | 없음 | 200 `signupGrant: 5`, `questionCost: 1`, `graphCost: 1`, `graphRegenerationCost: 1`, `reviewRewardMode: "ADMIN_SPECIFIED"` |
| GET | /me/ai/credits | 본인 | 없음 | 200 `data.balance`, `data.reserved`, `data.available` |
| GET | /admin/members/{memberId}/ai/credits | 관리자 | 없음 | 200 동일한 잔액 필드 |
| POST | /admin/members/{memberId}/ai/credit-adjustments | 관리자 | UUID `Idempotency-Key`, `type`, `amount`, `reason` | 201 `data`에 거래 정보 |

`balance`는 예약분을 포함한 잔액, `reserved`는 진행 중인 질문에 잡힌 수량, `available`은 `balance - reserved`입니다. 활성 회원과 지갑이 있어야 조회할 수 있으며 조건을 충족하지 않으면 404 `RESOURCE_NOT_FOUND`를 반환합니다.

관리자 조정은 `type`에 `GRANT` 또는 `RECOVERY`, `amount`에 양의 32비트 JSON 정수, `reason`에 공백만으로 이루어지지 않은 최대 1,000자 문자열을 받습니다. 응답 거래의 `amount`는 지급이면 양수, 회수면 음수입니다. 주요 응답 필드는 `transactionId`, `type`, `amount`, `balanceAfter`, `reason`, `createdAt`이며 관리자 조정의 `jobId`, `submissionId`, `originalTransactionId`는 null입니다.

같은 관리자·대상 회원·키 조합으로 24시간 이내에 같은 내용이 들어오면 최초 거래를 201로 반환합니다. 이때 `balanceAfter`는 최초 거래 직후 값입니다. 유형·수량·사유가 달라지면 409 `IDEMPOTENCY_CONFLICT`입니다. 만료된 키가 다시 들어오면 기존 거래의 키를 해제하고 새 요청으로 처리합니다.

회수는 가용량 이내에서만 가능합니다. 초과하면 409 `INSUFFICIENT_AVAILABLE_CREDITS`이고, 지급 후 잔액이 정수 상한을 넘거나 조정 입력이 잘못되면 422 `VALIDATION_ERROR`입니다.

## 질문 예약과 정산

| 단계 | 작업 상태 | balance | reserved | available |
| --- | --- | ---: | ---: | ---: |
| 가입 직후 | — | 5 | 0 | 5 |
| 질문 접수 | QUEUED | 5 | 1 | 4 |
| 작업 시작 | RUNNING | 5 | 1 | 4 |
| 성공한 경우 | SUCCEEDED | 4 | 0 | 4 |
| 실패한 경우 | FAILED | 5 | 0 | 5 |

질문 접수는 활성 회원을 잠근 뒤 도서·서재 진도·위키 범위와 대화 상태를 확인합니다. 예약과 질문 저장이 같은 트랜잭션에 있고, 가용량이 부족하면 409 `INSUFFICIENT_CREDITS`를 반환합니다. 실패 시 새 대화 생성과 기존 대화 갱신도 함께 롤백됩니다.

워커는 DB 큐에서 작업을 가져와 `RUNNING`으로 전환합니다. 작업 기한은 **접수부터 180초**입니다. 성공 시 답변·인용 저장, 예약 해제, 잔액 1개 차감, `USE` 거래 기록을 한 트랜잭션으로 처리합니다. 생성 오류, 시간 초과, 계정 비활성화 또는 탈퇴 세대 변경은 예약을 해제하고 실패로 기록합니다. 빈 위키 스냅샷의 경우 정해진 근거 부족 문구로 완료하며, 성공으로 저장되면 1개가 차감됩니다.

종료 처리에서는 회원 잠금 후 작업이 아직 `QUEUED` 또는 `RUNNING`인지 재확인합니다. DB는 `USE` 거래의 작업 ID를 유일하게 유지합니다. 탈퇴 세대를 접수 시 저장하므로 복구 후에도 탈퇴 전 작업의 결과를 성공으로 저장할 수 없습니다. 브라우저가 SSE 연결을 닫아도 워커는 독립적으로 처리합니다.

## 질문·대화 API

| Method | 경로 | 요청 | 성공 |
| --- | --- | --- | --- |
| POST | /ai/questions | UUID `Idempotency-Key`, `bookId`, `question`, 선택 `position`·`conversationId` | 202 `jobId`, `conversationId`, `questionId`, `appliedPosition`, `wikiVersion`, `cost`, `status`, `eventsUrl` |
| GET | /ai/jobs/{jobId} | 없음 | 200 작업 상태, `reservedCredits`, `chargedCredits`, 결과 또는 오류 |
| GET | /ai/questions/{questionId} | 없음 | 200 질문·답변·인용·차감량 또는 오류 |
| GET | /ai/questions/{questionId}/events | 선택 `Last-Event-ID` 헤더 | 200 `text/event-stream` |
| GET | /me/ai/conversations | 선택 `bookId`, `cursor`, `limit` | 200 대화 페이지 |
| GET | /ai/conversations/{conversationId} | 없음 | 200 대화 상세 |
| GET | /ai/conversations/{conversationId}/messages | 선택 `cursor`, `limit` | 200 시간순 메시지 페이지 |

질문은 공백만으로 이루어지지 않은 최대 4,000자 문자열입니다. `position`은 `{ "unit": "PAGE" 또는 "EPISODE", "value": 정수 }` 형태입니다. 생략 시 현재 서재 진도를 사용합니다. 지정 지점은 도서와 단위가 일치하고 0 이상이어야 하며, 현재 진도와 알려진 도서 전체 범위 이하여야 합니다. 접수 시 해당 범위에서 볼 수 있는 위키 스냅샷과 버전을 고정합니다. 답변 인용은 그 스냅샷에 포함된 항목·관계 ID에서 추출합니다.

`conversationId`를 생략하면 새 대화를 만듭니다. 기존 대화는 본인 소유이며 같은 도서여야 하고, 적용 지점은 그 대화의 이전 최대 지점 이상이어야 합니다. 같은 대화의 이전 질문이 처리 중이면 409 `REQUEST_IN_PROGRESS`입니다. 대화와 결과 조회에서도 현재 진도를 확인합니다. 목록의 `limit` 기본값은 20, 범위는 1~100이며 응답의 `nextCursor`를 다음 요청에 전달합니다.

질문 멱등성 키의 범위는 회원 단위이며 24시간 동안 유효합니다. 도서·질문·요청 지점·대화 ID가 같으면 기존 작업의 현재 상태를 반환하고 추가 예약은 발생하지 않습니다. 요청 내용이 달라지면 409 `IDEMPOTENCY_CONFLICT`입니다. 기존 작업을 반환할 때도 현재 진도로 조회 가능한지 검사합니다.

| 상황 | HTTP / 코드 |
| --- | --- |
| 서재 진도 없음 | 403 `SHELF_REQUIRED` |
| 지점 형식·단위·도서 범위 오류 | 422 `INVALID_POSITION` |
| 요청 지점이 현재 진도를 초과 | 422 `POSITION_EXCEEDS_CURRENT` |
| 조회 지점이 현재 진도를 초과 | 403 `SPOILER_SCOPE_EXCEEDED` |
| 기존 대화보다 이전 지점으로 질문 | 409 `CONVERSATION_POSITION_CONFLICT` |
| 적용 가능한 위키 스냅샷 없음 | 409 `WIKI_NOT_AVAILABLE` |
| 도서 연동 또는 답변 공급자 준비 안 됨 | 503 `AI_UNAVAILABLE` |

### SSE와 최종 상태

`eventsUrl`에 Bearer 헤더를 붙여 연결합니다. 브라우저에서는 인증 헤더를 넣을 수 있는 fetch 기반 SSE 클라이언트를 사용할 수 있습니다. 이벤트 순서는 `started`, `delta`(0개 이상), `completed` 또는 `failed`입니다. 성공 이벤트는 `chargedCredits: 1`과 인용을, 실패 이벤트는 `chargedCredits: 0`과 실패 코드를 전달합니다. 연결 중에도 세션·동의와 조회 범위를 확인합니다.

재연결 시 마지막 이벤트 ID를 `Last-Event-ID`로 보내면 이후 이벤트부터 받습니다. 완료 24시간 이후에는 SSE 재생이 410 `STREAM_REPLAY_EXPIRED`로 종료되며 저장된 결과는 질문 조회 API로 확인할 수 있습니다. 프론트는 최종 이벤트 또는 작업 조회로 차감 여부를 확인하고 잔액을 다시 조회할 수 있습니다.

## 관계도 생성·재생성

| Method | 경로 | 요청 | 성공 |
| --- | --- | --- | --- |
| POST | /ai/graphs | UUID `Idempotency-Key`, `bookId`, 선택 `position`·`regenerateFromGraphId` | 202 `jobId`, `appliedPosition`, `wikiVersion`, `cost`, `status` |
| GET | /ai/jobs/{jobId} | 없음 | 200 `type: GRAPH`, 완료 시 `result.graphId` |
| GET | /ai/graphs/{graphId} | 없음 | 200 관계도 정보, `nodes`, `edges`, `citations` |
| GET | /me/ai/graphs | 선택 `bookId`, `cursor`, `limit` | 200 본인 관계도 목록 |

접수 시 진도 범위의 승인 위키 스냅샷과 버전을 고정하고 1개를 예약합니다. 워커가 스냅샷에 포함된 인물·관계와 출처로 결과를 구성합니다. 생성 성공 시 1개를 차감하고, 실패·기한 초과 시 예약을 해제합니다. 관계도 작업은 상태 조회 API로 완료 여부를 확인합니다. 재생성은 같은 도서의 본인 관계도를 참조하며 새 작업과 새 결과를 생성합니다.

결과 조회에서 현재 진도를 다시 검사하고, 현재 범위 밖의 결과는 목록에서 숨깁니다. 생성 지점이 현재 지점과 다르거나 현재 위키 버전이 달라지면 `regenerationRequired`가 true입니다.

## 토큰 거래 조회와 환불

| Method | 경로 | 권한 | 요청 |
| --- | --- | --- | --- |
| GET | /me/ai/credit-transactions | 본인 | 선택 `type`, `from`, `to`, `cursor`, `limit` |
| GET | /me/ai/credit-transactions/{transactionId} | 본인 | 없음 |
| GET | /admin/members/{memberId}/ai/credit-transactions | 관리자 | 본인 목록과 동일 |
| POST | /admin/ai/credit-transactions/{transactionId}/refund | 관리자 | UUID `Idempotency-Key`, `reason` |

거래 목록은 가입 지급을 포함한 `GRANT`, `RECOVERY`, `USE`, `REFUND`를 최신순으로 반환합니다. 날짜 필터는 ISO-8601 시각이고 양 끝을 포함합니다. 예약과 예약 해제는 잔액 변동이 없으므로 거래를 생성하지 않습니다.

환불은 사용 거래 전액을 한 번 복구합니다. 같은 관리자·원거래·키·사유로 24시간 이내 재시도하면 기존 거래를 201로 돌려주며, 별개 요청으로 이미 환불된 사용 거래를 다시 요청하면 409 `ALREADY_REFUNDED`입니다.

## 모듈과 테이블

| 위치 | 역할 |
| --- | --- |
| account-lifecycle/application | 이메일·카카오 신규 가입 트랜잭션에서 `SignupCredits.grant` 호출 |
| credit/contract, credit/application | 가입 지급, 관리자 조정, 예약·정산 계약과 트랜잭션 정책 |
| credit/adapter-web, credit/adapter-persistence | `CreditController`, `JdbcCredits`, `JdbcAiCreditLedger` |
| ai/application | `AiQuestions`가 도서 범위, 질문 접수, 정산을 조율 |
| ai/adapter-web, ai/adapter-persistence | 질문·작업·대화 API, SSE, JDBC 큐와 증분 저장 |
| ai/adapter-worker, ai/adapter-integration | DB 작업 폴링·기한 처리, OpenAI 스트리밍 호출 |
| 루트 앱 | 보안·CORS, `AiBookContext` 연결, Flyway 마이그레이션 |

V1에는 `credit_wallet`과 가입 거래용 `credit_transaction`이 있고, V15는 관리자 지급·회수 및 사용 거래용 `credit_operation`과 `ai_conversation`, `ai_question`, `ai_question_delta`를 추가합니다. 기존 지갑과 가입 거래는 그대로 사용합니다. V15는 지갑이 없는 회원에 대해 백필을 수행하지 않습니다. 회원 물리 삭제 시 지갑·거래·대화·질문과 증분 데이터는 FK 연쇄 삭제 대상입니다. 관리자가 실행한 조정은 대상 회원 데이터에 속하며, 관리자 계정만 물리 삭제되면 거래의 `actor_id`가 null로 바뀝니다.

V16은 관계도 작업·결과용 `ai_graph`와 사용 내역당 한 건으로 제한한 `credit_refund`를 추가합니다.

대화 제목은 질문에 나온 승인 공개 범위의 인물·단체명을 사용합니다. 해당 이름이 없으면 `도서 질문`을 사용합니다.

## 실행 설정과 구현 범위

main의 기본 `AiBookContext`는 Book의 영구 판본·현재 서재 진도와 승인 Wiki 스냅샷을 조회합니다. 실제 질문·관계도는 해당 판본의 서재 등록과 공개 Wiki가 필요합니다. `ai-local` 프로필에서는 예제 도서 `00000000-0000-0000-0000-000000000101`과 40화 진도를 사용합니다. 답변 공급자는 `nospo.ai.openai-key` (`OPENAI_API_KEY`), `nospo.ai.openai-model` (`OPENAI_MODEL`), `nospo.ai.openai-url`로 설정하며 질문 접수 시 공급자 설정도 확인합니다. 관계도는 위키 스냅샷으로 구성하므로 모델 키 없이 동작합니다. 토큰 정책·잔액·거래 조회·관리자 조정과 환불에는 도서 연동과 모델 키가 필요하지 않습니다.

[기능 API 명세](NOSPO_Function_API_v2.1.md)의 관계도 생성·재생성 비용과 리뷰 보상 모드는 정책 응답에 포함됩니다. 자동 차감은 도서 질문과 관계도 생성·재생성에 연결됩니다. 관리자가 Wiki 승인 시 지정한 양수 보상은 공개 버전과 함께 확정되며, 토큰 거래에서 제출 ID를 조회할 수 있습니다. 0토큰 승인은 원장 거래를 만들지 않습니다.

`AiCreditsAndQuestionsTests`는 관리자 지급·회수와 멱등성, 질문 SSE 재생과 중복 차감 방지, 외부 공급자 실패 시 예약 해제, 현재 진도 초과 접수 차단, 탈퇴 후 복구 시 이전 작업 결과 차단, 관계도 재생성과 공개 범위, 토큰 원장 조회와 중복 환불 방지를 검증합니다.
