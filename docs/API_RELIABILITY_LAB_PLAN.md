# API Reliability Lab — 구현 전 설계안

> 2026-10-10 설계 원본 / 2026-10-11 사용자 요청에 따라 핵심 로컬 구현 완료. 실제 API 호출·운영 정책 적용은 TODO.
> 기존 API 테스트/모니터링 요구를 이 설계로 대체한다. 현재 구현·차이·검증 범위는 [구현 안내](API_RELIABILITY_LAB_IMPLEMENTATION.md)에 기록한다.
> 흐름: 실측 → 분석 → 후보 비교 → 개발자 판단 초안 → 사용자 확인 → 코드 반영.
> 실측값과 운영 설정은 확인 전 TODO. 아래 호출 상한·시간 배분은 제안이며 실측 결과가 아니다.

## 1. 관리자 단일 화면

관리자 화면에 API Reliability Lab 진입 버튼 하나를 추가하고 한 페이지에서 아래 순서로 사용한다. 직원 메뉴에는 표시하지 않고 서버도 ADMIN + 세션 + CSRF로 제한한다.

| 영역 | 구성 |
|---|---|
| A 실험 설정 | 마스킹된 키 입력, 명세의 3개 Endpoint 선택, employeeId/checkId 선택, POST용 성·이름·생년월일 입력, 실험 유형, 반복 수·동시성·측정용 제한시간·호출 간격·호출 예산 |
| 진행 상태 | 실행 ID, 예정/시작/종료/미실행 요청 수, 단계, 소비한 외부 호출 예산, 중단 이유, 중단 버튼 |
| B 관측 | n이 포함된 통계 카드, 상태코드/네트워크 오류 표, 요청별 시간순 표, 조건별 동시성 비교, pending 상태 타임라인, 명세 대조 표 |
| C 후보 비교 | Timeout/Retry/Polling/동시성 입력과 후보 비교표. 후보 변경은 추가 외부 호출 없이 기존 데이터로 계산 |
| D 판단 기록 | 선택값, 실행 ID, 통계별 n, 근거, 이유, 반대 선택의 장점, 부적절해질 상황. 미측정 항목은 TODO |
| E 출력 | 비식별 원본 JSON/CSV, MEASUREMENTS용 Markdown, 설정 YAML 초안 다운로드. 설정 파일을 자동 수정하지 않음 |

보조 그래프는 요청 순서별 응답시간·상태 표시 하나부터 구현한다. 값 없는 카드는 0 대신 측정 전/추가 실험 필요로 표시한다. 통계값과 수학 모델 결과를 서로 다른 표시로 구분한다.

## 2. 실험 절차

공통으로 실행 전에 조건을 고정하고 예정된 모든 외부 호출 수를 계산한다. 기본 실측에서는 애플리케이션 재시도를 끈다. HTTP Client 내부의 투명한 재시도 여부도 구현 시 확인하고, 관측 가능한 attempt와 실제 wire 요청을 혼동하지 않는다.

| 유형 | 절차 | 판단 범위 |
|---|---|---|
| GET 순차 | 기존 checkId 상세 GET 또는 employeeId 이력 GET을 같은 조건에서 반복. 상세/이력 결과는 별도 집계 | 응답 지연·코드·스키마 차이 |
| GET 동시성 | 같은 실행·키·대상·표본 수·제한시간·호출 간격으로 c=1 → 2 → 3 단계를 수행. 각 단계 앞뒤의 시각과 순서를 기록 | 관측한 범위의 지연·오류·처리량 비교 |
| 단일 POST 및 완료 관찰 | 시험용 데이터로 POST 1회. 즉시 clear/flagged면 별도 분류. pending이면 해당 checkId만 GET, 예산 내 관찰 | HTTP 시간과 업무 처리시간 분리 |
| 동일 employeeId 반복 POST | 승인받은 시험용 사번으로 동일 내용의 POST 최대 2회 순차 실행. 전후 이력 GET과 반환 checkId 비교 | 새 레코드 생성/같은 ID 반환/거절/결과 불명 |
| 실제 GET 재시도 | 후속 범위. 자연 발생한 실패를 시작점으로 각 attempt·대기·종료를 기록. 의도적인 장애 유발은 하지 않음 | 해당 실험에서 관측한 재시도 결과만 집계 |
| 연결 조건 비교 | 후속 범위. freshClient와 reusedClient 조건 비교 | 총 지연 비교만으로 DNS/TCP/TLS 시간을 분리했다고 주장하지 않음 |

- 동시성 실험은 GET으로 한정한다. 비교 단계들의 총 호출 수가 실행 예산을 넘으면 서버가 거절한다.
- 단계 순서는 시간 변화·캐시 영향과 혼재할 수 있다. 이를 표시하고 필요하면 역순/반복 실험을 요청한다. 오류 0건을 안전성 보장으로 해석하지 않는다.
- 503/429 또는 유효한 Retry-After를 관측하면 다음 호출을 계속 밀어 넣지 않는다. 안내 시간만큼 대기하거나 예산 부족 시 실험을 중단한다. 관측 분포가 이런 중단 규칙의 영향을 받았음을 기록한다.
- POST 타임아웃·통신 단절은 성공/실패 대신 결과 불명이다. 자동 재전송하지 않고 승인된 예산 내 이력 대조를 시도한다. 이력 대조도 실패하거나 다른 호출자 생성과 구분 못 하면 결과 불명을 유지한다.
- 직원 시드나 원본 인적사항을 바꾸지 않는다. 외부 삭제 API가 없으므로 테스트 레코드를 나중에 삭제할 수 있다고 약속하지 않는다.
- polling 관찰에는 반드시 측정용 간격·횟수·마감 시간을 입력한다. 이것은 운영 정책이 아니며 Swagger 예시값을 실제 완료시간으로 사용하지 않는다.

## 3. 수집 구조 및 개인정보 처리

아래는 필드 설계다. 예시 측정값/가상 실행 결과를 넣지 않는다.

### Run — 조건 및 provenance
- runId, comparisonGroupId, parentRunId(있을 때), 유형, 시작/종료 UTC, 실행/중단 상태와 이유
- 환경 라벨(local/Railway), 코드 리비전, Java/Client 버전, 명세 파일 SHA-256
- Endpoint enum, targetAlias, 연결 모드 힌트, 요청한 HTTP 버전/관측 HTTP 버전
- 반복 수, 단계별 동시성, 총 호출 예산, 측정용 연결·요청 제한시간, 호출 간격, deadline
- 워밍업 여부/실제 워밍업 n. 워밍업도 원본과 예산에 포함하고 본 통계와 분리
- planned/started/finished/notStarted counts, 모든 분석에 사용한 필터
- 키·키 해시·실제 성/이름·생년월일·요청/응답 전문을 저장하지 않는다

### Attempt — 요청 1회의 관측
- attemptId, logicalOperationId, 순번, 실행/동시성 단계, 재시도 차수, 요청 종류
- 예약/시작/종료 시각, queueWaitMs, httpElapsedMs 또는 abortElapsedMs. 경과시간은 System.nanoTime으로 계측하고 UTC 시각은 대조용으로만 사용
- HTTP 코드(nullable), 결과 유형 HTTP_RESPONSE / CONNECT_TIMEOUT / REQUEST_TIMEOUT / NETWORK_ERROR / CANCELLED
- 연결/요청 제한시간, 취소 시한, 계측하지 못한 연결 단계 시간은 null
- Retry-After 원문(값 검증·길이 제한) 및 파싱값, body.retryAfter의 숫자값, 충돌/파싱 오류 여부
- checkRef(실행 내 별칭), status, createdAt/completedAt/estimatedCompletionSeconds의 허용된 메타데이터
- 응답 bytes, JSON/필드/상태 enum/ID 대응/시각 순서의 검증 결과와 JSON 경로. 실제 필드값이나 예외 메시지 전문은 남기지 않는다

### Workflow — 배경 조회 1건
- checkRef, 생성 HTTP 요청과 응답 시각, POST 초기 상태
- 서버 createdAt/completedAt, pending 관측 목록, 첫 최종 관측
- HTTP 요청별 지연, 폴링 실제 간격·GET 횟수, terminal/censored/unknown 구분
- businessDuration = completedAt - createdAt (유효한 서버 시각이 모두 있을 때만)
- firstFinalObserved - firstPendingObserved는 관측·발견 시간으로 따로 표시
- 업무 처리시간, HTTP 요청 지연, 결과를 발견한 시간은 서로 대체하지 않는다
- completedAt이 없으면 정확한 완료시간을 임의로 채우지 않는다. 마지막 pending과 첫 최종 응답의 관측 구간 및 네트워크 지연 한계를 표시한다

사번/checkId 원문은 외부 호출과 선택에 필요한 동안만 메모리에서 사용한다. 분석·내보내기는 별칭을 사용하고 이름·생년월일·범죄/신용 등 상세 결과는 포함하지 않는다. Lab은 범죄/신용 결과를 판정하는 화면이 아니다.

핵심 버전은 측정 DB 테이블 없이 현재 요청과 브라우저 메모리로 처리하고, 필요하면 사용자가 비식별 자료를 내려받는다. 새로고침 후 Lab 결과 보존은 보장하지 않는다. 업무의 background_checks 저장 정책과 구분한다.

## 4. 통계 계산

- 시작된 외부 호출을 전체 n으로 삼고, 예정만 되었거나 실행 전 취소된 호출은 미실행으로 분리한다.
- HTTP 상태 비율 = 관측한 해당 코드 건수 / 전체 시도 n. HTTP 코드 미관측도 표시한다. 헤더 수신 후 본문 timeout처럼 코드와 전송 오류가 함께 관측된 경우를 보존한다. HTTP 코드 분류와 전송 완료/오류 분류는 서로 다른 축이며 오류를 이중 발생 건수로 합산하지 않는다. 완료 응답들만의 비율도 표시하면 분모 n_http를 따로 명시한다.
- 응답 지연 통계는 응답 본문 수신을 완료한 HTTP 응답 n_http를 기준으로 계산한다. 4xx/5xx도 포함하되 2xx와 오류 응답은 보조 집계로 분리한다. JSON 해석 시간과 Lab 큐 대기는 HTTP 시간에서 제외한다.
- 타임아웃/네트워크 오류의 종료까지 걸린 시간은 별도 집계한다. HTTP 응답시간 표본에 섞거나 제한시간을 실제 완료시간으로 넣지 않는다.
- 백분위수는 오름차순 x[0..n-1], h=(n-1)p, Q(p)=x[floor(h)] + (h-floor(h))*(x[ceil(h)]-x[floor(h)])의 선형 보간(Type 7)으로 통일한다. p50은 중앙값, p95/p99와 최댓값을 함께 보여준다.
- n=0이면 계산하지 않는다. n=1이면 같은 값을 표시하되 분포를 알 수 없는 한계를 설명한다.
- p99에 실제 사용된 상위 두 순서 통계량과 n을 표시한다. 표본 개수만으로 유효/무효를 단정하지 않고 상위 몇 개 관측에 민감하고 장기 SLO를 보장하지 않음을 설명한다.
- 평균-중앙값(ms), p95/p50 및 최대/p50(분모 0은 계산 제외), 시간순 지연과 상태를 표시한다.
- Q3+1.5*IQR 초과는 참고 이상치로 표시하되 삭제하지 않는다. 분포가 이산적이거나 IQR=0일 때 해석에 주의한다.
- 업무 완료 n과 미완료/중단 n을 같이 표시한다. 완료 건만의 평균을 전체 처리시간 분포로 설명하지 않는다.
- 동시성별 환경·Endpoint·대상·키 스코프·timeout·n·연결 모드가 같은 실행 안에서 비교되도록 한다. 다른 실행 간 키 일치 여부는 저장하지 않으므로 동일 인증 조건이라고 자동 인증하지 않는다.
- raw 결과는 특정 실험 조건의 관측이다. 응답들 사이의 독립성이나 시간에 따른 안정성을 검증 없이 가정하지 않는다.

## 5. 후보값 비교

### Timeout
- 측정 완료 요청 중 elapsed > 후보 T인 건수 / 완료 HTTP n을 표시한다.
- 타임아웃 표본은 중도 절단이다. 기존 측정 제한시간이 후보 T보다 짧았다면 그 요청은 초과 여부 미확정으로 분류한다.
- 후보보다 긴 제한시간까지 기다렸는데 응답이 안 온 요청은 해당 후보 초과의 근거로 표시한다. 네트워크 실패/조기 취소는 별도다.
- 확정 초과·확정 비초과·미확정 n을 표시한다. 단순 비율을 미래 실제 timeout 발생률로 부르지 않는다.
- p95/p99 비교 및 짧은 T의 조기 중단/재호출 부담, 긴 T의 리소스 점유·사용자 대기 증가를 설명한다.
- 전체 HTTP 지연으로 connect-timeout을 산출하지 않는다. 연결 단계 계측이 없으면 추가 실험 필요. 재사용 연결에서는 connect-timeout이 적용되지 않을 수 있다.

### Retry
- 상태코드 목록, 추가 재시도 R, fixed/exponential, 초기 b, backoff 상한 cap, Retry-After 적용, 전체 재시도 deadline을 비교한다.
- 총 시도 수 = R+1. fixed의 j번째 대기=b, exponential은 min(cap,b*2^(j-1)).
- 유효한 Retry-After가 있으면 max(backoff,안내 대기) 적용을 후보로 비교한다. header/body 불일치는 표시한다. 헤더 우선·body fallback은 개발자가 확인할 후보 정책이다.
- 헤더는 delay-seconds와 HTTP-date를 파싱하고 숫자 body.retryAfter는 초 단위로 분리한다. 무효/과거 값과 시계 차이 해석을 기록한다.
- 1회 요청 전체 상한 A와 대기값 d[j]를 정한 시나리오에서 W=(R+1)*A+sum(d[j]). 연결+read 설정만으로 A가 보장된다고 가정하지 않는다.
- 미래 Retry-After 상한이 없고 전체 deadline도 없다면 유한한 최악 대기시간은 계산 불가. deadline을 넘는 대기가 필요하면 안내 시간을 줄여 호출하는 대신 중단한다.
- Retry 성공률은 실제 재시도 workflow가 있을 때만 해당 n으로 집계한다. 일반 5xx 비율로 성공 확률을 만들어내지 않는다.
- POST에 GET 재시도 정책을 그대로 적용하지 않는다. 멱등성 보장 없는 POST 결과 불명은 자동 재전송 금지 후보로 다룬다.

### Polling
- 주기 Δ, 최대 추가 GET 수 K, 전체 관찰 한도 D를 비교한다. POST에서 즉시 완료된 건은 polling이 필요 없는 별도 그룹이다.
- 서버 업무 완료시간 d가 유효한 표본에만, 생성 시점을 0으로 둔 이상적인 관찰 시각 Δ,2Δ,... 모델을 적용한다.
- 필요 GET 수=ceil(d/Δ), 발견 지연=ceil(d/Δ)*Δ-d. K나 D를 넘으면 후보 예산 내 미발견으로 표시한다.
- 이 수치는 실제 요청 예측이 아니다. POST 수신 지연·HTTP 지연·실패·Retry-After·순차 호출 밀림은 포함되지 않은 수학 모델이라고 표시한다.
- 정확한 d가 없는 표본은 모델에서 제외하고 n을 밝힌다. 관측 구간만 있는 경우 단일 완료시간을 만들지 않는다.
- 최종 clear/flagged, 사용자 취소, 총 호출 수/기간 초과, 잘못된 키, 대상 없음, 유효하지 않은 응답에 대한 중단 후보를 기록한다. 오류=flagged로 처리하지 않는다.

### 동시성
- 각 c의 n, p50/p95/p99, 오류 n/비율, 해당 단계 전체 경과시간을 기준으로 한 완료 처리량을 비교한다.
- 순차→병렬 변화가 실제 개선인지, 큐 대기 또는 오류 응답 증가 때문인지 같이 표시한다.
- 관측하지 않은 동시성으로 외삽하지 않는다. 제안 상한 3은 실험 보호 장치이며 안전한 운영 상한이라는 결론이 아니다.

## 6. Client 및 실행 구조

기존 외부 Client가 없으므로 com.bit.portal.backgroundcheck.client에 BackgroundCheckClient를 만들고 업무 Service와 LabRunner가 재사용하는 안을 제시한다. Client에는 명세의 create/list/detail 3개 호출과 공통 HTTP/예외 계측만 둔다. 재시도·폴링의 의사결정은 상위 서비스/실험 러너가 담당한다.

권장 초기 구성은 Spring RestClient + JdkClientHttpRequestFactory(Java 21 HttpClient)다. 기존 Spring Web 의존성을 재사용한다. 4xx/5xx는 정규 응답으로 계측하고, Client 예외를 민감한 응답 전문과 함께 로그하지 않는다. 리다이렉트는 따르지 않고 Base URL과 Endpoint를 서버에서 고정한다. 키는 매 호출의 헤더에만 주입하며 공유 Client의 필드/defaultHeader에 저장하지 않는다.

connect-timeout은 HttpClient 연결 설정에, 요청 timeout은 JDK Factory의 setReadTimeout에 연결한다. 이름만으로 socket read idle timeout으로 해석하지 않고 실제 timeout 범위를 로컬 지연 서버로 테스트한 뒤 명시한다. 전체 본문 수신까지의 deadline이 추가로 필요하면 구현하고 테스트한다. 운영 설정명은 그 결과에 맞춘다.

실행 요청은 /api/admin/reliability-lab/experiments의 CSRF 보호 POST로 받는다. 키는 입력 요청 범위에서만 사용하고 DB/세션/localStorage/URL/다운로드/진행 이벤트에 넣지 않는다. UI도 전송 후 키 입력을 지운다. 브라우저 메모리와 HTTPS 요청에 순간적으로 존재하는 사실은 숨기지 않는다. 업무용 키는 기존 지시대로 서버 Railway 환경변수를 사용한다.

진행 보고는 한 요청의 NDJSON 스트림(start/progress/result)으로 보내는 안이다. 키를 보유한 영구 백그라운드 job이나 별도 key 저장소를 만들지 않는다. 프록시 버퍼링이 있으면 실시간 진행 표시가 늦을 수 있으며 운영 프록시를 임의 변경하지 않는다.

제안 보호 장치(사용자 확인 전):
- 첫 GET 실행 n=5, c=1로 시작; 한 실행 총 외부 호출 <=20, 전역 in-flight <=3, 실행 <=120초
- n/c/전체기간/측정용 timeout의 양수 검증과 서버 상한, 동시에 허용하는 Lab 실행 수 제한. 전체 deadline으로 헤더 이후 본문 정체도 제한하고 로컬 테스트한다.
- POST는 별도 활성화 및 승인된 시험 계획에서만, 최대 2회 순차. baseline/list/reconcile/poll/retry도 20회 예산에 포함
- 401, 잘못된 설정, 호출 예산/시간 초과 즉시 중단. 404/스키마 오류는 목적에 따라 관측 후 해당 workflow 중단
- 503/429는 다음 부하 증가 중단, Retry-After를 존중할 수 없으면 실행 종료
- 취소 시 신규 요청 발송을 멈춘다. 이미 전송한 POST를 되돌리거나 외부에서 취소할 수 있다고 표시하지 않음
- 실행 결과 Cache-Control: no-store; HTTP/DTO logging에 키·개인정보가 들어가지 않는지 테스트
- 기존 portal-dev opt-in 및 UI 플래그를 유지하고 별도 Lab 활성 플래그는 기본 off. Dockerfile·기본 application.yml·운영 DB·세션 설정 유지
- in-flight 제한은 우선 단일 서버 프로세스 내 Lab/업무 공통 제한이다. 다중 인스턴스에서는 합산 보장이 없으므로 실험 시 단일 인스턴스 확인 또는 분산 제한이 추가로 필요하다.
- 실제 외부 호출은 승인된 조건으로만 수행한다. 로컬 모의 테스트는 실측 보고서와 구분한다

## 7. 판단·설정·문서 연계

DecisionDraft 필드: settingName/value/unit, runIds, cohortFilter, sampleCounts, statisticSnapshot, 이유, 반대 장점, 부적절한 상황, 추가실험 필요 여부, DRAFT 상태. 운영 적용 승인 여부와 분리한다.

표시된 후보를 선택하면 아래 이름 구조의 YAML 초안을 출력한다. 값은 실측 후 개발자가 선택해야 하므로 미확정 상태에 실제 숫자를 넣지 않는다.

```yaml
# 초안 — 그대로 실행 설정에 복사하지 않음
background-check:
  client:
    connect-timeout: "<개발자 선택 Duration>"
    request-timeout: "<Client timeout 검증 후 선택 Duration>"
  retry:
    statuses: [] # 개발자 선택 목록
    max-retries: "<추가 시도 수>"
    backoff: "<FIXED 또는 EXPONENTIAL>"
    initial-delay: "<Duration>"
    max-delay: "<Duration>"
    respect-retry-after: "<개발자 선택>"
    max-duration: "<Duration>"
  polling:
    interval: "<Duration>"
    max-attempts: "<추가 GET 수>"
    max-duration: "<Duration>"
  max-in-flight: "<개발자 선택>"
```

MEASUREMENTS Markdown에는 실행 조건/중단 상태, n 및 분모, Type 7 계산법, HTTP 분포/오류, HTTP 지연과 업무 처리시간, 동시성 비교의 한계, POST 결과, 명세 대조, 후보 비교, 선택 근거를 넣는다. 원본 비식별 파일과 실행 ID를 연결한다.

Swagger 대조 상태는 일치/불일치/관찰되지 않음/명세 미기재로 구분한다. 명세에 있다는 이유만으로 실제 성공으로 체크하지 않는다. 최초 핵심 버전은 코드·JSON 형식·필드 유형·enum·ID 일치·시각·Retry-After 등 알려진 항목을 검증하며 전체 OpenAPI Validator 도입은 후속이다.

화면의 다운로드는 저장소 docs 파일을 자동으로 덮어쓰지 않는다. 사용자 확인 후 실제 설정 클래스·Client·업무 코드에 연결하고, 해당 변경을 테스트한 뒤 MEASUREMENTS/DECISIONS를 맞춘다. 기존 확정한 정책은 유지하고 이번 새 운영값만 TODO로 남긴다.

## 8. 6시간 범위와 검증

이미 구현한 로그인·권한·직원 CRUD 기반을 재사용한다. 실제 누적 작업시간/잔여시간은 아직 집계하지 않았으므로 아래는 새 작업의 예산안이지 남은 시간이 있다는 보장이 아니다.

Lab 핵심 개발 목표 약 120분(추정):
- 공통 Client·계측 20분
- 호출 예산·순차/동시 GET·POST 관찰·진행 25분
- 통계와 후보 계산 25분
- 단일 PrimeVue 화면 25분
- 내보내기 및 핵심 테스트 25분

6시간 전체에서는 Lab에 약 2시간을 배정하고 업무 Background Check 연동·배포·제출물 검증 시간을 남기는 것이 우선이다. 부족하면 고급 분석을 줄이며 업무의 관리자 결과 확인 기능을 포기하지 않는다. 실측 스크립트 실행 대기 시간 제외는 첨부 과제 규칙을 따른다.

필수: 순차/소규모 동시 GET, 제한된 POST 비교/완료 관찰, 원본 n/상태/오류, Type 7 통계, Timeout 비교, Retry 대기시간 계산(성공률 TODO), Polling 단순 모델, 판단 초안, JSON/CSV/Markdown/YAML 출력, 관리자 통제·key 미저장·서버 상한.

후속: 실제 재시도 자동 실험, 연결 단계 계측, 동시성 단계 역순 반복, 신뢰구간/부트스트랩, jitter 실험, 장기 저장/모니터링, 고급 그래프, 전체 OpenAPI Validator.

핵심 검증 계획:
- ADMIN/USER/익명 접근, CSRF, 퇴사 세션, 전체 호출 예산·전역 동시성·취소
- 로컬 지연 HTTP 서버의 connect/request/body timeout 구분, 401/404/500/503·Retry-After 형식·깨진 JSON
- POST 결과 불명에서 추가 POST가 나가지 않음, list/poll/retry가 예산에 포함됨
- n=0/1/짝수와 알려진 입력의 quantile, 타임아웃 중도절단, 통계별 분모, Retry 대기 합, Polling 경계값
- 키·인적사항·상세 결과가 DTO 문자열/로그/stream/다운로드에 없음
- 브라우저 단일 화면·실행 진행·후보 변경 시 네트워크 미발송·다운로드 내용
- 모의 테스트 입력은 테스트 파일에만 두고 실측 보고서의 값으로 사용하지 않음

## 기술 근거

구현 조정: 본문 수신이 멈춘 상황까지 전체 요청 deadline으로 제한하기 위해 Spring RestClient/JDK Factory 대신 Java 21 HttpClient를 직접 사용한다. 같은 독립 Client를 Lab과 업무 이력·상세 GET이 공유한다. 연결/전체 요청 설정명은 정수 ms 단위의 connect-timeout-ms / request-timeout-ms로 확정했다. Retry/Polling 운영 값 및 업무 POST 정책은 TODO 유지한다.

- [Java 21 HttpClient 연결 timeout과 연결 재사용](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.Builder.html)
- [Spring 6.2.7 JDK Client Factory timeout 매핑](https://docs.spring.io/spring-framework/docs/6.2.7/javadoc-api/org/springframework/http/client/JdkClientHttpRequestFactory.html)
- [Type 7 백분위수 정의](https://www.stat.ethz.ch/R-manual/R-devel/library/stats/html/quantile.html)
- [HTTP Retry-After 형식](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)
- 과제 원본 InterviewTasks.txt와 swagger.yaml v1.1.1. 명세는 실제 관측의 대체가 아니다.
