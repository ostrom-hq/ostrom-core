// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict"
import {mkdirSync, mkdtempSync, rmSync, writeFileSync} from "node:fs"
import {tmpdir} from "node:os"
import {join} from "node:path"
import {after, describe, it} from "node:test"
import {binaryRunner, main, parseArguments, USAGE} from "../cli.mjs"
import {fakeBinary} from "./fixtures.mjs"

const root = mkdtempSync(join(tmpdir(), "docs-truth-"))
after(() => rmSync(root, {recursive: true, force: true}))

function write(path, text) {
    mkdirSync(join(root, path, ".."), {recursive: true})
    writeFileSync(join(root, path), text)
    return join(root, path)
}

const routes = write("served-routes.txt", "GET /api/v1/health\n")
write("server/src/commonMain/Config.kt", 'val url = source["OSTROM_URL"]\n')
write("server/src/commonTest/ConfigTest.kt", 'val t = "OSTROM_TEST_ONLY"\n')
write("server/node_modules/x.js", "OSTROM_SKIPPED\n")
write("server/README.txt", "OSTROM_NOT_A_SOURCE\n")
const good = write("docs/good.md", "Run `ostrom login` with `OSTROM_URL` against `GET /api/v1/health`.\n")
write("docs/nested/bad.mdx", "Call `POST /api/v1/launches` with `OSTROM_TEST_ONLY` and `ostrom deploy`.\n")
write("docs/notes.txt", "POST /api/v1/launches\n")
const site = write("site.ts", "fetch('/api/v1/launches')\n")

function run(argv, env = {}) {
    const out = []
    const err = []
    const code = main(argv, {out: (line) => out.push(line), err: (line) => err.push(line), env, runner: () => fakeBinary()})
    return {code, out, err}
}

describe("parseArguments", () => {
    it("reads every option and the paths", () => {
        const options = parseArguments(["--routes", "r", "--ostrom", "o", "--source", "a", "--source", "b", "--tests", "--warn", "w", "--marker", "soon", "p"])
        assert.deepEqual({...options, marker: options.marker.source}, {
            routes: "r", ostrom: "o", sources: ["a", "b"], tests: true, warn: ["w"], marker: "soon", paths: ["p"],
        })
        assert.equal(options.marker.flags, "i")
    })

    it("refuses a missing value, an unknown option, no routes and no page", () => {
        assert.throws(() => parseArguments(["--routes"]), /--routes needs a value/)
        assert.throws(() => parseArguments(["--nope"]), /unknown option --nope/)
        assert.throws(() => parseArguments(["p"]), /--routes is required/)
        assert.throws(() => parseArguments(["--routes", "r"]), /no page to check/)
    })
})

describe("binaryRunner", () => {
    it("runs the binary without colour, at a fixed width, and returns its output", () => {
        const calls = []
        const exec = (binary, args, options) => {
            calls.push({binary, args, options})
            return "help"
        }
        assert.equal(binaryRunner("/bin/ostrom", exec)(["--help"]), "help")
        assert.equal(calls[0].binary, "/bin/ostrom")
        assert.deepEqual(calls[0].args, ["--help"])
        assert.equal(calls[0].options.env.NO_COLOR, "1")
        assert.equal(calls[0].options.env.COLUMNS, "120")
        assert.equal(calls[0].options.encoding, "utf8")
    })

    it("runs a real binary by default", () => {
        assert.equal(binaryRunner(process.execPath)(["-e", "process.stdout.write(process.env.NO_COLOR)"]), "1")
    })
})

describe("main", () => {
    it("prints the usage and exits 64 on a bad command line", () => {
        const {code, err} = run([])
        assert.equal(code, 64)
        assert.equal(err[0], `docs-truth: --routes is required\n\n${USAGE}`)
    })

    it("passes pages that cite only what is shipped", () => {
        const {code, out} = run(["--routes", routes, "--ostrom", "ostrom", "--source", join(root, "server"), good])
        assert.equal(code, 0)
        assert.deepEqual(out, ["docs-truth: 1 files, commands, routes, variables checked: 0 problems, 0 warnings"])
    })

    it("walks folders for Markdown pages, skips tests in sources, and fails on each problem", () => {
        const {code, out} = run(["--routes", routes, "--ostrom", "ostrom", "--source", join(root, "server"), join(root, "docs")])
        assert.equal(code, 1)
        const bad = join(root, "docs/nested/bad.mdx")
        assert.deepEqual(out, [
            `${bad}:1: POST /api/v1/launches is not served by ostrom-server main (served-routes.txt), and nothing around it says "coming soon"`,
            `${bad}:1: \`ostrom deploy\` is not a command of ostrom, and nothing around it says "coming soon"`,
            `${bad}:1: OSTROM_TEST_ONLY is read by none of the sources, and nothing around it says "coming soon"`,
            "docs-truth: 2 files, commands, routes, variables checked: 3 problems, 0 warnings",
        ])
    })

    it("counts the sources' tests with --tests, and checks only routes without --ostrom and --source", () => {
        assert.equal(run(["--routes", routes, "--tests", "--source", join(root, "server"), join(root, "docs/nested")]).out.length, 2)
        assert.deepEqual(run(["--routes", routes, join(root, "docs")]).out.at(-1), "docs-truth: 2 files, routes checked: 1 problems, 0 warnings")
        assert.equal(run(["--routes", routes, "--source", join(root, "server/src/commonMain/Config.kt"), good]).code, 0)
    })

    it("reads a non-Markdown file given by name line by line", () => {
        const {code, out} = run(["--routes", routes, site])
        assert.equal(code, 1)
        assert.match(out[0], /site\.ts:1: \/api\/v1\/launches is not served/)
    })

    it("turns problems under a --warn path into warnings that do not fail", () => {
        for (const warn of [join(root, "docs/nested"), `${join(root, "docs/nested")}/`, join(root, "docs/nested/bad.mdx")]) {
            const {code, out} = run(["--routes", routes, "--warn", warn, join(root, "docs")])
            assert.equal(code, 0)
            assert.match(out[0], /bad\.mdx:1: warning: POST/)
            assert.equal(out.at(-1), "docs-truth: 2 files, routes checked: 0 problems, 1 warnings")
        }
        assert.equal(run(["--routes", routes, "--warn", join(root, "docs/nest"), join(root, "docs")]).code, 1)
    })

    it("writes GitHub annotations in Actions", () => {
        const bad = join(root, "docs/nested/bad.mdx")
        assert.match(run(["--routes", routes, bad], {GITHUB_ACTIONS: "true"}).out[0], new RegExp(`^::error file=${bad},line=1,title=docs-truth::POST`))
        assert.match(run(["--routes", routes, "--warn", bad, bad], {GITHUB_ACTIONS: "true"}).out[0], /^::warning file=/)
    })

    it("reads the process environment when none is given", () => {
        const out = []
        assert.equal(main(["--routes", routes, good], {out: (line) => out.push(line), err: () => {}}), 0)
        assert.equal(out.length, 1)
    })

    it("reads the marker given", () => {
        assert.equal(run(["--routes", routes, "--marker", "launches", join(root, "docs")]).code, 0)
    })
})
