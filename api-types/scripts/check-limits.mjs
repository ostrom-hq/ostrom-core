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

// Limited per address, person, organization or automation (the identity module's RateLimiter, the organizations',
// the automations' and the usage ledger's), which answer 429 `rate_limited`.
export const LIMITED = [
  'mobileToken', 'unlockMobileApp', 'startDeviceLogin', 'getDeviceLogin', 'answerDeviceLogin', 'launcherLogin',
  'requestSignInEmail', 'exchangeSignInLink', 'createSignInLink', 'linkSignInProvider', 'createOrg',
  'triggerAutomation', 'recordUsageEvents',
]

// The governance writes a person's API token never applies: each opens a confirmation (202), at most 10 waiting per
// person (429 `confirmation_limit`). The invitations are also limited per organization (`InvitationLimit`).
export const HELD = {
  setGatePolicy: 'ConfirmationLimit', createRule: 'ConfirmationLimit', updateRule: 'ConfirmationLimit',
  deleteRule: 'ConfirmationLimit', bulkUpdateRules: 'ConfirmationLimit', createWorkflow: 'ConfirmationLimit',
  saveWorkflow: 'ConfirmationLimit', deleteWorkflow: 'ConfirmationLimit', setModelPolicy: 'ConfirmationLimit',
  updateOrgMember: 'ConfirmationLimit', removeOrgMember: 'ConfirmationLimit', inviteOrgMembers: 'InvitationLimit',
  createInvitationLink: 'InvitationLimit',
}

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
    if (parameter.in !== 'query' || parameter.name !== 'limit') continue
    const schema = resolve(parameter.schema) ?? {}
    if (schema.maximum === undefined) problems.push(`${id}: the limit parameter has no maximum`)
    if (schema.minimum === undefined) problems.push(`${id}: the limit parameter has no minimum`)
  }
}

// Each limited route answers its 429 with the shared response that says which limit it is.
const tooMany = (id, expected) => {
  const entry = operations.get(id)
  if (!entry || entry.operation['x-ostrom-status'] !== 'served') {
    problems.push(`${id}: no such served operation`)
    return
  }
  const answer = entry.operation.responses['429']
  if (!answer) problems.push(`${id}: no 429`)
  else if (answer.$ref !== `#/components/responses/${expected}`) problems.push(`${id}: its 429 is not ${expected}`)
}
for (const id of LIMITED) tooMany(id, 'TooManyAttempts')
for (const id of THROTTLED) tooMany(id, 'Throttled')
for (const [id, limit] of Object.entries(HELD)) {
  tooMany(id, limit)
  const held = operations.get(id)?.operation.responses['202']
  if (held?.$ref !== '#/components/responses/GovernanceConfirmationPending') {
    problems.push(`${id}: a token's write is held, but no 202 GovernanceConfirmationPending`)
  }
}
// And the other way: a served write that answers 202 GovernanceConfirmationPending is in HELD, so its 429 is checked.
for (const [id, { operation }] of operations) {
  if (operation['x-ostrom-status'] !== 'served' || id in HELD) continue
  if (operation.responses['202']?.$ref === '#/components/responses/GovernanceConfirmationPending') {
    problems.push(`${id}: a token's write is held (202), but it is not in HELD`)
  }
}
if (!resolve(contract.components.responses.Throttled)?.headers?.['Retry-After']) {
  problems.push('Throttled: no Retry-After')
}

if (problems.length > 0) {
  console.error(`The contract hides ${problems.length} limit(s) the server enforces:\n  ${problems.join('\n  ')}`)
  process.exit(1)
}
const limited = LIMITED.length + THROTTLED.length + Object.keys(HELD).length
console.log(`Limits: every page size has its bounds, ${limited} limited routes declare their 429.`)
