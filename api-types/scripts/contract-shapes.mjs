// Writes the JSON shape of every schema of openapi.yaml that has a Kotlin model (all but `x-ostrom-kotlin: pending`).
// The Kotlin test ContractTest compares this file with the serial descriptors of run.ostrom.core.api.models;
// CI regenerates it and fails when the committed copy differs. It also refuses an operation without
// `x-ostrom-status: served | planned`, the marker the public API reference filters on.
import { readFileSync, writeFileSync } from 'node:fs'
import { parse } from 'yaml'

const [input, output] = process.argv.slice(2)
const contract = parse(readFileSync(input, 'utf8'))
const schemas = contract.components.schemas
const PREFIX = '#/components/schemas/'
const METHODS = ['get', 'put', 'post', 'delete', 'patch', 'head', 'options', 'trace']
const STATUSES = ['served', 'planned']

const unmarked = Object.entries(contract.paths).flatMap(([path, item]) =>
  METHODS.filter((method) => item[method] && !STATUSES.includes(item[method]['x-ostrom-status'])).map(
    (method) => `${method.toUpperCase()} ${path}`,
  ),
)
if (unmarked.length > 0) {
  throw new Error(`operations without x-ostrom-status (served or planned):\n${unmarked.join('\n')}`)
}

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

// A union told apart by a property (`oneOf` + `discriminator`, a Kotlin sealed hierarchy): its shape is the property
// and the schema of each value; each variant's shape leaves the property out, as kotlinx.serialization writes it.
const discriminatorOf = {}
function union(name, schema) {
  const { propertyName, mapping } = schema.discriminator
  if (!mapping) throw new Error(`${name}: a discriminator needs its mapping`)
  const variants = {}
  for (const [value, ref] of Object.entries(mapping)) {
    const variant = ref.slice(PREFIX.length)
    if (schemas[variant]?.properties?.[propertyName]?.const !== value) {
      throw new Error(`${name}: ${variant} must have ${propertyName} const ${value}`)
    }
    discriminatorOf[variant] = propertyName
    variants[value] = variant
  }
  return { oneOf: { property: propertyName, mapping: variants } }
}

const out = {}
for (const name of Object.keys(schemas).sort()) {
  const schema = schemas[name]
  if (!schema.discriminator) continue
  const shape = union(name, schema)
  if (schema['x-ostrom-kotlin'] !== 'pending') out[name] = shape
}
for (const name of Object.keys(schemas).sort()) {
  const schema = schemas[name]
  if (schema['x-ostrom-kotlin'] === 'pending' || schema.discriminator) continue
  if (schema.enum) {
    out[name] = { enum: schema.enum }
    continue
  }
  const required = new Set(schema.required ?? [])
  const properties = {}
  for (const [property, value] of Object.entries(schema.properties ?? {})) {
    if (property === discriminatorOf[name]) continue
    const [type, nullable] = shape(value)
    properties[property] = { type, required: required.has(property), nullable }
  }
  out[name] = { properties }
}
const sorted = Object.fromEntries(Object.keys(out).sort().map((name) => [name, out[name]]))
writeFileSync(output, `${JSON.stringify(sorted, null, 2)}\n`)
console.log(`${Object.keys(sorted).length} schemas with a Kotlin model written to ${output}`)
