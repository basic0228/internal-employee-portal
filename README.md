# Internal Employee Portal

비트컴퓨터 개발자 채용 과제. 기존 Vue3/Vite/JavaScript + Spring Boot3.5.0/Java21/MyBatis/PostgreSQL 모노레포를 확장했다. UI는 PrimeVue4.5.5, 상태 관리는 Pinia, 통신은 Axios를 사용한다.

- 배포: https://internal-employee-portal-production.up.railway.app/
- 소스: https://github.com/basic0228/internal-employee-portal
- 제출 자료와 남은 항목: [SUBMISSION.md](docs/SUBMISSION.md)

## 현재 구현과 검증 범위

| 기능 | 구현 | 확인 범위 |
|---|---|---|
| 로그인·로그아웃·ADMIN/USER | 세션+CSRF, 서버 권한 검사 | 로컬 통합·브라우저, 배포 관리자 로그인 |
| 직원 관리 | 전체·상세 조회, 관리자 직접 계정 생성, 퇴사 처리 | 로컬 통합·브라우저, 배포10명 목록 |
| 본인 조회·수정 | 본인 정보 조회, 이메일·전화번호만 수정 | 로컬 소유권·보호 필드·CSRF·저장 검증 |
| 퇴사자 접근 | 신규 로그인과 기존 세션의 매 API 요청 재직 확인 | 로컬 세션·경로·오류 검증. 운영 퇴사 검증 TODO |
| 필수 시드 | EMP-001~010, 동명이인 허용, EMP-007 생년월일NULL | SQL·로컬 검증, 배포 목록 |
| 업무 배경 조회 | 관리자 수동POST, DB 최소 이력, 결과 확인·열람 기록, 중복/결과 불명 제어 | H2·모의HTTP, 운영EMP-001 즉시clear·EMP-002 pending 후 수동flagged, 이력/열람 저장·비로그인401. 자동 최종 발견·실제 응답 유실 복구·운영USER403 TODO |
| API Reliability Lab | 실험·통계·후속 절차·후보 비교·서버 자동 저장 | 배포 실측과 저장 원본 대조 |

**과제 전체 운영 검증은 미완료다.** 관리자 수동 배경 조회와 최소 이력 보관을 구현·활성화하고 업무2건을 운영에서 확인했다(실제 외부 호출 총5건). 조회일시·계정·대상·특이사항 여부 등 이력만 보관하며 상세 결과는 저장하지 않는다. 관리자만 열람하고 이력 삭제·만료는 없다. timeout/retry/polling은 보수적 초기값을 적용했으며 실측 최적값은 미확인이다. [설정과 한계](docs/BACKGROUND_CHECK_POLICY.md)를 참고한다. 비밀번호 변경·최초 강제 변경은 미구현이다. 제출 계정·전체 AI 대화·본인 작성 항목은 [제출 체크리스트](docs/SUBMISSION.md)에 있다.

배경 조회 상세는 이력 첫 열의 **‘상세 결과 보기’**를 눌러 외부 API에서 가져온다. 모달 위쪽에 선택 안내·조회 중·조회 실패·실제 결과를 구분해 표시하며, 상세 영역으로 자동 이동한다. 자동 상태 확인 및 신규 POST 응답은 상세 열람을 대신하지 않는다. 대기 제한이면 안내한 시각 이후 직접 다시 열람하고, 상세 조회는 자동 재시도하지 않는다. [2026-10-13 UI 검증 기록](docs/validation/background-detail-ui-20261013.md)을 참고한다.

## 제출 근거자료

- [MEASUREMENTS](docs/MEASUREMENTS.md): 고유39실행/65시도, 상세 GET·상태 분포·중복 POST·미검증 구분
- [설정 판단 근거](docs/API_DECISION_EVIDENCE.md): 통계·공학 모델, 후보·대안·재검토 상황
- [DECISIONS](docs/DECISIONS.md): API 실측 기반 타임아웃·재시도·폴링·중복 POST·동시성 판단
- [업무 정책 판단](docs/BUSINESS_DECISIONS.md): 퇴사 접근·조회 실행 시점·결과 보관·본인 수정의 기존 선택
- [업무 조회 정책](docs/BACKGROUND_CHECK_POLICY.md): 초기 설정, 실측 한계, 이력·중복·결과 불명·운영 적용
- [AI_LOG](docs/AI_LOG.md): 실제 변경·오류 사례와 본인이 어려워하는 이유
- [전체 AI 대화 텍스트](docs/ai-conversation/AI_CONVERSATION.txt): 생성 시점까지 현재 대화498메시지, 자격증명 마스킹·범위·출처 manifest 별도 기록
- [원본 인덱스](docs/measurements/integrated/INDEX.md): run ID·조건·원본 SHA256
- [통합 Excel](docs/measurements/submission-evidence.xlsx): 조건별 통계·후보 계산·원본 관측

전체65건을 단일 성능 분포로 합산하지 않는다. 초기11조건34건과 승인 후속18건, 별도 탐색13건을 분리했다. Lab pending→최종 발견 n=0, 실제 추가 retry1건/정상 회복0건이다. 이후 업무 화면의 수동 최종 발견 n=1은 별도 기능 검증이며 Lab 원본·Excel에 합치지 않았다. 정확한 서버 업무 시간·실제 POST 타임아웃 복구 n=0을 유지한다.

## 구조

- backend의 employee: Controller → Service → MyBatis Mapper
- backend의 auth: 세션 principal, 요청별 계정·재직 검사
- backend의 backgroundcheck/client: 업무와 Lab의 공통 외부 Client
- backend의 lab: 예산·중단·후속 프로토콜·서버 저장
- backend/src/main/resources/db/migration: V1~V5 SQL
- frontend/src/PortalApp.vue 및 components: 직원·관리자 화면, 조회 모달·Lab
- frontend/src/lab: 통계와 후속 계획
- scripts: 측정·시연·오프라인 감사·Excel 생성

## 로컬 실행·검증

JDK21와 Maven3.9+, Node/npm이 필요하다. DB 없이 임시 H2 시연:

```powershell
.\scripts\dev-preview.ps1
```

별도 터미널:

```powershell
cd frontend
$env:VITE_PORTAL_UI_ENABLED = 'true'
npm ci
npm run dev
```

http://localhost:5173 — **메모리 DB 전용** 계정 preview-admin / PreviewPassword1. 운영·제출 계정으로 사용하지 않는다. 종료하면 시연 데이터가 사라진다.

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend run test:lab
npm --prefix frontend run test:background
$env:VITE_PORTAL_UI_ENABLED = 'true'
npm --prefix frontend run build
```

최신 백엔드97개 통과(업무 이력15개 포함, 실패·오류·skip0). 앞선 업무 변경의 프론트23개·관리자UI opt-in 빌드·JAR 패키징도 통과했다. 이번 후속 검증에서는 프론트 소스를 바꾸지 않았다. 로컬과 운영 확인은 [SUBMISSION](docs/SUBMISSION.md)에 구분한다. H2 검증과 UI 빌드는 운영 PostgreSQL·배포 브라우저 검증을 대체하지 않는다. 번들 크기 경고는 남아 있다.

운영 USER 권한 확인 도구는 `./scripts/verify-user-access.ps1 -Execute`다. 기존 USER 로그인 정보를 직접 입력하며 본인정보200·금지12경로403·유효CSRF logout204를 검사한다. 외부 조회 입력을 사용하지 않고 비밀정보 없는 결과를 outputs에 저장한다. 로컬 실제HTTP 검증은 통과했고 운영 실행 결과는 아직 없다.

## PostgreSQL와 배포

SQL 자동 실행·Flyway는 도입하지 않았다. 기존 테이블과 적용 버전을 확인한 뒤 필요한 파일만 적용한다. V1 직원·계정, V2 필수 시드, V3 연락처, V4 lab_campaigns/lab_runs, V5 background_checks/background_check_views다. DROP/TRUNCATE와 기존 비밀번호 초기화는 수행하지 않는다. 사용자가 V5 생성 명령과 앱 변수 설정·배포를 완료한 후 운영에서 업무 이력2건과 수동 열람 기록의 저장·읽기를 확인했다.

Docker는 Vue 빌드→Maven/Java21→JRE21 통합 이미지다. VITE_PORTAL_UI_ENABLED는 빌드 플래그로 runtime 값만 바꿔서는 화면이 달라지지 않는다. 서버 새 기능은 portal-dev 프로필에서 활성화한다. 기본 플래그는 false이며 사용자 배포 설정과 구분한다. main 푸시는 Railway 자동 배포를 일으킬 수 있다.

| 설정 | 용도 |
|---|---|
| PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD | Railway PostgreSQL 연결 |
| PORTAL_ADMIN_USERNAME/PORTAL_ADMIN_PASSWORD | 최초 관리자 생성, 기존 비밀번호 덮어쓰지 않음 |
| LAB_ENABLED/LAB_POST_ENABLED/LAB_RECORDING_ENABLED | Lab 실행·POST·서버 저장 |
| BG_EXTERNAL_ENABLED | 외부 Client 실제 호출 허용 |
| BG_ENABLED | 업무 화면 외부 배경 조회 허용 |
| BG_RECORDING_ENABLED | V5 적용 후 업무POST·DB 이력 활성화. 기본false |
| X_CANDIDATE_KEY | 업무 외부 API 키, 서버 관리 |
| BG_CONNECT_TIMEOUT_MS/BG_REQUEST_TIMEOUT_MS | 업무 연결·전체 요청 제한, 기본5000/30000ms |
| BG_MAX_RETRIES/BG_RETRY_BACKOFF_MS | GET 추가retry/Fixed대기, 기본0/1000ms. POSTretry항상0 |
| BG_POLLING_INTERVAL_MS/BG_POLLING_MAX_DURATION_MS/BG_POLLING_MAX_ATTEMPTS | 자동 상태 확인 간격/전체 시간/최대 횟수, 기본5000/120000/24 |

Lab 입력 키는 해당 요청에서만 사용하고 저장하지 않는다. 업무 키·비밀번호를 VITE_*·소스·로그에 넣지 않는다. 세션 만료·쿠키·프록시 설정 확정은 별도이며 이번 자료 통합에서 변경하지 않았다. 상태 확인은 /api/health와 /api/health/db다.

## 오프라인 근거 재현

```powershell
node scripts/integrate-submission-evidence.mjs
node scripts/export-submission-workbook.mjs --skip-render
```

원본 중복·조건·민감 필드·실제 예산을 검사하고 같은 JSON으로 Excel을 만든다. @oai/artifact-tool 런타임이 별도로 필요하며 앱 의존성에 추가하지 않았다. 이 호스트의 native 렌더러는 Vulkan 오류가 있어 구조·수식 검증과 대체 레이아웃 검토를 구분한다. 다른PC/실제Excel 검증은 미완료다.

## 면접 설명 포인트

- 과거 세션 인증과 현재 DB 재직 상태를 왜 별도로 검사하는가?
- 연락처 저장과 퇴사 처리의 행 잠금은 어떤 순서를 보장하는가?
- 복성과 생년월일 미확인을 어떻게 외부 형식에 대응하는가?
- 목표3건을 관측한 C03이 STOPPED이면 왜 다음 동시성을 막는가?
- 정상 약15초와 오류218ms를 섞어 timeout을 정하면 무엇이 잘못되는가?
- 실제 retry 회복0/1을 서비스 회복 확률0%로 확정할 수 없는 이유는 무엇인가?
