# NOSPO Notice 개발 기록

## 1. 기능 개요

Notice 모듈은 공지 원본을 관리하고, 게시된 공지를 서비스 사용자에게 제공한다. 공개 조회는 홈 공지 배너에 표시할 제목과 상세 이동에 사용할 `noticeId`를 제공한다. 관리자 조회는 게시 상태와 관계없이 공지 원본을 확인하는 용도다. 홈 배너는 API의 사용 시나리오이며 프론트 연동 완료 여부는 이 백엔드 저장소에서 확인하지 않았다.

[기능/API v2.1](NOSPO_Function_API_v2.1.md)의 §6은 공개 조회, §14.2는 관리자 조회와 변경을 정의한다. §2의 공통 응답·페이지네이션과 §14의 관리자 감사 요구도 적용된다. 현재 공개 조회 2개와 관리자 조회 2개가 구현되어 있다.

## 2. 개발 환경 및 구조

`feat/notice`는 회원 상태, 세션 검증, 필수 약관 재동의 접근 제한이 이미 있는 `identity`를 기준으로 한다. 문서 작성 시 두 브랜치와 로컬 `origin/identity`의 기준 커밋은 `b4d142f613f07f9c3b9fc7b065d40657a92f9d58`다. 원격의 최신 상태는 별도로 조회하지 않았다.

| Gradle 모듈 | 역할 |
| --- | --- |
| `:notice:contract` | `NoticeSummary`, `NoticeDetail`, `AdminNotice` 응답 계약 |
| `:notice:application` | 공개·관리자 조회 흐름, 저장소 포트, 커서 서명 및 검증 |
| `:notice:adapter-persistence` | JDBC 조회, SQL 파라미터 바인딩, DB 행과 응답 모델 간 매핑 |
| `:notice:adapter-web` | 공개·관리자 HTTP 경로와 UUID·limit 입력 해석 |

루트 앱이 두 어댑터를 조립한다. 응답은 `ApiResponse`, 오류는 `ApiException`과 `ApiErrors`를 사용한다. UUID의 BINARY(16) 변환에는 `SqlIds`를 사용하고, DATETIME 값은 UTC 기준으로 바인딩·해석한다. 커서는 공통 `Secrets.fingerprint`의 HMAC을 재사용하며, 접근 제어는 기존 `SecurityConfig`와 재동의 필터에 연결한다.

## 3. 단계별 개발 기록

### 3.1. 1단계 — Notice 구조 및 데이터베이스 설계

네 Gradle 하위 모듈과 공지 조회 포트를 구성하고, 공지 전용 ERD와 Flyway V9를 추가했다. V9는 `notice` 테이블과 공개 목록 정렬 인덱스를 생성한다. 원본 인증 ERD와 V1~V8은 보존했다.

MySQL Testcontainers에서 V8 기준의 증분 마이그레이션과 ERD·실제 스키마 대조를 검증했다. 선별 테스트는 **30/30 통과**했으며 전체 회귀는 미실행이다.

### 3.2. 2단계 — 공개 공지 목록·상세 조회

`GET /api/v1/notices`와 `GET /api/v1/notices/{noticeId}`를 추가했다. 게시 상태가 참이고 게시 시각이 서버 시각 이하인 행만 노출한다. 목록은 고정 우선 및 게시 시각 내림차순이며, UUID로 동률을 해소한다.

목록에는 제목·요약 등 요약 필드만 포함한다. 상세에서 추가되는 `content`는 저장된 Markdown 원문이다. 프론트 렌더링 시 원시 HTML과 위험한 링크 스킴을 차단해야 하며, 해당 프론트 동작은 백엔드 검증 범위 밖이다. 비회원과 필수 약관 재동의 미완료 회원도 두 GET을 사용할 수 있다.

선별 테스트는 **43/43 통과**했으며 전체 회귀는 미실행이다.

### 3.3. 3단계 — 관리자 공지 조회 및 접근 제어

`GET /api/v1/admin/notices`와 `GET /api/v1/admin/notices/{noticeId}`를 추가했다. 목록과 상세에서 미게시·게시 취소·미래 시각 공지를 조회할 수 있다. 관리자 목록은 공통 기본 정렬인 생성 시각과 UUID 내림차순을 따르며, 전용 서명 커서를 사용한다.

인증 후 DB의 현재 `member.role`을 조회한다. 비회원·만료·폐기 세션은 401, USER는 403 `FORBIDDEN`, 미재동의 ADMIN은 403 `TERMS_RECONSENT_REQUIRED`다. 공개 두 GET과 기존 일반 API의 재동의 보호를 회귀 검증했다.

선별 테스트는 **47/47 통과**했으며 전체 회귀는 미실행이다. POST·PATCH·DELETE는 감사와 삭제 정책 협의가 필요해 보류했다.

## 4. API 구현 현황

관리자 권한은 유효한 세션의 ACTIVE ADMIN 및 필수 약관 재동의 완료를 뜻한다. 보류 행의 성공 응답은 v2.1에 정의된 계약이다.

| Method | URI | 권한 | 상태 | 성공 응답 |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/notices` | 공개 | 완료 | 200 `Page<NoticeSummary>` |
| GET | `/api/v1/notices/{noticeId}` | 공개 | 완료 | 200 `NoticeSummary + content` |
| GET | `/api/v1/admin/notices` | 관리자 | 완료 | 200 `Page<AdminNotice>` |
| GET | `/api/v1/admin/notices/{noticeId}` | 관리자 | 완료 | 200 `AdminNotice` |
| POST | `/api/v1/admin/notices` | 관리자 | 미구현·보류 | 201 `AdminNotice` |
| PATCH | `/api/v1/admin/notices/{noticeId}` | 관리자 | 미구현·보류 | 200 `AdminNotice` |
| DELETE | `/api/v1/admin/notices/{noticeId}` | 관리자 | 미구현·보류 | 204, 본문 없음 |

`NoticeSummary`는 `noticeId`, `title`, `summary`, `pinned`, `publishedAt`, `updatedAt`을 제공한다. `AdminNotice`는 여기에 `content`, `published`를 더한다. 생성 시각은 관리자 정렬에만 사용한다.

빈 목록 및 마지막 페이지에서 `nextCursor`는 null, `hasNext`는 false다. 빈 공개 목록 응답 예시는 다음과 같다.

```json
{
  "data": {
    "items": [],
    "nextCursor": null,
    "hasNext": false
  },
  "meta": {
    "requestId": "00000000-0000-0000-0000-000000000001"
  }
}
```

비정수 limit과 잘못된 UUID는 400 `INVALID_REQUEST`, 유효하지 않은 커서는 400 `INVALID_CURSOR`, 정수 limit의 범위 오류는 422 `VALIDATION_ERROR`다. 미존재 상세는 404 `RESOURCE_NOT_FOUND`며, 공개 상세에서는 미게시·게시 취소·미래 게시도 같은 404를 사용한다. 오류에는 `error.code`, `error.message`, `error.details`, `meta.requestId`가 포함된다.

## 5. 데이터베이스 설계

[Notice 전용 ERD](../erd/NOSPO_Notice.erd.json) · [Flyway V9](../src/main/resources/db/migration/V9__notice.sql)

| 컬럼 | 유형 | 제약 및 의미 |
| --- | --- | --- |
| `notice_id` | BINARY(16) | PK, UUID |
| `title` | VARCHAR(200) | NOT NULL, CHECK 1~200자 |
| `summary` | VARCHAR(500) | NOT NULL, CHECK 최대 500자, 빈 문자열 허용 |
| `content` | MEDIUMTEXT | NOT NULL, CHECK 1~50,000자, Markdown |
| `pinned` | BOOLEAN | NOT NULL, 기본 FALSE, CHECK 0/1 |
| `published` | BOOLEAN | NOT NULL, 기본 FALSE, CHECK 0/1 |
| `published_at` | DATETIME(6) | NULL 허용, 게시 상태이면 CHECK로 값 필수 |
| `created_at` | DATETIME(6) | NOT NULL, UTC 생성 시각 |
| `updated_at` | DATETIME(6) | NOT NULL, UTC 수정 시각 |

```sql
CREATE INDEX ix_notice_public_order
    ON notice (published, pinned DESC, published_at DESC, notice_id DESC);
```

`publishedAt`은 최초 게시 시각이다. 게시 전에는 null이며, 후속 쓰기 로직이 최초 게시 때 서버 시각을 설정하고 게시 취소·재게시 때 보존해야 한다. V9의 CHECK는 게시 상태와 시각의 관계를 검사한다. 최초 시각의 부여와 불변성 보장은 쓰기 구현에 남아 있다.

공지 조회에 기존 회원·인증 테이블과의 FK가 필요하지 않아 추가하지 않았다. 관리자 권한은 조회 요청의 인가 단계에서 확인한다. V1~V9 및 [원본 인증 ERD](../erd/NOSPO_Auth.erd.json)는 이력을 보존한다. 정책 확정 후 구조 변경이 필요하면 새 Flyway 버전으로 추가한다.

## 6. 접근 제어 및 보안

- 공개 예외는 공지 목록·상세 두 GET에만 적용된다. 공개 SQL이 `published = TRUE AND published_at <= ?`를 적용하므로 미게시, NULL 시각, 미래 공지가 결과에 포함되지 않는다.
- 관리자 경로는 기존 JWT 서명·만료·세션·ACTIVE 검증 후 현재 DB 역할이 정확히 ADMIN인지 확인하고, 그 다음 필수 약관 재동의 상태를 검사한다. 역할 승격·강등은 다음 요청에 반영된다.
- 기존 Origin·CORS·CSRF 규칙을 적용한다. 관리자 공지 경로에는 공개 예외가 없으며 관리자 쓰기 엔드포인트는 미등록 상태다.
- 공개·관리자 커서는 HMAC 서명 문맥이 다르다. 서명이 맞지 않거나 다른 API에서 발급한 커서는 거부한다.

## 7. 페이지네이션과 정렬

| 목록 | 정렬과 커서 |
| --- | --- |
| 공개 | `pinned DESC, published_at DESC, notice_id DESC`. 첫 페이지의 공개 기준 시각과 마지막 행의 정렬 키를 서명한다. |
| 관리자 | `created_at DESC, notice_id DESC`. 마지막 행의 생성 시각과 UUID를 서명한다. 게시 시각의 null 여부는 정렬에 영향을 주지 않는다. |

두 목록 모두 `limit` 기본 20, 범위 1~100의 keyset 페이지네이션이다. 프론트의 10·20·30 선택은 이 범위에서 지원된다. 서버는 요청 크기보다 한 행 더 확인해 다음 페이지 유무를 계산한다.

공개 목록은 첫 페이지 시각을 기준으로 삼되 현재 게시 상태를 매번 확인한다. 이후 공지 추가·삭제·재게시 또는 고정 변경에 따라 보이는 항목이 달라질 수 있다. 정렬 키가 경계를 넘으면 일부 항목이 중복되거나 누락될 수 있다. 관리자 역시 여러 요청에 걸쳐 DB 스냅샷을 유지하지 않는다. 오래된순은 미구현이다.

## 8. 테스트 및 검증

| 최종 선별 실행 | 통과 / 실행 | H2 통합 | MySQL Testcontainers | 재동의 정책 단위 | 실패 / 오류 / 스킵 | 전체 회귀 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1단계 | 30/30 | 6 | 7 | 17 | 0 / 0 / 0 | 미실행 |
| 2단계 | 43/43 | 12 | 14 | 17 | 0 / 0 / 0 | 미실행 |
| 3단계 | 47/47 | 14 | 16 | 17 | 0 / 0 / 0 | 미실행 |

수치는 각 단계의 최종 선별 실행이다. 마이그레이션·인증·재동의·조회 테스트가 여러 단계에 반복 포함되므로 고유 테스트 개수로 합산하지 않는다. 로컬 `build/notice-phase1-tests.log`, `build/notice-phase2-tests.log`, `build/notice-phase3-tests.log`에서 각 실행의 성공을 확인했고, 현재 보존된 JUnit XML에서 3단계 세부 수치를 확인했다. 1·2단계 세부 수치는 당시 실행 집계다.

MySQL에서는 V9 스키마와 정렬 인덱스, 실제 공개·관리자 커서 경계, 현재 역할 변경 및 재동의 차단을 검증했다. H2에서도 공개·관리자 HTTP 계약과 기존 인증·재동의 동작을 확인했다. 관리자 쓰기와 프론트 동작은 미검증이다.

Notice 개발 이후 전체 Gradle 회귀는 아직 실행하지 않았다. Identity **1,380/1,380**은 Notice 분기 전 기록이다. H2 Refresh·탈퇴의 간헐적 잠금 경합은 별도 Known Issue로 관리한다. 이번 문서 작성에서 테스트를 재실행하지 않았다.

## 9. 팀 협의가 필요한 정책

아래 권장 방향은 협의안이다. 합의 시 결정일과 영향을 후속 단계에 기록한다.

| 항목 | 현재 상태 | 선택지 | 권장 방향 | 결정 후 영향 범위 |
| --- | --- | --- | --- | --- |
| 삭제 | DELETE 보류, 성공 계약 204 | 물리 삭제 / 논리 삭제 | 복구·보존 필요성을 먼저 확인하고, 필요가 없다면 감사 기록과 공지 본문 보존을 분리하는 물리 삭제 검토 | 공지 쓰기 트랜잭션, 조회 제외, 복구·보존, 신규 마이그레이션 가능성 |
| 감사 기록 | 처리자·시각·사유 필수, 공통 저장소 미확인 | 공통 감사 저장소 / 공지 전용 저장소 | 저장 위치와 보존 기간을 합의하고 공지 변경과 같은 트랜잭션에 기록. 탈퇴 시 식별 가능한 로그 제거 경로를 함께 설계 | 감사 항목·접근권한·보존 기간·탈퇴 처리, 스키마 |
| 사유 입력 | §14.2에 전달 수단 없음 | 필수 또는 선택 입력, 요청 본문 또는 별도 전달 규칙 | 모든 변경에서 사유가 어떻게 확보되는지 확정하고 계약을 명시. 선택 입력이면 입력하지 않았을 때의 기록 규칙까지 합의 | POST·PATCH·DELETE 계약, 오류, 프론트 폼, 감사 |
| 동시 수정 | 쓰기와 충돌 판정 미구현 | 필드 단위 PATCH·행 잠금 / 버전 또는 조건부 요청 | 서로 다른 필드의 수정과 같은 필드의 오래된 수정 사례를 구분. 오래된 수정 거부가 필요하면 버전 또는 `If-Match` 합의 | PATCH·DELETE 응답 계약, 충돌 오류, 트랜잭션 테스트 |
| 이미지 첨부 | 저장·삭제 계약 및 API 미구현 | 본문 삽입 / 별도 첨부, 저장소·용량 방식 | 접근권한·허용 형식·용량·삭제 수명주기를 먼저 결정하고 Markdown 렌더링 정책과 연결 | DB·스토리지·업로드·정리 작업, 프론트 렌더링 |
| 정렬 | 공개 고정 우선 최신순, 관리자 생성 시각 최신순 | 기존 정렬 / 오래된순 확장과 고정 우선 유지 여부 | 기본 정렬을 유지하고 오래된순을 추가할 때 고정 우선과 신규 커서 문맥을 함께 결정 | 요청 파라미터, 커서, 인덱스와 실행 계획, 프론트 선택 UI |

## 10. 미구현 및 향후 작업

- 관리자 POST·PATCH·DELETE 및 감사 기록과 삭제 정책 반영.
- 쓰기 동시성, 트랜잭션 원자성, 최초 게시 시각 보존과 공개 반영 검증.
- 이미지 첨부, 오래된순, 프론트 관리자 UI 연동.
- 전체 Notice 기능이 안정화된 뒤 MySQL Testcontainers를 포함한 전체 Gradle 회귀.

문서 작성 시 Notice 변경은 로컬 미커밋·미추적 상태다. 이번 문서 작업에서 Git 커밋·푸시를 실행하지 않았다. PR 및 배포 상태는 확인하지 않았다.

## 11. 단계별 변경 이력

아래 Notice 소스 경로는 `modules/notice` 기준이며, 상위 경로가 있는 파일은 저장소 루트 기준이다. 일부 파일은 여러 단계에서 확장됐다.

| 단계 | 추가·변경 파일 |
| --- | --- |
| 1단계 | `build.gradle`, `settings.gradle`, `docs/NOSPO_Notice.erd.json`, `src/main/resources/db/migration/V9__notice.sql` |
| 1단계 | `contract/build.gradle`, `application/build.gradle`, `adapter-persistence/build.gradle`, `adapter-web/build.gradle` |
| 1단계 | `contract/src/main/java/kr/nospo/notice/contract/NoticeSummary.java`, `application/src/main/java/kr/nospo/notice/application/NoticeStore.java` |
| 1단계 | `adapter-persistence/src/main/java/kr/nospo/notice/persistence/package-info.java`, `adapter-web/src/main/java/kr/nospo/notice/web/package-info.java`, `src/test/java/kr/nospo/nospo_backend/MySqlNoticeMigrationTests.java` |
| 2단계 | `application/build.gradle`, `adapter-persistence/build.gradle`, `adapter-web/build.gradle`, `contract/src/main/java/kr/nospo/notice/contract/NoticeDetail.java` |
| 2단계 | `application/src/main/java/kr/nospo/notice/application/NoticeStore.java`, `application/src/main/java/kr/nospo/notice/application/NoticeCursor.java`, `application/src/main/java/kr/nospo/notice/application/PublicNoticeService.java` |
| 2단계 | `adapter-persistence/src/main/java/kr/nospo/notice/persistence/JdbcNoticeStore.java`, `adapter-web/src/main/java/kr/nospo/notice/web/PublicNoticeController.java` |
| 2단계 | `src/main/java/kr/nospo/nospo_backend/config/SecurityConfig.java`, `src/test/java/kr/nospo/nospo_backend/PublicNoticeTests.java`, `src/test/java/kr/nospo/nospo_backend/MySqlPublicNoticeTests.java`, `src/test/java/kr/nospo/nospo_backend/ConsentHistoryTests.java` |
| 3단계 | `contract/src/main/java/kr/nospo/notice/contract/AdminNotice.java`, `application/src/main/java/kr/nospo/notice/application/AdminNoticeCursor.java`, `application/src/main/java/kr/nospo/notice/application/AdminNoticeService.java`, `application/src/main/java/kr/nospo/notice/application/NoticeStore.java` |
| 3단계 | `adapter-persistence/src/main/java/kr/nospo/notice/persistence/JdbcNoticeStore.java`, `adapter-web/src/main/java/kr/nospo/notice/web/AdminNoticeController.java`, `adapter-web/src/main/java/kr/nospo/notice/web/NoticeRequest.java`, `adapter-web/src/main/java/kr/nospo/notice/web/PublicNoticeController.java` |
| 3단계 | `src/main/java/kr/nospo/nospo_backend/config/SecurityConfig.java`, `src/test/java/kr/nospo/nospo_backend/ConsentHistoryTests.java` |
| 단계별 작업 보고 | `docs/Terms_Reconsent_Analysis.md` |
| 누적 기록 작성 | `docs/NOSPO_Notice_Development.md` |

## 12. 이후 단계 누적 기록 규칙

- 1~3단계 기록을 보존한다. 4단계부터 문서 하단에 `## 13. 4단계 — 관리자 쓰기 및 감사 정책 구현`처럼 신규 섹션을 추가하고, 이후 단계도 번호를 이어 간다.
- 완료·진행 중·보류·미구현을 구분한다. 각 단계에 실행 범위·수치와 전체 회귀 실행 여부를 기록한다.
- 이전 기록의 사실 오류를 고칠 때는 변경 이력에 정정 내용과 이유를 남긴다.
- 설계 변경은 근거와 신규 Flyway 버전을 기록하고 기존 마이그레이션을 보존한다.
- 팀 정책이 확정되면 협의일과 결정 사항을 새 섹션에 기록하고, 기존 미확정 기록은 당시 이력으로 유지한다.
- [작업 결과 확인 보고서](Terms_Reconsent_Analysis.md)는 별도로 유지하고 기존 작업별 갱신 규칙을 따른다.

## 13. 4단계 — 관리자 쓰기, 논리 삭제와 정렬

### 구현과 팀 결정

| 팀 결정 | 적용 |
| --- | --- |
| 논리 삭제 | V10의 `deleted_at`에 서버 UTC 시각을 기록한다. 네 조회와 수정에서 삭제 행을 제외한다. 행과 본문은 보존한다. |
| 별도 감사 기록 생략 | Notice 감사 테이블과 변경 이력을 추가하지 않았다. 기존 인증·보안·운영 로깅은 유지했다. |
| 변경 사유 미입력 | 등록·수정·삭제에 사유 입력을 받지 않는다. |
| 마지막 업데이트 우선 | 필드 단위 원자 UPDATE와 트랜잭션을 사용한다. 동시성 충돌 응답이나 필수 버전을 도입하지 않았다. |
| 이미지 별도 업로드 | 기존 프로필은 회원 전용 DB 바이너리 저장이다. 공지에 쓸 운영 저장소, 접근 URL, 미사용 및 삭제 파일 수명주기가 확인되지 않아 업로드 API만 보류했다. |
| 최신순·오래된순 | 두 목록에 선택적 `sort=latest\|oldest`를 추가했다. 공개는 두 모드 모두 고정이 우선이다. 관리자는 §2.2와 기존 조회 기준에 따라 생성 시각을 사용한다. |

v2.1 §14는 관리자 변경의 처리자·시각·사유 기록과 탈퇴 시 개인 식별 가능 로그 제거를 요구한다. 이번 팀 합의는 **Notice에만 적용**되며, 별도 감사 기록과 변경 사유 미입력은 해당 공통 계약을 충족하지 않는다. 원본 명세는 유지했다. 명세 개정 또는 Notice 예외 승인 절차의 필요 여부를 후속 확인한다.

### API와 계약

| Method | 경로 | 결과 |
| --- | --- | --- |
| GET | `/api/v1/notices` | 200 `Page<NoticeSummary>` |
| GET | `/api/v1/notices/{noticeId}` | 200 `NoticeDetail` |
| GET | `/api/v1/admin/notices` | 200 `Page<AdminNotice>` |
| GET | `/api/v1/admin/notices/{noticeId}` | 200 `AdminNotice` |
| POST | `/api/v1/admin/notices` | 201 `AdminNotice` |
| PATCH | `/api/v1/admin/notices/{noticeId}` | 200 `AdminNotice` |
| DELETE | `/api/v1/admin/notices/{noticeId}` | 204, 응답 본문 없음 |

등록 요청 예시.

```json
{"title":"서비스 점검","summary":"","content":"**점검 안내**","pinned":false,"published":true}
```

등록은 v2.1에 따라 다섯 필드가 모두 필요하다. 스키마의 두 불리언 기본값은 FALSE이며 API에서는 명시적으로 입력한다. 제목 1~200자, 요약 0~500자, 본문 1~50,000자다. 제목·본문은 공백만 있는 값을 거부하고 Markdown 원문을 저장한다. PATCH는 예를 들어 `{"pinned":true}`로 지정 필드만 바꾼다. 빈 PATCH, 명시적 null, 유효하지 않은 값은 422 `VALIDATION_ERROR`다. 잘못된 JSON·UUID 및 알 수 없는 필드는 400 `INVALID_REQUEST`다.

201·200 쓰기 응답 예시.

```json
{
  "data": {
    "noticeId":"00000000-0000-0000-0000-000000000001",
    "title":"서비스 점검",
    "summary":"",
    "content":"**점검 안내**",
    "pinned":false,
    "published":true,
    "publishedAt":"2026-11-19T12:00:00.123456Z",
    "updatedAt":"2026-11-19T12:00:00.123456Z"
  },
  "meta":{"requestId":"00000000-0000-0000-0000-000000000002"}
}
```

서버 UTC 마이크로초 단위로 등록·수정 시각을 기록한다. 처음 게시할 때 `published_at`을 설정하고 게시 취소와 재게시에서 보존한다. 수정 SQL은 생략 필드를 유지하며 최초 게시 시각을 원자적으로 보존한다. 등록·수정의 응답 조회는 변경 트랜잭션에 포함된다. 동시에 수정하면 같은 필드의 마지막 DB 갱신이 반영된다. 다른 필드의 갱신을 덮어쓰지 않는다.

V10은 기존 notice 테이블에 `deleted_at DATETIME(6) NULL`만 추가하며 기존 행은 정상 상태로 유지된다. Notice ERD에 해당 컬럼을 추가했다. 중복 삭제·미존재 삭제와 삭제 후 수정은 404 `RESOURCE_NOT_FOUND`다. 중복 요청은 최초 삭제 시각을 바꾸지 않는다. 복구·영구 삭제 API는 없다.

### 정렬과 커서

- 공개 `GET /api/v1/notices?sort=oldest&limit=10`은 `pinned DESC, published_at ASC, notice_id ASC`로 정렬한다. 기본 latest는 고정 우선에 게시 시각과 ID 내림차순이다.
- 관리자 `GET /api/v1/admin/notices?sort=oldest&limit=20`은 `created_at ASC, notice_id ASC`다. 기본 latest는 기존 생성 시각과 ID 내림차순이다.
- `sort`를 생략하면 latest다. 기존 최신 커서의 서명 바이트 문맥과 본문 형식을 유지했다. 오래된순은 별도 문맥으로 서명한다. 조회 범위 또는 모드가 다른 커서는 400 `INVALID_CURSOR`다. 프론트에서 정렬을 바꿀 때는 cursor를 비운다. limit 기본 20 및 1~100 범위와 응답의 페이지 구조는 유지된다.
- Keyset 조회는 삭제된 행을 매번 필터링한다. 여러 요청을 동일 DB 스냅샷에 고정하지 않으므로 조회 중 고정·게시 상태가 바뀌면 페이지가 달라질 수 있다.

MySQL 8.4.7의 두 공개 목록 EXPLAIN에서 `ix_notice_public_order`는 사용 가능 후보였다. 소규모 격리 데이터에서 두 모드 모두 선택 인덱스가 없었고 `Using where; Using filesort`가 기록됐다. 특히 고정 내림차순에 시각·ID 오름차순을 섞는 오래된순은 기존 인덱스의 단순 전체 역방향과 다르다. 운영 규모의 비용은 확인되지 않아 신규 인덱스는 추가하지 않았다.

### 권한·배포 및 남은 작업

현행 JWT·ACTIVE·DB ADMIN·필수 재동의 검사를 통과한 뒤 관리자 공지 전용 CSRF 검사를 수행한다. 기존 Bearer 경로의 공통 정책은 유지된다. 관리자 목록 GET에서 CSRF 전용 세션 쿠키와 `X-CSRF-TOKEN` 응답 헤더를 발급한다. 프론트는 허용 Origin에서 자격 증명 옵션을 사용해 쿠키를 유지하고 쓰기 때 같은 세션과 헤더를 전달한다. 세션은 CSRF용이며 인증 자체에는 매 요청 JWT가 필요하다. 관리자 공지 경로만 CORS에 해당 헤더를 추가했다. 공개 예외 및 다른 경로의 보안 규칙은 유지했다.

운영 적용 전 V10 배포, 실제 프론트의 헤더·쿠키 처리(다중 인스턴스에서의 CSRF 세션 유지 포함), Markdown 렌더링 시 원시 HTML과 위험한 링크 차단을 확인한다. 이미지 업로드는 저장소·첨부 접근 권한·보존 및 미사용 파일 정리 기준 확정 후 진행한다. 본문 삽입 자동화는 범위 밖이다. H2 Refresh·탈퇴의 간헐적 잠금 경합은 별도 Known Issue다.

### 검증 결과

| 최종 실행 범위 | 실행 | 통과 | 실패 | 오류 | 스킵 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 선별 전체 | 57 | 57 | 0 | 0 | 0 |
| 선별 H2 / MySQL / 단위 | 21 / 19 / 17 | 21 / 19 / 17 | 0 | 0 | 0 |
| 전체 Gradle 회귀 | 1405 | 1405 | 0 | 0 | 0 |
| 전체 H2 | 621 | 621 | 0 | 0 | 0 |
| 전체 MySQL Testcontainers | 747 | 747 | 0 | 0 | 0 |
| 전체 DB 비의존 단위 | 37 | 37 | 0 | 0 | 0 |

H2와 MySQL에서 관리자 등록·수정·삭제, 길이·null 및 Markdown, 게시·고정 변경, 최초 게시 시각, 동시 PATCH의 다른 필드 보존, 논리 삭제와 롤백, 두 정렬의 커서 경계, 현재 관리자 역할과 실제 CSRF, 재동의를 검증했다. MySQL에서 V1~V10 초기화와 V9→V10 증분 적용 및 V9 기존 행·ERD 정합성을 확인했다. 전체 회귀는 기존 Identity·약관·세션과 Swagger 검사를 포함한다. 별도 H2 Refresh·탈퇴 잠금 경합은 최종 실행에서 관찰되지 않았으며 해소를 단정하지 않는다.

선별 초기 시도에서는 Bearer 기본 CSRF 예외 및 공지 OpenAPI 설명 누락이 발견돼 수정했다. 전체 회귀 첫 시도는 테스트용 HTTPS 콜백에 포트가 없어 MockMvc가 재구성한 요청 URL이 일치하지 않아 중단했다. 테스트 JVM에만 명시적 포트를 넣어 기존 Kakao 테스트 3/3을 재검증한 뒤, 최종 전체 회귀를 한 차례 완료했다. 해당 실행 결과는 `build/notice-phase4-tests.log`, `build/notice-phase4-callback-check.log`, `build/notice-phase4-full.log`에 보존했다. 베어러 CSRF 예외의 공식 근거는 Spring Security 리소스 서버 설정에서 확인했다. [Spring Security 리소스 서버 소스](https://github.com/spring-projects/spring-security/blob/7.1.0/config/src/main/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/resource/OAuth2ResourceServerConfigurer.java)

테스트는 격리 H2 2.5.250과 MySQL Testcontainers를 사용했다. 테스트 JVM에만 외부 연결 없는 가짜 콜백·Origin·메일 설정을 전달했다. 운영·개발 DB와 Docker 데이터 볼륨에 적용한 변경은 없다. Notice ERD는 공식 JSON 스키마와 공식 파서의 읽기·재직렬화로 검증했다.

### 변경 파일

- `build.gradle` 및 `modules/notice/application/build.gradle`, `modules/notice/adapter-web/build.gradle`
- `modules/notice/contract/src/main/java/kr/nospo/notice/contract/NoticeSort.java`
- `modules/notice/application/src/main/java/kr/nospo/notice/application/`의 `NoticeStore.java`, `NoticeCursor.java`, `PublicNoticeService.java`, `AdminNoticeCursor.java`, `AdminNoticeService.java`
- `modules/notice/adapter-persistence/src/main/java/kr/nospo/notice/persistence/JdbcNoticeStore.java`
- `modules/notice/adapter-web/src/main/java/kr/nospo/notice/web/`의 `AdminNoticeController.java`, `AdminNoticeInput.java`, `PublicNoticeController.java`, `NoticeRequest.java`
- `src/main/java/kr/nospo/nospo_backend/config/SecurityConfig.java`
- `src/main/resources/db/migration/V10__notice_soft_delete.sql`, `docs/NOSPO_Notice.erd.json`
- `src/test/java/kr/nospo/nospo_backend/`의 `ConsentHistoryTests.java`, `MySqlNoticeMigrationTests.java`, `PublicNoticeTests.java`, `MySqlPublicNoticeTests.java`
- `docs/NOSPO_Notice_Development.md`, `docs/Terms_Reconsent_Analysis.md`

### Git 상태와 영향 범위

시작·종료 브랜치는 `feat/notice`, HEAD는 `b4d142f613f07f9c3b9fc7b065d40657a92f9d58`이다. 로컬 `origin/identity`와 앞섬·뒤처짐은 각각 0개다. 기존 `.gitignore`와 1~3단계 작업을 보존했고, 변경 파일은 수정 또는 미추적 상태로 남겼다. 스테이징·커밋·푸시는 수행하지 않았다.

이번 변경은 Notice 범위의 DB 조회·쓰기와 관리자 공지 보안에 한정된다. 기존 Identity 업무 코드와 스키마, V1~V9, 인증 ERD 및 원본 기능/API v2.1에 적용한 변경은 없다. 실제 운영·개발 DB와 배포 환경은 미검증이다.

## 5단계 — ERD 경로 정리와 공지 이미지 저장소 검토

공지 ERD를 `docs/NOSPO_Notice.erd.json`에서 `erd/NOSPO_Notice.erd.json`으로 이동했다. ERD 내용, 인증 ERD, Flyway V1~V10을 보존했다. 이 문서의 ERD 링크와 `MySqlNoticeMigrationTests`의 파일 경로를 갱신했다. 앞 단계의 변경 파일 목록에는 당시 경로를 남겼다.

### 기존 저장 구조와 결정

회원 프로필은 `PUT /api/v1/me/profile-image`의 multipart `file`로 업로드한다. Spring MVC 설정은 파일 5 MiB, 요청 6 MiB다. `ImageIoProfileCodec`은 선언 MIME이나 확장자 대신 ImageIO 판독 형식으로 JPEG·PNG·WebP를 확인하고, 애니메이션을 차단하며 각 축이 최대 4096인 이미지를 PNG로 재인코딩한다. WebP에는 TwelveMonkeys ImageIO 플러그인을 사용한다.

`ProfileStore`의 JDBC 구현은 회원당 PNG 한 장을 `member_profile_image.png_data`의 `MEDIUMBLOB`에 저장한다. 갱신은 기존 행을 교체하고 명시적 삭제는 행과 회원 이미지 참조를 제거한다. 탈퇴 시 이미지를 복구 자료에 보관하고 활성 행을 삭제하며, 복구 시 복원한다. DB가 보존되면 서버 재시작과 재배포 후에도 읽을 수 있는 구조다. 조회 URL은 ACTIVE 회원의 JWT와 유효 세션으로 접근하는 상대 경로 `GET /api/v1/me/profile-image`다.

저장소 B로 판단해 공지 이미지 API 구현을 보류했다. 확인한 저장소 코드, Gradle 의존성 및 설정에는 재사용 가능한 범용 파일 어댑터, 객체 스토리지나 별도 파일 서버 연동, 공개 이미지 제공 설정이 없었다. 배포 환경의 영속 저장소와 독립 테스트 저장 위치도 확인되지 않았다. 회원별 DB 저장과 인증 기반 조회를 공지 이미지 저장에 도입하지 않았다.

### 차후 구현안

- `POST /api/v1/admin/notices/images`에 `multipart/form-data`의 `file` 한 개를 받는 안이다. 기존 관리자 공지 경로와 프로필 필드명을 따른다. 별도 정적 업로드 경로로 분류하고 `/{noticeId}` 상세 경로 및 ADMIN·CORS·CSRF 경로 매칭을 함께 검증한다.
- 유효 JWT와 세션, ACTIVE 회원, 현재 DB `member.role`의 ADMIN, 필수 약관 준수를 검사한다. 미재동의 ADMIN은 403 `TERMS_RECONSENT_REQUIRED` 정책을 적용한다. 관리자 목록에서 받은 CSRF 세션 쿠키와 `X-CSRF-TOKEN`을 허용 Origin에서 Bearer JWT와 함께 전달한다.
- 최초 허용안은 정적 JPEG·PNG와 파일 5 MiB/요청 6 MiB다. 확장자·선언 MIME·시그니처·디코딩 결과가 일치하는지 확인하고 SVG, 애니메이션, 위장 파일을 거부한다. 입력 크기, 픽셀 수, 동시 디코딩, 재인코딩 출력 크기를 제한한다. 원본 파일명을 키나 경로에 사용하지 않고 서버 생성 키와 덮어쓰기 방지 기능을 쓴다.
- 저장 성공 시 201 `data/meta`에 불투명한 `imageId`를 반환하고, 접근 모델 확정 후 실제로 제공되는 조회 URL을 포함할 수 있다. 오류는 `error/meta`를 사용한다. 후보는 400 `INVALID_REQUEST`(누락·빈 파일·잘못된 multipart), 413 `IMAGE_TOO_LARGE`, 415(요청 Content-Type 불일치), 422 `INVALID_IMAGE`, 503(저장소 일시 장애)다. 415·503의 오류 코드 및 최종 계약은 구현 시 확정한다. 응답과 로그에서 내부 경로와 자격 증명을 제외한다.
- 업로드는 독립 자원 생성으로 제한한다. 공지 화면에 표시하려면 이미지 식별자 연결 및 읽기 계약, 게시 전 접근, 공개 전환, 비공개·삭제 후 조회와 캐시 정책을 별도로 정해야 한다. 업로드 단계에서 본문 삽입이나 첨부 관계 생성을 수행하지 않는다.
- 팀에서 운영 영속 저장소와 독립 테스트 위치, 접근 방식(공개 CDN 또는 권한을 검증하는 조회), 보존 기간, 미사용 판단 근거, 속도·용량 제한을 정한다. 저장 후 응답·메타데이터 처리 실패에는 재시도 중복과 고아 객체를 고려해 보상 삭제 또는 재조정을 설계한다. 미사용 객체는 유예 기간과 참조 확인을 거쳐 정리한다. Notice Soft Delete에 객체 삭제를 연동하려면 별도 합의가 필요하다. 업로드 상태나 연결 관계의 영속화가 필요한 경우 신규 마이그레이션을 검토한다.

기존 공지 7개 API, 고정 우선 공개 정렬과 생성 시각 기반 관리자 정렬, 커서, 최초 게시 시각, 논리 삭제, JWT·현재 역할·재동의·CSRF에 기능 변경은 없다. 팀의 Notice 예외는 v2.1 공통 관리자 감사 요구와 구분된다. Notice는 전용 감사 테이블과 변경 사유 없이 `deleted_at`을 사용하며 동일 필드는 마지막 갱신이 우선한다. 공식 원본은 그대로 유지했다. H2 Refresh·탈퇴 잠금 경합은 별도 Known Issue다.

### 검증

이동 전후 ERD SHA-256이 일치하고 UTF-8 JSON과 현재 링크를 확인했다. MySQL 8.4.7 Testcontainers의 `MySqlNoticeMigrationTests` 1/1이 통과했다(실패·오류·스킵 0). 테스트는 Spring 앱을 띄우지 않고 컨테이너가 제공한 JDBC 접속값을 Flyway에 직접 주입하므로 `.env`의 DB 설정을 사용하지 않는다. V8→V9→V10 적용, 기존 행 보존, 새 위치의 ERD와 스키마 정합성을 확인했다. 개발·운영 DB, 파일 저장소, SMTP, Kakao 호출은 없었다. 기능 코드 변경이 없어 H2, 기타 MySQL, 단위 및 전체 회귀를 재실행하지 않았다. 위 4단계 전체 1405/1405는 이전 실행 기록이다.

## 6단계 — 관리자 공지 이미지 업로드

팀 최종 합의에 따라 이미지 업로드와 결과 반환만 제공한다. 5단계에서는 범용 저장소, 접근 방식, 영속 배포 구성을 확인하지 못해 구현을 보류했다. 6단계에서도 재사용 가능한 범용 저장소는 확인되지 않았다. 프로필의 ImageIO 재인코딩과 MySQL 저장 패턴을 참고해 독립적인 `notice_image` 테이블을 추가했다. Notice 경로는 JDK의 정적 JPEG·PNG 판독만 사용한다. 회원 프로필의 TwelveMonkeys WebP 지원과 저장 경로는 기존 구현을 유지한다.

### 요청과 응답

`POST /api/v1/admin/notices/images`에 `multipart/form-data`의 `file` 한 개를 전송한다. 허용 Origin에서 Bearer JWT와 관리자 목록 GET으로 발급받은 CSRF 세션 쿠키 및 `X-CSRF-TOKEN`을 함께 전달한다. JWT 서명·만료와 세션, ACTIVE 상태, 현재 DB `member.role`의 ADMIN, 필수 약관 재동의를 기존 관리자 공지와 같은 검사로 처리한다. 업로드는 관리자 공지 전용 CSRF 필터가 헤더를 검사한 뒤 MVC에서 multipart를 읽고 디코딩한다.

MySQL 커밋 뒤 201을 반환한다. `data.imageId`는 새 UUID이며 `meta.requestId`는 기존 공통 추적 ID다.

```json
{"data":{"imageId":"550e8400-e29b-41d4-a716-446655440000"},"meta":{"requestId":"00000000-0000-0000-0000-000000000001"}}
```

| 상태 | 공통 오류 코드 |
| --- | --- |
| 400 | `INVALID_REQUEST` — 빈 파일, 누락·중복 파일, 잘못된 multipart 또는 요청 Content-Type |
| 401 | `INVALID_CREDENTIALS` — 미인증, JWT 만료, 세션 폐기 등 |
| 403 | `FORBIDDEN`, `TERMS_RECONSENT_REQUIRED`, `ORIGIN_NOT_ALLOWED` |
| 413 | `IMAGE_TOO_LARGE` — 파일·요청·PNG 출력 한도 초과 |
| 422 | `INVALID_IMAGE`, `INVALID_IMAGE_DIMENSIONS`, `ANIMATED_IMAGE_NOT_ALLOWED` |
| 409 | `REQUEST_IN_PROGRESS` — 기존 공통 DB 경합 처리 |
| 500 | `INTERNAL_ERROR` — 저장 등 서버 오류 |
| 503 | `SERVICE_UNAVAILABLE` — 인스턴스의 동시 처리 슬롯 소진 |

오류는 공통 `error/meta`로 반환한다. 이미지 ID는 저장 완료 확인용이다. 조회 URL, 다운로드·목록·삭제 API, 표시·첨부 관계와 Markdown 자동 삽입은 제공 범위에 포함되지 않는다.

### 검증과 영속성

파일 최대 5 MiB, multipart 요청 최대 6 MiB, 축별 최대 4096픽셀과 전체 8,388,608픽셀 한도를 적용한다. 확장자와 선언 MIME, 파일 시그니처 및 ImageIO 디코딩을 확인한다. PNG 청크 CRC와 종결 위치를 검증하고 APNG 청크, SVG·WebP 등 다른 형식과 이미지로 위장한 파일을 거부한다. 원본 메타데이터를 제외한 픽셀을 PNG로 다시 쓰며 출력은 최대 16 MiB−1이다. 인스턴스당 동시 읽기·디코딩·저장 처리는 두 건으로 제한한다. 원본 파일명은 형식 확인에만 사용한다.

`V11__notice_image.sql`은 `image_id BINARY(16)` PK, `png_data MEDIUMBLOB NOT NULL`, `created_at DATETIME(6) NOT NULL` 및 빈 바이너리 방지 CHECK를 추가한다. PNG 형식이 고정되어 MIME 컬럼이 필요 없다. 서버가 UUID를 발급하고 단일 트랜잭션에서 INSERT한다. 키 중복 시 기존 행의 덮어쓰기를 금지하며 저장 중 예외는 롤백한다. 응답 반환 전에 저장을 완료한다. 기존 V1~V10과 테이블은 보존했고 `erd/NOSPO_Notice.erd.json`에 독립 테이블을 추가했다.

운영 DB에 적용하기 전에 MySQL 패킷 상한과 DB 용량·백업 증가량을 확인해야 한다. 첨부 참조와 정리 정책이 없으므로 업로드 이미지가 장기 보존될 수 있다. 네트워크 오류 뒤 재시도는 새 ID를 발급하므로 중복 데이터가 생길 수 있다. 업로드 속도 제한, 용량 감시와 보존 기준은 운영 과제다. 기존 H2 Refresh·탈퇴 잠금 경합은 별도 Known Issue다.

### 테스트 결과

선별 검증은 30/30으로 H2 14/14, MySQL Testcontainers 16/16이 통과했다. 실패·오류·스킵은 0건이다. 최초 컴파일에서 CSRF 핸들러의 final 클래스 상속을 합성으로 수정했다. 이후 선별 첫 실행에서 미인증 업로드가 공통 CSRF에서 먼저 403이 되는 문제와 저장 시각 테스트의 자료형 불일치로 4건이 실패했다. 업로드 경로가 관리자 공지 전용 CSRF 검사를 사용하도록 정리하고 시각 비교를 수정한 뒤 30건을 다시 확인했다.

H2 2.5.250 테스트 프로필과 MySQL 8.4.7 임시 컨테이너를 사용했다. 테스트 전 외부 `env.properties`와 상속 설정 여부를 확인했으며, 클래스패스 프로필 및 인메모리 대체값으로 격리했다. 각 MySQL 테스트가 컨테이너 URL과 자격 증명을 DataSource에 공급한다. `.env`의 실제 DB 설정을 사용하지 않았으며 개발·운영 DB 접속이나 Flyway 적용, 실제 SMTP·Kakao 호출, 외부 파일 저장소 사용은 없었다. 공식 ERD Editor 5.1.0 파서의 읽기·재직렬화, 공식 JSON 스키마, MySQL의 신규 컬럼·PK·CHECK를 검증했다. [ERD Editor JSON 스키마 문서](https://docs.erd-editor.io/docs/api/advanced/schema/)

최종 전체 Gradle 회귀는 한 차례 실행해 1413/1413을 통과했다. H2 625/625, MySQL Testcontainers 751/751, DB 비의존 단위 37/37이며 실패·오류·스킵은 각각 0건이다. 기존 Notice 7개 API와 Identity 회귀를 포함한다. 4단계의 전체 1405/1405는 이전 실행 기록이다. 선별·전체 실행 로그는 각각 `build/notice-phase6-selected.log`, `build/notice-phase6-full.log`에 남겼다.
