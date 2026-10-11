import { summarize, statistics, display, timeoutComparison, retryComparison, pollingComparison } from './analysis.js'

export function configuration(decisions) {
  const settings = new Map(decisions.map(d => [d.setting, d.value]))
  const value = key => settings.has(key) ? JSON.stringify(settings.get(key)) : '"TODO"'
  return `# DRAFT — 사용자 확인 전 적용 금지. LOCAL_MOCK은 외부 실측 근거가 아님.
# 현재 연결 가능한 설정: client.connect-timeout-ms / client.request-timeout-ms.
# retry / polling / max-in-flight는 후보 출력이며 실행 코드 바인딩은 후속 작업.
background-check:
  client:
    connect-timeout-ms: ${value('connect-timeout-ms')}
    request-timeout-ms: ${value('request-timeout-ms')}
  retry:
    statuses: ${value('retry-statuses')}
    max-retries: ${value('max-retries')}
    backoff: ${value('retry-backoff')}
    initial-delay-ms: ${value('retry-initial-delay-ms')}
    max-delay-ms: ${value('retry-max-delay-ms')}
    respect-retry-after: ${value('respect-retry-after')}
    max-duration-ms: ${value('retry-max-duration-ms')}
  polling:
    interval-ms: ${value('polling-interval-ms')}
    max-attempts: ${value('polling-max-attempts')}
    max-duration-ms: ${value('polling-max-duration-ms')}
  max-in-flight: ${value('max-in-flight')}
`
}
export function csv(result) {
  const fields = ['sequence','endpoint','concurrency','outcome','httpCode','elapsedMs','queueWaitMs','startedAt','endedAt','httpVersion','checkRef','status','issues']
  const cell = v => {
    const s = Array.isArray(v) ? v.join('|') : String(v ?? '')
    return '"' + (/^[=+\-@]/.test(s) ? "'" : '') + s.replaceAll('"','""') + '"'
  }
  return ['# source=' + result.run.source + '; runId=' + result.run.runId, fields.join(','),
    ...result.attempts.map(a => fields.map(f => cell(a[f])).join(','))].join('\n')
}
export function markdown(result, decisions, candidates, cohort = 'ALL') {
  const selected = result.attempts.filter(a => cohort === 'ALL' || a.endpoint === cohort)
  const s = summarize(selected, result.phases)
  const t = timeoutComparison(selected, candidates.timeoutMs)
  const retry = retryComparison({ ...candidates.retry, statuses: candidates.retryStatuses.split(',').map(s => Number(s.trim())).filter(n => n >= 100 && n <= 599) }, selected)
  const poll = pollingComparison(result.workflows || [], candidates.pollIntervalMs, candidates.pollMax, candidates.pollDurationMs)
  const endpoints = [...new Set(result.attempts.map(a => a.endpoint))].map(endpoint => ({ endpoint,
    ...statistics(result.attempts.filter(a => a.endpoint === endpoint && a.outcome === 'HTTP_RESPONSE').map(a => a.elapsedMs)) }))
  const safe = v => String(v ?? '').replaceAll('|','／').replaceAll('\n',' ')
  return `# API Reliability Lab — ${result.run.source === 'LOCAL_MOCK' ? '로컬 모의 검증 (외부 실측으로 제출 금지)' : '외부 API 실측'}
- 실행 ID: ${result.run.runId}
- 출처: ${result.run.source}
- 실행 상태/중단: ${result.run.state} / ${result.run.reason || '없음'}
- 조건: ${JSON.stringify(result.run.conditions)}
- 환경/버전: ${result.run.javaVersion} / ${result.run.client} / ${result.run.revision}
- HTTP 통계 대상: ${cohort}; 대상 시도 n=${s.n}, 완료 HTTP n=${s.http.n}, 전송 중단 n=${s.abort.n}; 실행 전체 시도 n=${result.attempts.length}
- 아래 전체 요약은 Endpoint가 혼합될 수 있음. Endpoint별 통계와 함께 해석.
- 백분위수: Type 7, h=(n-1)p 선형 보간. 상위 표본 민감성과 중도절단에 주의.

| n_http | 평균 ms | p50 ms | p95 ms | p99 ms | 최대 ms | 평균-중앙값 ms |
|---:|---:|---:|---:|---:|---:|---:|
| ${s.http.n} | ${display(s.http.mean)} | ${display(s.http.p50)} | ${display(s.http.p95)} | ${display(s.http.p99)} | ${display(s.http.max)} | ${display(s.http.meanMedianGap)} |

## Endpoint별 지연
| Endpoint | n_http | 평균 ms | p50 ms | p95 ms | p99 ms | 최대 ms |
|---|---:|---:|---:|---:|---:|---:|
${endpoints.map(x => `| ${x.endpoint} | ${x.n} | ${display(x.mean)} | ${display(x.p50)} | ${display(x.p95)} | ${display(x.p99)} | ${display(x.max)} |`).join('\n')}

## HTTP 상태 분포 (전체 시도 분모)
| 코드 | 건수 | n | 비율 % |
|---|---:|---:|---:|
${s.statuses.map(x => `| ${x.value} | ${x.count} | ${x.n} | ${display(x.ratio)} |`).join('\n')}

## 전송 결과 (HTTP 코드와 별도 축)
${s.outcomes.map(x => `- ${x.value}: ${x.count}/${x.n} (${display(x.ratio)}%)`).join('\n')}

## 동시성
${s.concurrency.map(x => `- c=${x.concurrency}, n=${x.n}, 지연 n=${x.latencyN}, p95=${display(x.p95)}ms, 오류=${x.failed}/${x.n}, 처리량=${display(x.throughput)}/s`).join('\n')}
동일 실행의 조건 비교이며 단계 순서·시간 변화 영향과 운영 상한의 미검증에 주의.

## 업무 처리시간 / 관측 시간
전체 업무 관측 n=${(result.workflows || []).length}. 각 행은 1건의 관측이며 같은 checkId 반복 GET은 별도 업무 표본으로 늘리지 않음.
${(result.workflows || []).map(w => `- ${w.checkRef} (n=1): ${w.state}, 업무=${w.businessDurationMs ?? '추가 실험 필요'}ms, pending→발견=${w.observedPendingToFinalMs ?? '추가 실험 필요'}ms`).join('\n') || '추가 실험 필요'}
업무 시각이 없는 건을 HTTP 지연이나 첫 발견 시각으로 대체하지 않음.

## 반복 POST
${JSON.stringify(result.postComparison || {})}

## 명세 대조 (실행 전체 n=${result.attempts.length})
${result.attempts.map(a => `- 요청 ${a.sequence}: ${a.issues?.length ? a.issues.join(', ') : a.outcome === 'HTTP_RESPONSE' ? '검사한 필드에서 차이 미관측' : '전송 중단으로 응답 검증 불가'}`).join('\n')}
검사 항목은 HTTP/JSON/필드/enum/ID/시각. 필드 생략 검사는 앱 필요 조건이며 명세의 required 선언과 구분. 관찰하지 못한 명세 동작은 TODO.

## 후보 비교 (운영 결정 아님)
- Timeout ${candidates.timeoutMs}ms: 완료 HTTP 초과 ${t.httpExceeds}/${t.httpN}; 전체 확정 초과 ${t.exceeds}, 비초과 ${t.within}, 미확정 ${t.unknown}, n=${t.n}.
- Retry 대기 모델: ${JSON.stringify(retry)}. 성공률: 추가 실험 필요.
- Polling 모델 n=${poll.n}, 제외=${poll.excluded}, 발견 n=${poll.delay.n}, 평균 발견지연=${display(poll.delay.mean)}ms. HTTP 지연·실패를 생략한 모델.

## 개발자 판단 초안 — 사용자 확인 전 TODO
${decisions.map(d => `- 설정 ${safe(d.setting)}=${safe(JSON.stringify(d.value))}; run=${d.runId}; 출처=${d.source}; 대상=${d.cohort}; 표본=${JSON.stringify(d.sampleCounts)}; 근거=${safe(JSON.stringify(d.statistics))}\n  이유: ${safe(d.reason)}\n  반대 장점: ${safe(d.alternative)}\n  부적절한 상황: ${safe(d.failureCase)}`).join('\n') || 'TODO'}
`
}
export function download(name, content, type = 'text/plain') {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const link = document.createElement('a')
  link.href = url; link.download = name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
