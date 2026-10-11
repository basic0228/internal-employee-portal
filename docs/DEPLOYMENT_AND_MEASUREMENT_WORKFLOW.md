# 수동 배포 → 브라우저 검증 → 반복 측정 → Excel → 정책 근거

2026-10-11 사용자 요청. 배포와 키 입력은 사용자가 직접 수행한다. 사용자는 실제 외부 API + 가상 직원 데이터 방식과 Railway 자동 배포 비활성을 확인했다. 외부 API 호출·운영 DB·Railway 설정 변경을 이 문서 작성 단계에서 수행하지 않았다.

2026-10-12 업데이트: 사용자가 이후 Railway 자동 배포를 사용한다고 명시했다. 현재는 기존 커밋·푸시 승인에 따라 main 푸시가 자동 배포를 유발한다. 아래 자동 배포 비활성 단계는 최초 수동 배포 당시의 기록이다. 후속 실측은 배포 URL 브라우저에서 수행하며 키·계정 로그인은 요청 시 사용자가 입력한다. 새 케이스 사용법은 [LAB_FOLLOWUP_IMPLEMENTATION.md](LAB_FOLLOWUP_IMPLEMENTATION.md)를 따른다.

## 0. 커밋·푸시와 수동 배포

AI가 변경 검토·로컬 검증·커밋·푸시를 담당한다. 사용자는 Railway 설정·DB 적용·실제 배포를 담당한다. main 푸시가 자동 배포를 유발할 수 있으므로 자동 배포 상태 확인 전에는 푸시하지 않는다. Railway 앱 서비스 Settings → Service Source에서 자동 배포를 비활성화한다. 설정 위치는 공식 문서를 참고한다.

배포 전 확인 순서:

1. Railway 자동 배포가 비활성인지 확인하고 알려준다. 변수 변경도 staged changes에 남겨 두고 실제 적용은 마지막에 한다.
2. Railway PostgreSQL에 연결한 SQL 클라이언트에서 [읽기 전용 스키마 점검](../scripts/check-portal-schema.sql)을 실행한다. 테이블/컬럼 결과만 알려준다. DB URL·비밀번호·실제 직원 데이터는 대화에 보내지 않는다.
3. 결과 확인 후 필요한 SQL만 적용한다. 신규 DB면 V1 → V2 → V3를 하나의 트랜잭션으로 실행한다. 기존 테이블이 있으면 V1을 다시 실행하지 않는다. V3 적용 여부는 email/phone 컬럼으로 확인한다. DROP/TRUNCATE/계정 초기화는 하지 않는다. 이 문서만 보고 기존 데이터베이스에 전체 SQL을 재실행하지 않는다.
4. 사용자에게 확정된 커밋/푸시 결과를 전달한 뒤 아래 변수를 검토·입력하고 해당 커밋을 수동 배포한다. UI 플래그는 이미지 빌드 시 적용되므로 새 이미지 빌드가 필요하다.

| 변수 | 값 / 목적 |
|---|---|
| VITE_PORTAL_UI_ENABLED | true — Docker 빌드 ARG로 새 UI 포함 |
| SPRING_PROFILES_ACTIVE | portal-dev — 현재 새 인증·직원·Lab 코드 활성화. preview 사용 금지 |
| PORTAL_ADMIN_USERNAME | 사용자가 선택한 최초 관리자 아이디 |
| PORTAL_ADMIN_PASSWORD | 사용자가 Railway에 직접 입력할 강한 비밀번호. 채팅/코드에 기록하지 않음 |
| LAB_ENABLED | true — 승인된 브라우저 첫 GET 시험을 위한 화면 실행 활성화 |
| LAB_POST_ENABLED | 초기 false. 가상 데이터 POST 시험 계획 승인 후 true |
| BG_EXTERNAL_ENABLED | true — 승인된 브라우저 외부 시험 활성화. 이 변수만으로 API를 호출하지 않음 |
| BG_ENABLED | false — 업무용 Background Check GET은 별도 실측·설정 확인 후 활성화 |

기존 PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD 연결을 유지한다. 초기 관리자 변수는 기존 관리자 비밀번호를 재설정하지 않는다. LAB/외부 플래그를 켜도 키는 생성되지 않는다. Lab에서 키를 직접 입력하고 일반 업무 키 X_CANDIDATE_KEY는 아직 필요하지 않다.

HTTPS 배포에서 검토할 세션 쿠키 변수: SERVER_SERVLET_SESSION_COOKIE_SECURE=true, SERVER_SERVLET_SESSION_COOKIE_HTTP_ONLY=true, SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=lax. 이 값은 제안이며 사용자 적용·실제 Set-Cookie 검증 전 운영 확정으로 기록하지 않는다. 세션 만료 시간·프록시 신뢰 설정은 임의로 바꾸지 않는다. CSRF는 유지한다.

5. 사용자 수동 배포 완료 후 알려준다. AI가 health와 화면을 확인한다. 관리자 로그인도 사용자가 직접 수행할 수 있도록 안내한다. DB 접속과 테이블 준비가 먼저여야 bootstrap 계정 생성이 정상 작동한다.

## 1. 배포 URL 브라우저 시험

대상: https://internal-employee-portal-production.up.railway.app/

사용자가 ‘목업 데이터’를 실제 외부 API에 가상의 이름·생년월일을 보내는 뜻으로 확정했다. 현재 모의 API는 테스트 전용이며 운영 JAR에는 없다. 실제 외부 API의 성능 정책에는 EXTERNAL 측정만 사용한다. 서버 내부 모의 수치를 외부 실측과 섞지 않는다.

확정한 실제 외부 API + 가상 직원 데이터 절차:

1. 승인된 초기 시험 조건을 정한다. 처음에는 가장 작은 호출부터 진행한다. POST는 외부 기록을 생성하며 삭제 명세가 없어 시험 ID/인적사항과 생성 횟수를 별도로 확인한다.
2. 사용자가 시험 활성 변수 변경과 배포를 직접 적용한다. 관리자 로그인·키 입력도 직접 수행하며 완료를 알려준다. 키를 채팅이나 로그로 전달하지 않는다.
3. AI가 승인된 호출만 화면에서 실행·관찰한다. 동일 페이지의 키 입력 후 필드를 읽거나 값을 재출력하지 않는다. 페이지/브라우저 세션이 다르면 사용자 입력이 자동 공유된다고 가정하지 않는다.
4. 진행·최종 이벤트, n, 상태·오류, 키 삭제, 모델 변화, 파일 다운로드를 확인한다. 출처 EXTERNAL과 실행 ID를 보존한다.

첫 검증은 순차 GET 1회, 동시성 1, 측정용 연결 제한 5초·전체 요청 제한 10초·실행 최대 30초를 시작 조건으로 사용한다. 이 값은 실제 지연을 아직 모르는 첫 관측의 유한한 안전 한계이며 운영 추천값이 아니다. 응답을 확인한 뒤 다음 실험 조건을 정한다. POST는 아직 활성화하지 않는다.

현재 preview 프로필·H2·공개 시연 계정을 Railway에 올리지 않는다.

## 2. n차 측정 스크립트와 Excel

1단계 작동 확인 후 그 결과에 맞춰 scripts/에 반복 측정 도구를 작성한다. 아직 실제 응답을 확인하지 않았으므로 반복 수·간격·측정 timeout·POST 수를 확정하지 않는다. 도구는 기존 Lab/Client 경로를 재사용하며 외부 API를 별도로 직접 호출해 정책을 우회하지 않는다.

실행마다 조건/코드·명세 버전/출처/runId/원본을 저장한다. 키·개인정보는 저장하지 않는다. 서버 상한 20회/실행·3동시·1실행·120초를 유지하고 여러 실행을 합칠 때도 전체 승인 예산을 둔다. 키 전달 방식과 단기 인증은 구현 전 확정한다. 화면 입력 키는 실행 후 삭제되므로 다음 실행이나 스크립트에서 재사용할 수 있다고 가정하지 않는다.

Excel 생성 시 실제 수집 파일만 사용한다. 예정 구성:

- README: 출처·분석 단위·Type 7·중도절단/작은 n 한계.
- Runs: 실행 ID·환경·Endpoint·조건·계획/실행 n·중단 사유.
- Attempts: 비식별 원본, HTTP 코드·전송 결과·시간·Retry-After·명세 issue.
- Workflows: 업무 처리시간·pending 관측·발견 시간·미완료.
- Summary: 조건/Endpoint별 n·평균/p50/p95/p99/max·분포·오류율·tail.
- Candidates: Timeout·Retry 대기 모델·Polling·동시성 비교. 비관측 값/Retry 성공률은 추가 실험 필요.
- DecisionDrafts: 설정 후보, runId·n·통계, 선택 이유·반대 장점·부적절해질 상황·미확정 상태.

조건이 다른 실행은 분리한다. 통계별 분모를 기재하며 모의 데이터와 실제 외부 관측을 혼합하지 않는다. 사용자 판단 전에는 설정값을 코드에 자동 적용하지 않는다.

## 3. 정책 TODO의 근거

Excel·원본·runId로 MEASUREMENTS.md의 실제 관측을 채운다. 근거가 있는 후보·한계·추가 실험을 정리해 사용자에게 제시한다. 사용자 결정 후 DECISIONS.md와 실제 설정 코드를 맞추고 다시 검증한다. 보관·업무 POST 실행 정책 등 통계만으로 정할 수 없는 항목은 별도 판단으로 유지한다.

## 2026-10-11 반복 측정 도구 준비 결과

- 첫 외부 타임아웃의 실제 다운로드 JSON을 사용자에게 받아 비식별 필드와 SHA-256을 확인하고 원본을 보존했다. 두 번째 LIST GET은 HTTP 200, 화면 지연 2,365.36ms(n=1)이었으나 다운로드 버튼 문제 후 새로고침되어 원본이 남지 않았다. 정확한 JSON을 복원했다고 주장하지 않는다.
- `scripts/measure-lab-get.ps1`를 작성하고 로컬 모의 API에서 2차수×2회, 총 4개 GET 관측·JSON 저장을 검증했다. 외부 수치와 섞지 않는다. 승인된 외부 3회 계획의 dry run도 네트워크 요청 없이 확인했다.
- 사용자가 동일 가상 사번 LIST GET 3회·동시성 1·2초 간격·연결 5초·요청 30초·실험 100초·POST/자동 재시도 없음 조건을 승인했다. 비밀번호와 키는 사용자 직접 프롬프트 입력이 필요하므로 실제 실행은 대기 중이다. [명령과 결과 보존 절차](MEASUREMENT_SCRIPTS.md)를 따른다.
- Excel은 현재 첫 원본 1건으로 Summary/Candidates/Attempts/Runs 핵심 시트를 만들었다. 미관측 업무·재시도·Polling 시트를 가상 결과로 채우지 않았다. 수식 검증은 통과했고 렌더러 Vulkan 오류로 시각 검증은 미완료다.
- UI에 JSON 텍스트 대체 경로와 다운로드 보완을 추가했다. 로컬 모의 결과의 텍스트 유효성·키 미포함은 확인했으나 배포 후 파일 저장은 미검증이다. 사용자 수동 재배포 후 확인한다. 이번 GET 스크립트는 기존 배포된 백엔드에서 동작하므로 이 UI 재배포를 기다릴 필요는 없다.
- 새 운영 timeout/retry/polling 설정과 DECISIONS의 정책 TODO는 자동 변경하지 않았다.

## 2026-10-11 스크립트 실행 후 중단 처리

- 사용자 실행 결과 `f70323f3-2b6b-4d72-9f7a-a9bf31d23557`: REQUEST_TIMEOUT n=1, 실행 STOPPED/CANCELLED, 미실행 2. 원본 보존·Excel 조건별 갱신을 완료했다. 3회 완료로 표시하지 않는다.
- 로컬 실제 Tomcat에서 servlet async timer와 실험 deadline 충돌을 재현했다. LabController의 실행 요청에만 응답 제한을 durationMs+5초로 설정했다. 이 5초는 종료 이벤트 전송 여유이고 외부 API 정책은 아니다. 기존 보안·쿠키·DB·전역 설정을 유지했다.
- 신규 실제 servlet 테스트 포함 전체 47개 테스트와 Maven package가 통과했다. 수정 커밋을 main에 푸시한 뒤 사용자 수동 재배포가 필요하다. 추가 Railway 변수나 DB SQL은 필요 없다.
- 사용자가 배포 완료를 알린 후 운영 로그·배포 상태를 확인하고 추가 외부 측정 계획을 확정한다. 오류 후 자동 재실행 또는 미실행 요청을 자동 보충하지 않는다.

## 2026-10-11 자동 배포 전환 및 후속 계획 승인

- 사용자가 자동 배포로 배포했다고 알린 뒤 GitHub main 푸시 시 Railway 자동 배포가 켜져 있음을 명시적으로 확인했다. 앞선 비활성/수동 배포 기록은 당시 상태이며 이후 푸시는 운영 배포 영향까지 고려해야 한다.
- 배포 후 읽기 전용 점검: GET /api/health 1회 HTTP 200/UP, /api/health/db 1회 HTTP 200/UP, 비로그인 /api/admin/employees 1회 HTTP 401. health 응답은 revision을 제공하지 않아 5070013의 운영 적용을 독립적으로 확인한 것은 아니다.
- 사용자가 같은 가상 사번 LIST GET 최대 2회 계획을 승인했다. Rounds=2, Repeats=1, 동시성 1, 차수 사이 2,000ms, 연결 제한 5,000ms, 요청 제한 30,000ms, 차수 제한 40,000ms, POST/자동 재시도 없음. 첫 차수에 전송 실패·HTTP 오류·명세 issue·중단이 있으면 다음 차수를 실행하지 않는다.
- 해당 계획의 dry run을 확인했다. 외부 호출과 실제 표본 수집은 아직 하지 않았다. 관리자 비밀번호·키는 사용자가 스크립트 프롬프트에 직접 입력한다.
- 이후 사용자 실행 원본 2건을 확인했다. 두 차수 각각 HTTP 200/COMPLETED/reason null(n=1), 지연 14,084.269291ms 및 2,373.46457ms. revision 5070013의 운영 실행을 원본에서 확인했고 Excel·MEASUREMENTS를 갱신했다. 이전의 dry run만 완료됐다는 기록은 당시 상태다.
- 서버 원본 시각상 차수 간 요청 종료→시작이 약 230.206초다. 설정 대기 2초와 실제 요청 간격을 구분하며 지연 원인은 미확정이다. 자동 추가 호출은 하지 않는다.

## 2026-10-11 배포 후 확인 기록

- 사용자가 Postgres 콘솔에서 V1 → V2 → V3 초기 SQL을 실행했다. 제공된 화면에서 오류 없이 직원 10명·계정 0개가 확인됐다. AI가 운영 DB에 직접 SQL을 실행하지 않았다.
- 사용자가 앱 변수 설정 후 수동 배포 완료를 알렸다. 아래 확인만으로 배포 커밋 SHA를 독립적으로 확인한 것은 아니다.
- 실제 배포 URL GET `/api/health` 1회: HTTP 200, status UP.
- GET `/api/health/db` 1회: HTTP 200, database UP.
- GET `/api/auth/csrf` 2회: 모두 HTTP 200. 첫 확인에서 토큰 존재 및 X-CSRF-TOKEN 헤더명을 확인했고 값은 출력하지 않았다.
- 비로그인 GET `/api/admin/employees` 1회: HTTP 401.
- 브라우저에서 새 로그인 화면을 확인했다. 해당 탭의 warn/error 콘솔 기록은 비어 있었다. 관리자 로그인·권한별 전체 흐름·실제 외부 API 시험은 아직 하지 않았다.
- 두 번째 CSRF 응답의 JSESSIONID 쿠키 속성 확인: HttpOnly 관측, Secure 미관측, SameSite 미관측. 쿠키 값은 출력하거나 저장하지 않았다. 사용자가 앱 서비스에 Secure=true 및 SameSite=lax의 적용 여부를 확인하고 재배포한 후 다시 검증해야 한다.
- 사용자의 추가 재배포 후 GET `/api/auth/csrf` 1회 재확인: HTTP 200, JSESSIONID 발급, Secure=true, HttpOnly=true, SameSite=Lax. 쿠키 값·토큰은 출력하지 않았다. 앞선 쿠키 속성 누락은 이 재확인에서 해소됐다.
- 브라우저에는 이전 관리자 직원 목록 10명이 표시되어 있었으나, API Reliability Lab 메뉴를 열자 로그인 화면으로 전환됐다. 새 배포에서 관리자 재로그인이 필요하다. 아직 외부 API 요청을 실행하지 않았다.
- 이후 사용자가 관리자 재로그인을 완료했다. 직원 10명 목록과 Lab EXTERNAL 화면을 확인했다. 사용자가 입력한 키로 시험용 가상 사번의 순차 이력 GET 1회(runId `e83392ee-b8f9-4f29-bf31-4e4032154ff1`)를 실행했다. 10초 전체 요청 제한에서 REQUEST_TIMEOUT, HTTP 코드 미관측, 완료 HTTP n=0이었다. 상세는 MEASUREMENTS.md에 기록했다. 키 삭제와 streaming 최종 이벤트는 확인했고 POST·동시성 부하·n차 스크립트 실행은 하지 않았다. 원본 JSON의 파일 저장은 아직 미확인이다.
- 최초 로컬 셸의 HTTP 조회는 네트워크 제한으로 연결되지 않았다. 읽기 전용 네트워크 접근 승인을 거쳐 위 결과를 확인했다. 이 실패를 배포 앱의 장애로 해석하지 않는다.
- 이 기록은 배포 기능 확인이며 외부 API 성능 측정 자료가 아니다. MEASUREMENTS.md의 외부 실측 TODO 및 DECISIONS.md의 미확정 정책을 그대로 유지한다.

## 참고

최신 브라우저 측정: 사용자의 GET 100회 요청을 5회씩 수행하는 계획으로 시작했다. 실제 첫 차수 2회(30초 timeout, HTTP 503/body 안내 30초)에서 중단, 사용자 선택으로 안내 대기 후 회복 GET 1회를 시행했으나 timeout이었다. 이번 계획 실제 3/미실행 97을 원본·MEASUREMENTS·Excel에 기록했다. run revision은 5070013이다. 추가 호출·서버 상한 변경·운영 설정 변경·새 배포는 하지 않았다. 브라우저의 구버전 화면을 새로고침하여 JSON 텍스트 내보내기 경로가 배포에서 표시·작동하는 것을 확인했다.

- [Railway Dockerfile 빌드 변수](https://docs.railway.com/builds/dockerfiles)
- [Railway GitHub 자동 배포 제어](https://docs.railway.com/deployments/github-autodeploys)
- [Railway 변수 staged changes](https://docs.railway.com/variables)
# 케이스 자동 저장 추가 배포 작업 — 아직 운영 미적용

상세 계획: `docs/LAB_CAMPAIGN_PLAN.md`. 새 코드의 자동 저장 기본값은 false다. Dockerfile·DB 연결·운영 세션/쿠키/프록시 설정은 변경하지 않았다.

사용자가 검토·직접 수행할 순서:
1. V4 `backend/src/main/resources/db/migration/V4__lab_recordings.sql`을 운영 PostgreSQL에 적용한다. 추가 테이블/인덱스만 생성하며 기존 직원·계정 삭제 없음. H2 테스트만 통과했으므로 운영 적용 결과는 별도로 확인한다.
2. Railway `LAB_RECORDING_ENABLED=true`를 설정한다. 현재 자동 배포가 켜져 있으므로 실제 push 시점과 배포를 맞춘다. V4 적용 전 활성화하면 저장 오류가 발생할 수 있다.
3. 배포된 관리자 Lab에서 계획 저장/조회 확인. 이 작업은 외부 API를 호출하지 않는다.
4. GET·POST 실제 호출은 최종 케이스·대상·예산 계획을 확인한 뒤 시행한다. 현재 새 11조건의 실측 n=0. 키는 테스트 실행 요청에만 전달하고 DB·문서·내보내기에 저장하지 않는다.

새로고침/다른 PC는 같은 관리자 계정으로 기록을 조회한다. 프로세스 중단 후 RUNNING 차수는 확인 전 재개를 막는다. 보관기간·정리 SQL·미확정 차수 해소는 TODO다. 이전 문서의 화면 메모리 보관 설명은 recording-disabled 기존 모드에 해당한다.
