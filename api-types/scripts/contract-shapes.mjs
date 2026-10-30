// Writes the JSON shape of every schema of openapi.yaml that has a Kotlin model (all but `x-ostrom-kotlin: pending`).
// The Kotlin test ContractTest compares this file with the serial descriptors of dev.ostrom.core.api.models;
// CI regenerates it and fails when the committed copy differs.
import { readFileSync, writeFileSync } from 'node:fs'
import { parse } from 'yaml'

const [input, output] = process.argv.slice(2)
const schemas = parse(readFileSync(input, 'utf8')).components.schemas
const PREFIX = '#/components/schemas/'

function isNull(schema) {
  return schema.type === 'null'
}

// returns [type, nullable]
function shape(schema) {
  if (schema.oneOf) {
    const others = schema.oneOf.filter((s) => !isNull(s))
    if (others.length !== 1) throw new Error(`unsupported oneOf: ${JSON.stringify(schema)}`)
    return [shape(others[0])[0], others.length !== schema.oneOf.length]
  }
  if (schema.$ref) return [{ ref: schema.$ref.slice(PREFIX.length) }, false]
  let type = schema.type
  let nullable = false
  if (Array.isArray(type)) {
    nullable = type.includes('null')
    const rest = type.filter((t) => t !== 'null')
    if (rest.length !== 1) throw new Error(`unsupported type: ${JSON.stringify(schema)}`)
    type = rest[0]
  }
  if (type === undefined) return ['any', false]
  if (type === 'array') return [{ array: shape(schema.items)[0] }, nullable]
  if (type === 'object') {
    if (!schema.additionalProperties) throw new Error(`inline object: ${JSON.stringify(schema)}`)
    return [{ map: shape(schema.additionalProperties)[0] }, nullable]
  }
  return [type, nullable]
}

const out = {}
for (const name of Object.keys(schemas).sort()) {
  const schema = schemas[name]
  if (schema['x-ostrom-kotlin'] === 'pending') continue
  if (schema.enum) {
    out[name] = { enum: schema.enum }
    continue
  }
  const required = new Set(schema.required ?? [])
  const properties = {}
  for (const [property, value] of Object.entries(schema.properties ?? {})) {
    const [type, nullable] = shape(value)
    properties[property] = { type, required: required.has(property), nullable }
  }
  out[name] = { properties }
}
writeFileSync(output, `${JSON.stringify(out, null, 2)}\n`)
console.log(`${Object.keys(out).length} schemas with a Kotlin model written to ${output}`)
