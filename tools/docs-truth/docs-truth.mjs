// SPDX-License-Identifier: Apache-2.0
// docs-truth: a page may cite only what the product does. Every cited API route must be served by ostrom-server main
// (api/contract/served-routes.txt), every `ostrom …` command and option must be in the help of the real binary, and
// every OSTROM_ variable must be read by the sources given. A citation of something not shipped is allowed only under
// an explicit marker ("Coming soon" by default): in its paragraph, list item, table row or heading, or on a page whose
// front matter says `status: coming-soon`.

/** First path segments of the API: a path that starts with another one (`/docs`, `/healthz`) is not a route. */
const API_ROOTS = ["api", "webhooks", "health", "ready"]
const METHODS = ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"]
const SHELLS = ["sh", "bash", "shell", "zsh", "console", "powershell", "ps1"]
export const DEFAULT_MARKER = /coming soon/i
/** Written in a chunk (`<!-- docs-truth: ignore … -->`, the reason after it), it lets the chunk cite anything. */
const IGNORE = /docs-truth:\s*ignore/
/** Hosts of an Ostrom server in examples; a URL on another host (a provider's API) is not a route of ours. */
const OUR_HOST = /(^|[.\/])(ostrom[\w.-]*|localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])(:\d+)?$/

/**
 * The routes of served-routes.txt, as `[method, segments]`. Comments and `!` lines (served in another shape than the
 * contract's, so not served for a reader) are left out; a path parameter is written `{}`.
 */
export function parseServedRoutes(text) {
    const routes = []
    for (const raw of text.split("\n")) {
        const line = raw.trim()
        if (line === "" || line.startsWith("#") || line.startsWith("!")) continue
        const [method, path] = line.split(/\s+/)
        routes.push([method.toUpperCase(), segmentsOf(path)])
    }
    return routes
}

/**
 * Whether [method] on [segments] is served. A served `{}` matches any segment. A cited `*` at the end stands for any
 * served route under the segments before it, and so does a path cited without a method (`the API under /api/v1`);
 * with a method, the route must be served as it is.
 */
export function isServed(routes, method, segments) {
    const prefix = method === null || segments.at(-1) === "*"
    const head = segments.at(-1) === "*" ? segments.slice(0, -1) : segments
    return routes.some(([servedMethod, served]) => {
        if (method !== null && servedMethod !== method) return false
        if (prefix ? served.length < head.length : served.length !== head.length) return false
        return head.every((segment, index) => served[index] === "{}" || served[index] === segment)
    })
}

/** Long options of one Clikt help text, each with whether its help (wrapped lines included) says "(coming soon)". */
export function parseOptions(help) {
    const texts = {}
    let last = null
    let inside = false
    for (const line of help.split("\n")) {
        if (/^\S.*:$/.test(line)) {
            inside = line === "Options:"
            last = null
            continue
        }
        if (!inside) continue
        const match = /^ {2}(?:-\w, )?(--[a-z][\w-]*)(.*)$/.exec(line)
        if (match !== null) {
            last = match[1]
            texts[last] = match[2]
        } else if (last !== null && /^ {3,}\S/.test(line)) {
            texts[last] += ` ${line.trim()}`
        }
    }
    return Object.fromEntries(Object.entries(texts).map(([name, text]) => [name, {comingSoon: /\(coming\s+soon\)/i.test(text)}]))
}

/** The subcommands of one Clikt help text: its `Commands:` section, one `  name  summary` line each. */
export function parseCommands(help) {
    const lines = help.split("\n")
    const start = lines.findIndex((line) => line.trim() === "Commands:")
    if (start === -1) return []
    const commands = []
    for (const line of lines.slice(start + 1)) {
        if (line.trim() === "") break
        const match = /^ {2}([a-z][\w-]*)(?: {2,}(.*))?$/.exec(line)
        if (match !== null) commands.push({name: match[1], summary: (match[2] ?? "").trim()})
        else if (commands.length > 0 && /^ {3,}\S/.test(line)) commands.at(-1).summary = `${commands.at(-1).summary} ${line.trim()}`
    }
    return commands
}

/** The hidden commands `ostrom help plumbing` lists: `  ostrom wait <id> …` lines. */
export function parsePlumbing(text) {
    return [...text.matchAll(/^ {2}ostrom ([a-z][\w-]*)/gm)].map((match) => match[1])
}

/**
 * The command tree of the binary. `run(args)` returns the standard output of `ostrom <args…>`, or throws. Depth-first;
 * a tree deeper than [maxDepth] is an error, never a silent cut.
 */
export function buildTree(run, {maxDepth = 8} = {}) {
    const visit = (path, summary) => {
        if (path.length > maxDepth) throw new Error(`ostrom ${path.join(" ")}: command tree deeper than ${maxDepth}`)
        const help = run([...path, "--help"])
        const usage = (/^Usage: (.*)$/m.exec(help) ?? ["", ""])[1]
        const node = {
            comingSoon: /\(coming soon\)\s*$/i.test(summary),
            passThrough: /args>/.test(usage),
            options: parseOptions(help),
            children: {},
        }
        for (const child of parseCommands(help)) node.children[child.name] = visit([...path, child.name], child.summary)
        return node
    }
    const root = visit([], "")
    // An empty tree would let every citation pass: a binary whose help lists no command is not the one to check against.
    if (Object.keys(root.children).length === 0) throw new Error("ostrom --help lists no command: is this the ostrom binary?")
    let plumbing = ""
    try {
        plumbing = run(["help", "plumbing"])
    } catch {
        // A binary without `ostrom help plumbing` has no hidden command to add.
    }
    for (const name of parsePlumbing(plumbing)) {
        if (name in root.children) continue
        try {
            root.children[name] = {...visit([name], ""), hidden: true}
        } catch {
            // A plumbing command with no help of its own (`ostrom hook` runs before the parser): a command all the same,
            // whose options are unknown, so any is accepted.
            root.children[name] = {comingSoon: false, passThrough: true, options: {}, children: {}, hidden: true}
        }
    }
    return root
}

/** The words of one shell line, quotes removed, up to the first `|`, `;`, `&&`, `>` or comment. */
export function shellWords(line) {
    const words = []
    for (const match of line.matchAll(/"[^"]*"|'[^']*'|\S+/g)) {
        const word = match[0]
        if (/^(\||\|\||;|&&|#|>|>>|2>|2>&1|<)$/.test(word) || word.startsWith("#")) break
        words.push(word.replace(/^(["'])(.*)\1$/, "$2"))
    }
    return words
}

/**
 * One `ostrom …` citation (its words, `ostrom` first) against the tree: `problems` lists what the binary does not
 * have, `comingSoon` says the command or one of its options is marked "(coming soon)" in the binary. A citation of
 * only `--help` documents the help, not the feature, and is never coming soon.
 */
export function checkCommand(tree, words) {
    let node = tree
    const path = ["ostrom"]
    const options = []
    const problems = []
    let descending = true
    let comingSoon = false
    for (const word of words.slice(1)) {
        if (word.startsWith("--")) {
            options.push({name: word.split("=")[0], node})
            continue
        }
        if (!descending || word.startsWith("-")) continue
        const child = node.children[word]
        if (child !== undefined) {
            node = child
            path.push(word)
            if (node.comingSoon) comingSoon = true
            continue
        }
        if (Object.keys(node.children).length > 0 && /^[a-z][\w-]*$/.test(word)) {
            problems.push(`\`${path.join(" ")} ${word}\` is not a command of ostrom`)
        }
        descending = false
    }
    const helpOnly = options.length > 0 && options.every((option) => option.name === "--help")
    for (const option of options) {
        if (option.name === "--help") continue
        const known = node.options[option.name] ?? option.node.options[option.name] ?? tree.options[option.name]
        if (known === undefined) {
            if (!node.passThrough) problems.push(`\`${path.join(" ")}\` has no option ${option.name}`)
        } else if (known.comingSoon) {
            comingSoon = true
        }
    }
    return {problems, comingSoon: comingSoon && !helpOnly}
}

/** Whether [name] (`OSTROM_X`, or `OSTROM_X_*` for a family) is read: in [variables], or a family with one in it. */
export function isKnownVariable(variables, name) {
    if (!name.endsWith("*")) return variables.has(name)
    const prefix = name.replace(/\*+$/, "")
    return [...variables].some((variable) => variable.startsWith(prefix) && variable.length > prefix.length)
}

/**
 * The `OSTROM_` names a source file reads. In Kotlin, only string literals (`"OSTROM_X"`), so a KDoc alone never
 * counts; a template reads a family: `"${name}_FILE"` adds the `_FILE` twin of each literal, `"OSTROM_${p}_X"` every
 * `OSTROM_…_X` the file names. In YAML, an interpolation (`${OSTROM_X}`, `vars.OSTROM_X`); in a script, every use
 * outside a comment line.
 */
export function variablesInSource(path, text) {
    const names = new Set()
    if (/\.ya?ml$/.test(path)) {
        // A YAML file (a Compose file) reads a variable by interpolating it; `OSTROM_X: value` only sets it.
        for (const match of text.matchAll(/(?:\$\{?|\b(?:vars|env|secrets)\.)(OSTROM_[A-Z0-9_]*[A-Z0-9])\b/g)) names.add(match[1])
        return names
    }
    if (/\.kts?$/.test(path)) {
        for (const match of text.matchAll(/"(OSTROM_[A-Z0-9_]*[A-Z0-9])"/g)) names.add(match[1])
        if (/\}_FILE"/.test(text)) for (const name of [...names]) names.add(`${name}_FILE`)
        for (const template of text.matchAll(/"OSTROM_\$\{?\w+\}?(_[A-Z0-9_]*[A-Z0-9])"/g)) {
            const family = new RegExp(String.raw`\bOSTROM_[A-Z0-9]+(?:_[A-Z0-9]+)*?${template[1]}\b`, "g")
            for (const match of text.matchAll(family)) names.add(match[0])
        }
        return names
    }
    for (const line of text.split("\n")) {
        if (/^\s*(#|\/\/|\*|<#)/.test(line)) continue
        for (const match of line.matchAll(/\bOSTROM_[A-Z0-9_]*[A-Z0-9]\b/g)) names.add(match[0])
    }
    return names
}

/**
 * The `OSTROM_` variables cited in [text]: `OSTROM_X`, `OSTROM_X_*` (or `OSTROM_X_…`) for a family, and
 * `OSTROM_X_{A,B}` for `OSTROM_X_A` and `OSTROM_X_B`.
 */
export function variablesCited(text) {
    return [...text.matchAll(/\bOSTROM_[A-Z0-9_]*(?:\{([A-Z0-9_,]+)\}|\*|…|[A-Z0-9])/g)].flatMap((match) => {
        if (match[1] !== undefined) return match[1].split(",").map((part) => match[0].replace(/\{.*\}$/, part))
        return [match[0].replace(/…$/, "*")]
    })
}

const ROUTE = new RegExp(
    String.raw`(?:\b(${METHODS.join("|")})\s+` + "`?" + String.raw`)?` +
        String.raw`(?:((?:https?:\/\/)?(?:[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+|localhost|\[::1\])(?::\d+)?)|(?<![\w.\/{}<>:-]))` +
        String.raw`(\/(?:${API_ROOTS.join("|")})(?![\w-])[^\s` + "`" + String.raw`'"()\]|,]*)`,
    "g",
)

/** The routes cited in [text], each `{method, path}` (method null when none is written before the path). */
export function routesIn(text) {
    return [...text.matchAll(ROUTE)]
        .filter((match) => match[2] === undefined || OUR_HOST.test(match[2]))
        .map((match) => ({method: match[1] ?? null, path: match[3].replace(/[.,:;!]+$/, "")}))
}

/**
 * `/api/v1/orgs/{org}/members/<id>?x=1` → `["api", "v1", "orgs", "{}", "members", "{}"]`: a parameter (`{x}`, `<x>`,
 * `:x`, `$X`) is `{}`; a last `…`, `...`, `*` or a trailing `/` is `*`, any route under the segments before it.
 */
export function segmentsOf(path) {
    const bare = path.split(/[?#]/)[0]
    const segments = bare.split("/").filter((segment) => segment !== "").map((segment) => {
        if (/^(\{.*\}|<.*>|:.+|\$\{?\w+\}?)$/.test(segment)) return "{}"
        if (/^(\*|…|\.\.\.)$/.test(segment)) return "*"
        return segment
    })
    if (bare.endsWith("/") && segments.at(-1) !== "*") segments.push("*")
    return segments
}

/**
 * The `ostrom …` commands cited in a chunk: its inline code spans, or the lines of a shell code block. A block without
 * a language is often a table (`ostrom doctor   check this device`): each line is cut at its first run of two spaces.
 */
export function commandsIn(chunk) {
    if (chunk.code !== undefined) {
        if (chunk.lang !== "" && !SHELLS.includes(chunk.lang)) return []
        return chunk.code.flatMap((raw, offset) => {
            let line = raw.replace(/^\s*(\$|>|PS>)\s+/, "").replace(/^\s*(?:[A-Z_][A-Z0-9_]*=\S*\s+)*/, "")
            if (chunk.lang === "") line = line.split(/\s{2,}/)[0]
            return /^ostrom(\s|$)/.test(line) ? [{line: chunk.line + offset, words: shellWords(line)}] : []
        })
    }
    return [...chunk.text.matchAll(/`(ostrom(?:\s[^`]*)?)`/g)].map((match) => ({
        line: chunk.line + newlines(chunk.text.slice(0, match.index)),
        words: shellWords(match[1]),
    }))
}

function newlines(text) {
    return (text.match(/\n/g) ?? []).length
}

/**
 * The chunks of a Markdown page, each `{line, text, marked}`: a paragraph, a list item, a table row, a heading or a
 * fenced code block (with `lang` and its `code` lines). A chunk is [marked] when its text, a heading above it or the
 * front matter (`status: coming-soon`) says [marker]; a code block also when the chunk just before it does.
 */
export function chunksOf(source, marker = DEFAULT_MARKER) {
    const lines = source.split("\n")
    const chunks = []
    const headings = []
    let index = 0
    let pageMarked = false
    if (lines[0] === "---") {
        const end = lines.indexOf("---", 1)
        pageMarked = lines.slice(1, end).some((line) => /^status:\s*coming-soon\s*$/.test(line))
        index = end + 1
    }
    const underMarker = () => pageMarked || headings.some((heading) => marker.test(heading))
    let current = null
    const close = () => {
        if (current === null) return
        current.marked = underMarker() || marker.test(current.text)
        chunks.push(current)
        current = null
    }
    while (index < lines.length) {
        const line = lines[index]
        const lineNumber = index + 1
        index += 1
        const fence = /^\s*(```+|~~~+)\s*([\w-]*)/.exec(line)
        if (fence !== null) {
            close()
            const body = []
            while (index < lines.length && !lines[index].trimStart().startsWith(fence[1])) body.push(lines[index++])
            index += 1
            const before = chunks.at(-1)
            chunks.push({line: lineNumber + 1, text: body.join("\n"), lang: fence[2], code: body, marked: underMarker() || before?.marked === true})
            continue
        }
        const heading = /^(#{1,6})\s+(.*)$/.exec(line)
        if (heading !== null) {
            close()
            headings.length = heading[1].length - 1
            headings.push(heading[2])
            current = {line: lineNumber, text: heading[2]}
            close()
            continue
        }
        if (line.trim() === "") {
            close()
            continue
        }
        const row = /^\s*\|/.test(line)
        if (row || /^\s*([-*+]|\d+\.)\s/.test(line)) {
            close()
            current = {line: lineNumber, text: line}
            if (row) close()
            continue
        }
        if (current === null) current = {line: lineNumber, text: line}
        else current.text += `\n${line}`
    }
    close()
    return chunks
}

/**
 * The problems of one file, each `{line, message}`. [facts]: `routes` (parseServedRoutes), `tree` (buildTree, or null
 * not to check commands), `variables` (a Set, or null not to check variables). A Markdown page is read by chunks; any
 * other file line by line, for routes and variables only.
 */
export function checkFile(source, facts, {marker = DEFAULT_MARKER, markdown = true} = {}) {
    const chunks = markdown
        ? chunksOf(source, marker)
        : source.split("\n").map((text, index) => ({line: index + 1, text, marked: marker.test(text)}))
    const problems = []
    const seen = new Set()
    for (const chunk of chunks) {
        const add = (line, message) => {
            if (chunk.marked || IGNORE.test(chunk.text) || seen.has(`${line} ${message}`)) return
            seen.add(`${line} ${message}`)
            problems.push({line, message: `${message}, and nothing around it says "${marker.source}"`})
        }
        const lineOf = (needle) => chunk.line + newlines(chunk.text.slice(0, chunk.text.indexOf(needle)))
        for (const {method, path} of routesIn(chunk.text)) {
            if (!isServed(facts.routes, method, segmentsOf(path))) {
                add(lineOf(path), `${method === null ? "" : `${method} `}${path} is not served by ostrom-server main (served-routes.txt)`)
            }
        }
        if (facts.tree !== null && markdown) {
            for (const {line, words} of commandsIn(chunk)) {
                const {problems: found, comingSoon} = checkCommand(facts.tree, words)
                for (const message of found) add(line, message)
                if (found.length === 0 && comingSoon) add(line, `\`${words.join(" ")}\` is coming soon in the binary`)
            }
        }
        if (facts.variables !== null) {
            for (const name of variablesCited(chunk.text)) {
                if (!isKnownVariable(facts.variables, name)) add(lineOf(name), `${name} is read by none of the sources`)
            }
        }
    }
    const front = /^---\n([\s\S]*?)\n---\n/.exec(source)
    if (markdown && front !== null && /^status:\s*(coming-soon|partial)\s*$/m.test(front[1]) && !marker.test(source.slice(front[0].length))) {
        problems.push({line: 1, message: `the page's status says a part is not shipped, but its text never says "${marker.source}"`})
    }
    return problems
}
