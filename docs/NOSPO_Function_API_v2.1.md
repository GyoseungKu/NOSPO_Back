# NOSPO 기능명세서 · API 명세서 v2.1

기능명세와 API 명세를 함께 확인하기 위한 통합 문서입니다. 기능별 설명·정책과 API의 요청·응답·예시를 포함합니다.

- 기능명세 출처: `function_v2.0.pdf`
- API 명세 출처: `api_v2.0.pdf`
- 변환 참고: API PDF에서 오른쪽이 잘린 표의 열은 동일 버전의 `NOSPO_API_Spec_v2.0.md` 원문을 대조하여 복원했습니다. PDF의 페이지 번호와 파일 첨부 표시는 제외했습니다.

## v2.1 변경 범위

위 출처는 v2.0 원본이며, v2.1은 제공된 변경 사항을 반영한 개정본입니다.

- 인물 성별(선택, 최대 50자)·성향(선택, 최대 1,000자)을 추가하고 인물·단체 프로필 모델을 구분합니다.
- 소속은 현재 초안의 단체에서 검색·선택합니다. 기존 AFFILIATION 관계 및 같은 초안 내 연결 규칙을 유지하며 별도 검색 API를 추가하지 않습니다.
- 기존 이미지 업로드·검수와 JPEG·PNG·WebP, 5 MiB, 실제 형식 검증·메타데이터 제거 정책을 유지합니다. 정지 이미지·가로/세로 각각 4,096px 제한과 화면·저장 처리 규칙을 보완합니다.
- 초안 이어쓰기, 저장 버튼, 로컬 미저장 변경 폐기, 별도 전체 초안 삭제 및 부분 저장 실패 처리를 명시합니다. revision-drafts의 기존 초안 재사용 규칙은 유지합니다.
- API 경로의 추가·삭제·변경은 없습니다. 인증·회원가입·도서·독서량·AI 비용·탈퇴 등 나머지 정책은 v2.0과 동일합니다.

## 목차

- [제1부. 기능명세서](#제1부-기능명세서)
- [제2부. API 명세서](#제2부-api-명세서)

## 제1부. 기능명세서

### 인증 / 로그인

- 이메일 로그인 (이메일 / 비밀번호 → 인증 토큰 발급)

- 카카오 로그인 시작

- 카카오 인증 결과 처리 (기존 회원 로그인 / 신규 회원 가입 절차 연결)

- 인증 토큰 재발급

- 로그아웃 (현재 로그인 세션 및 토큰 무효화)

> 이메일 로그인과 카카오 로그인이 연결된 회원은 어느 방식으로 로그인해도 동일한 회원 계정으로 접속합니다.

> 이메일이 같다는 이유만으로 계정을 자동 연결하지 않습니다. 기존 계정이 있으면 해당 계정으로 로그인한 후 카카오 연결을 진행하도록 안내합니다.

### 이메일 회원가입

- 닉네임 중복 확인

- 이메일 중복 확인

- 회원가입 이메일 인증 링크 발송 / 재발송

- 이메일 인증 링크 검증

- 휴대전화번호 입력

- 회원가입 완료 (닉네임 / 이메일 인증 증명 / 비밀번호 / 휴대전화번호 / 약관 동의 내역)

- 가입 기본 AI 토큰 5개 지급

- 가입 완료 후 자동 로그인

> 실명은 수집하지 않습니다. 휴대전화번호는 필수로 수집하지만 문자 인증은 진행하지 않으며, 본인 인증이나 계정 소유권 확인에 사용하지 않습니다.

> 닉네임·이메일 중복 여부는 가입 완료 시 다시 확인합니다. 비밀번호 확인값 비교는 프론트에서 처리하고, 비밀번호 정책 검증과 Argon2id 해시 저장은 서버에서 처리합니다.

### 카카오 회원가입

- 신규 소셜 가입에 필요한 정보 조회 (카카오 제공 정보 / 추가 입력 항목)

- 닉네임 입력 및 중복 확인

- 사용 가능한 이메일이 없는 경우 별도 이메일 입력 및 링크 인증

- 휴대전화번호 입력

- 소셜 회원가입 완료 (소셜 인증 증명 / 닉네임 / 이메일 정보 또는 인증 증명 / 휴대전화번호 / 약관 동의 내역)

- 가입 기본 AI 토큰 5개 지급

- 가입 완료 후 자동 로그인

> 카카오에서 제공한 이메일은 제공 여부와 인증 상태를 서버에서 확인합니다. 사용할 수 없는 경우 별도 이메일 인증을 진행합니다.

> 카카오 회원가입 시 비밀번호를 등록하지 않습니다. 가입 후 이메일 로그인 기능을 추가할 수 있습니다.

### 로그인 방식 연결

- 내 로그인 방식 및 연결 상태 조회

- 이메일 계정에 카카오 연결 시작

- 현재 계정 본인 재인증

- 연결할 카카오 계정 인증 및 연결 완료

- 카카오 전용 회원의 이메일 로그인 추가를 위한 이메일 인증

- 비밀번호 설정 및 이메일 로그인 추가 완료

> 카카오 연결은 기존 계정으로 로그인한 상태에서 본인 재인증과 카카오 인증을 모두 완료해야 합니다.

> 카카오 회원의 이메일 로그인 추가는 카카오 재인증 후 진행합니다. 추가 완료 후부터 비밀번호 변경·재설정 기능을 사용할 수 있습니다.

> 이미 다른 회원 계정에 연결된 카카오는 연결할 수 없습니다. 기존 두 회원 계정의 데이터 병합과 로그인 방식 연결 해제는 이번 구현 범위에서 제외합니다.

> 로그인 방식 추가는 신규 회원가입이 아니므로 기본 토큰을 다시 지급하지 않습니다.

### 비밀번호 재설정 — 비로그인 상태

- 비밀번호 재설정 인증번호 발송 / 재발송

- 인증번호 검증 및 재설정 권한 발급

- 비밀번호 재설정 (재설정 권한 / 신규 비밀번호)

- 재설정 완료 후 기존 로그인 세션 전체 무효화

> 이메일 로그인이 활성화된 회원에게 제공합니다. 카카오 전용 회원은 카카오로 로그인한 후 이메일 로그인 추가 기능을 이용합니다.

### 약관

- 회원가입에 필요한 약관 목록 조회

- 약관 상세 조회 (약관 ID / 버전)

- 내 약관 동의 이력 조회

- 재동의 대상 약관 조회

- 개정 약관 동의 제출

> 최초 약관 동의는 회원가입 완료 시 함께 저장합니다. 약관 개정 시 새 버전을 등록하고, 회원이 탈퇴하기 전까지 해당 회원의 동의 이력을 관리합니다.

### 내 정보

- 내 정보 조회 (회원 ID / 닉네임 / 이메일 / 휴대전화번호 / 프로필 사진 / 사용 가능한 로그인 방식)

- 닉네임 변경

- 휴대전화번호 변경

- 프로필 사진 등록 / 변경

- 프로필 사진 삭제

- 이메일 변경을 위한 본인 재인증 (현재 비밀번호 또는 연결된 카카오 인증)

- 이메일 변경 인증 발송

- 이메일 변경 인증 확인

- 이메일 변경 완료

- 비밀번호 변경 (현재 비밀번호 / 신규 비밀번호)

- 비밀번호 변경 완료 후 기존 로그인 세션 전체 무효화

> 이메일 변경 완료 시 중복 여부를 다시 확인합니다. 이메일 로그인을 사용하는 경우 변경된 이메일을 로그인에 사용합니다.

> 서비스 이메일을 변경해도 연결된 카카오 계정은 유지합니다. 카카오 연결은 이메일이 아닌 카카오 계정 식별자를 기준으로 관리합니다.

### 회원 탈퇴

- 회원 탈퇴 안내 및 삭제 대상 확인

- 회원 탈퇴를 위한 본인 재인증

- 회원 탈퇴 요청 및 처리

- 모든 로그인 세션과 인증 토큰 무효화

- 개인정보 및 개인 데이터 삭제

> 삭제 대상은 이메일·휴대전화번호·프로필·비밀번호·소셜 연결 정보·약관 동의 이력·서재·독서 기록·검색 이력·AI 대화·관계도·토큰 잔액과 개인 거래 이력·미승인 초안 및 제출본입니다.

> 진행 중인 AI 작업은 중단하거나 결과 저장을 차단하여 탈퇴 후 개인 데이터가 다시 생성되지 않도록 합니다.

> 이미 승인된 공용 위키는 작성자 연결과 식별 정보를 제거한 뒤 유지합니다. 최소 운영 로그에 는 회원 ID·이메일·휴대전화번호·IP 등 개인을 식별하거나 다시 연결할 수 있는 정보를 남기지 않습니다.

> 개인정보와 개인 데이터는 탈퇴 처리 시 즉시 삭제하며, 복구 기능을 제공하지 않습니다.

### 공지사항

- 공지사항 목록 조회 (페이징)

- 공지사항 상세 조회

### 메인 / 마이페이지 요약

- 지금 읽고 있는 책 조회

- 월간 독서량 요약 조회 (새롭게 읽은 페이지 / 화)

- 주간 독서 활동 요약 조회 (날짜별 독서량 / 활동 여부)

- 내 위키 초안 현황 조회 (임시저장 건수)

- 내 위키 검수 현황 조회 (검수 중 / 수정 요청 / 승인 / 반려 건수)

- 사용 가능한 AI 토큰 조회

> 홈 화면에서는 일반 도서의 페이지와 웹소설의 화를 구분하여 표시합니다. 웹소설의 권·챕터는 현재 위치를 설명하는 구간 정보로 표시하고, 회독 수는 표시하지 않습니다.

### 도서 검색 / 도서 정보

- 도서 검색 (검색어 / 검색 유형: 제목·저자·ISBN / 페이징)

- 검색 결과의 판본 구분 표시

- 도서 기본 정보 조회 (제목 / 저자 / 출판사 / 판본 / ISBN / 표지 / 분량 등)

- 웹소설 기본 정보 및 권·챕터·화 구성 조회

- 최근 나의 검색 이력 조회

- 최근 검색 이력 개별 삭제

- 최근 검색 이력 전체 삭제

> 일반 도서는 외부 도서 검색 API를 사용합니다. 웹소설은 카카오페이지·문피아 등의 인기 판타지 작품을 대상으로 사전 구성한 임시 데이터를 사용합니다.

> 도서 검색과 기본 정보 조회는 비로그인 상태에서도 제공합니다. 개인 검색 이력은 로그인한 사용자의 검색에 대해서만 저장합니다.

> 같은 작품이라도 판본별로 별도 도서로 검색·등록하고, 독서 진도와 위키를 독립적으로 관리합니다. 다른 판본의 진도·위키를 자동 공유하거나 변환하지 않습니다.

### 내 서재

- 내 서재 도서 목록 조회 (일반 도서·웹소설 / 정렬 / 페이징)

- 내 서재 도서 등록 (판본별 도서 ID / 현재 읽은 지점)

- 내 서재 도서 상세 조회

- 내 서재에서 도서 삭제

> 동일 판본은 서재에 중복 등록하지 않습니다. 다른 판본은 별도로 등록할 수 있습니다.

> 서재에서 삭제해도 공용 도서·위키는 삭제하지 않습니다. 독서 기록과 최고 도달 지점은 유지 하여 재등록 시 중복 집계를 방지합니다.

> 위키 열람과 AI 기능은 로그인 후 서재에 등록한 도서에 대해 제공합니다.

### 읽은 지점 / 독서 기록

- 도서별 현재 읽은 지점 및 최고 도달 지점 조회

- 도서별 읽은 지점 변경 (일반 도서: 페이지 / 웹소설: 화)

- 웹소설 현재 지점의 권·챕터·화 표시

- 월별 독서 기록 조회 (캘린더용)

- 기간별 독서 기록 조회 (주 / 일 / 직접 지정 기간)

- 도서별 독서 진행 이력 조회

> 기존 최고 도달 지점을 넘어선 증가분만 독서량으로 집계합니다. 이전 지점으로 이동해도 누적 독서량을 차감하지 않으며, 최고 도달 지점까지 다시 읽은 양은 추가 집계하지 않습니다.

> 최초 등록 시 입력한 지점은 이미 읽은 분량으로 간주하여 집계에서 제외합니다. 처음부터 기록하려면 지점 0으로 등록합니다.

> 웹소설은 작품 내 순서가 고정된 화를 기준으로 진도를 관리하고, 해당 화가 속한 권·챕터를 함께 표시합니다. 페이지·화·권·챕터를 섞어 합산하지 않습니다.

> 날짜별 통계는 한국 시간 기준으로 제공합니다.

### 위키 조회

- 판본별 위키 존재 여부 / 기본 정보 조회

- 등장인물 목록 조회 (조회 기준 지점 / 소속 등 필터 / 페이징)

- 인물 상세 조회 (이름 / 성별 / 성향 / 역할 / 설명 / 사진 / 공개 지점 / 주요 행적 / 능력·기술 / 관계 / 기타)

- 단체 목록 조회 (조회 기준 지점 / 페이징)

- 단체 상세 조회 (기본 정보 / 구성원 / 관계 / 주요 행적)

- 관계 목록 조회 (조회 기준 지점 / 관계 유형 / 인물·단체 필터)

> 서버에 저장된 현재 읽은 지점을 공개 범위의 상한으로 적용합니다. 더 이전 지점으로 조회할 수 있지만, 이후 정보를 보려면 현재 읽은 지점을 먼저 변경해야 합니다.

> 인물·단체·행적·관계·이미지 등 모든 정보에 공개 지점을 적용합니다. 이전 지점으로 돌아가 면 이후 정보는 다시 숨깁니다.

### 위키 작성 / 수정 초안

- 위키 작성 초안 생성 (판본별 도서 ID / 작성 기준 지점)

- 기존 위키 수정 초안 생성 (대상 위키 / 기준 버전 / 작성 기준 지점)

- 내 초안 목록 조회

- 초안 상세 조회

- 초안 기본 정보 수정

- 전체 초안 삭제 (초안 관리 화면의 별도 동작, 삭제 확인 후 실행)

- 초안 검수 제출

> 초안은 작성자 본인이 관리하며, 저장 버튼으로 현재 변경을 서버 초안에 반영합니다. 입력할 때마다 API를 호출한다는 의미가 아닙니다. 편집 내용은 관리자 승인 후 공개 위키에 반영합니다.

> 편집 시작 시 해당 판본의 작성 중인 초안을 확인합니다. 이어쓸 초안이 하나면 해당 초안을 사용하고, 여러 개면 사용자가 선택합니다. 일반 수정에서 이어쓸 초안이 없으면 UPDATE 초안을 생성합니다. 이어쓰기 시 기존 기준 위키 버전을 최신 버전으로 자동 교체하지 않습니다.

> ‘저장 안 함’은 현재 화면의 미저장 변경만 폐기하고 기존 서버 초안은 유지합니다. DELETE를 호출하지 않으며, 이미 저장된 변경의 롤백 API는 제공하지 않습니다. 여러 API로 저장하다 일부가 실패하면 성공한 요청은 유지하고 전체 저장 성공으로 표시하지 않습니다.

> 수정 초안에는 기준이 된 공개 위키 버전을 기록하여 다른 변경 사항과의 충돌을 확인합니다.

### 초안 내 인물 관리

- 인물 추가

- 인물 기본 정보 추가 / 수정 (이름 / 성별 / 성향 / 역할 / 설명 / 사진 / 공개 지점)

- 인물 삭제

- 인물 주요 행적 추가 / 수정 / 삭제

- 인물 능력·기술 추가 / 수정 / 삭제

- 인물 기타 정보 추가 / 수정 / 삭제

- 인물 소속 정보 추가 / 수정 / 삭제

> 성별은 선택 입력으로 최대 50자, 성향은 선택 입력으로 최대 1,000자입니다. 성향은 일반 설명과 분리하여 저장하고, 값이 없으면 null로 반환합니다.

> 소속은 현재 초안의 단체를 이름으로 검색·선택합니다. 수정 초안에 복제된 기존 공개 단체와 현재 초안에 새로 추가한 단체 모두 선택할 수 있습니다. 대상이 없으면 현재 초안에 단체를 추가한 후 연결합니다.

> 기본 정보도 공개 지점별로 관리합니다. 세부 정보에는 공개 지점과 근거 페이지 또는 화를 기록합니다.

### 초안 내 단체 관리

- 단체 추가

- 단체 기본 정보 추가 / 수정 (이름 / 설명 / 이미지 / 공개 지점)

- 단체 삭제

- 단체 구성원 추가 / 수정 / 삭제

- 단체 주요 행적 추가 / 수정 / 삭제

> 인물의 소속과 단체의 구성원은 동일한 관계 데이터로 관리합니다. 인물·단체 삭제 시 연결된 관계도 함께 검증합니다.

### 초안 내 관계 관리

- 관계 추가 (출발 대상 / 도착 대상 / 관계 유형 / 설명 / 공개 지점)

- 관계 수정

- 관계 삭제

- 관계의 변화 및 종료 정보 기록

> 인물 간·단체 간·인물과 단체 간 관계를 모두 지원합니다. 관계 유형은 우호·대립·중립·소속 등을 사용합니다.

> 관계의 변화·종료 정보에도 공개 지점을 적용하여 이후 사건이 미리 노출되지 않도록 합니다.

### 위키 이미지

- 초안에 사용할 인물·단체 이미지 업로드

- 초안 내 이미지 연결 / 변경 / 해제

- 위키 이미지 조회

- 파일 선택·로컬 미리보기 후 저장 시 업로드 및 프로필 연결
- 미참조 이미지 삭제

> JPEG·PNG·WebP 정지 이미지만 허용합니다. 파일당 최대 5 MiB, 가로·세로 각각 최대 4,096px입니다. 서버는 실제 형식·디코딩·용량·해상도·애니메이션 여부를 검증하고 EXIF 등 메타데이터를 제거합니다. 이미지는 프로필과 함께 검수하고 승인 후 공개합니다.

> 초안 이미지는 작성자와 검수 관리자만 조회합니다. 공개 위키 이미지는 승인 여부와 조회 기준 지점을 확인한 후 제공합니다.

### 위키 검수 — 사용자

- 내가 신청한 검수 목록 조회 (상태 / 페이징)

- 검수 신청 상세 조회 (제출 내용 / 처리 단계 / 검수 의견 / 승인 보상)

- 수정 요청을 받은 제출 건의 수정 초안 생성

- 수정 내용 재제출

- 내 위키 활동 이력 조회 (기간 / 도서 / 활동 유형)

> 제출본은 고정하고, 이후 수정은 별도 초안으로 관리합니다. 재제출 건은 이전 제출 건과 연결합니다.

> 신규 작성과 수정 모두 보상 대상입니다. 관리자가 최종 승인할 때 결정한 수량을 지급하며, 수정 요청이나 재제출 자체에는 보상하지 않습니다.

### AI 자연어 질문

- 질문 요청 (판본별 도서 ID / 조회 기준 지점 / 질문 / 대화 ID)

- 답변 스트리밍 수신

- 질문 처리 상태 및 완료된 답변 조회

- 내 대화 목록 조회

- 대화 상세 및 질문·답변 이력 조회

> 대화 ID가 없으면 새 대화를 생성하고, 있으면 본인의 동일 판본 대화에 질문을 추가합니다.

> 해당 판본의 승인된 위키 중 조회 기준 지점까지 공개된 정보만 사용합니다. 이전 대화에도 동 일한 범위 제한을 적용하며, 근거가 부족하면 답변할 수 없음을 안내합니다.

> 요청 접수 시 조회 기준 지점과 위키 버전을 고정합니다. 현재 읽은 지점을 이전으로 변경하 면 이후 정보가 포함된 기존 대화·답변의 조회를 제한합니다.

### AI 관계도

- 관계도 생성 요청 (판본별 도서 ID / 조회 기준 지점)

- 관계도 생성 작업 상태 조회 (대기 / 처리 중 / 완료 / 실패)

- 생성된 관계도 조회

- 내 관계도 목록 조회

- 관계도 재생성 요청

- 현재 읽은 지점과 생성 기준 지점의 일치 여부 및 재생성 필요 여부 확인

> 생성 요청 → 작업 ID 반환 → 상태 조회 → 결과 조회 방식으로 처리합니다. 생성 기준 지점과 위키 버전을 고정합니다.

> 현재 읽은 지점 이후의 정보가 포함된 기존 관계도는 조회를 제한합니다.

### AI 토큰

- 내 AI 토큰 잔액 조회 (보유 / 처리 중 예약 / 사용 가능 수량)

- AI 기능별 이용 비용 조회

- 내 AI 토큰 거래 이력 조회 (사용 / 지급 / 회수 / 복구 / 기간 / 페이징)

- AI 토큰 거래 상세 조회

| 항목 | 적용 정책 |
| --- | --- |
| 신규 가입 | 5토큰 지급 |
| 자연어 질문 | 질문 1건당 1토큰 |
| 관계도 생성 | 생성 1건당 1토큰 |
| 관계도 재생성 | 재생성 1건당 1토큰 |
| 기존 대화·결과 조회 | 차감 없음 |
| 처리 실패 | 차감 없음 |
| 위키 승인 보상 | 관리자가 규칙에 따라 수량 지정, 0토큰 허용 |

> 요청 시 필요한 수량을 예약하고 정상 완료 시 차감합니다. 실패 시 예약을 해제하며, 잘못 차감된 경우 복구 이력을 기록합니다.

> 동일 요청의 재전송·상태 조회·스트리밍 재접속으로 중복 차감하지 않습니다. 위키 근거 부족을 안내하는 정상 응답과 시스템 처리 실패는 구분합니다.

### 관리자 — 위키 검수

- 검수 요청 목록 조회 (상태 / 도서 / 페이징)

- 검수 요청 상세 조회 (인물 성별·성향·이미지 및 해당 공개 지점 포함)

- 기존 위키와 제출 내용의 차이 조회

- 기준 위키 버전 변경 및 충돌 확인

- 승인 보상 수량 및 산정 사유 입력

- 검수 승인 및 보상 지급

- 수정 요청 (수정 요청 내용 포함)

- 검수 반려 (반려 사유 포함)

- 검수 처리 이력 조회

> 관리자는 운영 규칙에 따라 보상 수량을 지정한 뒤 승인합니다. 보상 없이 승인할 경우 0토큰 을 입력합니다.

> 승인 시 위키 반영과 보상 지급을 연결하고 중복 승인·중복 지급을 방지합니다. 처리자·시각· 의견·보상 수량·산정 사유를 기록합니다.

> 현재 위키와 제출 내용에 충돌이 있으면 바로 승인하지 않고 수정 요청을 통해 해소합니다.

### 관리자 — 공지사항 / 약관 / 토큰

- 공지사항 목록 / 상세 조회

- 공지사항 등록 / 수정 / 삭제

- 약관 버전 목록 / 상세 조회

- 약관 버전 등록

- 약관 시행일 및 재동의 대상 설정

- 사용자별 AI 토큰 잔액 / 거래 이력 조회

- AI 토큰 수동 지급 / 회수 / 복구 (처리 사유 기록)

> 토큰 조정은 처리자·대상 회원·수량·사유를 기록합니다. 차감 복구는 원거래와 연결하여 중복 복구를 방지합니다. 회원 탈퇴 시 대상 회원을 식별할 수 있는 정보와 개인 거래 이력은 삭제합니다.

---

## 제2부. API 명세서

작성 기준: 2026-11-06 확정 기능명세서. 프론트엔드·백엔드 구현 및 협의용 REST API 계약.

이 문서는 확정된 서비스 정책을 반영한다. 인증 유효시간·입력 길이·페이지 크기 등 숫자는 구현을 위한 초기 설계값이며 실제 구현을 확인한 결과가 아니다. 특정 도서 검색 업체에 종속되지 않는 내부 도서 모델을 사용한다.

### 1. 확정 정책

| 구분 | 정책 |
| --- | --- |
| 개인정보 | 실명 수집 안 함. 휴대전화번호 필수, 문자 인증 없음. 번호를 본인 인증·계정 복구 근거로 사용하지 않음 |
| 로그인 | 이메일·카카오 지원. 가입 완료 시 자동 로그인 |
| 계정 연결 | 이메일 일치만으로 자동 연결 금지. 기존 계정 재인증 및 카카오 인증 후 연결. 기존 두 계정의 병합·연결 해제 없음 |
| 카카오 전용 회원 | 재인증·이메일 인증·비밀번호 설정 후 이메일 로그인 추가 가능 |
| 도서 | 일반 도서는 외부 검색 API 연동. 웹소설은 카카오페이지·문피아 인기 판타지 기반 사전 구성 임시 메타데이터 사용. 본문 수집 기능은 없음 |
| 판본 | bookId는 판본 식별자. 동일 작품의 다른 판본은 진도·서재·위키·대화·관계도를 독립 관리 |
| 독서량 | 최고 진도 증가분만 집계. 최초 등록 지점·재독은 제외. 회독 수 미표시 |
| 독서 단위 | 일반 도서 PAGE, 웹소설 EPISODE. 권·챕터는 구간 정보로 표시하며 독서량과 합산하지 않음 |
| 위키·AI | 로그인 및 해당 판본의 서재 등록 필요. 현재 진도를 공개 상한으로 사용 |
| AI 자료 | 해당 판본의 승인된 위키 중 공개 가능한 정보만 사용 |
| AI 비용 | 가입 5 credits. 질문·관계도 생성·재생성 각각 1 credit. 결과 조회 무료. 시스템 실패는 차감 없음 |
| 검수 보상 | 관리자 승인 시 수량·산정 사유 지정. 0 허용. 최종 승인 시 1회 지급 |
| 탈퇴 | 개인정보·개인 데이터 즉시 삭제. 승인된 공용 위키는 작성자 식별 정보를 제거하고 유지. 식별 불가능한 최소 운영 로그만 유지 |

### 2. 공통 계약

#### 2.1 전송·권한

- 기본 경로: `/api/v1`. 이하 모든 경로는 이 접두사를 생략한다.
- JSON 필드는 camelCase. JSON 요청은 `Content-Type: application/json`.
- 업로드는 multipart/form-data, 파일 필드명 `file`. SSE·리다이렉트·이미지·204는 공통 JSON 포맷의 예외.
- 인증: `Authorization: Bearer {accessToken}`. Access Token 15분, Refresh Token 14일. Refresh는 회전하며 서버 세션에 연결한다.
- Refresh는 Secure·HttpOnly 쿠키. Path `/api/v1/auth`, SameSite=Lax를 초기 배포 기준으로 사용한다. 프론트/API는 동일 사이트 배포 기준. 쿠키 변경·인증 API의 Origin을 허용 프론트와 검증한다. CORS는 명시적 허용 Origin만 허용하고 credentials 사용 시 와일드카드 금지.
- 권한 표기: `공개`, `회원`, `본인`, `관리자`. 본인은 인증된 회원의 소유 리소스만 접근 가능. 회원·본인 경로도 매번 객체 권한을 검증한다.
- 개인 리소스 소유권 불일치는 404. 관리자 권한 부족은 403. 회원 ID를 요청으로 받아 본인으로 간주하지 않는다.
- UUID 식별자, 시각은 UTC ISO 8601 문자열. 날짜는 YYYY-MM-DD, 월은 YYYY-MM. 독서 통계 시간대는 Asia/Seoul.
- Path 식별자는 모두 필수. 표의 요청 필드는 기본 필수이며 `?`만 선택. 요청 미기재는 body 없음. 응답 `없음`은 204.
- PATCH는 명시된 필드만 수정한다. null은 명시적으로 nullable인 필드에만 허용. PUT은 해당 하위 리소스의 전체 교체다.
- 모든 개인·인증·진도별 위키·AI 응답은 `Cache-Control: no-store`. 개인화 응답을 공용 캐시하지 않는다.

#### 2.2 응답과 페이지네이션

```json
{"data":{"id":"UUID"},"meta":{"requestId":"UUID"}}
```

```json
{"data":{"items":[],"nextCursor":null,"hasNext":false},"meta":{"requestId":"UUID"}}
```

```json
{"error":{"code":"VALIDATION_ERROR","message":"입력값을 확인해 주세요.","details":[{"field":"nickname","reason":"INVALID_FORMAT"}]},"meta":{"requestId":"UUID"}}
```

- 목록 공통 쿼리 `cursor?: string`, `limit?: integer` (기본 20, 1~100). 다음 커서가 없으면 nextCursor=null, hasNext=false.
- 표의 `Page<T>`는 위 목록 형태다. 필터·정렬 변경 시 cursor를 초기화한다. 기본 정렬은 createdAt DESC, id DESC, 별도 명시한 정렬에는 id를 최종 타이브레이커로 사용한다.
- 기간 필터 `from`, `to`는 양 끝 날짜 포함. 통계·기록 조회 최대 366일. 달력은 지정 월 전체.
- 성공 코드: 조회·수정·검증 200, 생성 201, 작업 접수 202, 본문 없는 성공 204, 이동 302.

#### 2.3 입력 및 공통 모델

| 모델/필드 | 계약 |
| --- | --- |
| nickname | NFC 정규화 후 2~20자, 한글·영문·숫자·밑줄. 앞뒤 공백 제거, 영문 대소문자를 구분하지 않는 중복 검사 |
| email | 최대 254자. 앞뒤 공백 제거 및 대소문자 정규화된 로그인 키 사용. 인증 완료된 이메일만 가입·변경·추가 로그인에 사용 |
| phoneNumber | E.164 형태의 휴대전화번호 문자열. 국내 입력은 +82로 정규화. phoneVerified=false. 번호에 회원 간 유일 제약 없음 |
| password | 10~128자, 공백 자동 제거·변형 금지. 서버 정책 검증 및 Argon2id 해시 저장. 응답·로그에 기록 금지 |
| Position | `{unit: "PAGE" 또는 "EPISODE", value: integer >= 0}`. 0은 읽기 전. 판본의 단위와 일치, 알려진 총 분량 초과 금지 |
| PositionContext | `{volumeLabel: string\|null, chapterLabel: string\|null, episodeLabel: string\|null}`. 서버가 구조 메타데이터로 계산 |
| Member | `{memberId,nickname,email,phoneNumber,phoneVerified:false,profileImageUrl:null\|string,loginMethods:["EMAIL","KAKAO"],createdAt}` |
| AuthResult | `{accessToken,tokenType:"Bearer",expiresIn:900,member:Member}` + Refresh Set-Cookie |
| ConsentInput | `{termVersionId:UUID,agreed:boolean}`. 필수 약관은 true 필수, 선택 항목도 동의/미동의를 명시 |
| VerificationIssued | `{verificationId:UUID,expiresAt,resendAvailableAt}` |
| Proof | 목적별 이름의 불투명 증명 문자열 + expiresAt. 회원/이메일/인증 요청/목적에 결합 |
| Credits | `{balance:integer,reserved:integer,available:integer}`; available=balance-reserved, 모든 값 0 이상 |
| ReviewState | PENDING / CHANGES_REQUESTED / APPROVED / REJECTED |
| JobState | QUEUED / RUNNING / SUCCEEDED / FAILED |

#### 2.4 인증 증명·재발송

- 이메일 링크·인증번호 10분, 검증 후 발급한 완료 증명 10분, 재인증 증명 5분, 카카오 교환 코드 60초, 소셜 가입 증명 15분.
- 인증번호 6자리, 인증 요청당 최대 5회 실패. 재발송 간격 60초. 발송은 이메일·회원·IP 기반 제한. 제한 시 429 + Retry-After.
- 재발송은 동일 발송 API 재호출. 같은 목적·대상에 대한 이전 인증 요청과 미사용 증명을 무효화한다.
- 인증 링크는 프론트 확인 화면을 열고, 프론트가 POST confirm을 호출한다. GET 메일 링크 방문만으로 가입·변경을 확정하지 않는다.
- 증명은 성공한 최종 동작에서만 원자적으로 소비한다. 검증 실패 시 가입·변경을 부분 반영하지 않는다.
- 비밀번호 재설정은 계정 없음·카카오 전용 여부를 발송 응답으로 노출하지 않는다. 동일한 202 형태를 반환하고 실제 대상에만 발송한다.

##### 비동기 메일 발송과 재발송 원자성

- 발송 대상 인증 요청과 `email_delivery`를 같은 DB 트랜잭션으로 저장한다. 202는 작업 접수이며 실제 전송은 워커가 수행한다. 계정 존재를 숨기는 비밀번호 재설정의 비대상 요청에는 기존과 같은 202 응답만 반환한다.
- 발송 상태는 `PENDING / PROCESSING / ACCEPTED / FAILED / CANCELLED`. ACCEPTED는 메일 제공자의 접수 확인이며 받은편지함 도착을 보장하지 않는다.
- 워커는 발송 직전 요청의 유효기간·무효화·회원 상태를 확인한다. 만료·무효화된 요청은 CANCELLED, 영구 오류는 FAILED로 종료한다. 일시 오류만 인증 유효기간 내에서 횟수를 제한하여 재시도한다.
- 원문 토큰·메일 자료는 재시도에 필요한 기간에만 암호화 보관한다. 제공자 접수 확인 또는 최종 실패·취소·만료 시 암호문과 키 참조를 삭제한다. 제공자 응답 유실로 중복 전송될 수 있으나 같은 요청의 같은 토큰·링크를 재사용하며 새 인증을 만들지 않는다.
- 계정 이메일과 인증 대상 이메일은 동일한 정규화 함수를 사용한다. 링크는 충분한 난수 토큰을 발급하고 해시를 저장·인덱스 조회한다. 짧은 인증번호는 요청 식별자·목적에 결합한 서버 비밀키 HMAC으로 검증한다.
- 정규화 이메일+인증 목적 단위로 동시 재발송을 직렬화한다. 최초 요청처럼 기존 행이 없는 경우에도 직렬화가 보장되어야 한다. 이전 요청·미사용 증명 무효화, 대기 발송 취소, 신규 요청·발송 작업 생성을 하나의 트랜잭션으로 처리한다.
- 이미 제공자에게 전송 중인 메일은 회수할 수 없다. 취소 요청을 기록하고 이전 링크의 검증을 차단한다. 워커 임대 만료 후 재점유와 소유권 검사로 오래된 워커가 취소 상태를 되돌리지 못하게 한다.
- 60초 재발송 간격을 유지한다. 이메일·회원·IP별 제한 카운터는 TTL이 있는 저장소에서 관리하며 단일 프로세스 메모리에만 의존하지 않는다.
- 재발송으로 무효화된 링크는 검증을 거부한다. 무효화되지 않은 이미 검증된 링크의 재클릭은 `409 CHALLENGE_ALREADY_VERIFIED`이며 증명을 재발급하거나 기존 유효 증명을 무효화하지 않는다. 프론트가 증명을 잃으면 새 인증 메일을 요청한다.

##### 인증 자료별 수명과 소유 관계

| 자료 | 수명·정리 기준 |
| --- | --- |
| 인증 요청 | 링크·인증번호 유효기간. 유효한 증명이나 처리 중 발송 작업이 참조하면 부모 행을 보존 |
| 인증 증명 | 검증·발급 시점부터 목적별 10분/5분/15분. 요청 만료와 별개 |
| 카카오 가입 컨텍스트 | signupProof 수명. 제공자 식별자·이메일·검증 여부·닉네임 후보 등 최종 가입 정보 포함 |
| 발송 작업 | 발송·제한적 재시도에 필요한 기간. 종료·만료 시 민감 발송 자료 즉시 제거 |
| 가입 멱등 응답 | 기존 24시간. 탈퇴 시 함께 삭제 |
| 가입 중단 자료 | 관련 유효 증명·발송 작업이 없어지고 참조 관계를 정리한 뒤 삭제 |

- 예: 12:10 만료 링크를 12:09에 검증하면 가입 증명은 12:19까지 유효하다. 12:10에 부모 요청을 삭제하면서 유효한 증명을 CASCADE 삭제하지 않는다.
- ERD는 인증 요청→증명, 증명→카카오 가입 컨텍스트, OAuth 흐름→컨텍스트에 RESTRICT를 적용한다. 유효한 자식을 보존하고 자식부터 정리한다. 부모 행 보존은 만료된 링크나 교환 코드의 재사용 허용을 뜻하지 않는다.
- 가입 성공에 사용한 인증 요청·증명·발송·카카오 컨텍스트·교환 자료·멱등 응답은 최종 회원에 귀속시키거나 즉시 정리한다. 소유 관계는 현재 이메일 문자열에 의존하지 않는다. 이메일 변경 전 주소를 담은 자료도 해당 회원의 탈퇴 대상이다.

#### 2.5 충돌 및 중복 방지

- 진도 GET, 초안 상세 GET, 관리자 검수 상세 GET은 ETag를 반환한다.
- 진도 PATCH, 초안 하위의 모든 변경·삭제·제출, 검수 결정에는 If-Match 필수. 누락 428, 불일치 412. 초안 하위 변경은 초안 전체 revision을 증가시킨다. 성공 시 새 ETag 반환(초안 삭제 제외).
- 가입·로그인 방식 연결 완료·검수 제출·AI 생성·검수 결정·토큰 조정/복구에는 Idempotency-Key(UUID) 필수. 범위는 회원(가입 전은 증명 또는 가입 시도 식별자)+HTTP method+path+key, 보관 24시간.
- 동일 키·동일 요청은 최초 상태 코드/결과 재사용, 다른 본문은 409 IDEMPOTENCY_CONFLICT. 진행 중 재시도는 409 REQUEST_IN_PROGRESS + Retry-After. 비밀 증명 소비 여부 확인보다 먼저 동일 요청 재실행 여부를 확인한다.
- 가입 자동 로그인 결과 재전송 자료는 암호화·접근제한하여 보관하며 토큰 유효기간을 연장하지 않는다. 탈퇴 시 삭제한다.
- 키 보관 기간과 별개로 회원 가입 보상, 제출 건 보상, AI 작업 차감, 원거래 복구에는 DB 유일 제약·원장으로 영구적인 중복 방지를 적용한다(탈퇴 시 개인 원장은 삭제).

### 3. 인증·이메일 회원가입

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 이메일 로그인 | POST | /auth/login | 공개 | email,password | 200 AuthResult |
| 재발급 | POST | /auth/token/refresh | Refresh | body 없음, Refresh 쿠키 | 200 accessToken,tokenType,expiresIn; 쿠키 회전 |
| 로그아웃 | POST | /auth/logout | 세션 | Access 또는 Refresh로 현재 세션 식별 | 204, Refresh 쿠키 삭제 |
| 닉네임 확인 | GET | /auth/availability/nickname | 공개 | Q: nickname | 200 available:boolean |
| 이메일 확인 | GET | /auth/availability/email | 공개 | Q: email | 200 available:boolean |
| 가입 메일 발송·재발송 | POST | /auth/signup/email-verifications | 공개 | email | 202 VerificationIssued |
| 가입 링크 검증 | POST | /auth/signup/email-verifications/confirm | 공개 | token | 200 emailProof,email,expiresAt |
| 가입 완료 | POST | /auth/signup | 공개 | nickname,emailProof,password,phoneNumber,consents:ConsentInput[] | 201 AuthResult |

- 가입 시 emailProof의 이메일을 사용한다. 클라이언트의 별도 이메일로 덮어쓸 수 없다. 닉네임·이메일 유일성, 현재 필수 약관을 최종 검증한다.
- 회원·계정·로그인 수단 생성, 약관 동의, 5 credits 지급, 증명 소비, 자동 로그인 세션, 가입 전 자료 귀속 및 멱등 성공 결과 저장을 하나의 DB 트랜잭션으로 확정한다. 이메일·닉네임 중복 충돌 시 모두 롤백한다. 계정 연결에는 지급하지 않는다.
- 로그아웃은 서버 세션을 폐기하고 해당 세션의 모든 JWT 사용을 막는다. 이미 폐기된 세션에 대한 반복 로그아웃은 204.
- Refresh 재사용이 탐지되면 해당 토큰 계열의 세션을 폐기한다. 프론트는 동시에 여러 재발급을 보내지 않는다.
- 주요 오류: INVALID_CREDENTIALS(401), EMAIL_ALREADY_EXISTS(409), NICKNAME_ALREADY_EXISTS(409), REQUIRED_CONSENT_MISSING(422), TERMS_VERSION_CHANGED(409), PROOF_EXPIRED(410), PROOF_ALREADY_USED(409).

### 4. 카카오 로그인·재인증·계정 연결

#### 4.1 공통 카카오 인증 흐름

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 카카오 로그인 시작 | GET | /auth/kakao/authorize | 공개 | Q: returnPath? (허용 상대경로) | 302 카카오 인증 화면 |
| 카카오 콜백 | GET | /auth/kakao/callback | OAuth 흐름 | Q: code,state 또는 error | 302 프론트 고정 콜백, exchangeCode 또는 오류 |
| 결과 교환 | POST | /auth/kakao/exchange | 흐름에 따름 | exchangeCode | 200 아래 결과 중 하나 |
| 신규 가입 정보 복원 | POST | /auth/signup/kakao/context | 공개 | signupProof | 200 email:null|string,emailVerified,nicknameSuggestion,requiredFields,expiresAt |
| 카카오 가입 | POST | /auth/signup/kakao | 공개 | signupProof,nickname,phoneNumber,emailProof?,consents | 201 AuthResult |
| 비밀번호 재인증 | POST | /me/reauth/password | 본인 | password,purpose | 200 reauthProof,expiresAt |
| 카카오 재인증 시작 | POST | /me/reauth/kakao | 본인 | purpose | 200 authorizationUrl |
| 카카오 연결 시작 | POST | /me/login-methods/kakao/authorization | 본인 | reauthProof(purpose=LINK_KAKAO) | 200 authorizationUrl |
| 카카오 연결 완료 | POST | /me/login-methods/kakao | 본인 | linkProof | 200 loginMethods |
| 로그인 방식 조회 | GET | /me/login-methods | 본인 | — | 200 loginMethods,canAddEmail,canLinkKakao |

`purpose` = CHANGE_EMAIL / ADD_EMAIL_LOGIN / LINK_KAKAO / WITHDRAWAL.

서버가 state에 연결된 흐름을 저장한다. 공통 콜백이 로그인·재인증·연결을 구분한다. state와 브라우저의 일회용 흐름 쿠키를 함께 검증하며 프론트가 임의의 목적·회원 ID를 지정할 수 없다. 로그인 상태에서 시작한 흐름은 같은 회원·세션에 묶인다. 토큰/증명을 URL에 넣지 않고 일회용 exchangeCode만 전달한다.

교환 응답은 `result`로 구분한다.

| result | 필드 | 의미 |
| --- | --- | --- |
| LOGIN_SUCCESS | auth:AuthResult | 이미 연결된 카카오. Refresh 쿠키 발급 |
| SIGNUP_REQUIRED | signupProof,expiresAt,signupInfo | 신규 가입. signupInfo는 context와 같은 정보 |
| EXISTING_ACCOUNT | nextAction:"LOGIN_AND_LINK" | 확인된 이메일이 기존 계정과 중복. 자동 가입·병합 안 함 |
| REAUTH_SUCCESS | reauthProof,purpose,expiresAt | 로그인 회원에 연결된 동일 카카오인지 검증 완료 |
| LINK_READY | linkProof,expiresAt | 연결할 카카오 소유권 확인 완료. 연결 확정은 별도 POST |

- REAUTH_SUCCESS, LINK_READY 교환은 인증 헤더와 시작 세션 일치 필수. 그 외 로그인 교환은 공개 흐름 증명 사용.
- 기존 연결 여부는 카카오 제공자 계정 식별자로 판단한다. 로그인 시 카카오의 변경된 이메일로 서비스 이메일을 덮어쓰지 않는다.
- 사용 가능한 이메일이 없는 신규 가입자는 3장의 가입 이메일 링크 API로 emailProof를 받는다. 가입 시 signupProof와 emailProof를 함께 검증한다.
- `social_signup_context`는 signupProof에 연결하고 그 증명의 유효기간 동안 유지한다. 60초 교환 코드 만료나 OAuth 정리 작업으로 유효한 가입 컨텍스트를 삭제하지 않는다. 가입 완료·중단·탈퇴 시에는 2.4의 소유·정리 규칙을 적용한다.
- linkProof는 재인증된 세션·대상 카카오에 묶인 일회용 증명이다. 카카오가 다른 회원에게 연결되어 있으면 409 SOCIAL_ACCOUNT_ALREADY_LINKED.
- 인증 취소·실패는 프론트 고정 콜백에 KAKAO_AUTH_CANCELLED / KAKAO_AUTH_FAILED 오류 코드만 전달한다. 외부 오류 원문·토큰은 노출하지 않는다.

#### 4.2 카카오 전용 회원의 이메일 로그인 추가

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 이메일 인증 발송 | POST | /me/login-methods/email/verifications | 본인 | reauthProof(ADD_EMAIL_LOGIN) | 202 VerificationIssued |
| 링크 확인 | POST | /me/login-methods/email/verifications/confirm | 본인 | token | 200 emailLoginProof,expiresAt |
| 이메일 로그인 추가 | POST | /me/login-methods/email | 본인 | emailLoginProof,password | 200 loginMethods |

등록된 서비스 이메일로 발송한다. 다른 이메일을 쓰려면 이메일 변경을 먼저 완료한다. emailLoginProof는 카카오 재인증·이메일 소유권·회원에 묶인다. 완료 시 이메일이 인증 당시 값과 같은지 다시 검사한다. 이미 EMAIL이 있으면 409 EMAIL_LOGIN_ALREADY_ENABLED. 연결 해제·회원 데이터 병합 API는 제공하지 않는다.

### 5. 비밀번호·내 정보·탈퇴

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 재설정 인증 발송·재발송 | POST | /auth/password-resets | 공개 | email | 202 VerificationIssued |
| 재설정 인증번호 검증 | POST | /auth/password-resets/verify | 공개 | verificationId,code | 200 resetProof,expiresAt |
| 비밀번호 재설정 | POST | /auth/password-resets/complete | 공개 | resetProof,newPassword | 204 |
| 내 정보 | GET | /me | 본인 | — | 200 Member |
| 닉네임 변경 | PATCH | /me/nickname | 본인 | nickname | 200 nickname |
| 연락처 변경 | PATCH | /me/phone-number | 본인 | phoneNumber | 200 phoneNumber,phoneVerified:false |
| 프로필 업로드 | PUT | /me/profile-image | 본인 | multipart file | 200 profileImageUrl |
| 프로필 삭제 | DELETE | /me/profile-image | 본인 | — | 204 |
| 이메일 변경 인증 발송 | POST | /me/email-changes | 본인 | newEmail,reauthProof(CHANGE_EMAIL) | 202 VerificationIssued |
| 이메일 변경 확인 | POST | /me/email-changes/verify | 본인 | verificationId,code | 200 emailChangeProof,expiresAt |
| 이메일 변경 완료 | PATCH | /me/email | 본인 | emailChangeProof | 200 email |
| 비밀번호 변경 | PATCH | /me/password | 본인 | currentPassword,newPassword | 204 |
| 탈퇴 안내 | GET | /me/withdrawal-info | 본인 | — | 200 deletes:string[],retains:string[],recoverable:false |
| 탈퇴 | POST | /me/withdrawal | 본인 | reauthProof(WITHDRAWAL),confirmed:true | 204, 쿠키 삭제 |

- 비밀번호 변경·재설정 완료 시 모든 세션 폐기. 프론트는 다시 로그인 화면으로 이동한다. 카카오 전용 회원의 비밀번호 변경은 409 EMAIL_LOGIN_NOT_ENABLED.
- 이메일 변경은 인증한 신규 이메일로만 변경하며 최종 유일성을 다시 검사한다. 서비스 이메일 변경은 카카오 연결 식별자에 영향을 주지 않는다.
- 프로필 JPEG/PNG/WebP, 최대 5 MiB. 실제 파일 시그니처 검증, SVG 금지, 메타데이터 제거 및 재인코딩. 위키 이미지에도 동일 규칙 적용.
- 탈퇴 시 계정 상태를 먼저 차단하고 모든 세션·증명·예약·대기 작업을 무효화한다. 개인정보, 동의 이력, 서재·진도·검색·AI 데이터, 개인 거래 원장, 미승인 초안·제출본·전용 파일을 삭제한다.
- 승인된 공개 위키·공개 버전에는 작성자 식별자를 남기지 않는다. 승인 제출본 등 개인 전용 원본은 삭제하고 공개 결과만 유지한다. 공개 콘텐츠 자체에 작성자 개인정보가 복제되어 있지 않도록 입력·감사 로그를 설계한다.
- 늦게 완료된 AI 작업이 탈퇴 회원 데이터·보상을 재생성하지 못하도록 계정 존재/삭제 상태를 최종 저장 시 검사한다. 참조가 끊긴 이미지도 제거한다.
- 204는 개인 데이터 삭제 및 공개 자료 익명화가 끝났을 때만 반환한다. 검색 인덱스·캐시·오브젝트 저장소도 포함한다. 실패 시 개인정보가 남아 있는데 성공했다고 응답하지 않으며, 계정 접근 차단을 유지하고 삭제 작업을 재처리한다.
- 즉시 삭제는 복구 유예기간 없이 삭제를 시작한다는 의미다. `WITHDRAWING`은 삭제 중 접근을 막는 임시 상태이며 완료 회원 행은 남기지 않는다. 복구 기능은 제공하지 않는다.
- 재인증 확인·증명 소비, WITHDRAWING 전환, `withdrawal_operation` 생성을 같은 트랜잭션으로 처리한다. 작업은 현재 단계·재시도 횟수·다음 시도 시각·마지막 오류 코드·워커 임대를 영속 저장한다. 요청 연결 종료나 서버 재시작 후에도 워커가 재개하며 사용자의 재로그인에 의존하지 않는다.
- DB 참조 삭제 전에 파일 키와 정리 범위를 `withdrawal_target`에 확보한다. 이미 삭제된 대상은 성공으로 간주한다. 파일 키 등 임시 식별 자료는 작업용으로 암호화하며 개인 데이터 암호키 폐기 후에도 남은 대상 정리가 가능해야 한다.
- 세션·Refresh·JWT·재인증·계정 연결을 차단하고 진행 중 작업의 최종 저장도 차단한다. 상태 확인과 최종 쓰기를 동일한 잠금/트랜잭션 경계로 직렬화해 확인 직후 탈퇴가 시작되는 경합을 막는다. 이미 시작된 파일 업로드·메일·색인 작업도 종료 또는 무효화를 확인하고 늦게 생성된 개인 파일까지 정리한 뒤 완료한다.
- 개인 DB·파일·캐시·색인 정리 및 공용 위키 익명화를 확인한 후 최종 트랜잭션에서 대상·작업 정보와 회원 행을 삭제한다. 작업→회원 FK는 RESTRICT로 두어 작업이 남은 상태의 회원 삭제를 차단한다. 영구적인 탈퇴 회원/작업 이력에 회원·파일 식별자를 남기지 않는다.
- 처리 미완료 시 204를 반환하지 않는다. 현행 API 경로·성공 응답을 유지하며 `202 + 작업 상태 조회` 방식은 별도 계약 변경 없이 도입하지 않는다. 실패 응답이나 연결 종료가 발생해도 영속 작업은 계속 처리한다.
- 탈퇴 후 재가입은 새 인증·새 회원 ID로 수행하고 기존 정책대로 5토큰을 지급한다. 이전 인증 증명·멱등 응답·서재·토큰은 재사용하지 않는다.
- 즉시 삭제 정책을 지키려면 개인 데이터의 복구 가능한 백업 사본도 남기지 않는 저장 구조(예: 회원별 암호키 폐기)를 적용해야 한다. 공용 위키 백업과 분리한다. 탈퇴를 법률 준수 여부에 대한 판단으로 해석하지 않는다.
- 개인 식별자가 없는 집계·최소 운영 로그만 유지한다. 재가입 금지용 이메일 해시 등 식별 가능한 표식을 별도로 보관하지 않는다. 따라서 탈퇴 후 재가입의 5토큰 반복 수령을 계정 식별 기반으로 차단하지 않는 정책이다.

### 6. 약관·공지사항

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 현행 가입 약관 | GET | /terms | 공개 | — | 200 items:TermVersion[] |
| 약관 버전 상세 | GET | /terms/{termVersionId} | 공개 | — | 200 TermVersion + content |
| 동의 이력 | GET | /me/consents | 본인 | Q: cursor?,limit? | 200 Page<Consent> |
| 재동의 대상 | GET | /me/consents/pending | 본인 | — | 200 items:TermVersion[],requiredAction:boolean |
| 개정 약관 동의 | POST | /me/consents | 본인 | consents:ConsentInput[] | 201 items:Consent[] |
| 공지 목록 | GET | /notices | 공개 | Q: cursor?,limit? | 200 Page<NoticeSummary> |
| 공지 상세 | GET | /notices/{noticeId} | 공개 | — | 200 NoticeSummary + content |

- TermVersion: termVersionId,termId,type(SERVICE/PRIVACY/OPTIONAL),version,title,required,effectiveAt,requiresReconsent. 아직 공개하지 않은 관리자 약관은 공개 API에서 404.
- Consent: consentId,termVersionId,agreed,agreedAt. 동의 이력은 append-only이며 회원 탈퇴 시 삭제한다.
- 개정 시행일부터 필요한 필수 재동의를 하지 않은 회원의 일반 서비스 요청은 403 TERMS_RECONSENT_REQUIRED. 내 정보·약관 조회/동의·재인증·로그아웃·탈퇴는 허용한다. 필수 거부 시 동의 완료 처리하지 않는다.
- NoticeSummary: noticeId,title,summary,pinned,publishedAt,updatedAt. 목록은 pinned DESC,publishedAt DESC,id DESC. 공개 API는 게시된 공지만 제공한다.

### 7. 도서 검색·판본·서재

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 도서 검색 | GET | /books/search | 공개 | Q: query,type(TITLE/AUTHOR/ISBN),bookType?(BOOK/WEB_NOVEL),cursor?,limit? | 200 Page<BookSummary> |
| 도서 상세 | GET | /books/{bookId} | 공개 | — | 200 Book |
| 웹소설 구성 | GET | /books/{bookId}/structure | 공개 | Q: cursor?,limit? | 200 Page<Episode> |
| 최근 검색 | GET | /me/search-history | 본인 | Q: cursor?,limit? | 200 Page<SearchHistory> |
| 검색 삭제 | DELETE | /me/search-history/{historyId} | 본인 | — | 204 |
| 검색 전체 삭제 | DELETE | /me/search-history | 본인 | — | 204 |
| 서재 목록 | GET | /me/shelf | 본인 | Q: bookType?,sort?(RECENT_ACTIVITY/RECENT_ADDED/TITLE),cursor?,limit? | 200 Page<ShelfItem> |
| 서재 추가 | POST | /me/shelf | 본인 | bookId,position | 201 ShelfItem |
| 서재 상세 | GET | /me/shelf/{bookId} | 본인 | — | 200 ShelfItem |
| 서재 제거 | DELETE | /me/shelf/{bookId} | 본인 | — | 204 |

| 모델 | 필드 |
| --- | --- |
| BookSummary | bookId,workId:null|string,bookType,title,authors:string[],publisher:null|string,editionLabel:null|string,isbn:null|string,coverUrl:null|string |
| Book | BookSummary + description,source,sourceItemId,positionUnit,totalUnits:null|integer,publicationDate:null|string |
| Episode | episodeId,sequence(작품 내 전역 화 번호),episodeLabel,volumeLabel:null|string,chapterLabel:null|string |
| SearchHistory | historyId,query,type,searchedAt |
| ShelfItem | book:BookSummary,currentPosition,highestPosition,positionContext,progressPercent:null|number,addedAt,lastProgressAt:null|string |

- bookId는 내부의 안정적인 판본 UUID. 외부 검색 결과도 provider item key/판본 메타데이터를 통해 내부 ID로 매핑한다. 프론트에 외부 provider ID를 bookId로 사용하게 하지 않는다.
- 검색 커서는 내부의 불투명 커서로 외부 페이지 방식 차이를 감춘다. 외부 검색 실패는 503 BOOK_PROVIDER_UNAVAILABLE; 성공 빈 검색과 구분한다.
- ISBN이 없는 웹소설도 내부 bookId를 부여한다. 사용자 직접 도서 생성 API는 없다. 임시 데이터는 seed/import로 준비한다.
- 검색어는 1~100자. 로그인 검색만 기록하며 동일 정규화 검색어·유형은 최근 시각을 갱신한다. 100개까지 유지한다.
- 구조 조회는 WEB_NOVEL에만 제공. 일반 도서에는 409 BOOK_TYPE_MISMATCH. 권·챕터 레이블은 구간 메타데이터이며 독서량 단위가 아니다.
- 등록 시 같은 회원·bookId 중복은 409 SHELF_ALREADY_EXISTS. 최초 등록은 highest=current=입력 지점, 누적 독서량 증가 없음.
- 삭제는 서재 연결만 해제. 재등록은 기존 최고 진도·기록을 보존하고 입력 지점이 더 높으면 그 증가분을 재등록일 독서량으로 1회 반영한다. 낮은 입력으로 최고 진도를 초기화하지 않는다.
- 총 분량 미상은 progressPercent=null. 단위가 다르거나 알려진 총 분량 초과는 422 INVALID_POSITION.

### 8. 진도·독서 기록·홈 요약

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 진도 조회 | GET | /me/shelf/{bookId}/progress | 본인 | — | 200 Progress, ETag |
| 진도 변경 | PATCH | /me/shelf/{bookId}/progress | 본인 | position; If-Match | 200 Progress + addedUnits,recordId:null|UUID, ETag |
| 지금 읽는 책 | GET | /me/reading/current | 본인 | — | 200 item:ShelfItem|null |
| 기간 독서량 | GET | /me/reading/summary | 본인 | Q: from,to | 200 from,to,timeZone,pages,episodes,activeDays,daily:DailyReading[] |
| 월별 캘린더 | GET | /me/reading/calendar | 본인 | Q: month | 200 month,timeZone,days:DailyReading[] |
| 독서 기록 | GET | /me/reading/records | 본인 | Q: from?,to?,bookId?,cursor?,limit? | 200 Page<ReadingRecord> |
| 위키 활동 | GET | /me/wiki/activities | 본인 | Q: from?,to?,bookId?,type?,cursor?,limit? | 200 Page<WikiActivity> |
| 검수 요약 | GET | /me/wiki/review-summary | 본인 | — | 200 draftCount,pending,changesRequested,approved,rejected |

- Progress: bookId,currentPosition,highestPosition,positionContext,progressPercent,updatedAt.
- DailyReading: date,pages,episodes,hasActivity. 날짜별 값은 0도 반환한다. activeDays는 새롭게 집계된 페이지 또는 화가 있는 날 수. 재독 이동만 한 날은 hasActivity=false지만 진행 이력에는 남는다.
- ReadingRecord: recordId,bookId,fromPosition,toPosition,addedUnits,recordedAt. 동일 지점 재저장은 기록 생성 없이 addedUnits=0,recordId=null.
- 변경 시 `addedUnits=max(0,newPosition-oldHighest)`, `newHighest=max(oldHighest,newPosition)`를 동일 트랜잭션으로 처리한다. current는 요청한 지점. PAGE와 EPISODE는 각각 별도 합계.
- 현재 읽는 책은 서재에 남아 있는 도서 중 lastProgressAt이 가장 최신인 1권. 진도 변경 이력이 없으면 최근 등록 도서. 없으면 null.
- WikiActivity: activityId,bookId,draftId:null|UUID,submissionId:null|UUID,type,occurredAt. type=CREATED/EDITED/SUBMITTED/RESUBMITTED/APPROVED/CHANGES_REQUESTED/REJECTED.
- summary의 임시저장 건수는 편집 가능한 초안만 포함. 검수 상태는 각 제출 건의 현재 상태를 센다. 월간·주간 홈은 summary에 해당 날짜 범위를 전달하여 재사용한다.

### 9. 공개 위키와 스포일러 계약

공통 경로 `/books/{bookId}/wiki`. 아래 경로는 모두 전체 경로다. 권한은 회원+해당 판본 서재 등록. 없으면 403 SHELF_REQUIRED.

| 기능 | Method | 경로 | 쿼리 | 성공·data |
| --- | --- | --- | --- | --- |
| 존재·기본 정보 | GET | /books/{bookId}/wiki | positionValue? | 200 exists,wikiId:null|UUID,version:null|integer,appliedPosition |
| 인물 목록 | GET | /books/{bookId}/wiki/characters | positionValue?,organizationId?,cursor?,limit? | 200 Page<CharacterSummary> + appliedPosition,wikiVersion |
| 인물 상세 | GET | /books/{bookId}/wiki/characters/{characterId} | positionValue? | 200 CharacterDetail + appliedPosition,wikiVersion |
| 단체 목록 | GET | /books/{bookId}/wiki/organizations | positionValue?,cursor?,limit? | 200 Page<OrganizationSummary> + appliedPosition,wikiVersion |
| 단체 상세 | GET | /books/{bookId}/wiki/organizations/{organizationId} | positionValue? | 200 OrganizationDetail + appliedPosition,wikiVersion |
| 관계 목록 | GET | /books/{bookId}/wiki/relations | positionValue?,type?,entityType?,entityId?,direction?(IN/OUT/BOTH),cursor?,limit? | 200 Page<VisibleRelation> + appliedPosition,wikiVersion |

- positionValue는 생략 시 저장 current. 단위는 판본에서 결정. current보다 크면 422 POSITION_EXCEEDS_CURRENT. 음수·총 분량 초과도 거부한다.
- 위키 미존재 기본 조회는 exists=false. 미존재 위키의 하위 리소스는 404. 존재하나 공개되지 않은 인물·단체는 404로 존재 여부도 숨긴다.
- 필터링은 페이지네이션 전에 적용한다. 숨겨진 항목의 건수·이름·ID·종료 지점·이미지 URL을 노출하지 않는다.
- CharacterSummary/OrganizationSummary: entityId,name,imageUrl:null|string,description:null|string. Detail은 해당 summary + profiles(선택된 공개 프로필),entries,relations 및 characters의 affiliations / organizations의 members.
- profiles 응답은 조회 지점 이하의 최신 유효 프로필을 선택하며 미래 버전과 총 버전 수를 보내지 않는다. 단순 기본 정보 변경도 공개 지점별로 저장한다.
- 인물 상세의 profiles에는 gender·personality를 포함한다. 기존 데이터에 값이 없으면 각 필드를 null로 반환하며 해당 프로필의 공개 지점을 적용한다. 초안·편집 정보·제출 스냅샷·관리자 조회에도 동일한 인물 프로필 모델을 사용한다.
- VisibleRelation: relationId,from:{type,id,name},to:{type,id,name},type,description. 양 끝 대상이 모두 공개되어야 한다.
- 관계 변화는 CHANGE/END 이벤트 각각에 revealAt를 부여한다. 종료 정보가 아직 공개되지 않았으면 미래 종료 시점도 반환하지 않는다.
- 위키 이미지, AI 입력, 기존 대화·결과 접근에도 같은 공개 규칙을 적용한다.

### 10. 위키 초안·하위 편집

#### 10.1 초안

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 초안 생성 | POST | /wiki/drafts | 본인 | bookId,mode(CREATE/UPDATE),position,wikiId?,baseWikiVersion? | 201 Draft, ETag |
| 내 초안 목록 | GET | /me/wiki/drafts | 본인 | Q: bookId?,cursor?,limit? | 200 Page<DraftSummary> |
| 상세 | GET | /wiki/drafts/{draftId} | 본인 | — | 200 Draft, ETag |
| 기본 정보 수정 | PATCH | /wiki/drafts/{draftId} | 본인 | position; If-Match | 200 DraftSummary, ETag |
| 삭제 | DELETE | /wiki/drafts/{draftId} | 본인 | If-Match | 204 |
| 이미지 업로드 | POST | /wiki/drafts/{draftId}/assets | 본인 | multipart file; If-Match | 201 assetId,mediaType,width,height, ETag |
| 미참조 이미지 삭제 | DELETE | /wiki/drafts/{draftId}/assets/{assetId} | 본인 | If-Match | 204, ETag |
| 이미지 조회 | GET | /wiki/assets/{assetId} | 본인/공개범위/관리자 | — | 200 이미지 바이트 |

- CREATE는 아직 공개 위키가 없을 때, UPDATE는 wikiId·baseWikiVersion 필수. 생성 시 해당 버전 스냅샷을 복제하고 기준을 기록한다. 다른 판본 위키를 지정할 수 없다.
- 작성 지점은 현재 진도 이하. 각 정보 revealAt도 작성 지점 이하. 작성 지점을 낮출 때 기존 항목 중 초과 지점이 있으면 422 DRAFT_POSITION_CONFLICT, 충돌 항목 ID 제공.
- DraftSummary: draftId,bookId,mode,status(DRAFT/SUBMITTED),position,baseWikiVersion:null|integer,parentSubmissionId:null|UUID,revision,updatedAt.
- Draft: DraftSummary + characters,organizations,relations,assets. 내 초안 목록은 status=DRAFT만. 제출된 초안은 읽기만 가능하고 수정·삭제는 409 DRAFT_NOT_EDITABLE.
- 저장 버튼에서 보낸 각 변경 요청이 성공하면 서버 초안에 저장된다. 입력마다 자동 호출한다는 의미가 아니다. 전체 초안 revision ETag가 모든 하위 편집의 동시 수정 기준이며 후속 요청은 직전 성공 응답의 새 ETag를 If-Match에 사용한다.
- 일반 수정 시작 시 GET /me/wiki/drafts의 bookId 필터로 작성 중인 초안을 확인한다. 이어쓸 초안이 하나면 사용하고, 여러 개면 사용자가 선택하여 상세를 조회한다. 없으면 POST /wiki/drafts로 UPDATE 초안을 생성한다. 목록은 필요한 페이지까지 확인한다.
- POST /wiki/drafts는 신규 초안을 생성하며 기존 초안을 자동 반환하는 API가 아니다. 이어쓰기는 기존 draftId와 baseWikiVersion을 유지하며 최신 공개 버전으로 자동 교체하지 않는다.
- ‘저장 안 함’은 현재 화면의 로컬 미저장 변경만 폐기한다. 기존 서버 초안 및 이미 저장된 변경은 유지하고 DELETE를 호출하지 않는다. 저장된 변경의 롤백 API는 없다.
- 전체 초안 삭제는 초안 관리 화면에서 삭제 확인 후 DELETE /wiki/drafts/{draftId}를 호출하는 별도 동작이다.
- 여러 API로 저장할 때 성공한 요청은 유지된다. 일부 실패 시 전체 저장 성공으로 표시하지 않고 저장된 항목과 실패한 항목을 구분하여 재시도한다. 412 충돌은 상세를 다시 조회한 뒤 사용자 변경을 재적용한다.
- 새 업로드 이미지는 업로드한 초안에서만 연결한다. UPDATE 생성 시 복제된 기존 공개 프로필의 이미지 참조는 서버가 승계하여 해당 초안에서 사용할 수 있게 한다. 다른 초안의 임의 assetId 연결은 허용하지 않는다. 참조된 이미지 삭제는 409 ASSET_IN_USE. 연결 해제는 해당 profile의 imageAssetId=null 수정으로 처리한다.
- 이미지 조회는 공개 위키에서 실제로 보이는 참조 또는 본인 초안/제출본·관리자 권한을 검사한다. assetId를 안다고 원본 파일에 직접 접근할 수 없게 한다.

##### 위키 이미지 정책과 저장 순서

| 항목 | 계약 |
| --- | --- |
| 형식 | JPEG·PNG·WebP 정지 이미지. 확장자·선언된 MIME만 신뢰하지 않고 실제 형식 검증. SVG 및 애니메이션 이미지 금지 |
| 용량 | 파일당 최대 5 MiB (5 × 1,024 × 1,024바이트) |
| 해상도 | 가로·세로 각각 최대 4,096px |
| 서버 검증 | 실제 형식, 디코딩 가능 여부, 용량, 해상도, 애니메이션 여부 |
| 저장 처리 | EXIF 등 메타데이터 제거 및 재인코딩 |
| 공개 | 프로필과 함께 검수하고 승인 및 조회 지점 확인 후 공개 |

1. 파일 선택 시 프론트에서 로컬 미리보기를 제공한다. 이 단계에서는 업로드하지 않는다.
2. 저장 시 기존 POST /wiki/drafts/{draftId}/assets를 현재 초안 ETag와 함께 호출한다.
3. 응답의 assetId를 imageAssetId에 넣고, 새 ETag를 If-Match로 사용하여 프로필 생성·수정을 요청한다. PUT이면 변경하지 않은 필드까지 전체 전송한다.
4. 업로드 성공 후 프로필 저장 실패 시 업로드 자체는 유지된다. 미참조 assetId로 프로필 저장을 재시도하거나 기존 미참조 이미지 삭제 API로 정리할 수 있다. 충돌 시 먼저 최신 초안과 ETag를 조회한다.

JPEG·PNG·WebP, 5 MiB, 실제 형식 검증·메타데이터 제거는 기존 정책이다. v2.1에서는 해상도·정지 이미지 제한과 화면·저장 처리 규칙을 보완한다.

#### 10.2 인물·단체·프로필·항목

아래 `{kind}`는 `characters` 또는 `organizations`. 경로 패턴은 두 종류 모두에 실제 구현한다. 권한은 초안 작성자 본인, 모든 변경에 초안 If-Match 필수. GET 200, POST 201, PUT/PATCH 200, DELETE 204. 모든 변경 성공에는 새 초안 ETag를 반환한다.

| 기능 | Method | 경로 | 요청 → data |
| --- | --- | --- | --- |
| 대상 추가 | POST | /wiki/drafts/{draftId}/{kind} | firstRevealAt:Position,profile:ProfileInput → Entity |
| 편집 정보 조회 | GET | /wiki/drafts/{draftId}/{kind}/{entityId} | — → Entity |
| 최초 등장 지점 변경 | PATCH | /wiki/drafts/{draftId}/{kind}/{entityId} | firstRevealAt → Entity |
| 대상 삭제 | DELETE | /wiki/drafts/{draftId}/{kind}/{entityId} | — |
| 프로필 추가 | POST | /wiki/drafts/{draftId}/{kind}/{entityId}/profiles | ProfileInput → Profile |
| 프로필 전체 수정 | PUT | /wiki/drafts/{draftId}/{kind}/{entityId}/profiles/{profileId} | ProfileInput → Profile |
| 프로필 삭제 | DELETE | /wiki/drafts/{draftId}/{kind}/{entityId}/profiles/{profileId} | — |
| 세부 항목 추가 | POST | /wiki/drafts/{draftId}/{kind}/{entityId}/entries | EntryInput → Entry |
| 세부 항목 전체 수정 | PUT | /wiki/drafts/{draftId}/{kind}/{entityId}/entries/{entryId} | EntryInput → Entry |
| 세부 항목 삭제 | DELETE | /wiki/drafts/{draftId}/{kind}/{entityId}/entries/{entryId} | — |

| 모델 | 필드 |
| --- | --- |
| CharacterProfileInput | name:string(1~100),gender:null\|string(최대50),personality:null\|string(최대1000),description:string(0~5000),role:null\|string(최대100),imageAssetId:null\|UUID,revealAt:Position |
| OrganizationProfileInput | name:string(1~100),description:string(0~5000),role:null,imageAssetId:null\|UUID,revealAt:Position. gender·personality 필드 금지 |
| ProfileInput | 대상 종류에 따른 CharacterProfileInput 또는 OrganizationProfileInput의 약칭 |
| Profile | profileId + 대상 종류에 해당하는 ProfileInput. 인물 응답의 gender·personality는 값이 없으면 null |
| EntryInput | type(EVENT/ABILITY/OTHER),title:string(1~200),content:string(1~10000),revealAt:Position,sourcePosition:Position |
| Entry | entryId + EntryInput |
| Entity | entityId,kind,firstRevealAt,profiles:Profile[],entries:Entry[] |

- 인물은 EVENT/ABILITY/OTHER, 단체는 EVENT만 허용한다. 소속·구성원은 관계 API로 관리한다.
- characters의 대상 추가·프로필 추가·전체 수정에는 CharacterProfileInput을, organizations에는 OrganizationProfileInput을 사용한다. 인물 성별·성향은 선택 입력이며 입력하지 않은 값은 null로 보낸다. personality는 description과 별도 필드다.
- 프로필 PUT은 전체 교체이므로 변경하지 않는 필드도 포함하여 해당 모델의 전체 필드를 전송한다. 인물의 gender·personality도 포함하고, 단체의 role은 null만 허용한다. 단체에 gender·personality를 보내거나 role에 null 이외의 값을 보내면 422 VALIDATION_ERROR다. 문자열 길이 제한 위반도 동일하다.
- 최초 프로필 revealAt=firstRevealAt. 동일 대상·동일 revealAt의 프로필 중복 금지. 최소 1개의 유효 프로필 필요. 프로필 변경은 미래 버전 정보가 과거 응답에 섞이지 않도록 처리한다.
- 인물·단체 삭제 시 해당 초안의 연결 관계와 이벤트도 원자적으로 삭제한다. 공개 위키에는 승인 전 반영되지 않는다.

#### 10.3 관계·소속·구성원

| 기능 | Method | 경로 | 요청 → data |
| --- | --- | --- | --- |
| 관계 추가 | POST | /wiki/drafts/{draftId}/relations | RelationInput → Relation |
| 관계 수정 | PUT | /wiki/drafts/{draftId}/relations/{relationId} | RelationInput → Relation |
| 관계 삭제 | DELETE | /wiki/drafts/{draftId}/relations/{relationId} | — |

RelationInput:

```json
{
  "from":{"type":"CHARACTER","id":"UUID"},
  "to":{"type":"ORGANIZATION","id":"UUID"},
  "type":"AFFILIATION",
  "description":"가입 관계",
  "revealAt":{"unit":"EPISODE","value":10},
  "sourcePosition":{"unit":"EPISODE","value":10},
  "events":[
    {"kind":"END","description":"탈퇴","revealAt":{"unit":"EPISODE","value":50},"sourcePosition":{"unit":"EPISODE","value":50}}
  ]
}
```

- Relation = relationId + RelationInput. type=FRIENDLY/HOSTILE/NEUTRAL/AFFILIATION. events는 필수 배열(없으면 []). event kind=CHANGE/END, CHANGE는 변경 후 type 필수.
- 인물→인물·단체→단체·인물↔단체 모두 지원. AFFILIATION은 인물→단체 또는 하위단체→상위단체 방향으로 저장한다. 인물 소속과 단체 구성원은 동일 관계를 양방향으로 조회한다.
- 대상은 같은 초안 내 리소스여야 한다. 자기 자신 연결 금지. 최초 공개 지점은 양 끝 대상 최초 등장 이후. 이벤트 지점은 관계 최초 공개 이후이며 오름차순. 관계 방향은 from→to로 보존한다.
- 관계 삭제는 204, 생성 201, 수정 200. 본인 권한·초안 If-Match 필수.
- 소속 선택 목록은 초안 상세 응답의 organizations를 사용하고 프론트에서 단체 이름으로 필터링한다. UPDATE에 복제된 기존 공개 단체와 현재 초안의 신규 단체 모두 선택 가능하다. 연결 ID는 현재 초안 응답의 entityId를 사용하며 공개 위키의 ID를 그대로 대입하지 않는다. 대상이 없으면 현재 초안에 단체를 추가한 후 연결한다. 별도 검색 API는 추가하지 않는다.

### 11. 사용자 검수

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 제출·재제출 | POST | /wiki/drafts/{draftId}/submissions | 본인 | If-Match, Idempotency-Key | 201 Submission |
| 내 검수 목록 | GET | /me/wiki/submissions | 본인 | Q: status?,bookId?,cursor?,limit? | 200 Page<SubmissionSummary> |
| 검수 상세 | GET | /wiki/submissions/{submissionId} | 본인 | — | 200 Submission |
| 수정 요청의 새 초안 | POST | /wiki/submissions/{submissionId}/revision-drafts | 본인 | — | 201 Draft 또는 기존 편집 초안 200, ETag |

- SubmissionSummary: submissionId,bookId,status,submittedAt,decidedAt:null|string,rewardAmount:null|integer,parentSubmissionId:null|UUID.
- Submission: Summary + snapshot(고정된 전체 초안),baseWikiVersion,reviewComment:null|string,history:[{action,comment,occurredAt}],rewardReason:null|string.
- 제출 시 내용·참조·필수 프로필·지점 범위 검증 후 스냅샷 고정. 초안은 SUBMITTED, 제출 건은 PENDING.
- PENDING → APPROVED / CHANGES_REQUESTED / REJECTED. 최종 결정 후 해당 건을 재결정하지 않는다. 수정 요청 시 새 초안·새 제출 건으로 연결한다.
- revision-drafts는 CHANGES_REQUESTED에서만 허용. 동일 제출 건에서 편집 가능한 재작성 초안은 1개만 유지한다. 기존 재작성 초안이 있으면 200으로 재사용하며 없으면 생성하여 201을 반환한다. 이는 v2.0부터의 규칙으로 일반 POST /wiki/drafts와 다르다.
- 재작성 초안의 최초 생성은 최신 공개 버전을 기준으로 기존 제안 내용을 이식하되 충돌 항목은 명시한다. 기존 재작성 초안을 재사용할 때에는 기준 버전을 자동 교체하지 않는다. 미해결 충돌이 있으면 제출을 거부한다. 재제출 시 parentSubmissionId를 서버가 지정한다.
- 반려 건은 재작성 연결 대상이 아니다. 필요하면 별도 신규 수정 초안을 생성한다.

### 12. AI 질문·스트리밍·관계도

#### 12.1 공통 작업

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 질문 접수 | POST | /ai/questions | 본인 | bookId,position?:Position,question:string(1~4000),conversationId?:UUID; Idempotency-Key | 202 jobId,conversationId,questionId,appliedPosition,wikiVersion,cost:1,status,eventsUrl |
| 작업 상태 | GET | /ai/jobs/{jobId} | 본인 | — | 200 Job |
| 질문 SSE | GET | /ai/questions/{questionId}/events | 본인 | Header Last-Event-ID? | 200 text/event-stream |
| 질문 결과 | GET | /ai/questions/{questionId} | 본인 | — | 200 QuestionResult |
| 내 대화 목록 | GET | /me/ai/conversations | 본인 | Q: bookId?,cursor?,limit? | 200 Page<ConversationSummary> |
| 대화 상세 | GET | /ai/conversations/{conversationId} | 본인 | — | 200 ConversationSummary |
| 대화 메시지 | GET | /ai/conversations/{conversationId}/messages | 본인 | Q: cursor?,limit? | 200 Page<Message> |
| 관계도 생성·재생성 | POST | /ai/graphs | 본인 | bookId,position?:Position,regenerateFromGraphId?:UUID; Idempotency-Key | 202 jobId,appliedPosition,wikiVersion,cost:1,status |
| 관계도 결과 | GET | /ai/graphs/{graphId} | 본인 | — | 200 Graph |
| 내 관계도 목록 | GET | /me/ai/graphs | 본인 | Q: bookId?,cursor?,limit? | 200 Page<GraphSummary> |

- 질문은 접수 후 SSE로 스트리밍한다. 브라우저는 Authorization 헤더를 넣을 수 있는 fetch 기반 스트림 사용. accessToken을 query string으로 전달하지 않는다.
- Job: jobId,type(QUESTION/GRAPH),status,createdAt,completedAt:null|string,result:{questionId?:UUID,graphId?:UUID}|null,error:{code,message}|null,reservedCredits,chargedCredits,appliedPosition,wikiVersion.
- QuestionResult: questionId,conversationId,jobId,status,question,answer:null|string,citations:[],appliedPosition,wikiVersion,chargedCredits,error:null|object.
- Message: messageId,questionId,role(USER/ASSISTANT),content,appliedPosition,createdAt,citations. 대화 메시지는 createdAt ASC,id ASC 커서로 조회한다.
- ConversationSummary: conversationId,bookId,title,createdAt,updatedAt,maxContextPosition. 제목 생성도 해당 공개 범위 정보만 사용한다.
- GraphSummary: graphId,bookId,generatedPosition,wikiVersion,createdAt,currentPosition,positionMatches,regenerationRequired.
- Graph: GraphSummary + nodes:[{id,type,name,imageUrl}],edges:[{id,from,to,type,description}],citations.
- citation: `{entryId?:UUID,relationId?:UUID,sourcePosition:Position,wikiVersion}`. 공개 가능한 출처만 제공.

#### 12.2 스트리밍 이벤트

```text
id: 1
event: started
data: {"questionId":"UUID","jobId":"UUID","appliedPosition":{"unit":"PAGE","value":120}}

id: 2
event: delta
data: {"text":"현재 읽은 범위에서는 "}

id: 3
event: completed
data: {"questionId":"UUID","chargedCredits":1,"citations":[]}
```

- 이벤트 종류 started/delta/completed/failed. failed data는 code,message,chargedCredits:0. 정상 completed는 결과 저장·차감 확정 후 발행한다.
- 서버 이벤트를 24시간 재생 가능하도록 보관. Last-Event-ID 이후 이벤트 재전달. 만료된 재생 요청은 410 STREAM_REPLAY_EXPIRED, 결과 조회 API로 복원한다.
- 네트워크 끊김은 작업 취소가 아니다. 작업은 계속 진행하고 완료 시 1회 차감한다. 프론트는 같은 질문을 새 POST로 재접수하지 않고 작업/결과 조회 또는 SSE 재접속을 사용한다.
- 시스템 실패 시 부분 답변은 최종 답변으로 저장하지 않고 실패 상태로 표시, 예약 해제. 근거 부족을 설명하는 정상 답변은 1토큰 차감.
- 생성이 180초 안에 완료되지 않으면 JOB_TIMEOUT 실패 및 예약 해제. 늦은 worker 성공은 결과 저장/차감하지 못하게 상태를 원자적으로 전환한다.

#### 12.3 공개 범위와 과금

- 모든 작업은 해당 판본의 서재 등록, 가용 credits>=1, 공개 위키 존재를 접수 시 검사한다. 위키가 없으면 409 WIKI_NOT_AVAILABLE, 예약하지 않는다.
- appliedPosition은 요청 지점 또는 저장 current. 요청 지점이 current 초과면 422. 접수 시 지점과 위키 버전을 고정한다.
- 기존 대화는 같은 회원·같은 bookId만 연결 가능. 새 질문의 지점보다 높은 범위를 가진 대화는 409 CONVERSATION_POSITION_CONFLICT, 새 대화 필요.
- 기존 대화·답변·관계도·SSE·작업 결과 링크 제공 시에도 현재 진도를 재검사한다. 범위를 초과하면 403 SPOILER_SCOPE_EXCEEDED. 대화/관계도 목록에서는 숨기며 제목·총 개수도 노출하지 않는다.
- 스트리밍 중 진도가 낮아지거나 서재 삭제/탈퇴가 발생하면 이후 전송을 중단한다. 이미 전송된 정보의 회수는 불가능하다. 진도 후퇴·서재 삭제는 생성 실패 자체가 아니며 작업 완료 시 차감하지만 결과 접근은 제한한다. 탈퇴 작업은 저장/차감을 중단하고 개인 데이터를 삭제한다.
- 이미 지불한 결과가 진도 후퇴로 숨겨져도 자동 환불하지 않는다. 생성 지점 이상으로 진도를 올리면 다시 접근 가능.
- positionMatches는 현재와 생성 지점 일치 여부. regenerationRequired는 지점 불일치 또는 공개 위키 버전 변경 시 true. 현재보다 낮은 지점의 결과는 조회 가능하며 필요 여부만 안내한다.
- regenerateFromGraphId는 같은 회원·판본의 기존 관계도여야 한다. 재생성은 새 작업·새 graphId를 만들고 1토큰 사용. 생성 성공 전 기존 관계도를 덮어쓰지 않는다.
- 토큰 예약, 성공 차감, 실패 예약 해제는 각각 중복 처리되어도 1회만 반영. 작업 상태·완료 결과·원장 확정은 일관되게 저장한다.

### 13. AI 토큰

| 기능 | Method | 경로 | 권한 | 요청 | 성공·data |
| --- | --- | --- | --- | --- | --- |
| 비용 정책 | GET | /ai/credit-policy | 공개 | — | 200 signupGrant:5,questionCost:1,graphCost:1,graphRegenerationCost:1,reviewRewardMode:"ADMIN_SPECIFIED" |
| 잔액 | GET | /me/ai/credits | 본인 | — | 200 Credits |
| 거래 목록 | GET | /me/ai/credit-transactions | 본인 | Q: type?,from?,to?,cursor?,limit? | 200 Page<CreditTransaction> |
| 거래 상세 | GET | /me/ai/credit-transactions/{transactionId} | 본인 | — | 200 CreditTransaction |

CreditTransaction: transactionId,type(USE/GRANT/RECOVERY/REFUND),amount(증감 정수),balanceAfter,reason,jobId:null|UUID,submissionId:null|UUID,originalTransactionId:null|UUID,createdAt.

- GRANT=가입/검수/수동 지급, RECOVERY=관리자 회수(음수), REFUND=원사용거래 복구(양수), USE=정상 AI 사용(음수).
- 예약은 잔액을 차감하지 않으며 거래가 아니다. 예약 해제는 거래 목록에 가짜 환불을 생성하지 않는다.
- 검수 0토큰 승인은 검수 처리 이력에만 남기고 금액 0인 원장 거래를 만들지 않는다.

### 14. 관리자

모든 API는 관리자 권한. 아래 표의 모든 관리 변경은 처리자·시각·사유를 기록하며, 탈퇴 정책에 따라 개인 식별 가능한 로그를 제거한다.

#### 14.1 검수

인물 성별·성향·이미지와 각 프로필의 공개 지점을 기존 제출 스냅샷과 함께 검수한다. 사용자·관리자 제출 상세와 변경 비교에 해당 필드를 포함하고 승인 시 공개 버전에 반영한다. 기존 데이터의 성별·성향은 값이 없으면 null이다.


| 기능 | Method | 경로 | 요청 | 성공·data |
| --- | --- | --- | --- | --- |
| 검수 목록 | GET | /admin/wiki/submissions | Q: status?,bookId?,cursor?,limit? | 200 Page<SubmissionSummary> |
| 상세 | GET | /admin/wiki/submissions/{submissionId} | — | 200 Submission, ETag |
| 변경 비교 | GET | /admin/wiki/submissions/{submissionId}/diff | — | 200 baseVersion,currentVersion,versionConflict,changes:[{entityType,entityId,field,before,after}] |
| 승인 | POST | /admin/wiki/submissions/{submissionId}/approve | rewardAmount:integer>=0,rewardReason:string(1~1000),comment?:string,expectedWikiVersion:integer; If-Match,Idempotency-Key | 200 status:"APPROVED",publishedWikiVersion,rewardAmount,rewardTransactionId:null|UUID |
| 수정 요청 | POST | /admin/wiki/submissions/{submissionId}/request-changes | comment:string(1~5000); If-Match,Idempotency-Key | 200 SubmissionSummary |
| 반려 | POST | /admin/wiki/submissions/{submissionId}/reject | reason:string(1~5000); If-Match,Idempotency-Key | 200 SubmissionSummary |
| 이력 | GET | /admin/wiki/submissions/{submissionId}/history | Q: cursor?,limit? | 200 Page<ReviewHistory> |

- 신규 위키 승인 expectedWikiVersion=0. 수정 승인은 제출 baseWikiVersion과 현재 버전이 같아야 한다. 승인 시 versionConflict가 있으면 409 WIKI_VERSION_CONFLICT, 수정 요청으로 전환하도록 안내한다.
- 제출 상태는 PENDING에서만 결정 가능. 동일 Idempotency-Key 재시도 외의 이미 처리된 건은 409 REVIEW_ALREADY_DECIDED.
- 공개 위키 버전 생성·제출 승인·보상 원장 저장을 동일 DB 트랜잭션으로 처리한다. 인덱스 갱신은 outbox로 재시도해도 보상을 재지급하지 않는다.
- 보상은 운영 규칙을 근거로 관리자가 직접 입력한다. 클라이언트가 사용자 검수 제출에 rewardAmount를 넣어도 받지 않는다.
- ReviewHistory: historyId,action,actorId:null|UUID,comment,rewardAmount:null|integer,rewardReason:null|string,occurredAt. 공개 사용자 상세에는 관리자 내부 식별자를 반환하지 않는다.

#### 14.2 공지사항

| Method | 경로 | 요청 | 성공·data |
| --- | --- | --- | --- |
| GET | /admin/notices | Q: cursor?,limit? | 200 Page<AdminNotice> |
| GET | /admin/notices/{noticeId} | — | 200 AdminNotice |
| POST | /admin/notices | title,summary,content,pinned:boolean,published:boolean | 201 AdminNotice |
| PATCH | /admin/notices/{noticeId} | title?,summary?,content?,pinned?,published? (1개 이상) | 200 AdminNotice |
| DELETE | /admin/notices/{noticeId} | — | 204 |

AdminNotice=NoticeSummary+content,published. title 1~200, summary 0~500, content 1~50000자. 게시 전은 publishedAt=null, 최초 게시 시 서버 시각 설정. 콘텐츠는 Markdown, 렌더링 시 위험한 HTML 차단.

#### 14.3 약관

| Method | 경로 | 요청 | 성공·data |
| --- | --- | --- | --- |
| GET | /admin/terms/versions | Q: termId?,cursor?,limit? | 200 Page<TermVersion> |
| GET | /admin/terms/versions/{termVersionId} | — | 200 TermVersion + content,publicationStatus |
| POST | /admin/terms/versions | termId,type,version,title,content,required:boolean | 201 TermVersion + publicationStatus:"DRAFT" |
| PUT | /admin/terms/versions/{termVersionId}/publication | effectiveAt,requiresReconsent:boolean | 200 TermVersion + publicationStatus |

termId는 같은 약관 종류의 안정적인 UUID. 새 약관이면 새 UUID 사용, 서버가 기존 종류와 일치 여부 검증. 동일 termId+version 중복은 409. 등록한 본문은 덮어쓰지 않고 새 버전 생성. effectiveAt은 현재 이후, 시행 전 설정 변경 가능. 시행된 버전의 시행 설정 변경은 409 TERM_ALREADY_EFFECTIVE. 재동의 대상은 시행 당시 해당 버전에 동의하지 않은 기존 회원이며 임의 회원 그룹 선택 기능은 제공하지 않는다.

#### 14.4 토큰

| Method | 경로 | 요청 | 성공·data |
| --- | --- | --- | --- |
| GET | /admin/members/{memberId}/ai/credits | — | 200 Credits |
| GET | /admin/members/{memberId}/ai/credit-transactions | Q: type?,from?,to?,cursor?,limit? | 200 Page<CreditTransaction> |
| POST | /admin/members/{memberId}/ai/credit-adjustments | type(GRANT/RECOVERY),amount:integer>0,reason:string(1~1000); Idempotency-Key | 201 CreditTransaction |
| POST | /admin/ai/credit-transactions/{transactionId}/refund | reason:string(1~1000); Idempotency-Key | 201 CreditTransaction |

회수는 available 이내에서만 허용. 초과 시 409 INSUFFICIENT_AVAILABLE_CREDITS. refund는 USE 원거래에 대한 전액 복구만 지원하며 이미 복구한 원거래는 409 ALREADY_REFUNDED. 탈퇴 회원에게 지급/복구/개인 원장 조회 불가(404).

### 15. 공통 및 주요 오류

| HTTP | code | 의미·프론트 처리 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_CURSOR | 문법·커서 오류, 입력/커서 초기화 |
| 401 | AUTH_REQUIRED / TOKEN_EXPIRED / SESSION_REVOKED | 재발급 또는 로그인 |
| 401 | INVALID_CREDENTIALS | 이메일/비밀번호를 구분하지 않고 로그인 실패 안내 |
| 403 | FORBIDDEN / TERMS_RECONSENT_REQUIRED | 권한 부족 / 약관 화면 이동 |
| 403 | SHELF_REQUIRED / SPOILER_SCOPE_EXCEEDED | 서재 등록 또는 읽은 지점 안내 |
| 404 | RESOURCE_NOT_FOUND | 없음·타인 소유·숨겨진 항목 |
| 409 | EMAIL_ALREADY_EXISTS / NICKNAME_ALREADY_EXISTS | 중복 입력 |
| 409 | SOCIAL_ACCOUNT_ALREADY_LINKED | 다른 회원에 연결된 카카오 |
| 409 | EMAIL_LOGIN_ALREADY_ENABLED / EMAIL_LOGIN_NOT_ENABLED | 로그인 방식 조건 위반 |
| 409 | SHELF_ALREADY_EXISTS / BOOK_TYPE_MISMATCH | 도서 상태/유형 불일치 |
| 409 | DRAFT_NOT_EDITABLE / ASSET_IN_USE | 초안 상태/이미지 참조 문제 |
| 409 | WIKI_VERSION_CONFLICT / REVIEW_ALREADY_DECIDED | 새 상태 조회 후 재검토 |
| 409 | WIKI_NOT_AVAILABLE / CONVERSATION_POSITION_CONFLICT | 위키 없음 / 새 대화 필요 |
| 409 | INSUFFICIENT_CREDITS / INSUFFICIENT_AVAILABLE_CREDITS | 잔액·가용량 부족 |
| 409 | IDEMPOTENCY_CONFLICT / REQUEST_IN_PROGRESS | 키 충돌 / 동일 요청 대기 |
| 409 | TERMS_VERSION_CHANGED / PROOF_ALREADY_USED | 약관 재조회 / 인증 재시작 |
| 409 | CHALLENGE_ALREADY_VERIFIED | 이미 검증된 링크. 기존 유효 증명은 유지하며 재발급하지 않음 |
| 409 | ALREADY_REFUNDED / TERM_ALREADY_EFFECTIVE | 중복 복구 / 시행된 약관 변경 금지 |
| 410 | PROOF_EXPIRED / STREAM_REPLAY_EXPIRED | 증명 재발급 / 결과 조회 |
| 412 | VERSION_MISMATCH | 최신 ETag로 조회 후 사용자 변경 재적용 |
| 413 | FILE_TOO_LARGE | 업로드 크기 제한 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 업로드/요청 형식 제한 |
| 415 | UNSUPPORTED_IMAGE_TYPE | 허용되지 않은 실제 이미지 형식 또는 애니메이션 이미지. JPEG·PNG·WebP 정지 이미지로 변경 |
| 422 | INVALID_IMAGE | 이미지 디코딩 실패 또는 손상된 이미지. 다른 파일 선택 |
| 422 | IMAGE_DIMENSIONS_EXCEEDED | 가로 또는 세로 4,096px 초과. 해상도를 줄인 파일 선택 |
| 422 | VALIDATION_ERROR / INVALID_POSITION / POSITION_EXCEEDS_CURRENT | 필드·진도 검증 |
| 422 | DRAFT_POSITION_CONFLICT / REQUIRED_CONSENT_MISSING | 초안 지점 충돌 / 필수 동의 누락 |
| 422 | INVALID_VERIFICATION_CODE / PROOF_PURPOSE_MISMATCH | 인증번호/목적 불일치 |
| 428 | PRECONDITION_REQUIRED | If-Match 누락 |
| 429 | RATE_LIMITED | Retry-After 후 재시도 |
| 503 | BOOK_PROVIDER_UNAVAILABLE / AI_UNAVAILABLE | 외부 연동 장애 |
| 500 | INTERNAL_ERROR | 내부 정보 없는 오류, requestId로 추적 |

비동기 접수 후 실패는 GET 상태 조회 자체를 500으로 만들지 않고 200 + status=FAILED + error.code(AI_GENERATION_FAILED/JOB_TIMEOUT 등)로 반환한다. 유효한 요청의 접수 전 오류는 해당 HTTP 오류로 응답한다.

### 16. 대표 요청·응답

#### 16.1 회원가입

```http
POST /api/v1/auth/signup
Content-Type: application/json
Idempotency-Key: <UUID>
```

```json
{
  "nickname":"책읽는새",
  "emailProof":"<가입 이메일 인증 증명>",
  "password":"<사용자가 입력한 비밀번호>",
  "phoneNumber":"+821012345678",
  "consents":[{"termVersionId":"<UUID>","agreed":true}]
}
```

응답 201 + Refresh Set-Cookie:

```json
{
  "data":{
    "accessToken":"<accessToken>","tokenType":"Bearer","expiresIn":900,
    "member":{"memberId":"<UUID>","nickname":"책읽는새","email":"reader@example.com","phoneNumber":"+821012345678","phoneVerified":false,"profileImageUrl":null,"loginMethods":["EMAIL"],"createdAt":"2026-11-06T00:00:00Z"}
  },
  "meta":{"requestId":"<UUID>"}
}
```

#### 16.2 진도 수정

```http
PATCH /api/v1/me/shelf/<bookId>/progress
Authorization: Bearer <accessToken>
If-Match: "progress-7"
Content-Type: application/json
```

```json
{"position":{"unit":"EPISODE","value":45}}
```

기존 최고 진도 40화, 현재 진도 30화였다면 addedUnits=5. 응답 200, ETag `"progress-8"`:

```json
{
  "data":{"bookId":"<UUID>","currentPosition":{"unit":"EPISODE","value":45},"highestPosition":{"unit":"EPISODE","value":45},"positionContext":{"volumeLabel":"2권","chapterLabel":"3챕터","episodeLabel":"45화"},"progressPercent":15.0,"updatedAt":"2026-11-06T01:00:00Z","addedUnits":5,"recordId":"<UUID>"},
  "meta":{"requestId":"<UUID>"}
}
```

#### 16.3 질문 생성

```json
{"bookId":"<판본 UUID>","position":{"unit":"EPISODE","value":45},"question":"현재까지 주인공과 길드의 관계를 알려줘."}
```

202 응답의 eventsUrl로 인증 헤더를 포함한 SSE 연결. 질문 생성은 동일 Idempotency-Key로 재시도한다. 결과 조회·SSE 재접속은 추가 비용 없음.

#### 16.4 검수 승인

```json
{"rewardAmount":3,"rewardReason":"운영 보상 규칙에 따른 주요 인물 3건 보완","comment":"근거와 공개 지점 확인 완료","expectedWikiVersion":7}
```

승인 시 새 공개 버전 8 생성 및 보상 3 지급. 이 예시 수량은 고정 보상 규칙을 의미하지 않는다. 0 입력 시 공개 위키 반영만 수행한다.

### 17. 구현 완료 확인 시나리오

1. 가입 요청 재시도에도 회원·5토큰·동의 기록이 중복 생성되지 않는다.
2. 이메일이 같은 카카오 로그인만으로 기존 회원 계정에 접근할 수 없다. 연결에는 기존 계정 재인증이 필요하다.
3. 다른 판본의 진도·위키·대화 ID를 섞으면 거부한다.
4. 진도 40→30→45에서 독서량 추가는 0+5. 서재 삭제/재등록으로 과거 분량을 중복 집계하지 않는다.
5. 미래 프로필·관계 종료·이미지·대화 제목이 현재 진도 응답에 노출되지 않는다.
6. 동시 질문 예약으로 가용 잔액이 음수가 되지 않는다. 스트림 단절·worker 재시도에도 1회만 차감한다.
7. 초안 편집과 제출 경합 시 제출본이 고정되고 If-Match 오류가 일관되게 발생한다.
8. 동시 관리자 승인·원거래 복구 요청에서도 위키 반영·보상·복구가 각각 1회만 발생한다.
9. 탈퇴 도중 작업이 완료되어도 개인 데이터가 재생성되지 않는다. 공용 위키는 유지되고 작성자 연결은 제거된다.
10. API 성공 응답은 실제 삭제·원장 확정 이후에 반환하며, 로그에 비밀번호·토큰·인증 증명을 남기지 않는다.
11. 인물 성별 50자·성향 1,000자 경계값을 허용하고 초과 입력은 거부한다. 기존 데이터는 null을 반환하며 미래 프로필의 성별·성향은 노출하지 않는다. 단체는 인물 전용 필드와 null이 아닌 role을 거부한다.
12. 복제된 기존 단체와 신규 단체 모두 현재 초안 entityId로 소속 연결 가능하며 다른 초안 대상은 거부한다.
13. 위키 이미지는 5 MiB·4,096px 경계값을 허용하고 초과, 손상, 미지원 형식, 애니메이션을 거부한다. 저장된 이미지에서 메타데이터가 제거되며 승인 전 공개되지 않는다.
14. 이미지 업로드 후 새 ETag로 프로필을 저장한다. 저장 실패 시 미참조 assetId로 재시도·삭제할 수 있고 UPDATE 초안의 복제 이미지 참조도 유지된다.
15. 초안이 여러 개면 사용자 선택으로 이어쓰며 기존 기준 버전을 유지한다. ‘저장 안 함’은 DELETE 없이 로컬 변경만 폐기하고 전체 삭제는 별도 확인 후 실행한다. 여러 API 중 일부 실패 시 성공한 저장은 유지하되 전체 성공으로 표시하지 않는다.
16. 링크 만료 1분 전 검증한 가입 증명은 발급 후 10분 동안 사용 가능하며 요청 정리로 삭제되지 않는다. 카카오 교환 코드 만료 후에도 유효한 signupProof로 컨텍스트를 복원한다.
17. 동시 재발송은 하나씩 처리되고 이전 링크·미사용 증명은 무효화된다. 검증된 링크 재클릭은 409이며 기존 증명을 보존한다.
18. 메일 제공자 응답 유실 후 같은 링크로 재시도한다. 만료·취소 작업은 새로 발송하지 않고 최종 상태의 암호문을 삭제한다. 워커 장애 후 임대 재점유가 가능하다.
19. 탈퇴 중 저장소 장애·HTTP 연결 종료·서버 재시작이 발생해도 접근을 차단하고 작업을 재개한다. 미완료 시 204를 반환하지 않으며 완료 후 회원·작업·파일 식별 자료가 남지 않는다.
20. 이메일 변경 전 인증·발송 자료와 가입 전 카카오·교환·멱등 자료가 탈퇴 정리 범위에 포함된다. 재가입에는 새 인증·회원 ID를 사용하고 5토큰을 지급한다.
21. 이메일·닉네임 중복 충돌 시 가입 트랜잭션 전체가 롤백되고, 성공한 동일 멱등 요청은 증명 소비 오류보다 기존 성공 결과를 먼저 반환한다.
