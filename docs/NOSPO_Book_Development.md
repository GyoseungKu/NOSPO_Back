# NOSPO 도서 누적 개발 기록

## Book 1단계 · 도서 검색과 판본 조회

기능·API v2.1 제1부와 제2부 §2·§7을 적용했다. §8의 진도·독서 기록·홈에서 참조할 판본 계약을 공개했다. 시작 브랜치는 `feat/reading`, HEAD는 `9a4c4dbfd5d33ffb7b4c98b0845dd5ec587fddb3`이다. 원격 main HEAD와 일치했고 시작 작업 트리는 깨끗했다. 원격에 공개된 다른 브랜치와 저장소에서 기존 Book 구현, V11 이후 마이그레이션, 확정된 도서 제공자 설정 및 웹소설 메타데이터를 찾지 못했다. 아직 공개되지 않은 팀원 작업은 병합 조율 때 확인해야 한다.

### 구현된 API

모든 경로의 접두사는 `/api/v1`이다.

| 메서드와 경로 | 동작 및 결과 |
| --- | --- |
| GET /books/search | 공개. 필수 `query` 1~100자 및 `type` TITLE/AUTHOR/ISBN. 선택 `bookType` BOOK/WEB_NOVEL, `cursor`, `limit`. 200 `Page<BookSummary>`. 일반 도서를 포함하는 요청은 제공자 미연결 시 503 |
| GET /books/{bookId} | 공개. 저장된 판본을 200 `Book`으로 반환 |
| GET /books/{bookId}/structure | 공개. 웹소설의 전역 회차를 200 `Page<Episode>`로 반환. BOOK은 409 `BOOK_TYPE_MISMATCH` |
| GET /me/search-history | 본인. 최신 검색순 200 `Page<SearchHistory>` |
| DELETE /me/search-history/{historyId} | 본인. 삭제 시 204. 누락 및 타인 소유는 404 |
| DELETE /me/search-history | 본인. 전체 삭제 시 204 |

목록 limit 기본값은 20이고 허용 범위는 1~100이다. 검색·회차·이력별 커서는 기존 Secrets의 HMAC과 암호화를 사용한다. 검색 커서는 검색어·유형·도서 유형 및 외부 페이지 적용 시 제공자에, 회차 커서는 판본에, 이력 커서는 회원에 결합한다. 웹소설 검색은 `bookId` 오름차순, 회차는 전역 번호 오름차순, 이력은 시각·ID 내림차순 키셋이다. 도서 유형을 생략하면 제공자 결과 뒤에 웹소설을 이어 붙인다.

성공은 `data/meta`, 오류는 `error/meta`이며 빈 조회 결과는 200과 빈 items, null nextCursor, false hasNext를 반환한다. 잘못된 파라미터·UUID는 400 `INVALID_REQUEST`, 커서가 일치하지 않으면 400 `INVALID_CURSOR`, 범위 위반은 422 `VALIDATION_ERROR`, 존재하지 않는 리소스는 404 `RESOURCE_NOT_FOUND`, 제공자 장애 또는 미연결은 503 `BOOK_PROVIDER_UNAVAILABLE`이다. UUID는 표준 문자열, 이력 시각은 UTC ISO 8601, 출판일은 YYYY-MM-DD다. 명세의 미상 필드와 출처가 제공하지 않은 소개는 null이며, 미상 저자는 빈 배열로 표현한다.

### 데이터와 모듈

| 모듈 | 책임 |
| --- | --- |
| `book:contract` | API 모델, 내부 판본을 읽는 `BookCatalog.find(UUID)` |
| `book:application` | 조회·제공자 포트·커서·검색 이력·웹소설 import |
| `book:adapter-persistence` | JDBC로 판본/회차/저자/이력 저장 및 조회 |
| `book:adapter-web` | HTTP 요청·응답, JWT principal, OpenAPI |

[V12](../src/main/resources/db/migration/V12__book_catalog.sql)는 `book_work`(확인된 작품의 연결 키), `book_edition`(판본과 출처 서지), `book_author`(저자 표시 순서), `book_episode`(회차 구성), `book_search_history`(회원별 검색)를 생성한다. 식별자는 BINARY(16), 검색 시각은 UTC DATETIME(6)이다. 도서 유형·ISBN의 조회 인덱스, 판본·회차 순서 유일 키, 회원·최신 시각 인덱스를 사용한다. 제목·저자 부분 일치는 평문 검색을 하며 목록은 페이지 크기로 제한한다. [도서 ERD](../erd/NOSPO_Book.erd.json)에서 회원은 Identity 소유 외부 테이블의 PK만 표시한다.

`bookId`는 Book이 판본 저장 시 발급하는 UUID다. 외부 판본의 고유 키 `(source, source_item_id)`를 유일하게 관리하고, 동시 요청이나 재검색에서도 저장된 UUID를 재사용한다. `source_item_id`는 제공자가 보장하는 **판본** 키의 UTF-8 바이트를 저장한다. 같은 제목이나 ISBN을 기준으로 판본이나 작품을 자동 연결하지 않는다. 최초 저장된 판본 서지를 재사용하며 서지 갱신 정책은 제공자 계약 확정 후 결정한다.

서재·진도·위키·AI는 `book_edition.book_id`의 UUID 또는 `BookCatalog.find(bookId)`를 참조한다. 조회 위치는 BOOK에서 PAGE, WEB_NOVEL에서 EPISODE다. 권·챕터는 표시용 레이블이다. `workId`는 선택적인 작품 연결 정보이며 판본마다 별도인 진도·위키·AI의 식별자로 사용하지 않는다. 내부 ID 발급과 외부 ID 매핑의 소유권은 Book에 있다.

### 접근 및 검색 기록

세 공개 GET은 기존 공개 요청 matcher에 추가했다. 공개 검색에는 기존 재동의 예외가 적용되며, 회원 전용 이력에는 기존 인증·활성 세션·재동의 정책을 적용한다. 성공한 검색만 기록하며 익명 요청은 기록하지 않는다. 정규화는 NFC·공백 정리·대소문자 무시를 적용하며 ISBN은 하이픈과 공백도 무시한다. 회원 락으로 갱신·삭제를 직렬화하며 같은 유형·정규화 키의 ID를 유지하고 가장 최근 100개를 보존한다. 모든 삭제는 소유자 조건을 적용하고, 개인 응답은 기존 `Cache-Control: no-store`를 사용한다.

탈퇴 보존 기간에는 기존 비활성 상태와 세션 차단으로 접근이 막힌다. 기간 내 기존 복구 절차로 활성화된 회원에게 보존된 이력이 다시 보인다. 최종 파기에서 기존 회원 행 삭제와 함께 FK CASCADE로 정리된다. 탈퇴 시 즉시 검색 기록을 지우는 별도 정책이 필요하면 Identity와 보존 정책을 정한 뒤 반영할 수 있다.

### 제공자·웹소설 데이터

일반 도서의 실제 HTTP 어댑터는 제공자와 키가 준비된 단계에 연결한다. `BookSearchProvider`는 판본 단위로 안정적인 키와 서지, 제한된 페이지, 다음 페이지 값, 장애 신호를 받는 포트다. 어댑터에서 판본 키를 확정할 수 없는 자료의 자동 병합을 피하고, 인증 정보의 환경변수 주입과 연결·읽기 타임아웃을 적용해야 한다. 자동화는 Mockito로 동작을 검증한다.

팀 웹소설 파일이 준비되면 승인된 import 작업에서 `WebNovelImport.importEdition(Novel)`에 아래 형식을 매핑한다. 한 판본을 트랜잭션으로 처리하며 같은 데이터 재입력 시 판본·회차 UUID를 유지한다. 새 회차를 추가하고 기존 회차의 레이블을 보정할 수 있다. 판본의 최초 작품 연결 및 총 분량을 바꾸려면 별도 정정 정책이 필요하다. 시작 시 자동 적재는 없으며 운영 DB에 입력한 데이터는 없다. 아래는 계약 설명용 합성 입력이다.

```json
{
  "workId": null,
  "edition": {
    "sourceItemId": "fixture:edition-1",
    "title": "테스트 판본",
    "authors": [],
    "publisher": null,
    "editionLabel": null,
    "isbn": null,
    "coverUrl": null,
    "description": null,
    "totalUnits": null,
    "publicationDate": null
  },
  "episodes": [
    { "sequence": 1, "episodeLabel": "1화", "volumeLabel": null, "chapterLabel": null }
  ]
}
```

양의 sequence를 판본 내 작품 전체 순서로 부여하며 같은 번호를 중복 입력할 수 없다. 알려진 총 화 수를 초과할 수 없고 콘텐츠 본문은 수집·저장하지 않는다.

### 검증과 후속 단계

Book 검증은 H2 API 7건, MySQL Testcontainers API 7건, MySQL 증분 마이그레이션·ERD 1건이 최종 통과했다. MySQL API 테스트는 빈 DB에 V1~V12를 적용했다. 테스트 로그의 JDBC 대상을 H2 메모리 및 MySQL 컨테이너의 로컬 포트로 확인했다. 선별 회귀 결과와 변경 파일 전체 목록은 [Book 1단계 결과 보고서](Terms_Reconsent_Analysis.md)에 기록했다.

실제 도서 제공자 연동, 승인된 웹소설 데이터 적재 및 서재·진도·독서 기록·홈 API는 후속 범위다. 서재는 회원·판본 중복 방지, 단위·총량 검증, 삭제 후 최고 진도 보존을 적용한다. 이후 진도 ETag 및 기록 원자성, PAGE·EPISODE 별도 통계와 홈 조회를 연결하고 전체 회귀를 실행한다. 기존 H2 Refresh·탈퇴 간헐 잠금 경합은 별도 Known Issue로 추적한다.

## Book 1.5단계 · 카카오 Daum 책 검색

`book:adapter-integration`에 `KakaoBookSearchProvider`를 추가했다. 기존 `BookSearchProvider`, `BookService`, 검색·상세 API, 응답 모델, HMAC 커서와 웹소설·검색 기록 흐름을 사용한다. API v2.1에는 제공자별 페이지와 서지 필드를 노출하지 않는다.

### 요청·응답과 장애

- 기존 `GET /api/v1/books/search` 내 BOOK 요청은 `GET https://dapi.kakao.com/v3/search/book`에 연결한다. TITLE→title, AUTHOR→person, ISBN→isbn이며 query는 UTF-8 URL 인코딩, sort는 accuracy다. OAuth의 `client_id`는 앱 REST API 키이므로 기존 `nospo.auth.kakao.client-id`에 연결된 `KAKAO_CLIENT_ID`를 공유한다. 책 검색은 `Authorization: KakaoAK {KAKAO_CLIENT_ID}`를 사용한다. `KAKAO_CLIENT_SECRET`은 OAuth 토큰 요청 전용이다. [카카오 로그인 REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api) · [카카오 Daum 검색 REST API](https://developers.kakao.com/docs/ko/daum-search/dev-guide#search-book)
- `meta.total_count`, `pageable_count`, `is_end` 및 documents 배열을 검증한다. title/authors/publisher는 대응 필드로, contents는 description으로, thumbnail은 coverUrl로, datetime은 응답 오프셋 기준의 출판일로 매핑한다. translators, url, 가격, status는 v2.1 응답에 포함하지 않는다. workId, editionLabel, totalUnits는 null이며 positionUnit은 PAGE다.
- HTTPS 호스트·경로 고정, 연결 2초, 읽기 3초, 응답 최대 1 MiB, 리디렉션 차단을 적용했다. 애플리케이션 수준의 재시도는 없다. 인증 401/403, 쿼터 429, 5xx, 타임아웃 및 비정상 JSON은 `503 BOOK_PROVIDER_UNAVAILABLE`을 반환한다. 내부 로그에는 상태나 예외 유형만 남긴다. 키가 비어 있으면 HTTP 요청 없이 503이다. WEB_NOVEL 지정 검색은 기존 내부 검색을 사용한다.

### 판본 식별과 저장 범위

ISBN10 및 978/979 ISBN13의 형식·체크디지트를 확인한다. 유효한 ISBN10은 ISBN13으로 환산한다. 유효한 ISBN13과 동일한 ISBN10은 한 판본으로 인식하고 서로 다른 유효 번호가 한 레코드에 있거나 쓸 수 있는 번호가 없으면 결과에서 제외한다. 동일 제목이라도 ISBN이 다르면 별도 판본이다. 표준 ISBN13을 `sourceItemId`, `KAKAO_DAUM`을 source로 사용한다.

카카오 운영정책 제5조는 사용자 환경 개선 목적의 최신 캐시를 전제로 한다. 카카오 공식 답변은 자체 DB 구축을 위한 영구 보관과 일시 캐시를 구분하며, 별도 답변에서 사용자가 선택한 일부 정보의 저장 가능성을 안내했다. 이번 단계에서는 검색 목록의 일괄 영구 저장과 NOSPO의 선택 저장 범위 확정을 보류했다. [카카오 운영정책](https://developers.kakao.com/terms/ko/site-policies) · [영구 저장 및 일시 캐시에 대한 답변](https://devtalk.kakao.com/t/api/151763) · [선택한 정보 일부 저장에 대한 답변](https://devtalk.kakao.com/t/api/152911)

공개 검색에는 source와 ISBN의 HMAC에서 도출한 임시 UUID를 `bookId`로 반환한다. 같은 비밀키와 판본이면 반복·동시 검색에서도 같은 값이다. 상세는 동일 인스턴스의 메모리 캐시에서 최대 5분간 조회할 수 있다. 캐시 만료·축출·재시작 후에는 404를 반환하며 재검색하면 같은 UUID로 다시 조회할 수 있다. 영구 판본 조회 포트 `BookCatalog.find`는 DB 판본만 제공한다. Wiki·AI·서재에서 영구 참조할 판본에는 계속 V12의 DB 발급 UUID를 사용한다. Kakao 응답은 DB에 기록하지 않으며 신규 Flyway는 없다.

### 페이지와 운영 한계

최초 요청에서 Kakao size를 min(limit, 50)으로 고정하고 page 1~50과 `is_end`를 사용한다. 요청당 최대 2회만 호출한다. 처리하지 못한 공급자 응답 부분과 반환한 ISBN을 최대 5분간(128개 상태) 보관해 다음 요청에서 이어 읽고 동일 ISBN을 제외한다. 반환 가능한 결과가 적으면 limit보다 짧거나 빈 페이지에도 다음 커서가 있을 수 있다. 카카오 결과가 소진되면 유형 미지정 검색은 기존 웹소설 단계로 이어진다.

외부 커서는 기존 HMAC·암호화로 query/type/bookType에 결합된다. 변조·필터 변경·만료·캐시 축출은 400이다. 정렬 중 카카오 데이터가 바뀌면 새 결과의 누락 가능성이 있고 다중 인스턴스에서는 검색 커서와 상세 요청을 동일 인스턴스로 라우팅해야 한다. 실제 키 발급 후 인증·쿼터·실제 응답을 별도로 확인하고 개인 서재에 저장할 최소 필드와 판본 승격 규칙을 확정한다.

루프백 HTTP 20건, Book H2 9건, Book MySQL 9건을 포함해 선별 86건이 통과했다. 실패·오류·스킵은 각 0건이다. 자세한 결과와 파일 목록은 [Book 1.5단계 결과 보고서](Terms_Reconsent_Analysis.md)에 기록했다.

공유 REST API 키 설정 수정 후 책 검색 22건과 기존 카카오 로그인 73건을 별도로 실행했다. 총 95건이 통과했고 실패·오류·스킵은 각 0건이다.

## Book 2단계 — 개인 서재

### API와 접근 제어

| 메서드 | 경로 | 성공 응답 |
| --- | --- | --- |
| GET | `/api/v1/me/shelf` | 200 Page<ShelfItem> |
| POST | `/api/v1/me/shelf` | 201 ShelfItem |
| GET | `/api/v1/me/shelf/{bookId}` | 200 ShelfItem |
| DELETE | `/api/v1/me/shelf/{bookId}` | 204 빈 본문 |

등록 요청은 `{"bookId":"UUID","position":{"unit":"PAGE","value":20}}` 형식이다. 웹소설에는 EPISODE를 사용한다. 값은 0 이상의 정수이며 판본의 알려진 총량을 초과하면 422 INVALID_POSITION이다. 요청 소유자는 기존 JWT의 인증된 회원이다. 활성 세션·ACTIVE 상태·필수 약관 재동의·Origin 정책을 사용하고 기존 Spring Security 설정은 그대로 적용한다. 미등록·타인 소유·삭제 후 조회나 반복 삭제는 404 RESOURCE_NOT_FOUND, 중복 등록은 409 SHELF_ALREADY_EXISTS다. Swagger에 요청 예시, ShelfItem, Bearer와 응답 상태를 추가했다.

### 저장과 재등록

V13의 `book_shelf`는 활성 연결과 addedAt, `book_progress`는 현재 지점·최고 지점·마지막 진도 변경 시각·revision, `book_reading_record`는 현재 지점의 이동과 집계 증가분을 저장한다. 진행 및 활성 연결은 회원과 판본 복합 PK를 사용한다. 기록과 연결은 진도의 복합 FK를 참조하며 공용 판본의 수명은 회원과 독립적이다. UUID는 BINARY(16), 시각은 UTC DATETIME(6)이다.

최초 등록은 current와 highest를 입력값으로 초기화하고 revision=0으로 시작한다. 독서 기록을 생성하지 않는다. 제거는 활성 연결 행을 삭제한다. 재등록은 addedAt을 다시 부여하고, 현재 지점은 요청값, 최고 지점은 기존 최고와 요청값 중 큰 값으로 저장한다. 실제 이동이 있으면 lastProgressAt을 갱신한다. 재등록 때 revision은 1 증가한다. 이전 최고를 초과할 때만 기존 current와 새 current 및 `새 current - 기존 최고`를 한 번 기록한다. 모든 쓰기는 기존 회원 행을 먼저 잠그고 활성 세션을 재확인한 뒤 READ COMMITTED 트랜잭션 한 건에서 실행한다.

탈퇴 직후 기존 인증 정책이 접근을 차단한다. 사용자 요청에 따른 기존 30일 복구 정책으로 서재·진도·기록을 보존하고, 최종 파기의 회원 행 삭제가 연쇄 정리한다. 기존 Identity 파기 코드는 그대로 적용한다. 명세서 제1부의 즉시 삭제 문구와 현재 배포된 복구 정책은 차이가 있으며 이번 단계는 작업 지시의 30일 복구 정책을 적용했다.

### 조회와 카카오 판본

기본 RECENT_ACTIVITY는 lastProgressAt이 있으면 해당 값, 없으면 addedAt을 사용한 내림차순이다. RECENT_ADDED는 addedAt 내림차순이다. TITLE은 제목 UTF-8 바이트 기준 오름차순이며 대소문자와 언어별 조합을 구분한다. UUID는 주 정렬 방향과 같은 최종 비교 키다. 목록 조회는 키셋과 고정 횟수의 일괄 카탈로그 조회를 사용한다. 커서는 15분간 유효하고 회원·필터·정렬·limit에 결합한다. 페이지 간 목록 변경으로 순서가 이동할 수 있다. 웹소설의 현재 회차는 회차 메타데이터에서 조회하며, 0 또는 메타데이터가 없는 번호의 레이블은 null이다. 백분율은 현재 지점과 알려진 총량으로 계산하며 반올림은 소수 두 자리다. 총량 미상은 null, 총량이 0이면 0이다.

등록은 먼저 영구 `BookCatalog`에서 판본을 확인한다. 캐시의 카카오 임시 ID는 `KAKAO_DAUM`과 검증된 ISBN 출처 키가 일치하고 같은 키의 BOOK 판본이 DB에 이미 있을 때 해당 영구 ID로만 연결한다. 임시 UUID를 FK에 쓰거나 카카오 응답을 영구 저장하는 흐름은 없다. 승인된 판본 부재, 캐시 만료 및 재시작으로 매핑할 수 없는 요청은 404다. 카카오 안내는 검색 결과의 영구 수집과 일부 사용자 선택 정보 저장을 구분한다. NOSPO의 허용 필드·보관·재표시 범위가 확정되기 전까지 신규 영구 승격은 보류한다. [카카오 운영정책](https://developers.kakao.com/terms/ko/site-policies) · [일시 캐시 안내](https://devtalk.kakao.com/t/api/151763) · [선택 정보 안내](https://devtalk.kakao.com/t/api/152911)

### 다음 단계

Book 3단계는 같은 회원 행 잠금 순서와 ShelfStore의 progress 및 record 연산을 재사용한다. 진도 수정에서는 활성 연결을 확인하고 revision의 ETag 비교와 갱신을 한 트랜잭션에 묶는다. 독서량은 기록의 added_units를 PAGE와 EPISODE로 나누며 UTC recorded_at을 Asia/Seoul 날짜 범위로 집계한다. 진도 수정·기록 조회·통계·홈 API는 후속 단계다. 기존 H2 Refresh·탈퇴 경합은 Known Issue로 추적한다. 검증 내역은 [Book 2단계 결과 보고서](Terms_Reconsent_Analysis.md)에 기록한다.

최종 선별 테스트 178건이 통과했다. 실패·오류·스킵은 각각 0건이다. H2 서재 9건, MySQL 서재 9건, 증분 마이그레이션·ERD와 실제 파기 서비스 검증 각각 1건을 포함했다.

## Book 2.5단계 — 카카오 선택 도서의 서재 등록

`POST /api/v1/me/shelf`의 v2.1 요청·응답을 유지했다. `ShelfEditionRegistration`이 먼저 영구 `BookCatalog`를 조회하고, 없으면 유효한 임시 캐시를 조회한다. 카카오 출처, BOOK/PAGE, ISBN 체크디지트 및 ISBN13 출처 키 일치, 제목 존재를 확인한다. 승인 판본 조회는 V12의 `(source, source_item_id)`를 사용한다. 이미 저장된 ISBN과 유형 또는 출처가 충돌하면 404다. 동일 ISBN이라도 다른 제공자 레코드로 추정 연결하지 않는다. 카카오 응답에서 제목이 바뀌어도 기존 승인 DB 레코드가 우선한다.

### 저장 정책 판단

| 확인 상태 | 근거와 적용 범위 |
| --- | --- |
| 명시적으로 안내됨 | 사용자 검색 질의에 대한 응답 표시와 사용자 환경 개선을 위한 일시 캐시. 최신성을 유지해야 한다. [REST API](https://developers.kakao.com/docs/ko/daum-search/dev-guide#search-book) · [운영정책](https://developers.kakao.com/terms/ko/site-policies) · [카카오 답변](https://devtalk.kakao.com/t/api/151763) |
| 조건부로 안내됨 | 사용자가 선택한 일부 정보를 서비스 기능에 사용하거나 저장하는 사례. 공용 레코드·개인 책장 유사 사례에서 재접근 시 갱신하는 캐시를 안내했고, 검색 대체와 카카오 데이터의 AI 질의 사용은 해당 답변의 허용 범위에서 제외했다. [선택 정보 답변](https://devtalk.kakao.com/t/api/152911) · [공용 레코드 사례 답변](https://devtalk.kakao.com/t/bookflow/152494) |
| NOSPO 적용 미확정 | ISBN, 제목, 저자, 출판사, 표지 URL, 출간일, 출처별 장기 저장 범위와 여러 회원 공유·공개 상세 재표시·최종 사용 후 보존 기간. NOSPO의 현재 공용 영구 모델 전체에 대한 허용 근거는 확인하지 못했다. |
| 금지 범위 | 검색 결과의 수집으로 자체 검색 DB를 구축하거나 검색 API를 대체·재판매하는 용도. [카카오 답변](https://devtalk.kakao.com/t/api/151763) · [공용 레코드 사례 답변](https://devtalk.kakao.com/t/bookflow/152494) |

`KakaoShelfStoragePolicy`의 운영 코드는 신규 공유 판본 생성을 차단한다. 설정만으로 변경할 수 있는 활성화 스위치는 없다. 현재 운영에서 새로 저장하는 카카오 서지 필드는 없다. 테스트에서만 승인을 대체할 수 있으며, 검증용 최소 후보는 V12가 요구하는 제목과 판본 식별용 출처·ISBN13이다. 저자·출판사·출간일·표지 URL·소개·작품 관계·판본 레이블·전체 페이지는 이 경로에서 저장하지 않는다. 원본 JSON, 목록, 상품 HTML, 이미지 바이너리 및 자체 검색 인덱스는 저장 대상에서 제외했다.

### 원자성과 연동

회원 행 잠금과 세션 재확인 이후, 판본 확인·승인된 생성 경로·서재/진도 연결을 기존 READ COMMITTED 트랜잭션에서 처리한다. 생성 경로는 기존 JDBC upsert와 V12 유일 키로 경합 시 기존 UUID를 읽고, 오류 시 판본·서재·진도를 롤백한다. 카카오 HTTP 호출은 이 트랜잭션에 없다. 최초 등록 및 재등록의 독서량·최고 진도 정책과 최종 파기 관계는 이전 단계와 같다. V1~V13, ERD, BookCatalog 공개 계약을 유지했다.

프론트는 임시 UUID를 검색 후 등록 요청에 사용하고, 201을 받으면 `data.book.bookId`로 교체한다. 이후 상세·삭제·향후 진도·위키·AI에는 영구 UUID를 사용한다. 임시 캐시는 5분, 최대 1,024개이며 인스턴스별이다. 만료·축출·재시작·다른 인스턴스 캐시 미존재 시 다시 검색한다. 카탈로그에 승인 판본이 없는 상황에서는 다시 검색해도 정책상 404가 유지된다. Swagger에 상태와 UUID 교체 규칙을 반영했다.

Book 3단계는 이미 등록 가능한 영구 판본을 기준으로 진행할 수 있다. 신규 카카오 판본 활성화는 허용 필드, 갱신 및 보존 기간, 서재 밖 재표시·공유 제한을 확정한 뒤 별도 반영한다. 기존 H2 Refresh·탈퇴 잠금 경합은 Known Issue로 유지한다.

선별 회귀 184건 통과, 실패·오류·스킵 각 0건이다. H2·MySQL 서재 테스트 각각 12건에서 운영 기본 차단, 신규 판본 후보의 테스트 승인·경합·롤백을 검증했다. 검증 내역은 [Book 2.5단계 결과 보고서](Terms_Reconsent_Analysis.md)에 기록했다.

## Book 3단계 — 독서 진도·독서 기록

| 메서드 | 경로 | 성공 |
| --- | --- | --- |
| GET | `/api/v1/me/shelf/{bookId}/progress` | 200 Progress와 ETag |
| PATCH | `/api/v1/me/shelf/{bookId}/progress` | 200 Progress, addedUnits, recordId 및 ETag |
| GET | `/api/v1/me/reading/records` | 200 Page<ReadingRecord> |

Progress는 bookId, currentPosition, highestPosition, positionContext, progressPercent, updatedAt이다. Position은 PAGE 또는 EPISODE 단위와 0 이상의 정수 값이다. 웹소설 회차 표시의 기준은 현재 지점의 실제 전역 회차 번호다. 총량을 모르면 백분율은 null이며 권·챕터는 표시용 레이블이다. updatedAt은 마지막 진도 변경 또는 최근 등록 시각 중 최신 시각이다.

### 조건부 쓰기와 기록

HTTP ETag는 명세의 revision 예시를 확장한 `"progress-{revision}-{HMAC}"` 형식이다. HMAC은 회원 UUID, 영구 판본 UUID, DB revision에 결합한다. 프론트는 GET에서 받은 값을 수정 없이 PATCH If-Match에 넣고, 412이면 최신 진도를 다시 조회한다. 헤더 누락은 428, 약한 태그·와일드카드·여러 값·잘못된 문법은 400, 이전 버전·다른 리소스·서명 불일치는 412다. 기존 허용 Origin에서 진도 경로에만 If-Match 요청과 ETag 읽기를 허용했다.

PATCH는 기존 회원 행부터 잠그고 세션을 재확인한다. 같은 READ COMMITTED 트랜잭션에서 활성 서재의 최신 revision과 If-Match 확인, 단위와 총량 검증, UPDATE와 기록 INSERT를 실행한다. 실제 이동이면 revision을 한 번 늘리고 기존 current와 새 current를 기록한다. 최고는 `max(이전 최고, 새 위치)`, 증가분은 `max(0, 새 위치 - 이전 최고)`다. 뒤로 이동과 기존 최고 이내 이동에는 0이 기록된다. 동일 위치 저장은 200과 기존 ETag 및 시각, addedUnits=0, recordId=null을 반환한다. 실패 시 진도와 기록은 함께 롤백된다.

읽기 목록은 활성 서재 연결 없이 회원 소유 기록을 조회하므로 서재 삭제 전 기록도 남는다. from/to는 YYYY-MM-DD의 Asia/Seoul 날짜이고 UTC로 환산한 시작 포함·종료 다음 날 미포함을 사용한다. 두 날짜가 있으면 양 끝 포함 최대 366일이며 한쪽만 지정하면 그쪽만 제한한다. V13의 회원·판본·시각 인덱스를 재사용해 recordedAt DESC, recordId DESC 키셋으로 읽는다. 암호화·서명 커서는 회원·날짜·판본·limit에 결합하고 15분간 유효하다. 응답 시각과 DB 시각은 UTC다.

### 연동과 후속 범위

기존 인증, 약관 재동의와 탈퇴 정책을 사용한다. 활성 링크가 없는 진도는 404이며 기록은 기존 30일 복구 보존을 거쳐 최종 파기에서 연쇄 삭제된다. V13과 ERD를 재사용했고 카카오 신규 판본 저장 차단을 유지했다. Swagger에 세 경로와 ETag/If-Match, PAGE/EPISODE 예시, 필터 및 공통 오류를 추가했다.

Wiki·AI 팀에는 `ReadingProgressCatalog.activeProgress(memberId, bookId)`를 제공한다. 해당 포트는 활성 회원과 활성 서재에 한해 현재·최고 위치 및 revision을 반환한다. 호출자가 먼저 사용자 인증·권한을 확인하고, 노출 상한은 currentPosition으로 계산한다.

Book 4단계는 동일 기록의 UTC 구간을 Asia/Seoul 날짜로 묶고 `added_units > 0`만 PAGE·EPISODE의 독서량과 activeDays에 반영한다. 홈·요약·캘린더 API와 이를 포함한 전체 회귀는 Book 4단계에서 2026-11-29까지 실행할 후속 범위다. 기존 H2 Refresh·탈퇴 잠금 경합은 Known Issue로 유지한다.

최종 선별 회귀 200건이 통과했다. 실패·오류·스킵은 각 0건이다. H2와 MySQL 서재 스위트에서 각각 진도·기록 검증 6건을 추가했다. 실제 실행 범위 및 CORS 분리 재검증은 [Book 3단계 결과 보고서](Terms_Reconsent_Analysis.md)에 기록했다.

## Book 4단계 — 홈·기간 독서량·월별 캘린더

| 경로 | 입력 | 성공 data |
| --- | --- | --- |
| GET /api/v1/me/reading/current | 없음 | item: ShelfItem 또는 null |
| GET /api/v1/me/reading/summary | from, to 필수 YYYY-MM-DD | from, to, timeZone, pages, episodes, activeDays, daily |
| GET /api/v1/me/reading/calendar | month 필수 YYYY-MM | month, timeZone, days |

세 조회는 기존 JWT·활성 세션·ACTIVE 회원·재동의 및 no-store 규칙을 적용한다. If-Match는 Book 3단계 진도 변경의 조건이다. Swagger에 요청 형식·권한·오류와 응답 구조를 추가했다.

current는 회원 본인의 활성 링크에 한정한다. SQL 정렬은 `last_progress_at DESC`, 활동 시각이 null인 후보의 `added_at DESC`, `book_id DESC`다. 활동 판본이 하나라도 있으면 최근 등록만 한 책보다 우선한다. 실제 진도 변경이 없으면 최근 등록이 선택되며 같은 시각은 UUID로 결정한다. 동일 위치 재저장은 활동 시각에 영향을 주지 않는다. 제거하면 후보에서 빠진다. 같은 위치로 재등록한 판본은 기존 활동 시각을 보존하고, 이전 활동이 없는 판본은 새 등록 시각으로 비교한다. SQL에서 한 판본만 선택하고 기존 ShelfItem 계산을 재사용한다.

summary와 calendar는 V13의 양수 added_units를 같은 일별 집계에서 계산한다. BOOK을 pages, WEB_NOVEL을 episodes로 합산하며 두 단위는 별도다. 활동을 나타내는 hasActivity는 둘 중 하나가 양수인 경우 true이고, activeDays는 그 날짜 수다. 최초 등록·재독·동일 위치 저장은 새 독서량을 추가하지 않는다. 재등록의 증가분은 저장된 기록에서 한 번 반영한다. 통계 SQL은 활성 서재에 조인하지 않으므로 제거 전 기록도 포함한다.

Java의 Asia/Seoul 날짜 시작과 마지막 날짜 다음 날 시작을 UTC로 환산한다. SQL은 회원·UTC 범위를 V13 `ix_book_record_time`의 선두 키로 제한하고, 날짜별 SUM의 결과만 반환한다. 날짜 변환에는 Java ZoneRules가 제공한 명시적 오프셋을 사용한다. 과거 오프셋이 바뀌는 구간은 같은 읽기 트랜잭션에서 나누어 읽고 같은 날짜로 합친다. JVM이나 DB 세션 기본 시간대에는 의존하지 않는다. 오늘 쓰는 서울 오프셋 기간은 한 집계 쿼리로 처리하고, Java에서 빠진 날짜를 0으로 채워 오름차순으로 돌려준다. V13과 ERD를 유지했다.

### 프론트 요청과 응답 예시

세 경로 모두 `Authorization: Bearer <accessToken>`을 사용한다. 모든 예시는 HTTP 200의 `data/meta` 응답이다. 회원 미인증·비활성 세션은 401, 필수 약관 재동의는 403 `TERMS_RECONSENT_REQUIRED`다. 쿼리 누락이나 형식 오류는 400 `INVALID_REQUEST`, 잘못된 기간은 400 `VALIDATION_ERROR`다.

```http
GET /api/v1/me/reading/current
Authorization: Bearer <accessToken>

GET /api/v1/me/reading/summary?from=2026-11-01&to=2026-11-07
Authorization: Bearer <accessToken>

GET /api/v1/me/reading/calendar?month=2026-11
Authorization: Bearer <accessToken>
```

빈 서재의 current 응답이다. 책이 있으면 item 자리에는 기존 ShelfItem 전체가 들어간다.

```json
{"data":{"item":null},"meta":{"requestId":"00000000-0000-0000-0000-000000000001"}}
```

진도를 변경한 일반 도서가 선택된 경우다. 현재 지점이 최고 지점보다 낮을 수 있다.

```json
{
  "data":{"item":{
    "book":{"bookId":"a01f1d26-5597-4ac4-889b-e60634a2ea34","workId":null,
      "bookType":"BOOK","title":"예시 도서","authors":[],"publisher":null,
      "editionLabel":null,"isbn":null,"coverUrl":null},
    "currentPosition":{"unit":"PAGE","value":25},
    "highestPosition":{"unit":"PAGE","value":40},
    "positionContext":{"volumeLabel":null,"chapterLabel":null,"episodeLabel":null},
    "progressPercent":25.00,"addedAt":"2026-11-01T00:00:00Z","lastProgressAt":"2026-11-01T01:00:00Z"
  }},
  "meta":{"requestId":"00000000-0000-0000-0000-000000000004"}
}
```

11월 1일에만 15쪽과 2화를 새로 읽은 경우의 summary다.

```json
{
  "data":{"from":"2026-11-01","to":"2026-11-07","timeZone":"Asia/Seoul",
  "pages":15,"episodes":2,"activeDays":1,"daily":[
    {"date":"2026-11-01","pages":15,"episodes":2,"hasActivity":true},
    {"date":"2026-11-02","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-03","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-04","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-05","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-06","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-07","pages":0,"episodes":0,"hasActivity":false}
  ]},
  "meta":{"requestId":"00000000-0000-0000-0000-000000000002"}
}
```

같은 기록을 사용하는 11월 calendar다. 집계가 없으면 모든 날짜를 위와 같은 0 형식으로 반환한다.

```json
{
  "data":{"month":"2026-11","timeZone":"Asia/Seoul","days":[
    {"date":"2026-11-01","pages":15,"episodes":2,"hasActivity":true},
    {"date":"2026-11-02","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-03","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-04","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-05","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-06","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-07","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-08","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-09","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-10","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-11","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-12","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-13","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-14","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-15","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-16","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-17","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-18","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-19","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-20","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-21","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-22","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-23","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-24","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-25","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-26","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-27","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-28","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-29","pages":0,"episodes":0,"hasActivity":false},
    {"date":"2026-11-30","pages":0,"episodes":0,"hasActivity":false}
  ]},
  "meta":{"requestId":"00000000-0000-0000-0000-000000000003"}
}
```

카카오 신규 판본 저장 차단 및 Book 3 ETag/CORS를 유지했다. 실제 카카오 도서 API와 국립중앙도서관 API는 미검증 상태이며 국립중앙도서관 어댑터는 이번 작업에 포함하지 않았다. 기존 H2 Refresh·탈퇴 잠금 경합은 Known Issue로 남긴다.

첫 Book 4 표적 테스트 8건과 선별 회귀 210건이 통과했다. 전체 프로젝트 Gradle 테스트는 1,500건 통과, 실패·오류·스킵 각 0건이었다. 전체 실행은 H2 658건, MySQL Testcontainers 784건, DB 없는 테스트 58건을 포함했다. 실제 DB 및 외부 서비스에는 접속하지 않았다. 세부 내역은 [Book 4단계 결과 보고서](Terms_Reconsent_Analysis.md)에 기록했다.

## Book 2.5 후속 — 카카오 선택 도서 영구 등록 활성화

### 정책과 영구 저장 범위

카카오 담당자는 사용자가 검색 후 직접 선택한 책의 최소 서지정보를 공용 판본에 저장하고, 회원 서재·진도·기록에서 장기 참조하는 NOSPO 문의 사례에 대해 이용 가능하다고 답했다. 일반 검색 결과의 일시 캐시는 과도한 쿼리 소모 방지를 위한 단시간 캐시로 안내했다. [NOSPO 문의 및 공식 답변](https://devtalk.kakao.com/t/daum-api/153062/2), [관련 캐시 문의](https://devtalk.kakao.com/t/api/152911), [관련 데이터 이용 문의](https://devtalk.kakao.com/t/api-daum-search/152062)

- 사용자 선택과 유효한 카카오 임시 판본이 모두 확인된 서재 등록 트랜잭션에서만 신규 생성한다. 판본은 내부 UUID, 출처 `KAKAO_DAUM`, canonical ISBN13인 `source_item_id`와 `isbn`, 정규화된 제목, 필수 유형 `BOOK/PAGE`로 저장한다. 저자는 빈 배열로 반환한다. 저자·출판사·출간일·표지의 영구 저장·재표시는 추가 확인 전까지 보류한다. 소개, 이미지 파일, 외부 응답 원문은 저장 대상에서 제외한다.
- 제공자 응답의 모호하거나 유효하지 않은 ISBN은 거부한다. 같은 판본을 가리키는 ISBN10/ISBN13은 동일 ISBN13으로 변환한다. 출처·식별자 유일 제약으로 동시 등록에서도 기존 UUID를 재사용하고 기존 제목과 메타데이터를 덮어쓰지 않는다. 기존 판본의 ISBN이 충돌하면 404로 종료한다.
- 신규 판본 생성과 개인 진도·서재 등록은 기존 회원 잠금 및 활성 세션 재검증을 포함한 READ COMMITTED 트랜잭션에서 수행한다. 검색은 임시 캐시를 사용하며 V12/V13 스키마로 처리한다.
- 요청 ID는 현재 인스턴스의 기존 5분 캐시와 HMAC 파생값으로 확인한다. 만료·축출·재시작·인스턴스 간 캐시 불일치·미확인 ID는 `404 RESOURCE_NOT_FOUND`를 반환한다. 다시 검색하면 새로 조회한 결과를 선택할 수 있다.
- Wiki·AI는 `BookCatalog.find(영구 UUID)`와 `ReadingProgressCatalog.activeProgress(memberId, bookId)`를 사용한다. 스포일러 상한은 `currentPosition`이다. 카카오 원본 메타데이터를 AI 입력으로 사용하지 않는 기존 팀 계약을 적용한다.

### 프론트 연동

`GET /api/v1/books/search?query=...&type=TITLE&bookType=BOOK`의 `bookId`로 기존 `POST /api/v1/me/shelf`에 `{"bookId":"<검색 결과 UUID>","position":{"unit":"PAGE","value":20}}`를 전달한다. 웹소설은 기존 `EPISODE`를 사용한다. 로그인과 필수 약관 동의가 필요하다. 201의 `data.book.bookId`로 화면 상태를 교체하고 이후 서재·상세·진도에서 이 영구 ID를 사용한다. 임시 UUID를 장기 보관하면 캐시가 사라진 뒤 재등록할 수 없다. 404 시 재검색, 409는 이미 등록, 422는 지점 검증 오류다. 영구 상세의 `authors=[]`, 출판사·표지 등 미보관 필드의 `null`, 미상의 `progressPercent=null`을 화면에서 처리한다.

진도 GET의 ETag를 PATCH의 If-Match에 전달한다. 최초 20페이지 등록은 독서량 0이고, 30페이지로 수정하면 +10이다. 삭제 뒤 35페이지로 재등록하면 +5를 한 번 반영한다. 기록과 홈 요약·캘린더는 같은 Asia/Seoul 날짜 집계를 사용한다.

### 통합 검증

선별 검증 75건이 통과했다. H2 서재 27건, MySQL 8.4.7 Testcontainers 서재 27건, 루프백 HTTP 카카오 어댑터 21건이다. 실패·오류·스킵은 각각 0건이다. 검색만으로 영구 판본이 생성되지 않고, 선택되지 않은 항목과 검증 불가 요청은 저장되지 않음을 확인했다. 두 회원의 동일 ISBN 동시 요청은 한 판본을 공유했으며, 판본·진도·서재 쓰기 단계별 오류를 주입해 모든 부분 쓰기가 롤백되는지 검증했다. 잔존 기록·회원별 통계·ETag 불일치 처리와 캐시 만료 후의 영구 상세 조회도 확인했다.

테스트 JVM은 루트 외 별도 작업 디렉터리와 클래스패스 설정으로 실행했다. DB·Flyway·Gradle·JVM·Docker 설정 상속을 사전 점검했고, Flyway 로그는 H2 인메모리와 Testcontainers의 루프백 MySQL만 확인했다. 파기·메일·카카오 정리 워커는 테스트 설정에서 비활성화했다. 카카오 도서 API는 가짜 키와 루프백 Mock으로 검증했다. 실제 카카오 도서 API·OAuth·SMTP 호출과 실제 DB 접속은 없었다.

전체 Gradle `test`는 20분 9초에 완료되었다. 1,508건 모두 통과했으며 H2 662건, MySQL Testcontainers 788건, DB 미사용 58건이다. 실패·오류·스킵은 각각 0건이었다. Book 1~4, Identity(카카오 OAuth·재동의·탈퇴·복구·파기 포함), Notice, Swagger 및 현 브랜치의 Flyway·ERD 검증을 포함한다. 기존 H2 Refresh·탈퇴 잠금 경합은 이번 실행에서 관찰되지 않았으며 Known Issue로 남긴다. 실제 카카오 REST 연동과 미보관 필드의 정책 확인은 후속 범위다.

시작·종료 브랜치는 `feat/reading`, HEAD는 `cd7f0335f3028c24d7b738fe9200f2f55a5ce66b`다. 결과 보고서는 미추적 상태이며 Git add·commit·push를 실행하지 않았다.
