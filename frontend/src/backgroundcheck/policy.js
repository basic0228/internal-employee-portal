// Workflow states do not imply a completed business result.
export function findings(status) {
  return status === 'clear' ? '특이사항 없음' : status === 'flagged' ? '특이사항 있음 · 검토 필요' : '특이사항 미확정'
}
export function blocksCreation(row) {
  return ['DISPATCHING', 'UNKNOWN'].includes(row.localState) || row.status === 'pending'
}
export function canAutoPoll(row, now = Date.now()) {
  return row.localState === 'KNOWN' && row.status === 'pending' && !row.pollStopped && Date.parse(row.pollDeadline) > now
}
export function pollDelay(row, interval, now = Date.now()) {
  return Math.max(interval, Date.parse(row.nextPollAt || '') - now || 0)
}
