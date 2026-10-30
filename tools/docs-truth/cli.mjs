// SPDX-License-Identifier: Apache-2.0
// The command line of docs-truth: reads the facts (served routes, the binary's help, the sources' variables), checks
// every page given, prints one `path:line: problem` per problem (and a GitHub annotation in Actions), exits 1 on any.
import {execFileSync} from "node:child_process"
import {readdirSync, readFileSync, statSync} from "node:fs"
import {join, relative} from "node:path"
import {buildTree, checkFile, DEFAULT_MARKER, parseServedRoutes, variablesInSource} from "./docs-truth.mjs"

export const USAGE = `Usage: docs-truth --routes <served-routes.txt> [options] <file or folder>...

Fails when a page cites an API route, an ostrom command or option, or an OSTROM_ variable that the product does not
have, unless the page says "Coming soon" around it. A folder is read for its .md and .mdx pages; any other file given
by name is read line by line, for routes and variables only.

Options:
  --routes <file>     ostrom-core's api/contract/served-routes.txt (required)
  --ostrom <binary>   the real ostrom binary: commands and options are checked against its help
  --source <path>     a folder or file whose OSTROM_ variables count as read (repeatable); none: variables unchecked
  --tests             the sources' tests count too (a contributor's page may cite a variable only tests read)
  --warn <path>       problems in pages under this path are warnings, not failures (repeatable)
  --marker <regex>    the marker that allows a citation of what is not shipped (default: ${DEFAULT_MARKER.source})`

const SKIPPED = new Set([".git", "node_modules", "build", ".gradle", ".kotlin", "dist", ".next"])
const SOURCE_FILE = /\.(kts?|sh|ps1|mjs|cjs|js|ts|py|ya?ml)$/
const TEST_PATH = /(^|\/)(test|tests|__tests__|[A-Za-z]*Test)(\/|$)|\.test\.|Test\.kts?$/

function walk(path, keep) {
    if (statSync(path).isFile()) return [path]
    return readdirSync(path, {withFileTypes: true})
        .filter((entry) => !SKIPPED.has(entry.name))
        .sort((a, b) => a.name.localeCompare(b.name))
        .flatMap((entry) => entry.isDirectory() ? walk(join(path, entry.name), keep) : keep(entry.name) ? [join(path, entry.name)] : [])
}

/** Parses [argv]; throws an Error whose message is for the user. */
export function parseArguments(argv) {
    const options = {routes: null, ostrom: null, sources: [], tests: false, warn: [], marker: DEFAULT_MARKER, paths: []}
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index]
        const value = () => {
            index += 1
            if (index >= argv.length) throw new Error(`${argument} needs a value`)
            return argv[index]
        }
        if (argument === "--routes") options.routes = value()
        else if (argument === "--ostrom") options.ostrom = value()
        else if (argument === "--source") options.sources.push(value())
        else if (argument === "--warn") options.warn.push(value())
        else if (argument === "--tests") options.tests = true
        else if (argument === "--marker") options.marker = new RegExp(value(), "i")
        else if (argument.startsWith("--")) throw new Error(`unknown option ${argument}`)
        else options.paths.push(argument)
    }
    if (options.routes === null) throw new Error("--routes is required")
    if (options.paths.length === 0) throw new Error("no page to check")
    return options
}

/** Runs the binary for its help, without colour and at a fixed width, so the help reads the same everywhere. */
export function binaryRunner(binary, exec = execFileSync) {
    return (args) => exec(binary, args, {encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], env: {...process.env, NO_COLOR: "1", TERM: "dumb", COLUMNS: "120"}})
}

/** The whole run; returns the exit code. [io] holds `out`, `err` (one line each), `env` and `runner` (binary → run). */
export function main(argv, {out, err, env = process.env, runner = binaryRunner}) {
    let options
    try {
        options = parseArguments(argv)
    } catch (e) {
        err(`docs-truth: ${e.message}\n\n${USAGE}`)
        return 64
    }
    const facts = {
        routes: parseServedRoutes(readFileSync(options.routes, "utf8")),
        tree: options.ostrom === null ? null : buildTree(runner(options.ostrom)),
        variables: null,
    }
    if (options.sources.length > 0) {
        facts.variables = new Set()
        for (const source of options.sources) {
            for (const file of walk(source, (name) => SOURCE_FILE.test(name))) {
                if (!options.tests && TEST_PATH.test(relative(source, file))) continue
                for (const name of variablesInSource(file, readFileSync(file, "utf8"))) facts.variables.add(name)
            }
        }
    }
    const annotate = env.GITHUB_ACTIONS === "true"
    let failures = 0
    let warnings = 0
    let files = 0
    for (const path of options.paths) {
        for (const file of walk(path, (name) => /\.mdx?$/.test(name))) {
            files += 1
            const markdown = /\.mdx?$/.test(file)
            const warn = options.warn.some((prefix) => file === prefix || file.startsWith(prefix.endsWith("/") ? prefix : `${prefix}/`))
            for (const {line, message} of checkFile(readFileSync(file, "utf8"), facts, {marker: options.marker, markdown})) {
                if (warn) warnings += 1
                else failures += 1
                out(annotate ? `::${warn ? "warning" : "error"} file=${file},line=${line},title=docs-truth::${message}` : `${file}:${line}: ${warn ? "warning: " : ""}${message}`)
            }
        }
    }
    const checked = [facts.tree === null ? null : "commands", "routes", facts.variables === null ? null : "variables"].filter(Boolean).join(", ")
    out(`docs-truth: ${files} files, ${checked} checked: ${failures} problems, ${warnings} warnings`)
    return failures > 0 ? 1 : 0
}
