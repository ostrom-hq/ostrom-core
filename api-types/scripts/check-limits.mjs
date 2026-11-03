// SPDX-License-Identifier: Apache-2.0
// Fails when the contract hides a limit ostrom-server main enforces (second security audit of the contract, 03/11/2026,
// findings F4 and F5): a page size without its maximum, a route the server rate-limits without its 429, or a costly read
// the server throttles without its 429 and `Retry-After`. The lists below are read from ostrom-server main; a server PR
// that limits another route adds it here, in its core PR.
import { readFileSync } from 'node:fs'
import { parse } from 'yaml'

const [contractFile] = process.argv.slice(2)
const contract = parse(readFileSync(contractFile, 'utf8'))
const METHODS = ['get', 'put', 'post', 'delete', 'patch']

// Bounded per caller by ReadThrottle (kernel/http/ReadThrottle.kt), which answers 429 with `Retry-After`.
export const THROTTLED = [
  'getGate', 'listApprovals', 'listAudit', 'listSessions', 'getSession', 'listReceipts', 'getReceipt', 'verifyReceipt',
  'getSpending', 'listActivity',
]

// Limited per address or per person by the identity module's RateLimiter, which answers 429 `rate_limited`.
export const LIMITED = [
  'mobileToken', 'unlockMobileApp', 'startDeviceLogin', 'getDeviceLogin', 'answerDeviceLogin', 'launcherLogin',
  'requestSignInEmail', 'exchangeSignInLink', 'createSignInLink',
]

const resolve = (node) => {
  let current = node
  while (current && current.$ref) {
    current = current.$ref.slice(2).split('/').reduce((at, key) => at[key], contract)
  }
  return current
}

const operations = new Map()
for (const [path, item] of Object.entries(contract.paths)) {
  for (const verb of METHODS.filter((m) => item[m])) {
    const parameters = [...(item.parameters ?? []), ...(item[verb].parameters ?? [])].map(resolve)
    operations.set(item[verb].operationId, { path, verb, operation: item[verb], parameters })
  }
}

const problems = []
for (const [id, { operation, parameters }] of operations) {
  if (operation['x-ostrom-status'] !== 'served') continue
  for (const parameter of parameters) {
    if (parameter.in === 'query' && parameter.name === 'limit' && resolve(parameter.schema)?.maximum === undefined) {
      problems.push(`${id}: the limit parameter has no maximum`)
    }
  }
}

const tooMany = (id) => {
  const entry = operations.get(id)
  if (!entry) {
    problems.push(`${id}: no such operation`)
    return null
  }
  const answer = entry.operation.responses['429']
  if (!answer) problems.push(`${id}: no 429`)
  return answer ? resolve(answer) : null
}
for (const id of LIMITED) tooMany(id)
for (const id of THROTTLED) {
  const answer = tooMany(id)
  if (answer && !answer.headers?.['Retry-After']) problems.push(`${id}: its 429 has no Retry-After`)
}

if (problems.length > 0) {
  console.error(`The contract hides ${problems.length} limit(s) the server enforces:\n  ${problems.join('\n  ')}`)
  process.exit(1)
}
console.log(`Limits: every page size has a maximum, ${LIMITED.length + THROTTLED.length} limited routes declare 429.`)
