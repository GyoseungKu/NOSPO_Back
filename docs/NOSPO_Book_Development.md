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
