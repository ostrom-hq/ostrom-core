// SPDX-License-Identifier: Apache-2.0
// Fails when an operation's `x-ostrom-status` disagrees with what ostrom-server main serves, listed in
// api/contract/served-routes.txt (written by scripts/served-routes.py): `served` when a listed route has the
// operation's method and path (path parameters written `{}`; a `+` line is a literal path served through a parameter
// route), `planned` otherwise, and `planned` for a route listed with `!` (served on main in another shape).
import { readFileSync } from 'node:fs'
import { parse } from 'yaml'

const [contractFile, listFile] = process.argv.slice(2)
const contract = parse(readFileSync(contractFile, 'utf8'))
const METHODS = ['get', 'put', 'post', 'delete', 'patch', 'head', 'options', 'trace']

const served = []
const otherShape = []
for (const raw of readFileSync(listFile, 'utf8').split('\n')) {
  const line = raw.trim()
  if (line === '' || line.startsWith('#')) continue
  const different = line.startsWith('!')
  const [method, path] = line.replace(/^[!+]/, '').trim().split(/\s+/)
  ;(different ? otherShape : served).push(`${method} ${path}`)
}

const keyOf = (method, path) => `${method} ${path.replace(/\{[^}]*\}/g, '{}')}`

const problems = []
let servedCount = 0
let plannedCount = 0
for (const [path, item] of Object.entries(contract.paths)) {
  for (const verb of METHODS.filter((m) => item[m])) {
    const key = keyOf(verb.toUpperCase(), path)
    const onMain = served.includes(key) && !otherShape.includes(key)
    const status = item[verb]['x-ostrom-status']
    const expected = onMain ? 'served' : 'planned'
    if (expected === 'served') servedCount += 1
    else plannedCount += 1
    if (status !== expected) {
      problems.push(`${verb.toUpperCase()} ${path} (${item[verb].operationId}): ${status} in the contract, ${expected} on main`)
    }
  }
}
if (problems.length > 0) {
  throw new Error(`x-ostrom-status disagrees with api/contract/served-routes.txt:\n${problems.join('\n')}`)
}
console.log(`x-ostrom-status matches ostrom-server main: ${servedCount} served, ${plannedCount} planned`)
