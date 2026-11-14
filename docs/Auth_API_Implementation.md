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
