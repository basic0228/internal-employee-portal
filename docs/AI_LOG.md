# AI_LOG — AI 협업 기록

> 과제 요구사항: AI와 대화한 **전체 내용**을 별도 파일로 첨부하고, 아래 A/B/C를 정리한다. 이 문서는 요약·사례 기록용이며 전체 대화 내보내기를 대체하지 않는다.

## 협업 범위
- 요구사항 분석, DB/인증/외부 API 설계 정책 논의
- Railway + PostgreSQL 배포 환경 구성
- GitHub 인증/병합 및 Dockerfile 빌드 오류 대응
- Vue 3 + Spring Boot 통합 배포 및 Codespaces 환경 확인

## A. AI 제안을 거절하거나 되돌린 지점 — 3개 필요

### 사례 1 — 프론트엔드 기술 선택
- **AI 제안:** 6시간 과제의 구현 시간을 줄이기 위해 Spring Boot + Thymeleaf 단일 UI를 추천.
- **내 선택:** Vue 3 + Spring Boot를 선택.
- **근거:** 기존 Vue 개발 경험을 활용하고 프론트/백엔드 구조를 명확히 분리하기 위함.
- **상태:** 실제 대화에서 선택 확인됨.

### 사례 2 — 배경 조회 실행 정책 확정 보류
- **AI 제안:** 분석 단계에서 관리자 수동 실행을 권장안으로 제시.
- **사용자 선택:** “배경조회 실행 : api 리턴 확인 후 결정”으로 실행 정책 확정을 보류.
- **판단 근거:** 실행·재조회·중복 처리는 실제 외부 API 거동을 확인한 뒤 정해야 한다는 개발 원칙.
- **검증:** 현재 조회 버튼은 비활성이고 외부 API 호출 코드·폴링은 추가하지 않았다. MEASUREMENTS는 측정 전 TODO 유지.
- **증빙:** 이번 대화의 해당 사용자 메시지와 BackgroundCheckDialog.vue. 원문 전체 첨부는 별도 준비.

### 사례 3 — DB에 성/이름을 분리 저장하는 제안 변경
- **AI 제안:** 초기 DB 제안의 employees에 full_name, last_name, first_name을 함께 저장.
- **사용자 선택:** 배경 조회 모달에서 조회용 이름과 생년월일을 입력하고 아래에 이력을 표시하는 UI로 변경.
- **판단 근거:** 조회 시 입력·확인할 수 있도록 하고, 한글 성의 경계를 임의로 확정하지 않음.
- **검증:** employees에는 full_name만 저장. 모달은 성/이름을 빈 입력으로 시작하고 생년월일만 기존 값으로 표시하며 직원 원본을 업데이트하지 않는다. EMP-007 모달 브라우저 확인.
- **증빙:** 이번 대화의 이름 분리 관련 사용자 메시지, V1 SQL, BackgroundCheckDialog.vue.


## B. AI가 처음에 잘못 만들어 수정한 지점 — 1개 이상 필요

### 사례 1 — Dockerfile JAR 경로
- **최초 상태:** AI가 제공한 프로젝트 Dockerfile에서 특정 JAR 파일명(`employee-portal-0.0.1-SNAPSHOT.jar`)을 복사하도록 구성되어 Railway 빌드에 실패함.
- **발견 방법:** Railway Build Logs의 `COPY --from=build ...` checksum 오류 확인(빌드 로그).
- **수정:** 빌드 결과 JAR 경로를 `backend/target/*.jar`로 변경하고 재배포.
- **검증:** Railway에서 `Deployment successful`, `/api/health` 응답 확인.
- **추가 점검:** 여러 JAR가 생성되는 설정에서는 와일드카드 대신 정확한 실행 JAR 경로를 사용해야 함.

## C. 현재 가장 설명하기 어려운 코드 — 1개 필요
- **대상 코드:** TODO (구현 후 실제로 어려운 부분 기재)
- **어려운 이유:** TODO
- **현재 이해한 내용:** TODO
- **추가 확인 계획:** TODO

## 작업 중 발견한 사항 (선택)
| 시점 | 이슈 | 확인 방법 | 처리 |
|---|---|---|---|
| 초기 Git 업로드 | 원격 README와 로컬 README의 병합 충돌 | Git 출력 | 병합 커밋 후 push. README 충돌 마커 잔존 여부 재확인 필요 |
| Railway 초기 배포 | Docker 이미지의 JAR 복사 실패 | Build Logs | Dockerfile 경로 수정 후 배포 성공 |

## 전체 대화 기록 첨부 체크
- [ ] 이 과제에 사용한 모든 AI 대화 내역을 원문 그대로 별도 파일로 첨부
- [ ] 실제 작업 과정과 A/B/C의 사실관계 일치 확인
- [ ] API 키·비밀번호·토큰 등 비밀정보가 기록에 노출되지 않도록 점검


## 2026-10-10 — 확정 범위 소스 반영

### 실제 AI 구현 오류 — 재직 검사 경로 판별
- **최초 구현:** EmploymentGuardFilter가 getServletPath() 문자열만으로 /api/ 요청을 구분.
- **틀린 점:** servletPath가 비어 있는 요청에서 API 재직 검사가 생략됨.
- **발견:** MockMvc 통합 테스트의 기존 세션 퇴사 차단 및 계정 활성 상태에서 재직 상태 변경 테스트가 기대 401 대신 200을 받음. 해당 실행은 14개 중 2개 실패.
- **수정:** Spring Security의 PathPatternRequestMatcher를 사용해 요청 경로와 컨텍스트 경로를 처리.
- **검증:** 이후 통합/단위/배포 호환 테스트 총 18개 통과. 기존 세션 2개 무효화, 새 로그인 거절, 컨텍스트 경로 차단, DB 조회 오류 시 보호 리소스 실행 중단 확인.
- **한계:** Java 21/H2/MockMvc 검증. 실제 PostgreSQL·Railway에서는 아직 검증하지 않았다.

### 테스트 환경에서 실제 발생한 문제
- PostgreSQL의 ON CONFLICT (employee_id) 구문이 H2 호환 모드에서 실패해 첫 실행은 테스트 컨텍스트 초기화에 실패했다.
- 시드 대상 테이블의 유일한 중복 키가 사번인 점을 확인하고 ON CONFLICT DO NOTHING으로 수정했다. H2에서 재실행·기존 값 보존 테스트 통과. PostgreSQL 검증은 TODO.
- 첫 시연 스크립트는 spring-boot:run에서 테스트 프로필 리소스를 읽지 못해 PostgreSQL 연결 시도로 실패했다.
- spring-boot:test-run으로 수정하고 CLI에 H2 URL·드라이버·사용자를 명시했다. 수정 후 로그의 jdbc:h2:mem:portal-preview 및 브라우저 로그인 성공 확인.
- 브라우저에서 기존 PrimeVue 5.0.2의 Invalid PrimeUI License 경고를 발견했다. 당시 버전·라이선스는 미확정이었으며 경고를 숨기는 수정은 하지 않았다. 이후 사용자 결정 및 4.5.5 전환 결과는 아래에 기록한다.

### 이번 작업의 확인 범위
- 백엔드: Java 21.0.12.1 / Maven 3.9.9 / H2 2.3.232, 18개 테스트 통과.
- 프론트: 기존 시작 화면 빌드와 새 UI 빌드 통과. 새 UI 번들 크기 경고는 최적화 여지가 남음.
- 브라우저: 관리자 로그인, EMP-007 계정 생성, 배경 조회 입력 모달, 관리자 로그아웃, 생성된 직원 로그인·본인 조회 확인.
- 운영 DB·Railway 변경 및 외부 Background Check 호출은 하지 않았다.
- docs/screenshots/employee-preview.jpg는 임시 H2 DB의 실제 직원 화면 검증 이미지다.
- 기존 AI_LOG A1/B1은 이전 협업 기록이다. 이번 세션에서는 해당 이전 전체 대화·Railway 로그 원문을 재확인하지 않았다. 제출 전 증빙 대조가 필요하다.

### C 항목은 사용자가 선택해야 함
- TODO 유지: AI가 “본인이 가장 설명하기 어려운 부분”을 대신 확정하지 않는다.
- 검토 후보: EmploymentGuardFilter와 PortalDevSecurityConfig의 세션 복원 → 재직 검사 → CSRF/인가 순서.
- 전체 대화 원문 내보내기/첨부는 아직 완료되지 않았다. 이 요약을 전체 원문으로 제출하지 않는다.

- 추가 검증: Maven -DskipTests package 성공. 실행 JAR 목록에서 H2 드라이버, application-preview.yml, 테스트 클래스가 제외됨을 확인했다.
- 추가 UI 확인: 좁은 브라우저 화면에서 직원 표를 가로 스크롤하도록 수정하고 남궁서준 조회 모달의 성/이름 공란·생년월일 사전 표시 확인. docs/screenshots/background-check-preview.jpg 저장.

## 2026-10-10 — PrimeVue 배포판·인증 방식·연락처 수정 확정

- **사용자 결정:** 배포판 4.5.5로 전환, 로컬 세션 + CSRF 유지, 운영 만료 시간·쿠키·프록시 적용 전 검토, 본인은 연락처만 수정.
- **반영:** PrimeVue 4.5.5 / @primeuix/themes 1.2.5 / PrimeIcons 7.0.0을 package.json·lockfile에 고정했다. 공식 npm 메타데이터와 설치 패키지에서 MIT 라이선스를 확인했다. @primeui/license-manager 의존성은 설치 목록에서 제거됐다.
- **연락처 구현:** 이메일·전화번호만 받는 DTO, 본인 사번을 세션에서 얻는 PUT API, 직원 폼, 데이터 보존 V3 마이그레이션. 추가 본문 필드는 거절하고 변경 시각은 DB가 기록한다.
- **실제 검증:** Java 21 / H2 / MockMvc 총 25개 테스트 통과(이번 연락처 관련 7개 추가). PrimeVue 4.5.5 새 UI 빌드 성공.
- **브라우저:** 임시 H2에서 관리자 로그인·EMP-007 계정 생성·로그아웃·직원 로그인·연락처 저장·새로고침 후 재조회 성공. 생년월일 미확인 유지. 현재 브라우저 warn/error 로그 조회는 빈 배열이었고 라이선스 경고도 표시되지 않았다.
- **증빙:** docs/screenshots/employee-contact-preview.jpg는 가상 연락처가 저장된 현재 UI다. 이전 employee-preview.jpg/background-check-preview.jpg는 5.x 사용 당시의 과거 검증 이미지다.
- **남은 한계:** 운영 PostgreSQL/V3, Railway, 실제 외부 API는 미검증. 번들 500kB 초과 경고와 로컬 Node 22.17.1의 일부 의존성 엔진 경고가 남아 있다. Docker/Node 24 실행은 이번에 검증하지 않았다.
- **사실 기록 원칙:** 이번 연락처 테스트는 첫 실행에서 모두 통과했다. AI 오류·사용자 거절 사례를 추가로 만들어 기록하지 않는다. C 항목과 전체 대화 원문 제출은 TODO 유지.

- 추가 검증: 기본 UI 빌드 및 실행 JAR 패키징 성공. JAR의 V3 SQL·연락처 DTO 포함, H2·preview·테스트 클래스 제외를 확인했다. 임시 시연 서버는 종료했다.

## 2026-10-10 — API Reliability Lab 설계 요청

- 사용자가 API 테스트·모니터링 요구를 실측 → 분석 → 후보 비교 → 개발자 결정 → 코드 반영을 지원하는 관리자 단일 화면으로 확장했다.
- 현재 코드·과제·Swagger를 다시 확인했고, 기존 Lab/외부 Client가 없는 상태임을 확인했다.
- 실제 API의 POST 멱등성 보장은 명세에 없으므로 별도 승인된 반복 POST 실험과 결과 불명 처리를 제안했다.
- Type 7 통계, 타임아웃 중도절단, HTTP/업무/발견 시간 구분, 재시도 실험 없는 성공률 계산 금지를 설계 문서에 기록했다.
- 이번 단계는 설계 문서 변경만 수행했다. 런타임 코드·운영 환경·외부 API는 변경/호출하지 않았고 기능 테스트 완료를 주장하지 않는다.

## 2026-10-11 — API Reliability Lab 구현

- **사용자 승인:** 설계안대로 화면과 기능 구현 요청. 외부 호출·운영 설정·DB 변경은 승인 범위에 포함하지 않았다.
- **반영:** ADMIN 단일 PrimeVue 화면, 공통 Java 21 HTTP Client, NDJSON 실행·취소, GET/소규모 동시성/POST 완료 관찰/반복 POST, Type 7 통계, 후보 모델, 판단 DRAFT, 파일 초안 생성. 업무 이력·상세 GET도 같은 Client에 연결했다.
- **설계 조정:** Spring RestClient/JDK Factory 제안에서 직접 JDK HttpClient로 변경했다. 응답 헤더 뒤 본문 멈춤에도 Future 전체 deadline을 적용하기 위한 판단이며 운영 timeout 숫자를 확정한 것은 아니다.

### 실제 구현 오류와 수정

1. **NDJSON stream 종료 오류:** 초안에서 ObjectMapper.writeValue(output, ...)가 OutputStream을 닫을 수 있는 방식으로 작성했다. 코드 검토에서 발견해 writeValueAsBytes 후 write/newline/flush로 수정했다. 첫 HTTP 모의 테스트 이전에 수정했으므로 이 오류로 테스트가 실패했다고 기록하지 않는다. 이후 여러 progress와 마지막 result 수신을 테스트·브라우저에서 확인했다.
2. **Markdown의 Endpoint 혼합:** 화면 후보 통계는 선택한 Endpoint였으나 Markdown은 전체 요청을 다시 계산했다. 검토 중 발견해 cohort를 전달하고 전체/Endpoint별 표본을 명시했다. POST 200ms + DETAIL 10ms의 가상 단위 테스트 입력에서 DETAIL 후보 50ms의 초과 0/1, 전체 명세 대조 n=2를 확인했다. 이 입력은 외부 실측 수치가 아니다.
3. **PowerShell NDJSON 해석 오류:** 첫 lab-preview-smoke.ps1 실행은 application/x-ndjson 응답을 문자열로 가정해 최종 이벤트 검사에서 실패했다. byte[] 응답을 UTF-8로 해석하도록 수정했다. 재실행에서 LOCAL_MOCK/COMPLETED/3개 관측을 확인했다. 외부 API는 호출하지 않았다.

### 테스트에서 실제 발생한 문제

- 전체 43개 실행 중 BODY_STALL 테스트 1개가 실패했다(헤더 코드 기대 200, 실제 미관측 0). 70ms가 헤더 도착까지 보장한다고 가정한 테스트를 수정했다. 모의 본문 멈춤을 2초로 늘리고 요청 제한 500ms를 사용해 헤더 도착과 본문 제한을 구분했다. 43개 재실행 통과.
- 이후 패키징 실행에서 Retry-After 충돌 테스트 1개가 실패했다(충돌 true 기대, false). 정상 503 본문 비교에 충분한 요청 제한을 주고 HTTP_RESPONSE 완료 assertion을 추가했다. 정상 응답 검증의 대기 여유와 타임아웃 실험은 구분했다. 재실행 통과. 이 테스트 제한값은 운영 정책 근거가 아니다.
- 중단된 응답의 수신 바이트를 0으로 표시하면 미계측을 실제 0바이트로 오해할 수 있어 null로 수정했다. 완료 본문만 실제 길이를 기록한다.

### 최종 확인 범위

- **백엔드:** Java 21.0.12.1 / Maven 3.9.9, 최종 mvn package에서 총 45개 테스트 통과(실패/오류/skip 0). ADMIN/USER/익명·CSRF, 기존 인증/연락처/퇴사 회귀, 실험 예산·동시성·소유자 취소, POST 불명 재전송 금지, 401/404/500/503·Retry-After·잘못된 JSON·본문 멈춤·크기 제한, 비식별 결과 검증 포함.
- **프론트:** Node 통계·초안 생성 테스트 7개 통과. 새 UI 및 기본 시작 화면 빌드 성공. 새 UI의 500kB 초과 번들 경고는 남아 있다.
- **실행 JAR:** 공통 Client·Lab·명세 사본 포함. H2·모의 API 클래스·application-preview.yml 미포함을 확인했다. Dockerfile·기본 application.yml 변경 없음.
- **브라우저:** 로컬 관리자 로그인, c=1→2→3 각 3회 GET의 실제 모의 관측 9개와 최종 이벤트, 키 입력 공란·password 마스킹, POST 1회 후 pending→clear, HTTP/업무 시간 구분, Timeout/Retry/Polling 후보, 실행·통계 연결 DRAFT, 업무 이력·상세 표시 확인. warn/error 로그 조회는 빈 배열이었다.
- **다운로드 검증 한계:** JSON 버튼 클릭 후 내장 브라우저의 download 이벤트 대기가 timeout되어 파일 저장 완료를 확인하지 못했다. JSON/CSV/Markdown/YAML 생성 코드와 통계 연결은 테스트했고 YAML 화면 출력은 확인했다. 실제 브라우저 파일 저장 검증을 완료했다고 표시하지 않는다.
- **증빙:** docs/screenshots/reliability-lab-preview.jpg, reliability-lab-latency.jpg, background-history-preview.jpg. 모든 API 결과는 LOCAL_MOCK이며 외부 성능 근거가 아니다. 이 작업에서 시작한 H2·모의 API·Vite 서버는 종료했다.
- **운영:** 실제 외부 호출·Railway 배포·PostgreSQL 적용 없음. MEASUREMENTS 외부 수치와 DECISIONS의 새 운영 정책은 TODO 유지.
- **A/C/원문:** 새로운 사용자 거절 사례를 만들어 채우지 않았다. 기존 A의 미완성 항목, 본인이 어려운 코드(C), 전체 대화 원문 제출은 TODO다. 검토 후보로 공통 Client의 Future/본문 구독/cancellation 및 중도절단 통계를 제시하되 사용자가 직접 선택해야 한다.

## 2026-10-11 — 수동 배포·실측 단계 준비

- 사용자가 커밋·푸시 → 직접 배포 → 배포 URL에서 직접 키 입력 → 실제 API에 가상 데이터 시험 → n차 스크립트/Excel → TODO 근거 순서를 요청했다.
- 사용자에게 확인했고 ‘실제 외부 API + 가상 직원 데이터’와 Railway 자동 배포 비활성 답변을 받았다. 테스트용 모의 API/H2를 운영 이미지에 추가하지 않았다.
- git fetch origin 후 HEAD와 origin/main의 ahead/behind는 0/0이었다. 원격 충돌은 없었다.
- Dockerfile에 비밀정보가 아닌 VITE_PORTAL_UI_ENABLED ARG를 추가했다. 기본 false를 유지하고 사용자가 Railway에서 true를 입력하면 새 UI가 빌드되도록 했다. 기본/opt-in 프론트 빌드는 이전 검증에서 통과했으나 Docker CLI가 없어 컨테이너 이미지 빌드는 여기서 실행하지 않았다.
- 읽기 전용 스키마 확인 SQL과 직접 수행할 배포/변수/초기 시험 절차 문서를 준비했다. 운영 DB·Railway 설정·외부 호출은 아직 수행하지 않았다. n차 실행 조건과 Excel의 실제 표본도 아직 없다.

## 2026-10-11 — 사용자 수동 배포 후 첫 외부 관측

- 사용자가 Postgres 초기 SQL 및 Railway 변수 설정·배포를 직접 수행했다. AI는 운영 DB에 직접 쓰기 작업을 하지 않았다. 새 UI, health/DB 200, CSRF 발급, 비로그인 관리자 API 401을 읽기 전용으로 확인했다.
- 첫 쿠키 검사에서 Secure/SameSite가 미관측이었다. 사용자 추가 설정·재배포 후 Secure=true/HttpOnly=true/SameSite=Lax를 실제 응답에서 확인했다. 세션·토큰 값은 출력하지 않았다.
- 사용자 관리자 재로그인 후 Lab을 열고 가상 사번 LIST GET 1회 조건을 준비했다. 키는 사용자가 직접 입력했고 AI는 원문을 읽지 않았다. 첫 AX 버튼 클릭은 관측상 실행 상태가 바뀌지 않았고, 입력 validity를 확인한 뒤 Playwright 버튼 클릭으로 실제 실행을 시작했다. 최종 표본은 1건이다. 물리적인 외부 전송 회수를 별도 계측한 것은 아니다.
- 첫 EXTERNAL run `e83392ee-b8f9-4f29-bf31-4e4032154ff1`: REQUEST_TIMEOUT 1건, 완료 HTTP n=0, 헤더 코드 미관측, 화면의 중단 경과 10,003.93ms. 중도절단을 정상 지연 백분위수로 채우지 않았다. start/progress/result 이벤트 3개 및 키 입력란 공란을 확인했다.
- 원본 JSON 내보내기 버튼을 클릭했지만 download 이벤트가 12초 안에 관측되지 않아 파일 저장 완료를 확인하지 못했다. 해당 한계를 MEASUREMENTS에 남겼으며 정확한 원본 값/시각을 만들어 채우지 않았다. 화면 증빙은 reliability-lab-external-first-get.jpg이다.
- 외부 정상 응답·POST·n차 부하 측정·Excel 생성은 아직 미완료다. 추가 사용자 거절 사례나 Retry 성공률을 만들어 기록하지 않았고 DECISIONS의 운영 정책은 TODO를 유지했다.
- 이후 사용자가 첫 run의 다운로드 JSON을 제공했다. 실제 파일을 읽어 runId·EXTERNAL·revision·표본 및 화면 반올림 전 값을 검증했고, 민감 필드 점검 후 docs/measurements/raw/에 원본 바이트와 SHA-256을 보존했다. 브라우저 download 이벤트 미관측은 실제 다운로드 실패를 입증하지 않았으며 사용자 제공 파일로 제품의 JSON 내보내기 저장을 확인했다. MEASUREMENTS의 원본 미확인 문구를 수정했다.

## 2026-10-11 — 원본 저장 보완·수집 스크립트·Excel 초안

- **실제 후속 관측:** 같은 가상 사번 LIST GET 1회, 연결 5초·요청 30초·실험 40초에서 HTTP 200, 화면 2,365.36ms(n=1)을 확인했다. 사용자가 원본 JSON 버튼이 동작하지 않아 새로고침했다고 알렸다. 이 실행의 정확한 원본 값·시각을 만들지 않았고 Excel에 JSON 원본 표본으로 합산하지 않았다.
- **다운로드 보완:** 링크를 DOM에 연결하고 Blob URL 해제 대기를 늘렸다. 원인 확정 또는 배포 후 파일 저장 성공으로 기록하지 않는다. `JSON 텍스트 보기`를 추가해 다운로드 없이도 결과를 복사할 수 있게 했다. 임시 LOCAL_MOCK 브라우저에서 읽기 전용·유효 JSON·입력 키 미포함·저장 전 새로고침 안내를 확인했고 warn/error 콘솔은 비어 있었다. 증빙은 reliability-lab-json-fallback.jpg다.
- **실제 AI 구현 오류:** 이전 timeoutComparison은 타임아웃까지 경과한 시간이 후보보다 크면 완료 응답의 초과로 분류했다. 첫 실제 중단 10,003.931728ms와 후보 10,001ms로 검증하니 취소 오버헤드를 완료 지연으로 오인했다. 수정 전 변경한 중도절단 테스트와 새 회귀 테스트 2개가 실패했다. 모든 중단 표본을 완료 지연 미확정으로 처리한 뒤 8개 테스트가 통과했다. 운영 제한보다 짧은 실험 deadline도 고려해야 한다는 이유를 코드와 화면에 남겼다.
- **수집 도구:** 승인 origin, GET 전용, 전체 예산 10, dry run 기본값, 사용자 직접 credential/key 입력, 별도 세션+CSRF, 기존 Lab 재사용, 비식별 JSON·다음 차수 오류 중단을 구현했다. PowerShell 파싱 오류 0건, 임시 H2/LOCAL_MOCK에서 2차수×2GET=4개 관측 저장·민감 필드 없음 확인. 실제 외부 승인 계획 dry run은 요청하지 않았다.
- **사용자 승인:** 순차 실제 LIST GET 총 3회, 동시성 1, 2초 간격, 연결 5초·요청 30초·실험 100초, POST/자동 재시도 없음 조건을 승인했다. 키와 비밀번호는 사용자 프롬프트 입력이 필요하다. 승인 자체를 측정 완료로 기록하지 않는다.
- **Excel:** 제공된 실제 첫 원본 1건만 @oai/artifact-tool로 생성했다. 전체 n=1/완료 HTTP n=0/중단 n=1, Type 7·조건·SHA-256·후보 TODO를 유지했다. 수식 오류 검사 0건, 후보 입력 변경·빈칸 원복 검증 통과. 이미지 렌더링은 `Failed to resolve Vulkan proc vkEnumerateInstanceVersion`으로 실패했다. 렌더링을 생략한 XLSX 저장은 성공했으나 시각 검증 완료를 주장하지 않는다.
- **검증 범위:** 프론트 테스트 8개 및 opt-in UI 빌드 성공. 500kB 번들 경고가 남아 있다. 백엔드 소스 변경이 없어 기존 45개 통과 기록을 이번 재실행으로 표기하지 않는다. DECISIONS의 운영 정책은 TODO 유지. 추가 A 거절 사례·C 사용자 선택·전체 대화 원문은 꾸며 채우지 않는다.
