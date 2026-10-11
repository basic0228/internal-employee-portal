# 승인된 소규모 GET 측정과 Excel 재생성

## 사용자가 지금 실행할 명령

**현재 상태:** 아래 승인된 계획은 이미 실행됐고 첫 GET 타임아웃 뒤 CANCELLED로 중단됐다(`f70323f3-2b6b-4d72-9f7a-a9bf31d23557`, 실제 n=1/미실행 2). 그대로 재실행하지 않는다. Lab 전용 servlet 응답 제한 수정을 사용자 수동 배포한 뒤, 운영 로그 및 새 측정 계획을 확인한다. 아래 명령은 수행한 계획의 기록이다.

2026-10-11 사용자가 다음 조건으로 승인했다: 실제 외부 API, 동일 가상 사번의 LIST GET 총 3회, 동시성 1, 호출 간 2초, 연결 제한 5초, 전체 요청 제한 30초, 실험 최대 100초. POST·자동 재시도는 없다. 이 값은 실험 조건이며 운영 정책은 TODO다.

PowerShell 7 터미널에서 실행한다. 관리자 아이디·비밀번호와 X-Candidate-Key는 실행 중 프롬프트에 직접 입력한다. 대화나 명령행에 비밀 값을 넣지 않는다.

```powershell
pwsh -NoProfile -File "C:\workspace\internal-employee-portal\scripts\measure-lab-get.ps1" `
  -PortalUrl "https://internal-employee-portal-production.up.railway.app/" `
  -Endpoint LIST -TargetId LAB-20261011-001 `
  -Rounds 1 -Repeats 3 -MaxConcurrency 1 `
  -ConnectTimeoutMs 5000 -RequestTimeoutMs 30000 `
  -DurationMs 100000 -IntervalMs 2000 -RoundGapMs 0 `
  -OutputDirectory "C:\workspace\internal-employee-portal\docs\measurements\raw" `
  -ExecuteApprovedPlan
```

`-ExecuteApprovedPlan`을 빼면 계획만 출력한다. 네트워크 요청·로그인·키 입력은 하지 않는다. 위 계획은 현재 배포된 Lab 서버에서 사용할 수 있으며 새 UI 다운로드 보완 배포가 선행 조건은 아니다. 브라우저 로그인과 별도의 스크립트 세션을 만들고 종료 시 해당 세션 로그아웃을 시도한다.

실행이 끝나면 `lab-<runId>.json`의 생성 여부를 확인하고 완료 또는 실패를 알려준다. 오류가 발생해도 임의로 재실행하지 않는다. 세션 인증·CSRF·capabilities 조회는 별도 포털 요청이며 외부 GET 표본 수에 포함하지 않는다.

## 제한과 결과 보존

- LIST/DETAIL GET만 지원한다. 실험 실행 자체는 포털에 POST하지만 외부 Background Check 생성 POST를 수행하지 않는다.
- 실제 운영 포털의 HTTPS origin 또는 명시적으로 LOCAL_MOCK을 선택한 loopback만 허용한다. 전체 도구 호출의 application attempt 예산은 최대 10회다. 서버 상한도 확인한다. 새 조건의 외부 실험은 별도 사용자 승인이 필요하다.
- 동시성 실험은 1부터 선택한 상한까지 각 단계에서 repeats개를 실행한다. 계획 수는 `Rounds × Repeats × MaxConcurrency`다. 승인된 이번 실행은 1×3×1이다.
- 최종 이벤트·실행 ID·출처·관측·설정 조건·버전을 JSON으로 보존한다. 키·실제 employeeId/checkId·인적사항 필드는 저장을 거절한다. 파일을 덮어쓰지 않는다.
- 최종 결과에 전송 실패, HTTP ≥400, 명세 issue 또는 중단이 있으면 **다음 차수**를 시작하지 않는다. 같은 차수의 나머지 GET은 서버 실행 계획에 따라 계속될 수 있다. 이번 계획은 한 차수다.
- 읽을 수 있는 NDJSON에 최종 이벤트가 없으면 partial 파일로 구분한다. 연결 자체가 끊겨 응답을 읽지 못하면 파일이 없을 수 있다. 이 경우 완료 원본을 만든 것으로 표시하지 않는다.
- 요청 제한과 실험 deadline은 서로 다르다. 마지막 요청은 남은 실험 시간에 의해 일찍 중단될 수 있다. 설정한 연결 제한으로 실제 연결 단계 지연을 측정했다고 주장하지 않는다.

## Excel 생성

`scripts/lab-export-xlsx.mjs`는 외부 호출 없이 보존된 JSON만 읽는다. Summary, Candidates, Attempts, Runs 네 시트를 생성한다. 실행 ID·Endpoint·동시성별로 구분하고 서로 다른 조건을 자동 합산하지 않는다. 원본 SHA-256과 버전을 남긴다. 중복 runId와 민감 필드가 있는 입력은 거절한다.

`.gitattributes`로 `docs/measurements/raw/*.json`의 줄바꿈 자동 변환을 막아 Git checkout 후에도 기록한 원본 바이트와 SHA-256을 유지한다.

이 워크스페이스에서는 Codex 제공 `@oai/artifact-tool`과 번들 Node를 사용했다. `scripts/node_modules`는 제공 런타임 패키지 경로의 로컬 junction이며 Git에 포함하지 않는다. 별도 컴퓨터에서는 이 의존성을 먼저 준비해야 한다. 예:

```powershell
& "C:\Users\lilie\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe" `
  "C:\workspace\internal-employee-portal\scripts\lab-export-xlsx.mjs" `
  "C:\workspace\internal-employee-portal\outputs\api-reliability-lab.xlsx" `
  "C:\workspace\internal-employee-portal\docs\measurements\raw\lab-<runId>.json" `
  --no-render
```

이번 Windows 런타임의 이미지 렌더러는 `Failed to resolve Vulkan proc vkEnumerateInstanceVersion`으로 실패했다. `--no-render`로 계산 검증·XLSX 저장은 성공했으나 화면 배치 검증은 미완료다. 렌더링 가능한 환경에서는 옵션을 빼고 생성된 시트 PNG를 직접 확인한다.

현재 초안은 원본 6개 실행의 실제 관측 7건(타임아웃 4/HTTP 200 2/HTTP 503 1)을 포함한다. 기록 조건·버전·collection batch가 같은 이전 완료 2건의 기술 통계는 별도로 유지한다. 원본이 없는 브라우저 HTTP 200 2건은 JSON으로 재구성하거나 Excel 원본 표본에 합산하지 않았다. 실행별 조건을 분리하고 Runs에 예정·시작·종료·미실행 n을 기록한다. 평균·Type 7 백분위수는 원본 기반 계산 스냅샷이다. 새 원본이 생기면 빌더를 다시 실행한다. 개수·비율·후보 비교는 Excel 수식이며 후보 셀 입력 변경과 원본별 오류 개수·중단 평균 대조를 검증했다.

## 정책 근거와 남은 검증

완료 HTTP 지연과 중단까지 경과시간을 별도로 집계한다. Type 7은 `h=(n−1)p`에서 인접 순위의 선형 보간이다. 작은 n의 p99는 극소수 관측에 의존하며, 타임아웃을 제외한 완료 분포는 낙관적으로 보일 수 있다. 중단 경과시간에는 취소 오버헤드가 포함되므로 완료 지연의 후보 초과를 단정하지 않는다.

Retry 성공률·Retry-After 실제 효과·업무 완료시간·Polling·동시성 상한은 필요한 실험 전까지 추가 실험 필요다. 후보 셀은 비워 두고 선택 이유·반대 장점·부적절해질 상황은 TODO다. 사용자 확인 전 DECISIONS.md와 Spring 운영 설정을 자동 변경하지 않는다.

JSON 다운로드 보완은 DOM에 연결한 링크와 URL 수명 연장, `JSON 텍스트 보기` 대체 경로다. 로컬 LOCAL_MOCK에서 JSON 유효성·키 미포함·읽기 전용·경고 표시를 확인했다. 실제 배포 후 파일 저장 동작은 별도 검증이 필요하다.

## 중단 재현과 Lab 응답 제한 수정

외부 1회 요청의 timeout과 실험 전체 CANCELLED를 구분한다. StreamingResponseBody에도 별도 servlet async timer가 있다. 제한이 실험 전체보다 짧으면 여러 요청·간격 대기를 수행하는 스레드가 먼저 interrupt될 수 있다. 로컬 실제 Tomcat에서 MVC async 제한 100ms를 강제로 설정한 회귀 테스트가 수정 전 STOPPED로 실패하며 AsyncRequestTimeoutException을 기록했다. 이는 로컬 검증 조건이며 운영 API timeout 추천값이 아니다.

LabController의 해당 실행 요청에만 `durationMs + 5,000ms`를 설정했다. 추가 5초는 실행 종료·최종 이벤트 전송 여유이며 외부 지연 실측으로 결정한 값이 아니다. 외부 개별 요청과 실험 자체의 deadline은 유지한다. 혹시 이 응답 제한까지 도달하면 STREAM_TIMEOUT으로 구분한다. 글로벌 application.yml·쿠키·프록시·보안·DB 설정은 바꾸지 않았다.

수정 후 실제 servlet 반복 timeout 관측 완료와 실험 deadline 중단/최종 이벤트 보존 테스트가 통과했다. Maven package에서 전체 47개 테스트가 통과했다. 운영 로그는 아직 확인하지 않았으며, 운영 CANCELLED가 이 메커니즘이었다는 결론과 배포 후 정상 동작은 별도 검증 대상이다.

## 후속 승인 계획 결과

사용자가 자동 배포 켜짐을 확인한 뒤 Rounds=2/Repeats=1/동시성 1/연결 5초/요청 30초/차수 40초/차수 사이 설정 대기 2초 계획을 승인·실행했다. `2bc4b99c-502d-4f1b-9fbb-9a767c193833` 및 `7766daec-5f98-450b-89b8-7cd18f4683d2`는 revision 5070013, 각 HTTP 200(n=1), 총 완료 HTTP n=2다. 지연 14,084.269291ms와 2,373.46457ms를 원본으로 확인했다.

서버 원본 시각상 첫 요청 종료→다음 시작이 약 230.206초다. 설정 sleep 2초와 실제 요청 간격을 구분한다. 원인은 미확정이며 클라이언트 단계별 추가 계측 전 연결 재사용/cold start로 설명하지 않는다. 두 실행 모두 30초 내 완료했으므로 30초 이상 운영 스트리밍을 검증했다고 표시하지 않는다.

사용자는 별도 조작 없이 기다렸다고 확인했고 로컬 네트워크 영향 가능성을 언급했다. PC↔Railway 통신은 차수 사이 시간에 영향을 줄 수 있다. Railway→외부 API 요청의 서버 elapsedMs와 구분해서 분석하며 원인은 미확정으로 남긴다.

## 배포 브라우저 100회 계획의 중단 및 오프라인 근거

사용자가 브라우저 100회 시행을 요청했다. 첫 5회 차수에서 실제 2회만 실행한 뒤 503/body retryAfter=30초에서 서버가 중단했다. 사용자 선택에 따라 안내 대기 이후 단일 회복 확인을 했으나 30초 timeout으로 실패했다. 실제 3회/미실행 97회다. 기존 스크립트의 전체 예산 10을 우회하거나 서버 상한을 변경한 것이 아니다.

`docs/measurements/browser-100-plan.json`에는 요청한 횟수와 이번 계획의 runId를 기록한다. `scripts/lab-evidence.mjs`가 원본에서 실제 n을 계산하며, `lab-export-xlsx.mjs --plan <파일>` 옵션으로 항목별 근거를 추가한다. Summary에는 계획/실제 횟수와 조건별 정상·오류 지연을, Evidence에는 10개 항목의 근거/표본 수/추가 검증/TODO를, Candidates에는 5·10·15·20·30초 고정 후보 재분류를 기록한다. 표준 XLSX의 원본 표에는 이전 실행도 남지만 계획 집계는 지정한 runId만 사용한다. 회복 확인을 일반 재시도 성공률로 계산하지 않는다. 타임아웃 경과와 503의 본문 수신 지연은 성공 지연과 분리한다.

```powershell
$labSourcePaths = Get-ChildItem -LiteralPath docs/measurements/raw -Filter 'lab-*.json' | Sort-Object Name | Select-Object -ExpandProperty FullName
& 'C:/Users/lilie/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe' scripts/lab-export-xlsx.mjs outputs/01a124de-b755-76c1-a212-a325ffee9c34/api-reliability-lab.xlsx @labSourcePaths --no-render --plan docs/measurements/browser-100-plan.json
& 'C:/Users/lilie/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe' --test scripts/lab-evidence.test.mjs
```

위 명령은 보존된 JSON만 읽으며 API를 호출하지 않는다. 원본 테스트 5개(실제 n, 성공/오류 분리, 미관측 코드, 회복 실패, 중복/누락/예산 초과)가 통과했다. 배포 JSON fallback의 읽기 전용 값을 원본으로 저장하는 경로는 확인했으나, 브라우저 download 이벤트를 통한 저장은 여전히 별도 확인 대상이다.
# 케이스별 100 HTTP 요청 계획/서버 자동 저장

`docs/LAB_CAMPAIGN_PLAN.md` 참조. 관리자 단일 Lab 화면에서 GET 9조건/Polling/중복 POST 계획을 저장하고 한 케이스씩 실행한다. 목표는 POST 업무 실행 수가 아니라 POST·Polling GET·전후 이력 GET·오류까지 포함한 실제 HTTP 관측 수다. `lab_campaigns`/`lab_runs` 저장에 V4와 LAB_RECORDING_ENABLED가 필요하다.

계획별 JSON 내보내기는 `{plan,runs:[{runId,caseId,campaignId,recordingStatus,stale,result}]}` 구조다. `result`는 기존 실행 원본 구조이고 출처(LOCAL_MOCK/EXTERNAL)를 유지한다. 키·가상 대상·개인정보·외부 상세는 제외된다. 계획별 상세 조회는 전체 차수를 포함한다. 최근 저장 목록의 100차수 제한과 케이스의 HTTP 100건 목표를 구분한다.

기존 오프라인 Excel builder/GET plan 분석은 단일 run 원본 및 기존 GET 계획 입력 방식이다. **새 캠페인 묶음/POST·Polling 항목별 Excel 자동 집계는 TODO**다. 새 실측 확보 전 표본을 생성하지 않는다. 브라우저의 기존 단일 run 분석/JSON/CSV/Markdown 내보내기는 저장된 실행을 불러와 사용할 수 있다.
