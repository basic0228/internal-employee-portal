import { test } from 'node:test'
import assert from 'node:assert/strict'
import { defaultCases, nextRequest, caseProgress, canContinue } from './campaign.js'
test('eleven cases count total HTTP requests, not POST workflows', () => {
  const cases = defaultCases()
  assert.equal(cases.length, 11)
  assert.equal(cases.reduce((n, c) => n + c.goal, 0), 1100)
  assert.equal(cases.filter(c => c.type === 'GET_FIXED').length, 9)
  assert.equal(cases.filter(c => c.type === 'REPEAT_POST')[0].concurrency, 1)
})
test('polling POST and GET share the same remaining budget', () => {
  const c = defaultCases().find(c => c.type === 'CREATE_POLL')
  for (let remaining = 1; remaining <= 100; remaining++) {
    const r = nextRequest(c, remaining, {})
    assert.equal(r.callBudget, 1 + r.maxPolls)
    assert.ok(r.callBudget <= remaining)
  }
  assert.equal(nextRequest(c, 1, {}).maxPolls, 0)
})
test('duplicate protocol uses four HTTP calls; clipped tails never exceed the budget', () => {
  const c = defaultCases().find(c => c.type === 'REPEAT_POST')
  assert.equal(nextRequest(c, 100, {}).callBudget, 4)
  assert.equal(nextRequest(c, 3, {}).callBudget, 3)
})
test('only actual saved observations count and unresolved runs block resume', () => {
  const entry = { caseId: 'case-1', conditions: { goal: 100 } }
  const progress = caseProgress(entry, [{ caseId: 'case-1', recordingStatus: 'RUNNING', result: { attempts: [{}, {}], run: { plannedUpperBound: 6 } } }])
  assert.equal(progress.observed, 2); assert.equal(progress.remaining, 98); assert.equal(progress.unresolved, true)
})
test('timeouts, overload and uncertain POST do not trigger another automatic unit', () => {
  const good = { run: { state: 'COMPLETED' }, attempts: [{ outcome: 'HTTP_RESPONSE', httpCode: 200, issues: [] }] }
  assert.equal(canContinue(good), true)
  assert.equal(canContinue({ ...good, postComparison: { result: 'UNKNOWN_NO_REPOST' } }), false)
  assert.equal(canContinue({ ...good, attempts: [{ outcome: 'REQUEST_TIMEOUT' }] }), false)
  assert.equal(canContinue({ ...good, attempts: [{ outcome: 'HTTP_RESPONSE', httpCode: 503 }] }), false)
})
