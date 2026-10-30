// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict"
import {describe, it} from "node:test"
import {
    buildTree,
    checkCommand,
    checkFile,
    chunksOf,
    commandsIn,
    isKnownVariable,
    isServed,
    parseCommands,
    parseOptions,
    parsePlumbing,
    parseServedRoutes,
    routesIn,
    segmentsOf,
    shellWords,
    variablesCited,
    variablesInSource,
} from "../docs-truth.mjs"
import {HELP, fakeBinary} from "./fixtures.mjs"

const ROUTES = parseServedRoutes([
    "# comment",
    "! GET /api/v1/auth/me  another shape",
    "",
    "GET /api/v1/health",
    "get /api/v1/instance/keys",
    "DELETE /api/v1/orgs/{}/members/{}",
    "POST /webhooks/stripe",
].join("\n"))

describe("parseServedRoutes", () => {
    it("keeps served lines, upper-cases the method, and drops comments, blanks and other-shape lines", () => {
        assert.deepEqual(ROUTES, [
            ["GET", ["api", "v1", "health"]],
            ["GET", ["api", "v1", "instance", "keys"]],
            ["DELETE", ["api", "v1", "orgs", "{}", "members", "{}"]],
            ["POST", ["webhooks", "stripe"]],
        ])
    })
})

describe("segmentsOf", () => {
    it("writes every kind of parameter as {}", () => {
        assert.deepEqual(segmentsOf("/api/v1/orgs/{org}/members/<id>"), ["api", "v1", "orgs", "{}", "members", "{}"])
        assert.deepEqual(segmentsOf("/api/v1/x/:id/$ID/${ID}"), ["api", "v1", "x", "{}", "{}", "{}"])
    })

    it("drops the query and the fragment", () => {
        assert.deepEqual(segmentsOf("/api/v1/health?full=1"), ["api", "v1", "health"])
        assert.deepEqual(segmentsOf("/api/v1/health#top"), ["api", "v1", "health"])
    })

    it("reads an ellipsis, a star or a trailing slash as any route under the path", () => {
        assert.deepEqual(segmentsOf("/api/v1/…"), ["api", "v1", "*"])
        assert.deepEqual(segmentsOf("/api/v1/..."), ["api", "v1", "*"])
        assert.deepEqual(segmentsOf("/api/*"), ["api", "*"])
        assert.deepEqual(segmentsOf("/api/v1/"), ["api", "v1", "*"])
        assert.deepEqual(segmentsOf("/api/*/"), ["api", "*"])
    })
})

describe("isServed", () => {
    it("needs the method and the exact path when a method is cited", () => {
        assert.equal(isServed(ROUTES, "GET", ["api", "v1", "health"]), true)
        assert.equal(isServed(ROUTES, "POST", ["api", "v1", "health"]), false)
        assert.equal(isServed(ROUTES, "GET", ["api", "v1"]), false)
        assert.equal(isServed(ROUTES, "GET", ["api", "v1", "health", "x"]), false)
    })

    it("matches a served parameter with anything, and a cited parameter only with a served one", () => {
        assert.equal(isServed(ROUTES, "DELETE", ["api", "v1", "orgs", "acme", "members", "{}"]), true)
        assert.equal(isServed(ROUTES, "GET", ["api", "v1", "{}", "keys"]), false)
    })

    it("reads a path without a method, or ending in *, as a prefix of a served route", () => {
        assert.equal(isServed(ROUTES, null, ["api", "v1"]), true)
        assert.equal(isServed(ROUTES, null, ["api", "v1", "health"]), true)
        assert.equal(isServed(ROUTES, null, ["api", "v2"]), false)
        assert.equal(isServed(ROUTES, "GET", ["api", "v1", "instance", "*"]), true)
        assert.equal(isServed(ROUTES, null, ["api", "v1", "health", "deep", "er"]), false)
    })
})

describe("routesIn", () => {
    it("finds routes with or without a method, in code or prose", () => {
        assert.deepEqual(routesIn("Call `GET /api/v1/health`, then POST `/webhooks/stripe`."), [
            {method: "GET", path: "/api/v1/health"},
            {method: "POST", path: "/webhooks/stripe"},
        ])
        assert.deepEqual(routesIn("(/health) and '/ready' and \"/api\""), [
            {method: null, path: "/health"},
            {method: null, path: "/ready"},
            {method: null, path: "/api"},
        ])
    })

    it("strips trailing punctuation and stops at a quote, a bracket or a pipe", () => {
        assert.deepEqual(routesIn("see /api/v1/health: it answers; /api/v1/x|y"), [
            {method: null, path: "/api/v1/health"},
            {method: null, path: "/api/v1/x"},
        ])
    })

    it("reads a URL on an Ostrom host as a route, and ignores one on another host", () => {
        assert.deepEqual(routesIn("curl https://ostrom.example.com/api/v1/health http://localhost:8080/ready"), [
            {method: null, path: "/api/v1/health"},
            {method: null, path: "/ready"},
        ])
        assert.deepEqual(routesIn("app.ostrom.run/api/v1/x and http://[::1]:80/health"), [
            {method: null, path: "/api/v1/x"},
            {method: null, path: "/health"},
        ])
        assert.deepEqual(routesIn("https://openrouter.ai/api and https://api.github.com/api/v3"), [])
    })

    it("ignores paths that are not the API's: other roots, files, words", () => {
        assert.deepEqual(routesIn("/docs/install, /healthz, docs/api/x, ./api/x, /api-types, https://x.io"), [])
    })
})

describe("variablesCited", () => {
    it("finds names, families and brace lists", () => {
        assert.deepEqual(variablesCited("`OSTROM_URL`, OSTROM_RUNNERS_*, OSTROM_GATEWAY_…, OSTROM_K_{A,B_C}."), [
            "OSTROM_URL",
            "OSTROM_RUNNERS_*",
            "OSTROM_GATEWAY_*",
            "OSTROM_K_A",
            "OSTROM_K_B_C",
        ])
    })

    it("ignores a name that only ends with OSTROM_", () => {
        assert.deepEqual(variablesCited("VITE_OSTROM_API_URL"), [])
    })
})

describe("isKnownVariable", () => {
    const known = new Set(["OSTROM_URL", "OSTROM_RUNNERS_DRIVER"])

    it("knows a name read by a source", () => {
        assert.equal(isKnownVariable(known, "OSTROM_URL"), true)
        assert.equal(isKnownVariable(known, "OSTROM_URI"), false)
    })

    it("knows a family when one name of it is read", () => {
        assert.equal(isKnownVariable(known, "OSTROM_RUNNERS_*"), true)
        assert.equal(isKnownVariable(known, "OSTROM_URL*"), false)
        assert.equal(isKnownVariable(known, "OSTROM_MCP_*"), false)
    })
})

describe("variablesInSource", () => {
    it("reads only string literals in Kotlin, never a KDoc", () => {
        const text = '/** `OSTROM_DOC_ONLY` */\nval a = source["OSTROM_HOST"]\n'
        assert.deepEqual([...variablesInSource("A.kt", text)], ["OSTROM_HOST"])
    })

    it("adds the _FILE twin of each literal when the file reads ${name}_FILE", () => {
        const text = 'secret("OSTROM_STRIPE_SECRET_KEY")\nfun secret(name: String) = source[name] ?: source["${name}_FILE"]\n'
        assert.deepEqual([...variablesInSource("B.kts", text)], ["OSTROM_STRIPE_SECRET_KEY", "OSTROM_STRIPE_SECRET_KEY_FILE"])
    })

    it("reads a templated family: every OSTROM_…_X the file names", () => {
        const text = '/** `OSTROM_GITHUB_CLIENT_ID`, `OSTROM_GOOGLE_CLIENT_ID` */\nval id = value(source, "OSTROM_${provider}_CLIENT_ID")\n'
        assert.deepEqual([...variablesInSource("C.kt", text)].sort(), ["OSTROM_GITHUB_CLIENT_ID", "OSTROM_GOOGLE_CLIENT_ID"])
    })

    it("reads only interpolations in YAML", () => {
        const text = "image: ${OSTROM_IMAGE:-x}\nenv:\n  OSTROM_SET_ONLY: $OSTROM_HOST\n  key: ${{ vars.OSTROM_KEY }}\n"
        assert.deepEqual([...variablesInSource("compose.yml", text)], ["OSTROM_IMAGE", "OSTROM_HOST", "OSTROM_KEY"])
    })

    it("reads every use in a script, but not in a comment line", () => {
        const text = "# OSTROM_COMMENT\n  // OSTROM_SLASH\n * OSTROM_STAR\ndir=${OSTROM_INSTALL_DIR:-x} $env:OSTROM_TARGET\n"
        assert.deepEqual([...variablesInSource("install.sh", text)], ["OSTROM_INSTALL_DIR", "OSTROM_TARGET"])
    })
})

describe("parseOptions", () => {
    it("reads the long options of the Options section, with whether each is coming soon, wrapped lines included", () => {
        assert.deepEqual(parseOptions(HELP.claude), {
            "--repo": {comingSoon: false},
            "--cloud": {comingSoon: true},
            "--dry-run": {comingSoon: false},
            "--help": {comingSoon: false},
        })
    })

    it("reads nothing outside the Options section", () => {
        assert.deepEqual(parseOptions("Usage: x\n\n  --not-an-option  in the description\n"), {})
    })
})

describe("parseCommands", () => {
    it("reads the Commands section, wrapped summaries included, up to the first blank line", () => {
        assert.deepEqual(parseCommands(HELP.root), [
            {name: "claude", summary: "Start Claude Code with your team's harness"},
            {name: "harness", summary: "The harness of your team: rules, hooks, skills (coming soon)"},
            {name: "login", summary: "Sign in"},
            {name: "help", summary: ""},
        ])
        assert.deepEqual(parseCommands("Usage: ostrom\n"), [])
    })
})

describe("parsePlumbing", () => {
    it("reads the command names of the plumbing topic", () => {
        assert.deepEqual(parsePlumbing(HELP.plumbing), ["wait", "hook", "check", "login"])
    })
})

describe("buildTree", () => {
    it("walks the help depth-first, marks coming-soon and pass-through commands, adds plumbing once, without help or with", () => {
        const tree = buildTree(fakeBinary())
        assert.deepEqual(Object.keys(tree.children), ["claude", "harness", "login", "help", "wait", "hook", "check"])
        assert.equal(tree.children.claude.passThrough, true)
        assert.equal(tree.children.login.passThrough, false)
        assert.equal(tree.children.harness.comingSoon, true)
        assert.equal(tree.children.harness.children.list.comingSoon, true)
        assert.equal(tree.children.wait.hidden, true)
        assert.deepEqual(tree.children.check, {comingSoon: false, passThrough: true, options: {}, children: {}, hidden: true})
        assert.equal(tree.children.claude.hidden, undefined)
    })

    it("does without a plumbing topic", () => {
        const tree = buildTree(fakeBinary({plumbing: false}))
        assert.deepEqual(Object.keys(tree.children), ["claude", "harness", "login", "help"])
    })

    it("refuses a tree deeper than the limit instead of cutting it", () => {
        assert.throws(() => buildTree(fakeBinary(), {maxDepth: 1}), /ostrom harness list: command tree deeper than 1/)
    })

    it("tolerates a help without a Usage line", () => {
        const help = "Options:\n  --version  Print\n\nCommands:\n  a  A\n"
        const tree = buildTree((args) => args[0] === "help" ? (() => { throw new Error("no") })() : help.replace("Commands:\n  a  A\n", args.length === 1 ? "Commands:\n  a  A\n" : ""))
        assert.deepEqual(tree.options, {"--version": {comingSoon: false}})
        assert.deepEqual(Object.keys(tree.children), ["a"])
    })

    it("refuses a binary whose help lists no command, which would let every citation pass", () => {
        assert.throws(() => buildTree(() => "Usage: x\n"), /ostrom --help lists no command/)
    })
})

describe("shellWords", () => {
    it("splits on spaces, unquotes, and stops at a pipe, a list operator, a redirection or a comment", () => {
        assert.deepEqual(shellWords(`ostrom wait "a b" 'c' | jq`), ["ostrom", "wait", "a b", "c"])
        assert.deepEqual(shellWords("ostrom doctor && echo ok"), ["ostrom", "doctor"])
        assert.deepEqual(shellWords("ostrom doctor > out.txt"), ["ostrom", "doctor"])
        assert.deepEqual(shellWords("ostrom doctor #note"), ["ostrom", "doctor"])
    })
})

describe("checkCommand", () => {
    const tree = buildTree(fakeBinary())

    it("passes a shipped command and its options, an option of a parent or of the root", () => {
        assert.deepEqual(checkCommand(tree, ["ostrom", "login", "--server", "x"]), {problems: [], comingSoon: false})
        assert.deepEqual(checkCommand(tree, ["ostrom", "--version"]), {problems: [], comingSoon: false})
        assert.deepEqual(checkCommand(tree, ["ostrom", "--server=x", "login"]), {problems: [], comingSoon: false})
    })

    it("names a command or an option the binary does not have", () => {
        assert.deepEqual(checkCommand(tree, ["ostrom", "deploy"]).problems, ["`ostrom deploy` is not a command of ostrom"])
        assert.deepEqual(checkCommand(tree, ["ostrom", "login", "--nope"]).problems, ["`ostrom login` has no option --nope"])
    })

    it("says when the command or one of its options is coming soon", () => {
        assert.deepEqual(checkCommand(tree, ["ostrom", "harness", "list"]), {problems: [], comingSoon: true})
        assert.deepEqual(checkCommand(tree, ["ostrom", "claude", "--cloud"]), {problems: [], comingSoon: true})
    })

    it("reads a citation of only --help as the help, never coming soon", () => {
        assert.deepEqual(checkCommand(tree, ["ostrom", "harness", "--help"]), {problems: [], comingSoon: false})
    })

    it("lets a pass-through command take any option, and stops at a positional argument or a placeholder", () => {
        assert.deepEqual(checkCommand(tree, ["ostrom", "claude", "--anything", "-p", "deploy"]), {problems: [], comingSoon: false})
        assert.deepEqual(checkCommand(tree, ["ostrom", "wait", "<id>", "--timeout", "5"]), {problems: [], comingSoon: false})
        assert.deepEqual(checkCommand(tree, ["ostrom", "login", "deploy", "list"]), {problems: [], comingSoon: false})
        assert.deepEqual(checkCommand(tree, ["ostrom", "harness", "<name>"]), {problems: [], comingSoon: true})
    })
})

describe("commandsIn", () => {
    it("reads the ostrom lines of a shell block, without a prompt or variable assignments", () => {
        const chunk = {line: 10, lang: "sh", code: ["$ ostrom login", "OSTROM_URL=x ostrom doctor --json", "echo ostrom", "ostrom"]}
        assert.deepEqual(commandsIn(chunk), [
            {line: 10, words: ["ostrom", "login"]},
            {line: 11, words: ["ostrom", "doctor", "--json"]},
            {line: 13, words: ["ostrom"]},
        ])
        assert.deepEqual(commandsIn({...chunk, lang: ""}).length, 3)
    })

    it("cuts each line of a block without a language at two spaces, a table more often than a command", () => {
        const table = {line: 1, lang: "", code: ["ostrom                 home screen", "ostrom --help          every command"]}
        assert.deepEqual(commandsIn(table).map(({words}) => words), [["ostrom"], ["ostrom", "--help"]])
        assert.deepEqual(commandsIn({...table, lang: "sh"}).map(({words}) => words), [["ostrom", "home", "screen"], ["ostrom", "--help", "every", "command"]])
    })

    it("reads no command in a block that is not shell", () => {
        assert.deepEqual(commandsIn({line: 1, lang: "text", code: ["ostrom login"]}), [])
    })

    it("reads the inline code spans of prose, with their line", () => {
        assert.deepEqual(commandsIn({line: 4, text: "Run `ostrom login`\nthen `ostrom` and `ostromx`."}), [
            {line: 4, words: ["ostrom", "login"]},
            {line: 5, words: ["ostrom"]},
        ])
    })
})

describe("chunksOf", () => {
    it("cuts paragraphs, list items, table rows, headings and code blocks, with their first line", () => {
        const chunks = chunksOf("---\ntitle: t\n---\n# Title\n\nOne\ntwo\n\n- item\n  more\n| a | b |\n| c | d |\n```sh\nostrom x\n```\n~~~\nraw\n")
        assert.deepEqual(chunks.map(({line, text}) => [line, text]), [
            [4, "Title"],
            [6, "One\ntwo"],
            [9, "- item\n  more"],
            [11, "| a | b |"],
            [12, "| c | d |"],
            [14, "ostrom x"],
            [17, "raw\n"],
        ])
        assert.equal(chunks[5].lang, "sh")
        assert.equal(chunks[6].lang, "")
    })

    it("marks a chunk that says Coming soon, the chunks under such a heading, and a block after a marked chunk", () => {
        const chunks = chunksOf("## Now\n\nA\n\nComing soon:\n```sh\nostrom x\n```\n\n## Login (coming soon)\n\n### Deeper\n\nB\n\n## Later\n\nC\n")
        assert.deepEqual(chunks.map(({text, marked}) => [text, marked]), [
            ["Now", false],
            ["A", false],
            ["Coming soon:", true],
            ["ostrom x", true],
            ["Login (coming soon)", true],
            ["Deeper", true],
            ["B", true],
            ["Later", false],
            ["C", false],
        ])
    })

    it("marks every chunk of a coming-soon page, and none of an available page", () => {
        assert.equal(chunksOf("---\nstatus: coming-soon\n---\nA\n")[0].marked, true)
        assert.equal(chunksOf("---\nstatus: available\n---\nA\n")[0].marked, false)
        assert.equal(chunksOf("```sh\nostrom\n```\n")[0].marked, false)
    })
})

describe("checkFile", () => {
    const facts = {routes: ROUTES, tree: buildTree(fakeBinary()), variables: new Set(["OSTROM_URL"])}

    it("passes a page that cites only what is shipped", () => {
        assert.deepEqual(checkFile("Run `ostrom login` with `OSTROM_URL` against `GET /api/v1/health`.\n", facts), [])
    })

    it("names every route, command and variable that is not shipped, on its own line", () => {
        const page = "---\nstatus: available\n---\nCall\n`POST /api/v1/launches` and\n`OSTROM_NOPE`.\n\n```sh\nostrom deploy\nostrom harness list\n```\n"
        assert.deepEqual(checkFile(page, facts), [
            {line: 5, message: 'POST /api/v1/launches is not served by ostrom-server main (served-routes.txt), and nothing around it says "coming soon"'},
            {line: 6, message: 'OSTROM_NOPE is read by none of the sources, and nothing around it says "coming soon"'},
            {line: 9, message: '`ostrom deploy` is not a command of ostrom, and nothing around it says "coming soon"'},
            {line: 10, message: '`ostrom harness list` is coming soon in the binary, and nothing around it says "coming soon"'},
        ])
    })

    it("names a problem once per line", () => {
        assert.equal(checkFile("`OSTROM_NOPE` or `OSTROM_NOPE`, `GET /api/x` or `GET /api/x`\n", facts).length, 2)
    })

    it("lets a marked chunk, or one that says docs-truth: ignore, cite what is not shipped", () => {
        assert.deepEqual(checkFile("Coming soon: `POST /api/v1/launches`, `ostrom deploy`, `OSTROM_NOPE`.\n", facts), [])
        assert.deepEqual(checkFile("`OSTROM_GONE` is removed. <!-- docs-truth: ignore (removed) -->\n", facts), [])
        assert.deepEqual(checkFile("Soon: /api/x\n", facts, {marker: /soon/i}), [])
    })

    it("skips commands without a tree and variables without sources", () => {
        assert.deepEqual(checkFile("`ostrom deploy` `OSTROM_NOPE`\n", {...facts, tree: null, variables: null}), [])
    })

    it("reads a file that is not Markdown line by line, for routes and variables only", () => {
        const source = "const a = '/api/v1/launches'\nconst b = 'coming soon: /api/v1/x'\n// `ostrom deploy`\nOSTROM_NOPE\n"
        assert.deepEqual(checkFile(source, facts, {markdown: false}).map(({line}) => line), [1, 4])
    })

    it("refuses a claim that checks are proven unless the same chunk says where they ran, Coming soon or not", () => {
        const message = (claim) => [{line: 1, message: `"${claim}" without saying where the checks ran (attestation: declared by the workstation, an isolated runner or CI)`}]
        assert.deepEqual(checkFile("Every receipt carries proven checks.\n", facts), message("proven checks"))
        assert.deepEqual(checkFile("Coming soon: tests are proven.\n", facts), message("tests are proven"))
        assert.deepEqual(checkFile("A proof that the tests passed.\n", facts), message("proof that the tests passed"))
        assert.deepEqual(checkFile("Des checks prouvés.\n", facts, {markdown: false}), message("checks prouvés"))
        assert.deepEqual(checkFile("Verified checks, each with its attestation.\n", facts), [])
        assert.deepEqual(checkFile("Checks are proven when they ran in CI.\n", facts), [])
        assert.deepEqual(checkFile("Des checks prouvés, déclarés par le poste.\n", facts), [])
        assert.deepEqual(checkFile("Proven checks. <!-- docs-truth: ignore -->\n", facts), [])
        assert.deepEqual(checkFile("The check runs offline.\n", facts), [])
    })

    it("needs a page whose status is coming-soon or partial to say Coming soon in its text", () => {
        const problem = {line: 1, message: `the page's status says a part is not shipped, but its text never says "coming soon"`}
        assert.deepEqual(checkFile("---\nstatus: partial\n---\nAll done.\n", facts), [problem])
        assert.deepEqual(checkFile("---\nstatus: coming-soon\n---\nComing soon.\n", facts), [])
        assert.deepEqual(checkFile("---\nstatus: available\n---\nAll done.\n", facts), [])
        assert.deepEqual(checkFile("No front matter.\n", facts), [])
        assert.deepEqual(checkFile("---\nstatus: partial\n---\n", facts, {markdown: false}), [])
    })
})
