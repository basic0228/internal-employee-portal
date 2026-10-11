# 수동 배포 → 브라우저 검증 → 반복 측정 → Excel → 정책 근거

2026-10-11 사용자 요청. 배포와 키 입력은 사용자가 직접 수행한다. 사용자는 실제 외부 API + 가상 직원 데이터 방식과 Railway 자동 배포 비활성을 확인했다. 외부 API 호출·운영 DB·Railway 설정 변경을 이 문서 작성 단계에서 수행하지 않았다.

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

## 참고

- [Railway Dockerfile 빌드 변수](https://docs.railway.com/builds/dockerfiles)
- [Railway GitHub 자동 배포 제어](https://docs.railway.com/deployments/github-autodeploys)
- [Railway 변수 staged changes](https://docs.railway.com/variables)
