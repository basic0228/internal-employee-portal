# Internal Employee Portal

비트컴퓨터 개발자 채용 과제. 기존 Vue 3 + Spring Boot 3.5.0 / Java 21 / MyBatis / PostgreSQL 모노레포를 확장한다.

## 현재 상태

기본 프로필은 기존 배포용 시작 프로젝트의 보안·DB 설정을 유지한다. 새 기능은 개발용 opt-in이다. **Railway에 아직 반영하지 않았으며 과제 전체 구현은 완료되지 않았다.**

개발 프로필 구현:
- 서버 세션 로그인·로그아웃, CSRF 보호, ADMIN/USER 구분
- 관리자 직원 목록·상세 API, 직접 직원 계정 생성
- 본인 정보 조회·연락처 수정: 로그인 계정의 사번으로만 접근, 이메일·전화번호만 저장
- 퇴사 처리, 신규 로그인 차단, 기존 세션의 매 API 요청 재직 상태 검사
- 필수 직원 10명 SQL 시드, 동명이인 허용, 생년월일 미확인 NULL 보존
- PrimeVue 로그인·직원 목록·계정 생성·퇴사 확인 화면
- 배경 조회 입력 모달과 이력·특이사항 표시 구조

미구현:
- 실제 Background Check 요청·이력·상세 연동, 중복·오류·폴링 정책
- 외부 API 실측 스크립트 및 결과
- 결과 보관 정책과 background_checks 테이블
- 운영 마이그레이션 자동 실행·Railway 보안/프록시 설정 적용
- 초기 비밀번호 변경 및 제출 문서 최종본

사용자 결정에 따라 MIT 배포판 **PrimeVue 4.5.5**, 호환 테마 **@primeuix/themes 1.2.5**, **PrimeIcons 7.0.0**을 정확한 버전으로 고정했다. package.json과 lockfile에 반영했으며 로컬 브라우저에서 로그인·계정 생성·연락처 저장을 확인했다. 이전 5.x의 라이선스 경고는 현재 실행에서 표시되지 않았다.

## 구조

- backend/src/main/java/com/bit/portal/employee: Controller → Service → MyBatis Mapper
- backend/src/main/java/com/bit/portal/auth: DB 계정, 세션 principal, 요청별 재직 검사
- backend/src/main/java/com/bit/portal/config/PortalDevSecurityConfig.java: 개발 프로필 보안 규칙
- backend/src/main/resources/db/migration: V1 스키마 / V2 필수 시드 / V3 연락처 컬럼
- frontend/src/PortalApp.vue: 개발용 관리자·직원 화면
- frontend/src/components/BackgroundCheckDialog.vue: 외부 API 미연결 조회 모달
- scripts/dev-preview.ps1: 임시 H2 시연 서버
- docs/DECISIONS.md: 확정 정책·남은 결정·검증 범위
- docs/MEASUREMENTS.md: 외부 실측 전 TODO
- docs/AI_LOG.md: 실제 협업 사례, 제출 준비 TODO

## 로컬 테스트

JDK 21와 Maven 3.9+가 필요하다.

```powershell
mvn -f backend/pom.xml test
cd frontend
npm ci
$env:VITE_PORTAL_UI_ENABLED = 'true'
npm run build
```

2026-10-10 검증: Java 21 / Maven 3.9.9 / H2 PostgreSQL 호환 모드 / MockMvc에서 **25개 테스트 통과**. PrimeVue 4.5.5 새 UI 빌드 통과. 기본 UI 빌드와 Java 21 실행 JAR 패키징도 성공했다. 실행 JAR에서 테스트 전용 H2·preview 설정·테스트 클래스 제외와 V3 SQL 포함을 확인했다. 연락처 수정의 소유권·인사정보 변경 거절·형식 검증·CSRF·퇴사 세션 차단을 포함한다. 실제 브라우저에서 저장 후 새로고침으로 재조회했다.

테스트는 운영 PostgreSQL을 사용하지 않는다. H2 호환 모드 테스트가 PostgreSQL 검증을 대체하지는 않는다.

## 운영 DB 없이 로컬 화면 확인

첫 번째 PowerShell 터미널, 저장소 루트:
```powershell
.\scripts\dev-preview.ps1
```

Maven이 PATH에 없다면 `-MavenCommand`로 mvn.cmd 경로를 지정한다. JDK 21을 사용하도록 JAVA_HOME을 설정해야 한다.

두 번째 터미널:
```powershell
cd frontend
$env:VITE_PORTAL_UI_ENABLED = 'true'
npm run dev
```

- 접속: http://localhost:5173
- **메모리 DB 전용 시연 계정:** preview-admin / PreviewPassword1
- 시드 직원의 계정은 관리자 화면에서 사번·기존 인적사항을 동일하게 입력하여 생성한다.
- preview 설정과 H2 드라이버는 테스트 클래스패스에만 있으며 운영 JAR에 포함되지 않는다.
- preview 스크립트는 CLI로 H2 URL·드라이버를 명시해 PG 환경변수를 사용하지 않는다.
- 프로세스를 종료하면 생성한 계정과 데이터가 사라진다. 시연 비밀번호를 운영에 사용하지 않는다.

## 개발 PostgreSQL 사용

운영 DB와 분리된 DB에서 스키마를 확인한 다음 V1 → V2 → V3 순서로 적용한다. 현재 SQL 자동 실행이나 Flyway 도입은 하지 않았다.

```bash
psql "$LOCAL_DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction \
  -f backend/src/main/resources/db/migration/V1__employees_and_accounts.sql \
  -f backend/src/main/resources/db/migration/V2__required_employee_seed.sql \
  -f backend/src/main/resources/db/migration/V3__employee_contact_fields.sql
```

- V1은 최초 1회 적용용이다. 기존 테이블이 있으면 실패하므로 상태를 먼저 확인한다.
- V2는 기존 사번을 덮어쓰지 않는다. 기존 데이터와 필수 시드 차이는 별도로 검증한다.
- V3는 이메일·전화번호 NULL 허용 컬럼만 추가한다. V1/V2가 이미 적용된 DB에는 V3만 1회 적용한다. 운영에는 아직 적용하지 않았다.
- DROP/TRUNCATE 및 기존 계정 비밀번호 초기화는 하지 않는다.
- PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD를 개발 DB로 설정한다.
- 최초 관리자용 PORTAL_ADMIN_USERNAME / PORTAL_ADMIN_PASSWORD를 서버 환경변수로 주입한다.
- `mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=portal-dev`로 개발 기능을 활성화한다.
- 최초 관리자 생성은 기존 관리자가 없는 경우에만 수행한다.

## API — portal-dev에서만 활성

| Method | Path | 권한 |
|---|---|---|
| GET | /api/auth/csrf | 로그인 전 사용 가능 |
| POST | /api/auth/login | CSRF + username/password form |
| POST | /api/auth/logout | CSRF |
| GET | /api/auth/me | 인증된 활성 계정 |
| GET | /api/me | 본인 직원 정보 |
| PUT | /api/me/contact | 본인 직원의 이메일·전화번호 + CSRF |
| GET | /api/admin/employees | ADMIN |
| GET | /api/admin/employees/{id} | ADMIN |
| POST | /api/admin/employees | ADMIN + CSRF |
| POST | /api/admin/employees/{id}/termination | ADMIN + CSRF |

연락처 수정은 이메일·전화번호 두 필드를 교체하는 PUT이다. 빈 값/null은 해당 연락처를 지운다. 이메일 최대 254자, 전화번호 최대 32자(숫자와 +, 괄호, 공백, 점, 하이픈)를 허용한다. 이름·생년월일·사번·재직 상태·계정 권한 등 다른 필드가 본문에 있으면 400으로 거절한다. 최종 수정 시각은 서버 DB가 기록한다.

조회 모달의 이력·상세 GET은 공통 BackgroundCheckClient를 사용한다. 기본은 비활성이며, 승인 후 서버의 BG_ENABLED·BG_EXTERNAL_ENABLED·X_CANDIDATE_KEY와 확정한 timeout 설정이 필요하다. pending는 특이사항 미확정, clear는 특이사항 없음, flagged는 검토 필요로 표시한다. 요청 실패를 빈 이력으로 표시하지 않는다. 업무 POST 실행 정책은 실측 후 결정하므로 조회 실행 버튼은 아직 비활성이다.

## Railway 배포 기반 유지

기존 Dockerfile: Node 24에서 Vue 빌드 → Maven/Java 21에서 Spring Boot 빌드 → JRE 21 실행.

기존 애플리케이션 서비스 변수:
- PGHOST = ${{Postgres.PGHOST}}
- PGPORT = ${{Postgres.PGPORT}}
- PGDATABASE = ${{Postgres.PGDATABASE}}
- PGUSER = ${{Postgres.PGUSER}}
- PGPASSWORD = ${{Postgres.PGPASSWORD}}

기존 상태 확인:
- GET /api/health
- GET /api/health/db

Dockerfile, 기본 application.yml, 기존 PG 변수는 변경하지 않았다. .tools는 Git과 Docker 빌드 컨텍스트에서 제외한다.

새 기능 배포에는 **DB 검증, 보안/세션·쿠키·프록시 검토, 별도 운영 적용 승인**이 필요하다. 기본 빌드는 현재 시작 화면을 유지하며 새 UI 플래그를 자동으로 켜지 않는다. DB 상태 확인 API의 공개 여부와 HTTP 로그인 리다이렉트는 운영 적용 전 재검토한다.

X-Candidate-Key 및 비밀번호를 프론트 코드, VITE_* 변수, 문서, 로그에 기록하지 않는다. 업무 연동 키는 서버 환경변수로 주입한다. 사용자가 요청한 Lab의 직접 입력 키는 해당 실험 요청에서만 서버에 전달하고 저장·다운로드하지 않는다.

## 면접 설명 포인트

- 세션의 인증 정보와 현재 DB 재직 상태를 왜 별도로 검사하는가?
- 재직 검사 필터는 왜 세션 복원 이후, CSRF·인가 이전에 실행되는가?
- 직원 생성·계정 생성의 트랜잭션은 중복 아이디 실패에서 무엇을 보장하는가?
- 왜 한글 성명을 자동 분리하지 않고 조회용 입력을 받는가?
- H2 테스트만으로 확인할 수 없는 PostgreSQL 특성은 무엇인가?
- 연락처 수정에서 사번을 요청 본문으로 받지 않는 이유는 무엇인가?
- 연락처 저장과 퇴사 처리가 동시에 일어나면 행 잠금은 어떤 순서를 보장하는가?

## API Reliability Lab — 로컬 구현

추가 실험은 Lab의 `저장할 실험 계획`에서 선택한다. 정상 상태 확인, 제한 GET 재시도, 기존 pending 관찰, 폴링 간격 비교, 균형 순서 동시성 비교를 제공한다. 각 후속 절차는 한 번 종료하며 미사용 HTTP 예산을 채우지 않는다. [후속 구현·배포 후 실행 안내](docs/LAB_FOLLOWUP_IMPLEMENTATION.md), [설계 근거](docs/LAB_FOLLOWUP_TEST_PLAN.md). 기존 lab_campaigns/lab_runs를 사용하며 추가 SQL·환경변수는 없다. 외부 실측과 운영 설정 확정은 별도다.

다음 작업 순서는 [수동 배포·실측·Excel 분석 안내](docs/DEPLOYMENT_AND_MEASUREMENT_WORKFLOW.md)를 따른다. 배포와 키 입력은 사용자가 직접 수행한다.

관리자 메뉴의 단일 화면에서 GET 순차/동시성, POST 완료 관찰, 동일 employeeId 반복 POST를 실행하고 Type 7 통계·Timeout/Retry/Polling 후보·판단 DRAFT를 확인한다. JSON/CSV/Markdown/YAML 초안을 다운로드할 수 있다. [설계안](docs/API_RELIABILITY_LAB_PLAN.md), [구현·검증·실행 안내](docs/API_RELIABILITY_LAB_IMPLEMENTATION.md).

`scripts/dev-preview.ps1`는 H2와 테스트 전용 모의 API(127.0.0.1:18081)를 사용한다. 프론트는 `VITE_PORTAL_UI_ENABLED=true`로 실행한다. `scripts/lab-preview-smoke.ps1`는 LOCAL_MOCK만 허용하는 순차 GET 검증 스크립트다.

실제 호출은 기본 off: LAB_ENABLED=false, LAB_POST_ENABLED=false, BG_EXTERNAL_ENABLED=false. 승인 없이 켜지 않는다. 임시 실험 상한은 20회/실행, 공통 동시 호출 3, 실행 1개, 120초다. 운영 권장값이나 다중 인스턴스 합산 보장이 아니다. POST 타임아웃은 결과 불명으로 남기고 자동 재전송하지 않는다.

`background-check.client.connect-timeout-ms`와 `request-timeout-ms`는 현재 업무 GET 설정에 연결된다. request timeout은 응답 본문 수신까지의 전체 제한이며 단순 socket read timeout과 다르다. Retry/Polling/max-in-flight YAML은 후속 구현을 위한 후보 초안이다. 실제 재시도 성공률·외부 측정·운영 적용은 TODO다.

| Method | Path | 권한 |
|---|---|---|
| GET | /api/admin/reliability-lab/capabilities | ADMIN |
| POST | /api/admin/reliability-lab/experiments | ADMIN + CSRF / NDJSON |
| POST | /api/admin/reliability-lab/experiments/{id}/cancel | 실행 소유 ADMIN + CSRF |
| GET | /api/admin/employees/{id}/background-checks | ADMIN / 서버 키 |
| GET | /api/admin/employees/{id}/background-checks/{checkId} | ADMIN / 서버 키 / 직원 소유 확인 |

통계 단위 테스트: `npm --prefix frontend run test:lab`. 서버 테스트: Java 21에서 `mvn -f backend/pom.xml test`. 외부 실측 보고서는 [MEASUREMENTS.md](docs/MEASUREMENTS.md)의 TODO를 유지하며 모의 수치를 채워 넣지 않는다.
