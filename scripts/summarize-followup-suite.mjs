// Offline audit only: reads exported observations; makes no HTTP requests.
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { dirname, resolve } from 'node:path'
import assert from 'node:assert/strict'
import { statistics, timeoutComparison } from '../frontend/src/lab/analysis.js'
import { retryEvidence } from '../frontend/src/lab/followup.js'

const manifestPath = resolve(process.argv[2] || 'docs/measurements/raw/followup-suite-20261012-d19050c7.json')
const folder = dirname(manifestPath)
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
const forbidden = /^(candidateKey|x-candidate-key|password|employeeId|checkId|firstName|lastName|dateOfBirth|responseBody)$/i
function scan(value) {
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    assert(!forbidden.test(key), `Private field: ${key}`)
    scan(child)
  }
}
scan(manifest)
const seen = new Set(), runs = [], hashes = []
for (const entry of manifest.runs) {
  assert(!seen.has(entry.runId), 'Duplicate run')
  seen.add(entry.runId)
  const raw = await readFile(resolve(folder, `lab-${entry.runId}.json`), 'utf8')
  const result = JSON.parse(raw)
  const campaignRaw = await readFile(resolve(folder, `campaign-${entry.campaignId}.json`), 'utf8')
  const campaign = JSON.parse(campaignRaw)
  scan(result); scan(campaign)
  assert.equal(result.run.runId, entry.runId)
  assert.equal(result.run.source, 'EXTERNAL')
  assert.equal(result.run.state, entry.state)
  assert.equal(result.attempts.length, entry.actualHttp)
  assert.equal(result.run.started, result.attempts.length)
  const saved = campaign.runs.find(row => row.runId === entry.runId)
  assert(saved && !saved.stale && saved.recordingStatus !== 'RUNNING', 'Unresolved recording')
  assert.deepEqual(saved.result, result, 'Campaign/run mismatch')
  hashes.push({ runId: entry.runId, campaignId: entry.campaignId,
    runSha256: createHash('sha256').update(raw).digest('hex'),
    campaignSha256: createHash('sha256').update(campaignRaw).digest('hex') })
  runs.push(result)
}
const attempts = runs.flatMap(r => r.attempts)
const posts = attempts.filter(a => a.endpoint === 'CREATE')
assert.equal(attempts.length, manifest.actualHttp)
assert.equal(posts.length, manifest.actualPost)
assert(attempts.length <= manifest.approvedHttpCeiling && posts.length <= manifest.approvedPostCeiling)
const distribution = field => [...new Set(attempts.map(a => a[field] ?? 'UNOBSERVED'))].map(value => ({
  value, count: attempts.filter(a => (a[field] ?? 'UNOBSERVED') === value).length, denominator: attempts.length
}))
const cohorts = [...new Set(attempts.map(a => `${a.endpoint}/${a.outcome}/${a.httpCode ?? 'UNOBSERVED'}`))].map(key => {
  const rows = attempts.filter(a => `${a.endpoint}/${a.outcome}/${a.httpCode ?? 'UNOBSERVED'}` === key)
  return { key, metric: rows[0].outcome === 'HTTP_RESPONSE' ? 'completed_http_ms' : 'interruption_elapsed_ms',
    latency: statistics(rows.map(a => a.elapsedMs)) }
})
const untouchedCeiling = manifest.omissions.reduce((n, item) => n + item.plannedHttp, 0)
const executedProcedureCeiling = runs.reduce((n, r) => n + r.run.plannedUpperBound, 0)
assert.equal(untouchedCeiling + executedProcedureCeiling, manifest.approvedHttpCeiling, 'Budget coverage mismatch')
const summary = { suiteId: manifest.suiteId, runs: runs.length, actualHttp: attempts.length, actualPost: posts.length,
  completedHttp: attempts.filter(a => a.outcome === 'HTTP_RESPONSE').length,
  statuses: distribution('httpCode'), outcomes: distribution('outcome'), cohorts,
  retries: runs.filter(r => r.logicalOperations.length).map(r => ({ runId: r.run.runId, ...retryEvidence(r) })),
  // Same endpoint, outcome and code; cohorts from other experiments are never silently merged.
  listSuccessTimeoutCandidates: [10000, 30000, 60000].map(candidate => ({ candidate,
    ...timeoutComparison(attempts.filter(a => a.endpoint === 'LIST' &&
      (a.httpCode === 200 && a.outcome === 'HTTP_RESPONSE' || a.outcome === 'REQUEST_TIMEOUT')), candidate) })),
  workflowN: runs.reduce((n, r) => n + r.workflows.length, 0),
  terminalWorkflowN: runs.reduce((n, r) => n + r.workflows.filter(w => w.state === 'TERMINAL').length, 0),
  exactBusinessTimeN: runs.reduce((n, r) => n + r.workflows.filter(w => Number.isFinite(w.businessDurationMs)).length, 0),
  executedProcedureCeiling, untouchedCeiling, unusedInExecutedProcedures: executedProcedureCeiling - attempts.length,
  totalUnusedCeiling: manifest.approvedHttpCeiling - attempts.length, hashes,
  limitation: 'Descriptive pilot with error-dependent stopping; interruption durations are not completed latency; no causal retry or polling effect estimated' }
const output = manifestPath.replace(/\.json$/, '-summary.json')
assert.notEqual(output, manifestPath)
await writeFile(output, JSON.stringify(summary, null, 2), 'utf8')
console.log(JSON.stringify(summary, null, 2))
