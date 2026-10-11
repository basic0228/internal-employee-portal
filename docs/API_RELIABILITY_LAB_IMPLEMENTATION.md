# API Reliability Lab — 로컬 구현과 검증

2026-10-11. 사용자 승인한 핵심 설계 범위를 구현했다. 외부 API·운영 DB·Railway는 변경하거나 호출하지 않았다. 이 문서의 모의 검증을 MEASUREMENTS의 외부 실측으로 제출하지 않는다.

## 구현 파일

| 파일 | 역할 |
|---|---|
| frontend/src/components/ReliabilityLab.vue | ADMIN 단일 화면 A~E, 진행·취소, 후보 비교, 판단 DRAFT, 다운로드 |
| frontend/src/lab/analysis.js | Type 7, 분모·중도절단, Timeout/Retry/Polling 모델 |
| frontend/src/lab/exports.js | 비식별 JSON/CSV·Markdown·YAML 초안 |
| frontend/src/PortalApp.vue / portal.css | 관리자 메뉴·Lab 배치·반응형 레이아웃 |
| backend/.../backgroundcheck/client/BackgroundCheckClient.java | 공통 HTTP 전송, 계측, 본문 크기·전체 deadline·동시성 제한 |
| backend/.../lab/LabRequest.java / LabController.java / LabRunner.java | 검증·예약·실험·NDJSON·소유자 취소 |
| backend/.../backgroundcheck/BackgroundCheckService.java / BackgroundCheckController.java | 같은 Client를 쓰는 업무 이력·상세 GET, 서버 키·직원 소유 검증 |
| backend/src/main/resources/background-check-api.yaml | 첨부 Swagger 사본·실행 결과의 SHA-256 근거 |
| backend/src/main/resources/application-portal-dev.yml | 기본 off 플래그 및 업무 GET timeout 바인딩 |
| backend/src/test/.../lab / application-preview.yml | 테스트 전용 모의 API·실험 검증·시연 |
| scripts/lab-preview-smoke.ps1 | LOCAL_MOCK만 허용하는 재현 가능한 순차 GET 검증 |

## 단일 화면과 실험

A는 마스킹된 키·대상·반복·동시성·측정 제한·호출 예산·POST 확인을 받는다. B는 Endpoint별/선택 대상 통계, 상태/전송 결과, 시각 순 산점도, 동시성, 업무 처리/발견 시간, POST 결과, 명세 차이를 표시한다. C는 추가 호출 없이 후보를 재분류·계산한다. D는 실행 ID·출처·대상·n·통계 snapshot·이유·반대 장점·부적절한 상황을 DRAFT로 기록한다. E는 파일 초안을 다운로드하며 저장소/설정을 자동 수정하지 않는다.

| 실험 | 절차와 호출 예산 |
|---|---|
| GET 순차 | 선택한 GET을 n회, 앱 재시도 없이 측정 |
| GET 동시성 | 동일 대상·제한으로 c=1→2→최대 3. 단계별 n회, 합계 n×단계 수 |
| POST 완료 관찰 | POST 1회. pending이면 간격을 두고 최대 K회 상세 GET. 즉시 clear/flagged이면 종료 |
| 동일 employeeId 반복 POST | 이력 GET → POST → 간격 → POST → 이력 GET, 최대 4회. 생성 결과 불명이면 추가 POST를 보내지 않음 |

401/404, 429/503 또는 유효한 Retry-After, 정상 코드의 계약 오류, 취소·기간·호출 예산에서 중단한다. 동시 묶음에서 이미 전송된 호출은 모두 관측한다. 중단/연결 취소는 이미 생성된 외부 기록을 되돌리지 않는다. 반복 POST의 이력 변화만으로 타 호출 배제·불명 POST 귀속·exactly-once를 주장하지 않는다.

상한은 20회/실행, 1실행, 120초, 공통 Client 3 in-flight다. 업무 GET과 Lab이 같은 프로세스 제한을 공유한다. 다중 인스턴스 합산·실제 외부 처리 중인 작업의 개수는 보장하지 않는다. 타임아웃 뒤 외부 작업이 계속될 수 있다.

## 계측·통계·모델의 의미

- Java 21 HttpClient를 직접 사용한다. 연결 제한과 별도로 헤더+본문 전체 수신 Future에 deadline을 적용한다. 본문 최대 256 KiB, redirect 미추적, HTTP/1.1. 본문 멈춤 테스트로 단순 헤더 제한과 구분했다.
- elapsedMs는 앱 sendAsync 시작부터 본문 완료/전송 중단까지의 단조 시계 시간이다. JSON 해석은 제외한다. queueWaitMs는 공통 슬롯 대기와 요청 준비 시간이다. 연결·DNS·TLS·TTFB 개별 시간은 계측하지 않는다.
- 동일 실행에서 Client를 재사용하지만 실제 소켓 재사용·JDK 내부 전송 복구는 계측하지 않는다. 앱 재시도 OFF는 wire 요청이 정확히 1회임을 의미하지 않는다.
- 원본 Run은 실행 ID, 조건, 출처, 명세 hash, Java/Client/코드 버전, 진행·중단 상태를 담는다. 로컬 코드 버전은 LOCAL_UNRECORDED이며 제출 실측 때 Git 버전을 함께 기록해야 한다.
- Attempt에는 전송 결과, HTTP 코드(미관측은 null), 시작/종료·elapsed·queue, bytes, 업무 상태·check 별칭, 검증 issue, Retry-After 헤더/본문·충돌을 담는다. 개인식별 대상·키·상세 결과는 내보내지 않는다. sequence는 관측 수집 순서이며 동시 응답의 정확한 도착 순서가 아니다. 그래프는 startedAt 순으로 정렬한다.
- Workflow는 같은 checkId를 1건으로 묶는다. 서버 completedAt−createdAt과 첫 pending 관측→첫 최종 발견을 구분한다. 미완료는 CENSORED이며 완료 시각을 만들지 않는다.
- Type 7: 정렬된 값에서 h=(n−1)p, floor/ceil 사이 선형 보간. 모든 지연 통계는 완료 HTTP n, 코드/전송 분포는 해당 전체 시도 n. 헤더 후 타임아웃은 코드와 오류 양쪽에 나타날 수 있으므로 합산하지 않는다.
- p99 보간에 사용한 순위, 평균−중앙값, IQR 참고 이상치를 표시한다. 이상치는 제거하지 않는다. 작은 n을 무효로 단정하지 않으며 tail 민감성·완료 표본 선택 편향을 설명한다.
- Timeout 후보보다 긴 완료 응답은 초과, 늦게 끝난 timeout은 확정 초과, 그 외 중단은 미확정이다. 타임아웃 경과시간을 완료 응답시간으로 대체하지 않는다.
- Retry의 R은 추가 시도 수다. W=(R+1)×가정한 요청 전체 상한+대기 합. Fixed/Exponential(cap 포함), 선택 코드에 관측한 Retry-After 최대값 적용 여부를 비교한다. 미래 Retry-After·성공률·비관측 부하는 추가 실험 필요다. 전체 deadline을 별도로 둔다.
- Polling 모델은 서버 업무 d를 사용한 ceil(d/주기)다. 즉시 완료 POST·완료 시각 미확인은 제외한다. HTTP 지연·POST 수신 지연·실패·Retry-After를 생략한 단순 모델이며 GET 수와 발견 지연의 표본을 따로 표시한다.
- 동시성 단계는 같은 실행 조건이나 고정 순서·시각의 교란이 남는다. 오류 0건은 운영 안전성의 증명이 아니다.

## 로컬 실행과 실제 호출 준비

Java 21, Maven, npm을 준비하고 프로젝트 루트에서 실행한다.

```powershell
./scripts/dev-preview.ps1 -MavenCommand mvn
# 별도 터미널
$env:VITE_PORTAL_UI_ENABLED='true'
npm --prefix frontend run dev
# 로컬 관리자 preview-admin / PreviewPassword1
# Lab 키는 테스트 전용 preview-test-key. 실제 키를 시연에 넣지 않는다.
./scripts/lab-preview-smoke.ps1 -OutputFile .tools/lab-preview-result.json
```

preview는 H2 메모리 DB와 테스트 클래스의 모의 API만 사용하며 테스트 설정·H2는 운영 JAR에서 제외한다. 관리자에서 입력하는 개인정보는 조회용이며 직원 원본을 수정하지 않는다. 화면의 초기 제한 숫자는 측정 조건 입력 편의를 위한 값이며 외부 측정 결과·운영 추천값이 아니다.

실제 측정 전에는 시험용 ID/키·GET 초기 n·제한·POST 데이터·단일 서버 여부를 확인받는다. portal-dev의 LAB_ENABLED와 BG_EXTERNAL_ENABLED는 기본 false, POST는 추가 LAB_POST_ENABLED와 화면 확인이 필요하다. Lab 키는 해당 실험 요청에만 사용한다. 업무 GET은 별도로 BG_ENABLED와 서버 X_CANDIDATE_KEY 및 확정 BG_CONNECT_TIMEOUT_MS/BG_REQUEST_TIMEOUT_MS를 필요로 한다. UI/서버 플래그를 운영에 적용하지 않았다.

현재 설정 바인딩은 background-check.client.connect-timeout-ms / request-timeout-ms다. read-timeout 대신 본문 전체까지 제한하는 request-timeout을 쓴다. Retry/Polling/max-in-flight YAML 항목은 실행 코드에 연결되지 않은 후보 초안임을 출력에 명시한다. 사용자 확인 후 해당 정책 코드를 추가·검증해야 한다. MEASUREMENTS/DECISIONS는 외부 실측·운영 판단 TODO를 유지한다.

## 검증과 남은 범위

검증 결과와 실제 발생한 오류 수정은 AI_LOG의 2026-10-11 항목에 기록한다. 자동 테스트는 Java 21/H2/MockMvc/loopback 모의 서버 및 Node 통계 계산이며 실제 PostgreSQL·외부 API 성능을 보장하지 않는다.

최종 결과: 백엔드 45개·프론트 계산/내보내기 생성 7개 통과, Maven package 및 새 UI/기본 UI 빌드 성공. 브라우저에서 모의 GET 동시성·POST 완료 관찰·후보 모델·판단 기록·업무 이력/상세를 확인했다. 내장 브라우저의 download 이벤트를 받지 못해 파일 저장 완료 검증은 남았다. 다운로드 형식 생성과 화면 YAML 출력은 확인했다. 새 UI 번들 500kB 초과 경고는 남아 있다.

외부 실측, 운영 timeout/retry/polling 확정, 업무 POST 정책, metadata 저장·보관, 실제 재시도 자동 실험, 연결 단계 계측, 다중 서버 제한, 순서 반전 비교, 전체 OpenAPI validator, 장기 저장은 남은 범위다. 6시간 핵심 범위에서는 입력·실험·통계·후보 모델·판단·다운로드·접근 통제에 집중했다. 누적 과제 작업시간은 별도 집계 TODO다.

## 예상 면접 질문

1. 헤더를 받은 뒤 본문이 멈추면 timeout은 어떻게 보장하는가?
2. 타임아웃 표본을 p99 계산에서 제외하면 어떤 편향이 생기는가?
3. POST 타임아웃 후 왜 자동 재전송을 하지 않는가?
4. 실제 재시도 없이 대기시간은 계산할 수 있지만 성공률은 왜 계산할 수 없는가?
5. 업무 처리시간과 폴링 발견 지연은 무엇이 다른가?
6. 동시성 3에서 오류 0건을 봤다면 어떤 결론까지 낼 수 있는가?
7. Lab과 업무 화면이 같은 Client를 공유하는 이유와 설정 초안을 자동 적용하지 않는 이유는 무엇인가?
