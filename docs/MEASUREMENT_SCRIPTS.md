# 승인된 소규모 GET 측정과 Excel 재생성

## 사용자가 지금 실행할 명령

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

현재 초안은 첫 외부 타임아웃 원본 1건만 포함한다. 두 번째 HTTP 200은 화면 관측만 남았으므로 JSON으로 재구성하거나 Excel 원본 표본에 합산하지 않았다. 승인된 3회 측정 파일을 받으면 파일별 조건을 확인하고 다시 생성한다. 평균·Type 7 백분위수는 원본 기반 계산 스냅샷이다. 새 원본이 생기면 빌더를 다시 실행한다. 개수·비율·후보 비교는 Excel 수식이며 후보 셀 입력 변경을 검증했다.

## 정책 근거와 남은 검증

완료 HTTP 지연과 중단까지 경과시간을 별도로 집계한다. Type 7은 `h=(n−1)p`에서 인접 순위의 선형 보간이다. 작은 n의 p99는 극소수 관측에 의존하며, 타임아웃을 제외한 완료 분포는 낙관적으로 보일 수 있다. 중단 경과시간에는 취소 오버헤드가 포함되므로 완료 지연의 후보 초과를 단정하지 않는다.

Retry 성공률·Retry-After 실제 효과·업무 완료시간·Polling·동시성 상한은 필요한 실험 전까지 추가 실험 필요다. 후보 셀은 비워 두고 선택 이유·반대 장점·부적절해질 상황은 TODO다. 사용자 확인 전 DECISIONS.md와 Spring 운영 설정을 자동 변경하지 않는다.

JSON 다운로드 보완은 DOM에 연결한 링크와 URL 수명 연장, `JSON 텍스트 보기` 대체 경로다. 로컬 LOCAL_MOCK에서 JSON 유효성·키 미포함·읽기 전용·경고 표시를 확인했다. 실제 배포 후 파일 저장 동작은 별도 검증이 필요하다.
