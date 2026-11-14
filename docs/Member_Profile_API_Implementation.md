# 내 정보 변경

API 기본 경로는 `/api/v1`입니다. 모든 API는 유효한 Bearer Access 토큰이 필요합니다. 변경 요청에서 브라우저 Origin을 보내면 설정된 허용 Origin이어야 합니다.

| Method | 경로 | 요청 | 성공 |
| --- | --- | --- | --- |
| PATCH | /me/nickname | nickname | 200 data.nickname |
| PATCH | /me/phone-number | phoneNumber | 200 data.phoneNumber, phoneVerified:false |
| PUT | /me/profile-image | multipart file | 200 data.profileImageUrl |
| GET | /me/profile-image | 없음 | 200 image/png, 본인 이미지 |
| DELETE | /me/profile-image | 없음 | 204 |
| POST | /me/reauth/password | password, purpose:CHANGE_EMAIL | 200 data.reauthProof, expiresAt |
| POST | /me/email-changes | newEmail, reauthProof | 202 data.verificationId, expiresAt, resendAvailableAt |
| POST | /me/email-changes/verify | verificationId, code | 200 data.emailChangeProof, expiresAt |
| PATCH | /me/email | emailChangeProof | 200 data.email |
| PATCH | /me/password | currentPassword, newPassword | 204, Refresh 쿠키 삭제 |

기존 GET /me와 로그인 응답에도 변경된 닉네임·연락처·이메일·프로필 URL이 반영됩니다. 연락처 형식 정규화만 수행하며 소유권 검증은 하지 않으므로 phoneVerified는 false입니다.

## 모듈 구조

- member/application: ProfileService, ProfileStore 및 ProfileImageCodec 포트.
- member/adapter-web: 프로필 변경 및 이미지 조회 HTTP 계약.
- member/adapter-persistence: 닉네임·연락처·이미지 DB 접근과 중복 제약 오류 변환.
- member/adapter-integration: 실제 이미지 디코딩·검증·메타데이터 제거·PNG 재인코딩.
- identity/application: 재인증·이메일·비밀번호 변경과 로그인·재설정 트랜잭션 정책. SQL과 웹 구현에 의존하지 않습니다.
- identity/adapter-persistence: AccountStore 및 VerificationStore의 JDBC 구현.
- identity/adapter-web: 인증·계정 변경 HTTP 계약. 로그인 응답의 회원 정보는 member/contract로 조회합니다.
- account-lifecycle/adapter-web: 가입과 기존 내 정보 조회 HTTP 계약.
- 루트 앱: 모듈 조립, 보안 설정, Flyway 마이그레이션.

## 인증과 경합

재인증 증명은 목적·회원·세션에 묶인 일회용이며 5분 만료입니다. 현재 CHANGE_EMAIL 및 WITHDRAWAL 목적의 비밀번호 재인증이 가능하지만 탈퇴 API 자체는 구현 범위에 포함하지 않습니다. 잘못된 목적은 422입니다.

이메일 변경 인증은 6자리 코드, 10분 만료, 최대 5회 시도입니다. 완료 증명은 추가 10분 동안 유효합니다. 인증 요청은 회원·대상 이메일 각각 시간당 5회이며 재발송 간격은 60초입니다. 재발송에는 새로운 재인증 증명이 필요합니다. 같은 회원의 이전 이메일 변경 인증·증명은 무효화합니다.

이메일 변경 최종 저장에서 DB 유일성을 다시 검사합니다. 다른 회원이나 세션은 코드를 검증하거나 증명을 소비할 수 없습니다. 이메일 변경 후 이전 주소로 발급된 비밀번호 재설정 자료는 무효화합니다. 비밀번호 변경은 현재 비밀번호를 확인하고 전체 세션과 진행 중 인증 증명을 폐기하므로 프론트는 로그인 화면으로 이동해야 합니다.

정보 변경은 활성 회원 잠금과 쓰기를 같은 트랜잭션에 두며, 계정 변경은 세션 활성 여부도 다시 확인합니다. 비밀번호 해싱·이미지 디코딩은 DB 잠금 밖에서 수행합니다.

## 프로필 이미지

JPEG/PNG/WebP 실제 형식만 허용합니다. 파일명·클라이언트 Content-Type만으로 허용하지 않습니다. 최대 업로드 크기는 5 MiB, 가로·세로는 각각 4,096px입니다. PNG/WebP 애니메이션은 거부합니다. 메타데이터를 승계하지 않은 새 이미지로 PNG를 인코딩하며 재인코딩 결과가 MEDIUMBLOB 한도인 16 MiB 미만이어야 합니다.

WebP 디코더는 [TwelveMonkeys ImageIO 3.15.0](https://github.com/haraldk/TwelveMonkeys/releases/tag/twelvemonkeys-3.15.0)을 사용합니다.

현재 저장 어댑터는 `member_profile_image` 테이블입니다. V2 Flyway 마이그레이션이 이를 생성합니다. 교체·삭제와 회원의 이미지 참조 변경이 하나의 트랜잭션으로 처리됩니다. 별도 파일 서버 설정 없이 동작하며, 향후 객체 저장소로 전환할 수 있도록 저장·인코딩 포트를 분리했습니다.

반환 URL `/api/v1/me/profile-image`는 본인 전용 인증 경로입니다. 프론트에서는 Authorization 헤더를 붙여 fetch한 뒤 Blob URL로 표시합니다. `<img src>`만으로 Bearer 인증을 전달할 수 없습니다. 공개 프로필 이미지 제공은 별도 정책으로 확장해야 합니다.

주요 오류는 409 NICKNAME_ALREADY_EXISTS/EMAIL_ALREADY_EXISTS, 400 INVALID_PROOF/INVALID_VERIFICATION, 413 IMAGE_TOO_LARGE, 422 INVALID_IMAGE/INVALID_IMAGE_DIMENSIONS/ANIMATED_IMAGE_NOT_ALLOWED입니다.
