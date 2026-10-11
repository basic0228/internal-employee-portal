# MEASUREMENTS — Background Check API 실측

> 상태: 측정 전. 아래 수치는 실제 API 호출 후 작성한다. 실행 대기 시간은 과제의 6시간 작업 시간에서 제외할 수 있다.

## 측정 환경
- 측정 일시: TODO
- 측정 위치/환경: TODO
- API Base URL: `https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com`
- 인증: `X-Candidate-Key` 헤더 (키 원문은 문서/로그에 기재하지 않음)
- 측정 도구 및 버전: TODO
- 요청 타임아웃(측정용): TODO
- 샘플 및 동시성 설정: TODO

## 1. GET /background-checks/{checkId} 응답 지연

| 조건 | 표본 수(n) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |
|---|---:|---:|---:|---:|---:|
| 순차 호출 | TODO | TODO | TODO | TODO | TODO |
| 동시 호출 | TODO | TODO | TODO | TODO | TODO |

- 측정 방식(백분위수 계산법 포함): TODO
- 네트워크 오류와 HTTP 오류를 통계에 포함하는 기준: TODO

## 2. HTTP 상태코드 분포

| 실험 조건 | 표본 수(n) | HTTP 코드 | 발생 건수 | 비율(%) |
|---|---:|---|---:|---:|
| TODO | TODO | TODO | TODO | TODO |

- 타임아웃·연결 실패는 HTTP 상태코드가 아니므로 별도 집계: TODO

## 3. 동일 employeeId 반복 POST

- 대상 직원/시험용 employeeId: TODO
- 반복 횟수(n): TODO
- 응답 checkId 비교 및 중복 생성 여부: TODO
- 부작용 및 결론: TODO

## 4. pending → 최종 상태 소요 시간

| 대상 | 표본 수(n) | pending 관측 | 최종 상태 | 소요 시간(초) |
|---|---:|---|---|---:|
| TODO | TODO | TODO | TODO | TODO |

- POST가 즉시 clear/flagged를 반환하는 경우 별도 기록: TODO
- estimatedCompletionSeconds와 실측 비교: TODO

## 5. 동시 요청 증가 실험

| 동시성 | 표본 수(n) | p50(ms) | p95(ms) | p99(ms) | 실패율(%) | 비고 |
|---:|---:|---:|---:|---:|---:|---|
| 1 | TODO | TODO | TODO | TODO | TODO | |
| 2 | TODO | TODO | TODO | TODO | TODO | |
| 3 | TODO | TODO | TODO | TODO | TODO | 임시 서버 상한 |

- 외부 서비스에 과도한 부하를 주지 않도록 소규모로 단계적 진행.

## 6. Swagger 명세와 실제 동작 비교

| 명세 항목 | 명세 내용 | 실제 관찰(n 포함) | 판단 |
|---|---|---|---|
| POST 초기 상태 | pending/clear/flagged 가능 | TODO | TODO |
| GET 완료 상태 | clear/flagged | TODO | TODO |
| 503 | Retry-After 헤더 및 retryAfter 필드 | TODO | TODO |
| 후보자 키 분리 | 키별 기록 분리 | TODO | TODO |
| 기타 미기재 동작 | — | TODO | TODO |

- 명세에 있지만 관찰하지 못한 동작: TODO
- 명세에 없지만 발견한 동작: TODO
- 명세 값이 실제에 부적절한 부분: TODO

## 7. 실측 기반 운영 정책

| 정책 | 결정 값 | 근거가 된 실측 결과 |
|---|---|---|
| 연결/응답 타임아웃 | TODO | TODO |
| GET 재시도 횟수 | TODO | TODO |
| 재시도 간격 | TODO | TODO |
| pending 폴링 주기 | TODO | TODO |
| 폴링/재시도 중단 조건 | TODO | TODO |
| POST 타임아웃 시 중복 생성 방지 | TODO | TODO |

> 측정 전 임의의 수치를 최종 정책으로 확정하지 않는다. 모든 수치에 표본 수(n)를 적는다.

## API Reliability Lab 연계 — 측정 전 TODO

API Reliability Lab의 핵심 도구를 2026-10-11 로컬에 구현했다. 외부 API는 호출하지 않았으므로 실제 수치는 모두 TODO다. LOCAL_MOCK 검증은 이 보고서의 외부 실측으로 사용하지 않는다. 실측 후 다음 항목을 기존 결과와 함께 내보낸다.

- runId·조건·코드/명세 버전·진행/중단 상태·시도/응답/미완료 n
- 백분위수 계산법(Type 7 구현), 통계별 분모, 타임아웃 표본의 중도절단 한계
- HTTP 지연과 업무 처리시간·발견 시간 구분
- Timeout/Retry/Polling/동시성 후보 비교 및 모델의 가정
- 선택값과 연결된 실행 ID·n·통계·이유·반대 장점·부적절한 상황
- 실제 재시도 실험이 없으면 Retry 성공률은 추가 실험 필요

설계 및 임시 보호 상한은 측정 결과가 아니다. 현재 운영 정책은 확정하지 않는다.

실행 준비·비활성 플래그·검증 범위는 [구현 안내](API_RELIABILITY_LAB_IMPLEMENTATION.md)를 참조한다. Markdown 다운로드를 검토해 승인된 EXTERNAL 실행만 이 문서에 반영한다. 화면 선택 Endpoint와 전체 Endpoint별 요약을 함께 기록한다. 기존 기록을 자동 삭제하거나 덮어쓰지 않는다.
