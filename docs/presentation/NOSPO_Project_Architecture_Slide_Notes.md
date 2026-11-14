# NOSPO 프로젝트 구조와 단일 모듈 비교 — 슬라이드 원고

기준: 2026-11-08 작업 디렉터리. 단일 모듈 예시는 일반적인 비교용 구성.
PPT 본문은 편집 가능하며 전체 Java 파일 50개를 부록에서 설명한다.

## 01. 프로젝트 구조를 이해하는 멀티모듈 수업

전체 디렉터리의 역할  ·  단일 모듈과 1:1 대조  ·  실제 회원가입 흐름

01

같은 Controller · Service · DB 코드가
왜 여러 폴더와 모듈로 나뉘었을까?

50개 Java 파일

루트 실행·설정·테스트 + 업무 구현

22개 코드 하위 모듈

support와 역할별 Gradle 라이브러리

하나의 실행 앱

Spring Boot 서버에 모두 조립

근거: `settings.gradle`, `build.gradle`, `modules`, `src`, `docs`, `erd`

## 02. 먼저 구분할 세 가지: 폴더 · 모듈 · 서버

모듈을 나누었다고 서버나 DB가 자동으로 나뉘지는 않습니다.

02

패키지 / 폴더

코드의 이름과 위치
kr.nospo.member.*

→

Gradle 모듈

컴파일과 의존성 경계
:member:application

→

실행 / 배포

현재 루트 Spring Boot 앱 하나
API와 메일 스케줄러 함께 실행

현재 구조: 업무 경계를 가진 하나의 애플리케이션, 즉 모듈러 모놀리스

근거: `settings.gradle`, `build.gradle`, `src/main/java/kr/nospo/nospo_backend/NospoBackendApplication.java`

## 03. 저장소 최상위 디렉터리 전체 지도

소스·설정·설계 자료와 도구 생성 폴더를 구분해서 읽습니다.

03

위치

역할

공부할 때 보는 것

src/

실행·공통 설정·리소스·통합 테스트

시작점, 보안, DB 스키마, 테스트

modules/

업무별 / 역할별 Java 라이브러리

회원·인증·약관·보상·가입 조합

docs/ · erd/

규칙·API 명세·요약표·ERD·학습 자료

계획과 현재 구현의 차이

gradle/ · gradlew*

Gradle Wrapper와 실행 스크립트

저장소에 지정된 Gradle로 실행

settings.gradle · build.gradle

모듈 등록 / 빌드·의존성·실행 앱 조립

어떤 코드가 무엇을 참조하는가

.git/ · .idea/ · .gradle/ · build/

버전 관리 / IDE / 캐시 / 빌드 결과

업무 코드가 아님, 생성물 별도 취급

README.md · HELP.md · .gitignore

실행 안내 / 참고 링크 / 추적 제외

실행법은 README와 실제 설정 확인

근거: `.gitignore`, `README.md`, `HELP.md`, `gradle`, `docs`, `erd`, `src`, `modules`

## 04. 두 축으로 읽으면 긴 경로도 해석된다

modules/identity/adapter-web/src/main/java/.../EmailVerificationController.java

04

첫 번째 축: 어느 업무인가?

member: 프로필·상태
identity: 계정·인증·세션
terms: 약관·동의
credit: 보상
account-lifecycle: 가입 전체 순서

두 번째 축: 어떤 역할인가?

contract: 외부에 공개할 기능
domain: 업무 규칙
application: 기능 수행 순서·포트
adapter-*: HTTP·DB·외부 기술·스케줄
support: 여러 업무의 기술 지원

identity / adapter-web = 인증 업무의 HTTP 입구

근거: `modules`

## 05. 폴더를 나누는 방식이 어떻게 달라졌나?

단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.

05

일반적인 단일 모듈

src/main/java/.../
  controller/
  service/
  repository/
  dto/
  config/

한 build.gradle 안에서 컴파일

현재 NOSPO 멀티모듈

modules/{업무}/{역할}/
  contract/
  domain/
  application/
  adapter-web/
  adapter-persistence/

역할별 build.gradle로 경계 표현

단일 모듈도 업무별 패키지 구성이 가능하다. 차이는 Gradle 의존성 경계를 추가한 점이다.

근거: `settings.gradle`, `modules/member/application/build.gradle`

## 06. 익숙한 이름과 현재 모듈의 1:1 대응표

기존 개념은 유지되고, 업무 소유권과 연결 인터페이스가 더 명확해졌습니다.

06

단일 모듈에서 흔한 위치

현재 역할 / 예시

주의할 차이

controller/

adapter-web / SignupController

HTTP 입력·출력 담당

service/

application / SignupService

업무별 기능과 전체 조합 분리

repository/

adapter-persistence / JdbcMembers

현재는 JPA가 아니라 JDBC 구현

dto/

web Request · Command · contract record

DTO 전체가 contract인 것은 아님

validator/ · model/

domain / MemberInput · EmailAddress

현재 domain은 정규화·검증 위주

client/ · mail/ · scheduler/

integration / worker 어댑터

외부 기술과 실행 입구 분리

common/ · config/

support / 루트 config

기술 공통과 앱 조립 설정 구분

근거: `modules`

## 07. 같은 실행 방식, 더 명시적인 코드 의존성

단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.

07

일반적인 단일 모듈

하나의 Spring Boot 앱
하나의 DB 사용 가능
Service끼리 Java 메서드 호출

패키지 규칙만으로 경계를 지키는 경우가 많음

현재 NOSPO 멀티모듈

하나의 Spring Boot 앱
현재 하나의 DB 사용
contract를 통한 Java 메서드 호출

Gradle 모듈 의존성으로 참조 범위를 표현

멀티모듈은 마이크로서비스 전환이나 네트워크 통신 추가를 의미하지 않는다.

근거: `build.gradle`, `src/main/resources/application.properties`

## 08. settings.gradle은 모듈 이름과 실제 폴더의 주소록

논리적 Gradle 경로와 Java 패키지 이름은 서로 다른 개념입니다.

08

실제 설정의 의미

include ':member:contract'

project(':member:contract').projectDir
    = file('modules/member/contract')

읽는 순서

① 프로젝트 등록
② 디렉터리 매핑
③ 해당 build.gradle 확인
④ 의존하는 모듈 따라가기

member 같은 상위 프로젝트도 등록되지만, 실제 코드는 주로 역할별 하위 모듈에 있다.

근거: `settings.gradle`

## 09. build.gradle은 무엇을 가져다 쓰는지 명시한다

member/application/build.gradle의 실제 의존성 예시

09

하위 모듈

api project(':member:contract')
implementation project(':member:domain')
implementation '...:spring-context'

api: 사용하는 쪽에도 컴파일 의존성 공개
implementation: 전이 컴파일 공개 제한

루트 실행 앱

Spring Boot 플러그인 적용
필요한 업무 모듈을 모두 의존성으로 추가
하위 모듈은 java-library
Java 21 · 공통 라이브러리 버전 관리

bootJar로 실행할 앱에 모듈을 함께 포함

implementation도 런타임에는 필요하다. api 전파 때문에 경계 규칙이 완벽히 강제되지는 않는다.

근거: `build.gradle`, `modules/member/application/build.gradle`

## 10. Gradle은 코드 연결, Spring은 객체 연결

NospoBackendApplication의 scanBasePackages = "kr.nospo"가 모듈의 Bean을 함께 검색합니다.

10

클래스패스 구성

루트 build.gradle이
라이브러리 모듈을 포함

→

컴포넌트 검색

@Service / @Repository
@Component를 발견

→

생성자 주입

Members → MemberService
MemberStore → JdbcMembers

컴파일 가능한 것과 실행할 구현체가 선택되는 것은 서로 다른 단계다.

근거: `src/main/java/kr/nospo/nospo_backend/NospoBackendApplication.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberService.java`, `modules/member/adapter-persistence/src/main/java/kr/nospo/member/persistence/JdbcMembers.java`

## 11. 전체 업무 모듈: 소유하는 데이터로 이해하기

누가 데이터를 책임지고, 다른 업무에 어떤 기능을 공개하는지 확인합니다.

11

업무

책임 / 데이터

외부에 공개하는 기능

member

닉네임·전화번호·프로필·회원 상태

Members

identity

이메일·비밀번호·인증·세션·메일 작업

SignupIdentity

terms

약관 버전·시행·회원 동의

Terms

credit

가입 지갑·보상 원장

SignupCredits

account-lifecycle

가입 전체 진행·멱등 결과

현재 별도 contract 없음

support

응답·오류·암호화·잠금·요청 제한

업무 모델이 아닌 기술 지원

근거: `modules`, `src/main/resources/db/migration/V1__email_signup.sql`

## 12. member: 평범한 회원 Service를 쪼개서 보면

단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.

12

일반적인 단일 모듈

MemberController / MemberService
MemberRepository / MemberDto

Service가 검증하고 저장소 호출
프로필과 상태를 조회·변경

현재 NOSPO 멀티모듈

contract / Members, Profile
application / MemberService, MemberStore
domain / MemberInput
persistence / JdbcMembers

현재 독립적인 member 웹 모듈은 없음

이메일·비밀번호는 identity가 소유하고, member는 닉네임·전화번호·회원 상태를 맡는다.

근거: `modules/member/contract/src/main/java/kr/nospo/member/contract/Members.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberService.java`, `modules/member/domain/src/main/java/kr/nospo/member/domain/MemberInput.java`

## 13. member.create: 실제 코드에서 보는 네 단계

회원 생성 한 가지 기능만 따라가면 전체 구조의 기본형을 볼 수 있습니다.

13

Members

다른 업무가 보는 공개 기능
create / get / active

→

MemberService

MemberInput으로 검증
정규화 후 저장 요청

→

MemberStore

application이 요구하는
저장 기능 인터페이스

→

JdbcMembers

JdbcTemplate으로
member INSERT / SELECT

실행 호출은 저장소로 향하지만, 소스에서 JdbcMembers가 MemberStore에 의존한다.

근거: `modules/member/contract/src/main/java/kr/nospo/member/contract/Members.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberService.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberStore.java`, `modules/member/adapter-persistence/src/main/java/kr/nospo/member/persistence/JdbcMembers.java`

## 14. contract와 Store는 왜 인터페이스가 둘인가?

두 인터페이스가 대답하는 질문이 다릅니다.

14

Members: 업무의 공개 입구

누가 사용하나?
→ 다른 업무의 서비스

어떤 약속인가?
→ 회원을 만들거나 프로필을 조회한다.

구현체: MemberService

MemberStore: 저장 기능의 포트

누가 사용하나?
→ MemberService

어떤 약속인가?
→ 필요한 데이터를 저장·조회한다.

구현체: JdbcMembers

contract는 업무 간 계약, Store는 업무 내부에서 필요로 하는 저장 연결 지점이다.

근거: `modules/member/contract/src/main/java/kr/nospo/member/contract/Members.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberStore.java`

## 15. identity: 인증 업무와 외부 기술을 분리한다

단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.

15

일반적인 단일 모듈

AuthController / AuthService
MailService / JwtProvider
AccountRepository

인증·메일·암호화가 한 서비스에
함께 들어가기 쉬움

현재 NOSPO 멀티모듈

web / EmailVerificationController
application / 인증·발송·가입 identity 서비스
contract / SignupIdentity
domain / EmailAddress
persistence / JdbcIdentityStore
integration / SMTP·JWT / worker / 스케줄

현재 13개 Java 파일: 인증 순서, DB 구현, SMTP와 암호 기술을 각각 추적할 수 있다.

근거: `modules/identity`

## 16. identity 내부를 실제 클래스 단위로 나누기

application은 해야 할 일을 정하고, 어댑터는 기술적인 수행 방법을 제공합니다.

16

클래스 / 인터페이스

현재 역할

EmailVerificationService

이메일 정규화·요청 제한·재발송·링크 확인·proof 발급

SignupIdentityService

가입 증명 검사·계정 생성·세션 발급·활성 세션 확인

EmailDeliveryService

발송 작업 점유·전송·재시도·완료·만료 정리

IdentityStore / JdbcIdentityStore

계정·인증·메일 작업의 저장 포트 / SQL 구현

EmailSender / SmtpEmailSender

메일 발송 포트 / SMTP 구현과 실패 분류

IdentityCrypto / JwtIdentityCrypto

해시·토큰 포트 / Argon2와 HS256 JWT 구현

EmailWorker / EmailAddress

스케줄 진입점 / 이메일 정규화·검증

근거: `modules/identity`

## 17. terms와 credit: 작은 업무도 소유권을 갖는다

단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.

17

일반적인 단일 모듈

TermsService / ConsentRepository
CreditService / WalletRepository

SignupService에서 다른 테이블을
직접 수정하는 코드가 생길 수 있음

현재 NOSPO 멀티모듈

terms: Terms → TermService
→ TermStore → JdbcTerms
TermsController는 조회 HTTP 입구

credit: SignupCredits → SignupCreditService
→ CreditStore → JdbcCredits

약관 동의 검증은 terms, 5크레딧 지급은 credit가 책임진다.

근거: `modules/terms`, `modules/credit`

## 18. account-lifecycle: 가입 전체를 조율하는 곳

단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.

18

일반적인 단일 모듈

하나의 SignupService에서
회원 저장 + 계정 저장 + 동의 저장
+ 지갑 저장 + 토큰 발급

업무별 Repository를 직접 조합하기 쉬움

현재 NOSPO 멀티모듈

SignupController: HTTP 입출력
SignupService: 전체 순서와 트랜잭션
SignupResult: 응답 데이터
SignupRecords: 멱등 기록 포트
JdbcSignupRecords: 결과 SQL 저장

다른 업무는 contract로 호출

진행 책임은 lifecycle에, 실제 데이터 변경 책임은 각 업무 모듈에 둔다.

근거: `modules/account-lifecycle`

## 19. support와 루트 config는 역할이 다르다

공통 기술 기능과 애플리케이션 조립 설정을 구분합니다.

19

위치

클래스

역할

support

ApiResponse · ApiException · ApiErrors

성공 응답, 업무 오류, 예외→HTTP 변환

support

Secrets · SqlIds · DatabaseGuard

암호·지문, UUID·시각 변환, 잠금·제한

root/config

AuthConfiguration

Clock·JWT encoder/decoder·스케줄 활성화

root/config

SecurityConfig

인증 경로·CORS·Origin·오류 응답

root/config

OpenApiConfig

Swagger 문서·Bearer 인증 표시

root/config

SignupCleanup

만료한 가입 멱등 기록 정리

근거: `modules/support`, `src/main/java/kr/nospo/nospo_backend/config`

## 20. 실제 HTTP API는 세 Controller에서 시작한다

명세에 있는 모든 API가 구현된 것은 아닙니다.

20

Controller

구현 경로 (기본 /api/v1)

내부 진입점

TermsController

GET /terms · GET /terms/{id}

Terms

EmailVerificationController

GET /auth/availability/email

EmailVerificationService

EmailVerificationController

POST /auth/signup/email-verifications

request

EmailVerificationController

POST .../email-verifications/confirm

confirm

SignupController

GET /auth/availability/nickname

SignupService

SignupController

POST /auth/signup · GET /me

signup · me

근거: `modules/account-lifecycle/adapter-web/src/main/java/kr/nospo/lifecycle/web/SignupController.java`, `modules/identity/adapter-web/src/main/java/kr/nospo/identity/web/EmailVerificationController.java`, `modules/terms/adapter-web/src/main/java/kr/nospo/terms/web/TermsController.java`

## 21. 이메일 인증 → 가입 증명 → 가입 완료

메일의 token, 가입용 emailProof, 인증용 Access Token은 용도가 다릅니다.

21

메일 요청

인증 요청 + 발송 작업 저장
202 접수 응답

→

링크 확인

메일 token을 POST
emailProof 발급

→

가입 완료

emailProof + 가입 정보
Access JWT + Refresh 쿠키

→

내 정보 조회

Bearer Access Token
GET /api/v1/me

메일 token과 emailProof는 각각 발급 후 10분. Access Token은 15분이다.

근거: `modules/identity/application/src/main/java/kr/nospo/identity/application/EmailVerificationService.java`, `modules/account-lifecycle/application/src/main/java/kr/nospo/lifecycle/application/SignupService.java`

## 22. 메일 발송: HTTP 요청과 실제 전송을 분리

현재 워커는 별도 서버가 아니라 같은 Spring Boot 앱의 스케줄러입니다.

22

요청 트랜잭션

인증 요청 생성
암호화된 발송 자료 저장

→

DB outbox

email_delivery
전송할 일을 지속 보관

→

워커 점유

기본 3초 fixed delay
점유·임대 확인

→

SMTP / 결과

트랜잭션 밖에서 전송
완료·재시도 별도 기록

202 = 작업 접수, ACCEPTED = 제공자 접수. 받은편지함 도착 보장은 아니다.

근거: `modules/identity/application/src/main/java/kr/nospo/identity/application/EmailDeliveryService.java`, `modules/identity/adapter-worker/src/main/java/kr/nospo/identity/worker/EmailWorker.java`, `modules/identity/adapter-integration/src/main/java/kr/nospo/identity/integration/SmtpEmailSender.java`

## 23. 가입 서비스의 실제 실행 순서

가입 성공 결과 조회가 증명 사용 여부 검사보다 먼저 수행됩니다.

23

단계

실행 내용

주요 담당

트랜잭션 전

입력 확인·scope/지문·비밀번호 해시

SignupService · identity

트랜잭션 시작

멱등 잠금·기존 성공 결과 복원/충돌 확인

DatabaseGuard · SignupRecords

검증 / 회원

proof 검증 → 회원 생성

identity → member

동의 / 계정

약관 검사·저장 → 계정 생성·proof 소비

terms → identity

보상 / 인증

가입 5크레딧 → 세션·토큰 생성

credit → identity

결과 확정

성공 응답 암호화·멱등 기록 저장·커밋

SignupRecords

근거: `modules/account-lifecycle/application/src/main/java/kr/nospo/lifecycle/application/SignupService.java`

## 24. 트랜잭션은 모듈을 넘어 하나로 묶인다

단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.

24

일반적인 단일 모듈

@Transactional SignupService
여러 Repository 변경을 함께 커밋
중간 실패 시 전체 롤백

같은 앱·DB에서 구현 가능

현재 NOSPO 멀티모듈

SignupService의 TransactionTemplate
READ_COMMITTED 격리 수준
member·terms·identity·credit 변경을 묶음

terms와 credit의 @Transactional도
기존 가입 트랜잭션에 참여

Gradle 모듈 경계와 DB 트랜잭션 경계는 다르다. 같은 트랜잭션 구성이 전제다.

근거: `modules/account-lifecycle/application/src/main/java/kr/nospo/lifecycle/application/SignupService.java`, `modules/terms/application/src/main/java/kr/nospo/terms/application/TermService.java`, `modules/credit/application/src/main/java/kr/nospo/credit/application/SignupCreditService.java`

## 25. 재시도와 동시성: 이름이 비슷해도 목적은 다르다

회원과 보상이 중복 생성되지 않도록 여러 장치를 함께 사용합니다.

25

장치

해결하는 문제

현재 구현

멱등 기록

성공 응답 유실 후 같은 요청 재전송

proof 기반 scope + UUID 키 + 요청 지문

DB 잠금

동시에 같은 작업을 처리하는 경합

auth_guard 행 upsert 잠금

유일 제약 / 원자 갱신

경합으로 중복 데이터가 저장되는 상황

닉네임·이메일 충돌 / proof 소비 검사

요청 제한

인증 메일 요청 과다

이메일 5회/시간 · IP 30회/시간

발송 임대 / 소유 확인

죽은 워커 작업 복구와 중복 처리

claim · lease token · finish

멱등 결과는 24시간 보관. 요청 제한 카운터는 별도의 REQUIRES_NEW 트랜잭션으로 처리한다.

근거: `modules/support/src/main/java/kr/nospo/support/DatabaseGuard.java`, `modules/account-lifecycle/application/src/main/java/kr/nospo/lifecycle/application/SignupService.java`, `modules/account-lifecycle/adapter-persistence/src/main/java/kr/nospo/lifecycle/persistence/JdbcSignupRecords.java`

## 26. 인증된 /me: JWT 외에 DB 세션도 검사

현재 구현은 JWT 서명만 확인하고 끝나는 방식이 아닙니다.

26

Security 필터

Bearer 추출
인증 필요 경로 확인

→

JWT 검증

HS256 서명·만료·issuer
subject=회원 UUID

→

추가 상태 검사

sid 세션 활성 여부
member 활성 여부

→

/me 응답

회원 프로필 + 이메일
인증 주체로 ID 확인

Refresh 쿠키는 14일로 발급되지만, 현재 Refresh 재발급 API는 아직 없다.

근거: `src/main/java/kr/nospo/nospo_backend/config/SecurityConfig.java`, `src/main/java/kr/nospo/nospo_backend/config/AuthConfiguration.java`, `modules/identity/application/src/main/java/kr/nospo/identity/application/SignupIdentityService.java`

## 27. 실제 스키마는 migration을 기준으로 읽기

DB는 공유하지만 테이블을 수정할 책임은 각 업무가 갖습니다.

27

소유 책임

현재 테이블

member

member

identity: 계정·세션

account · password_credential · auth_session · refresh_token

identity: 인증·발송

auth_challenge · auth_proof · email_delivery

terms

term · term_version · member_consent

credit

credit_wallet · credit_transaction

lifecycle / 기술 지원

idempotency_record / auth_guard · auth_rate_limit

V1 마이그레이션은 16개 테이블. ERD의 카카오·탈퇴 설계와 현재 스키마는 일치 범위가 다르다.

근거: `src/main/resources/db/migration/V1__email_signup.sql`, `erd/NOSPO_Auth.erd.json`

## 28. resources와 실행 설정의 역할

현재 애플리케이션은 .env를 자동으로 읽지 않으며, 실행 환경변수로 설정합니다.

28

설정 / 파일

현재 의미

application.properties

DB·메일·인증·Swagger 설정, 실제 값은 환경변수로 주입

db/migration/V1__email_signup.sql

Flyway 스키마 생성, JPA ddl-auto=none

AUTH_ENCRYPTION_KEY / HMAC_KEY / JWT_KEY

서로 다른 Base64 32바이트 키, 미설정 시 시작 실패

DB_* / SMTP_* / MAIL_FROM

MySQL 연결 / SMTP 제공자 설정

AUTH_ALLOWED_ORIGINS / SECURE_COOKIE

허용 Origin과 Refresh 쿠키의 보안 속성

application-test.properties

H2 MySQL 모드, 테스트 전용 키, 워커 비활성화

근거: `src/main/resources/application.properties`, `src/test/resources/application-test.properties`, `README.md`

## 29. 테스트 디렉터리는 시스템 동작을 설명하는 예제

현재 주요 검증 코드는 각 하위 모듈이 아니라 루트 src/test에 모여 있습니다.

29

파일

역할

EmailSignupTests

가입 원자성·멱등성·경합·재발송·메일 재시도·HTTP 계약

MySqlEmailSignupTests

위 가입 테스트를 MySQL 8.4.7 컨테이너에서 실행

SwaggerUiTests / SmtpEmailSenderTests

문서 접근·인증 / SMTP 영구·일시 오류 분류

NospoBackendApplicationTests

테스트 프로필로 Spring 컨텍스트 로딩

TestcontainersConfiguration

개발용 컨테이너 Bean, 이미지 mysql:latest

TestNospoBackendApplication

컨테이너 설정을 붙여 앱 실행하는 테스트 진입점

H2 빠른 테스트와 MySQL 검증은 목적이 다르다. Docker가 없으면 MySQL 테스트는 건너뛴다.

근거: `src/test/java/kr/nospo/nospo_backend`, `src/test/resources/application-test.properties`

## 30. 문서·ERD는 미래 범위까지 포함한다

설계에 등장하는 기능을 모두 현재 구현으로 해석하면 구조가 더 혼란스러워집니다.

30

현재 코드로 확인되는 범위

이메일 인증과 비동기 메일 작업
이메일 회원가입과 가입 보상
약관 조회·동의 저장
JWT·Refresh 발급, 내 정보 조회

승인된 약관 본문은 별도 등록 필요

설계 또는 후속 구현 범위

기존 회원 로그인·카카오·탈퇴
Refresh 회전·로그아웃·비밀번호 재설정
도서·독서·위키·AI·공지 업무

ERD에는 social / oauth / withdrawal 포함
API 요약 XLSX도 전체 명세 참고 자료

현재 동작: Controller·서비스·migration / 목표 정책: 기능명세·개발 규칙·ERD를 대조

근거: `docs/NOSPO_Function_API_v2.1.md`, `docs/NOSPO_Development_Conventions.md`, `docs/NOSPO_API_Summary.xlsx`, `erd/NOSPO_Auth.erd.json`, `README.md`

## 31. 멀티모듈로 얻는 것과 추가되는 비용

단일 모듈도 잘 설계할 수 있고, 모듈만 나눈다고 좋은 구조가 보장되지는 않습니다.

31

관점

단일 모듈

현재 멀티모듈

시작·탐색

파일 이동이 적고 초기에 단순

작은 기능도 여러 경로를 따라감

경계

패키지·리뷰 규칙 중심

Gradle 의존성 + 공개 인터페이스

변경 영향

내부 구현 직접 참조가 쉬움

계약을 유지하면 구현 변경을 줄일 수 있음

협업

작은 팀·좁은 범위에서 편리

업무별 소유권이 보이지만 계약 협의 필요

테스트·실행

한 프로젝트에서 쉽게 통합

분리 테스트 가능, 현재는 루트 통합 중심

운영

하나의 서버로 배포 가능

현재도 하나의 서버, 자동 분산·고가용성 아님

근거: `settings.gradle`, `build.gradle`, `docs/NOSPO_Development_Conventions.md`

## 32. 수정 요청이 오면 어느 디렉터리로 갈까?

단일 모듈에서 찾던 위치를 현재 구조에 대응해 봅니다.

32

변경 요구

단일 모듈의 흔한 위치

현재 시작 위치

가입 JSON / Refresh 쿠키

SignupController · dto/

lifecycle/adapter-web/SignupController

닉네임 허용 문자

MemberService · validator/

member/domain/MemberInput

가입 보상 수량

CreditService

credit/application/SignupCreditService

가입 단계·원자성

SignupService

lifecycle/application/SignupService

메일 재시도 / SMTP 발송

MailService

identity/application / adapter-integration

DB 컬럼 추가

Entity · Repository · SQL

migration + 소유 업무의 persistence

근거: `modules`, `src/main/java/kr/nospo/nospo_backend/config`

## 33. 예제: 닉네임 규칙 변경의 영향 경로

실제 구현을 바꾸지 않고, 변경 시 검토할 위치를 따라가는 학습 예제입니다.

33

업무 규칙

MemberInput.nickname
길이·허용 문자 변경

→

입력·저장 확인

Controller 제한
DB 컬럼·유일 키 확인

→

호출자 확인

Members 계약 유지 시
가입 호출부 변경 최소화

→

검증·문서

중복·정규화 회귀 검증
API와 설명 갱신

변경 위치는 줄어들 수 있지만, HTTP 검증과 DB 제약의 일관성은 함께 확인해야 한다.

근거: `modules/member/domain/src/main/java/kr/nospo/member/domain/MemberInput.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberService.java`, `modules/account-lifecycle/adapter-web/src/main/java/kr/nospo/lifecycle/web/SignupController.java`, `src/main/resources/db/migration/V1__email_signup.sql`

## 34. 추천 코드 읽기 순서: 작은 경로부터 전체로

한 번에 모든 모듈을 외우기보다, 한 호출이 지나가는 파일을 연결하세요.

34

순서

읽을 코드

답해 볼 질문

1

settings.gradle → root build.gradle

어떤 모듈이 실행 앱에 포함되는가?

2

Members → MemberService → Store → JdbcMembers

인터페이스마다 누가 호출하고 누가 구현하는가?

3

SignupController → SignupService

가입의 순서와 트랜잭션은 누가 책임지는가?

4

EmailVerificationService → EmailWorker / Delivery

요청 접수와 실제 전송은 왜 분리됐는가?

5

SecurityConfig → AuthConfiguration

JWT 이외에 어떤 상태를 검사하는가?

6

migration → Jdbc* → EmailSignupTests

테이블 소유권과 실패·경합 동작이 맞는가?

근거: `docs/NOSPO_Multi_Module_Study_Guide.md`, `src/test/java/kr/nospo/nospo_backend/EmailSignupTests.java`

## 35. 이 다섯 질문에 답하면 구조를 이해한 것

발표자 노트에는 짧은 해설을 넣었습니다.

35

1

Members와 MemberStore는 왜 따로 필요한가?

2

Gradle 의존성과 Spring 생성자 주입의 차이는 무엇인가?

3

모듈이 달라도 가입 전체를 롤백할 수 있는 이유는?

4

adapter-worker는 별도 서버라는 뜻인가?

5

명세서의 API가 현재 구현됐는지는 어디서 확인하는가?

근거: `docs/NOSPO_Multi_Module_Study_Guide.md`

## 36. 루트 실행·설정

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

36

파일

소속

역할

NospoBackendApplication.java

root / main

Spring Boot 시작, kr.nospo 전체 컴포넌트 검색

AuthConfiguration.java

root / main

Clock, JWT encoder/decoder, 세션 검증, 스케줄 활성화

OpenApiConfig.java

root / main

Swagger 제목과 /me의 Bearer 인증 문서화

SecurityConfig.java

root / main

허용 경로, CORS·Origin 검사, Bearer 인증과 보안 오류

SignupCleanup.java

root / main

만료 멱등 결과 정리, 기본 60초 fixed delay

근거: `src/main/java/kr/nospo/nospo_backend/NospoBackendApplication.java`, `src/main/java/kr/nospo/nospo_backend/config/AuthConfiguration.java`, `src/main/java/kr/nospo/nospo_backend/config/OpenApiConfig.java`, `src/main/java/kr/nospo/nospo_backend/config/SecurityConfig.java`, `src/main/java/kr/nospo/nospo_backend/config/SignupCleanup.java`

## 37. member

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

37

파일

소속

역할

JdbcMembers.java

member / adapter-persistence

member 테이블 SQL, 중복 닉네임 오류 변환

MemberService.java

member / application

회원 입력 검증·정규화 후 저장 포트 호출

MemberStore.java

member / application

회원 생성·조회·닉네임·상태 확인 저장 포트

Members.java

member / contract

회원 업무의 공개 인터페이스와 Profile record

MemberInput.java

member / domain

닉네임·전화번호 정규화와 검증 규칙

근거: `modules/member/adapter-persistence/src/main/java/kr/nospo/member/persistence/JdbcMembers.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberService.java`, `modules/member/application/src/main/java/kr/nospo/member/application/MemberStore.java`, `modules/member/contract/src/main/java/kr/nospo/member/contract/Members.java`, `modules/member/domain/src/main/java/kr/nospo/member/domain/MemberInput.java`

## 38. identity / contract·domain·application (1)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

38

파일

소속

역할

EmailDeliveryService.java

identity / application

메일 작업 점유·전송·재시도·완료·정리

EmailSender.java

identity / application

메일 전송 포트와 영구/일시 실패 표현

EmailVerificationService.java

identity / application

인증 발급·재발송·링크 확인·가입 proof 생성

IdentityCrypto.java

identity / application

비밀번호 해시·Access Token 생성 포트

IdentityStore.java

identity / application

인증·계정·세션·발송 작업 저장 포트와 record

SignupIdentityService.java

identity / application

증명 검사·계정 생성·세션 발급·상태 조회

근거: `modules/identity/application/src/main/java/kr/nospo/identity/application/EmailDeliveryService.java`, `modules/identity/application/src/main/java/kr/nospo/identity/application/EmailSender.java`, `modules/identity/application/src/main/java/kr/nospo/identity/application/EmailVerificationService.java`, `modules/identity/application/src/main/java/kr/nospo/identity/application/IdentityCrypto.java`, `modules/identity/application/src/main/java/kr/nospo/identity/application/IdentityStore.java`, `modules/identity/application/src/main/java/kr/nospo/identity/application/SignupIdentityService.java`

## 39. identity / contract·domain·application (2)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

39

파일

소속

역할

SignupIdentity.java

identity / contract

가입 인증·계정·세션의 공개 인터페이스

EmailAddress.java

identity / domain

이메일 소문자화·공백 제거·형식 검증

근거: `modules/identity/contract/src/main/java/kr/nospo/identity/contract/SignupIdentity.java`, `modules/identity/domain/src/main/java/kr/nospo/identity/domain/EmailAddress.java`

## 40. identity / adapters

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

40

파일

소속

역할

JwtIdentityCrypto.java

identity / adapter-integration

Argon2 비밀번호 해시, HS256 JWT 생성

SmtpEmailSender.java

identity / adapter-integration

SMTP 발송, fragment 링크, SMTP 오류 분류

JdbcIdentityStore.java

identity / adapter-persistence

identity 테이블 SQL, 작업 lease와 조건부 갱신

EmailVerificationController.java

identity / adapter-web

이메일 가용성·인증 요청·확인 HTTP API

EmailWorker.java

identity / adapter-worker

메일 처리·정리를 주기 실행하는 스케줄 입구

근거: `modules/identity/adapter-integration/src/main/java/kr/nospo/identity/integration/JwtIdentityCrypto.java`, `modules/identity/adapter-integration/src/main/java/kr/nospo/identity/integration/SmtpEmailSender.java`, `modules/identity/adapter-persistence/src/main/java/kr/nospo/identity/persistence/JdbcIdentityStore.java`, `modules/identity/adapter-web/src/main/java/kr/nospo/identity/web/EmailVerificationController.java`, `modules/identity/adapter-worker/src/main/java/kr/nospo/identity/worker/EmailWorker.java`

## 41. terms · credit (1)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

41

파일

소속

역할

JdbcCredits.java

credit / adapter-persistence

credit_wallet와 SIGNUP 거래 원장 INSERT

CreditStore.java

credit / application

가입 지갑 생성과 보상 기록 저장 포트

SignupCreditService.java

credit / application

가입 보상 수량 5 결정, 지급 호출

SignupCredits.java

credit / contract

가입 보상 지급 공개 인터페이스

JdbcTerms.java

terms / adapter-persistence

현행 시행 버전 조회, term 잠금, 동의 INSERT

TermsController.java

terms / adapter-web

현행 약관 목록·버전 상세 HTTP API

근거: `modules/credit/adapter-persistence/src/main/java/kr/nospo/credit/persistence/JdbcCredits.java`, `modules/credit/application/src/main/java/kr/nospo/credit/application/CreditStore.java`, `modules/credit/application/src/main/java/kr/nospo/credit/application/SignupCreditService.java`, `modules/credit/contract/src/main/java/kr/nospo/credit/contract/SignupCredits.java`, `modules/terms/adapter-persistence/src/main/java/kr/nospo/terms/persistence/JdbcTerms.java`, `modules/terms/adapter-web/src/main/java/kr/nospo/terms/web/TermsController.java`

## 42. terms · credit (2)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

42

파일

소속

역할

TermService.java

terms / application

현행 약관·필수 동의 검증, 동의 저장 조율

TermStore.java

terms / application

약관 조회·잠금·동의 저장 포트

Terms.java

terms / contract

약관 조회·가입 동의 기록의 공개 계약

근거: `modules/terms/application/src/main/java/kr/nospo/terms/application/TermService.java`, `modules/terms/application/src/main/java/kr/nospo/terms/application/TermStore.java`, `modules/terms/contract/src/main/java/kr/nospo/terms/contract/Terms.java`

## 43. account-lifecycle · support (1)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

43

파일

소속

역할

JdbcSignupRecords.java

account-lifecycle / adapter-persistence

idempotency_record SQL과 24시간 보관

SignupController.java

account-lifecycle / adapter-web

닉네임 가용성·가입·내 정보, Refresh 쿠키

SignupRecords.java

account-lifecycle / application

성공 멱등 결과 조회·저장·정리 포트

SignupResult.java

account-lifecycle / application

가입 응답 및 회원 정보 record

SignupService.java

account-lifecycle / application

전체 가입 순서·트랜잭션·멱등 처리·내 정보 조합

ApiErrors.java

support

예외→공통 HTTP 오류 응답, 민감 상세 노출 제한

근거: `modules/account-lifecycle/adapter-persistence/src/main/java/kr/nospo/lifecycle/persistence/JdbcSignupRecords.java`, `modules/account-lifecycle/adapter-web/src/main/java/kr/nospo/lifecycle/web/SignupController.java`, `modules/account-lifecycle/application/src/main/java/kr/nospo/lifecycle/application/SignupRecords.java`, `modules/account-lifecycle/application/src/main/java/kr/nospo/lifecycle/application/SignupResult.java`, `modules/account-lifecycle/application/src/main/java/kr/nospo/lifecycle/application/SignupService.java`, `modules/support/src/main/java/kr/nospo/support/ApiErrors.java`

## 44. account-lifecycle · support (2)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

44

파일

소속

역할

ApiException.java

support

HTTP 상태·오류 코드·재시도 대기시간 표현

ApiResponse.java

support

공통 성공 응답 data와 meta.requestId

DatabaseGuard.java

support

DB 행 잠금, 독립 트랜잭션 요청 제한, TTL 정리

Secrets.java

support

난수 token, SHA-256, HMAC 지문, AES-GCM 암호화

SqlIds.java

support

UUID↔바이트와 Instant→SQL Timestamp 변환

근거: `modules/support/src/main/java/kr/nospo/support/ApiException.java`, `modules/support/src/main/java/kr/nospo/support/ApiResponse.java`, `modules/support/src/main/java/kr/nospo/support/DatabaseGuard.java`, `modules/support/src/main/java/kr/nospo/support/Secrets.java`, `modules/support/src/main/java/kr/nospo/support/SqlIds.java`

## 45. 테스트 (1)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

45

파일

소속

역할

EmailSignupTests.java

root / test

가입·메일·동시성·재시도·HTTP 통합 검증

MySqlEmailSignupTests.java

root / test

동일 가입 테스트를 MySQL 8.4.7에서 실행

NospoBackendApplicationTests.java

root / test

Spring 애플리케이션 컨텍스트 로딩

SmtpEmailSenderTests.java

root / test

SMTP 5xx 영구 실패·4xx 재시도 분류 확인

SwaggerUiTests.java

root / test

Swagger 공개 접근과 그 외 경로 인증 확인

TestNospoBackendApplication.java

root / test

개발용 Testcontainers 구성으로 앱 실행

근거: `src/test/java/kr/nospo/nospo_backend/EmailSignupTests.java`, `src/test/java/kr/nospo/nospo_backend/MySqlEmailSignupTests.java`, `src/test/java/kr/nospo/nospo_backend/NospoBackendApplicationTests.java`, `src/test/java/kr/nospo/nospo_backend/SmtpEmailSenderTests.java`, `src/test/java/kr/nospo/nospo_backend/SwaggerUiTests.java`, `src/test/java/kr/nospo/nospo_backend/TestNospoBackendApplication.java`

## 46. 테스트 (2)

아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.

46

파일

소속

역할

TestcontainersConfiguration.java

root / test

개발용 mysql:latest 컨테이너 Bean 설정

근거: `src/test/java/kr/nospo/nospo_backend/TestcontainersConfiguration.java`

## 47. 코드가 있는 하위 프로젝트 22개

상위 업무 프로젝트는 묶음 역할이며, 필요한 역할만 실제 하위 모듈로 구현했습니다.

47

업무

실제 하위 Gradle 모듈

개수

member

contract · domain · application · adapter-persistence

4

identity

contract · domain · application · adapter-persistence
adapter-web · adapter-integration · adapter-worker

7

terms

contract · application · adapter-persistence · adapter-web

4

credit

contract · application · adapter-persistence

3

account-lifecycle

application · adapter-persistence · adapter-web

3

support

업무별 하위 역할 없이 단일 기술 지원 라이브러리

1

근거: `settings.gradle`, `build.gradle`

## 48. 소스 외 파일도 역할을 연결해서 읽기

자동 생성물과 IDE 내부 자료는 업무 설계 근거로 사용하지 않습니다.

48

파일 / 디렉터리

역할과 확인 포인트

README.md / HELP.md

프로젝트 실행·환경변수·검증 / 생성된 참고 문서

gradlew · gradlew.bat · gradle/wrapper/*

Unix·Windows 빌드 진입점, Gradle 배포·Wrapper JAR

settings.gradle / 각 build.gradle

프로젝트 등록·경로 매핑 / 의존성·플러그인·공통 설정

docs/개발 규칙 · 기능/API 명세 · XLSX

업무 경계·정책·목표 API / 전체 API 요약 시트

docs/학습 가이드 · presentation/

상세 코드 해설 / 이 PPT와 재생성 스크립트

erd/NOSPO_Auth.erd.json

17개 설계 테이블, 현재 migration의 16개와 범위 차이

.gitignore / .git · .idea · .gradle · build

추적 제외 설정 / 이력·IDE·캐시·컴파일·테스트 결과

근거: `README.md`, `HELP.md`, `.gitignore`, `gradle/wrapper/gradle-wrapper.properties`, `docs`, `erd`

## 49. 실행 명령과 자료의 범위

본 PPT 작성 과정에서는 애플리케이션 테스트를 실행하지 않았습니다.

49

로컬에서 직접 확인하기

./gradlew bootRun
→ Java 21·DB·키·SMTP 설정 필요

./gradlew :test -PskipMysql
→ H2 중심 테스트 (MySQL 제외)

./gradlew :test --tests "*MySqlEmailSignupTests"
→ Docker와 MySQL 통합 검증

자료를 읽는 기준

현재 소스와 설정을 우선 확인
API·ERD의 후속 설계와 구분
단일 모듈 예시는 비교용 가정
각 슬라이드 발표자 노트에 근거 경로

PPT 본문·표·도식은 편집 가능한 요소
PNG는 동일 배치 명령으로 만든 미리보기

읽기 순서: 빌드 설정 → member 한 경로 → 가입 조합 → 인증·발송 → DB·테스트

근거: `README.md`, `settings.gradle`, `build.gradle`, `src/main/resources/db/migration/V1__email_signup.sql`
