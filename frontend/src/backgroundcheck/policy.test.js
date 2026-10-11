import test from 'node:test'
import assert from 'node:assert/strict'
import { findings, blocksCreation, canAutoPoll, pollDelay } from './policy.js'

test('unknown creation and pending observation never become a negative finding', () => {
  assert.equal(findings(undefined), '특이사항 미확정')
  assert.equal(findings('pending'), '특이사항 미확정')
  assert.equal(blocksCreation({ localState: 'UNKNOWN' }), true)
  assert.equal(blocksCreation({ localState: 'KNOWN', status: 'pending', pollStopped: true }), true)
  assert.equal(blocksCreation({ localState: 'KNOWN', status: 'clear' }), false)
})
test('polling respects stop and deadline and never shortens the server waiting time', () => {
  const row = { localState: 'KNOWN', status: 'pending', pollStopped: false, pollDeadline: '2026-10-12T00:02:00Z', nextPollAt: '2026-10-12T00:00:30Z' }
  const now = Date.parse('2026-10-12T00:00:00Z')
  assert.equal(canAutoPoll(row, now), true)
  assert.equal(pollDelay(row, 5000, now), 30000)
  assert.equal(canAutoPoll({ ...row, pollStopped: true }, now), false)
  assert.equal(canAutoPoll(row, Date.parse(row.pollDeadline)), false)
})
