// Offline only: consumes a saved campaign; never connects to the API or database.
import fs from 'node:fs/promises'
import { analyzeCampaign } from './campaign-evidence.mjs'
const input = process.argv[2] || new URL('../docs/measurements/raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json', import.meta.url)
const bundle = JSON.parse(await fs.readFile(input, 'utf8'))
const evidence = analyzeCampaign(bundle)
console.log(JSON.stringify({campaignId:evidence.campaignId,counts:evidence.counts,cases:evidence.cases,
  cohorts:evidence.cohorts,retryModels:evidence.retryModels,retryWithoutHint:evidence.retryWithoutHint,
  workflows:evidence.workflows,duplicates:evidence.duplicates},null,2))
