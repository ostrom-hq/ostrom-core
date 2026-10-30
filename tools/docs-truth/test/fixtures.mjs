// SPDX-License-Identifier: Apache-2.0
// Help texts in the shape Clikt prints them, and a fake binary that answers `ostrom <path…> --help` with them.

export const HELP = {
    root: [
        "Usage: ostrom [<options>] <command> [<args>]...",
        "",
        "Options:",
        "  --version        Print the version",
        "  --server=<text>  Ostrom server URL",
        "  -h, --help       Show this message and exit",
        "",
        "Commands:",
        "  claude   Start Claude Code with your team's harness",
        "  harness  The harness of your team: rules, hooks,",
        "           skills (coming soon)",
        "  login    Sign in",
        "  help",
        "",
        "Run ostrom help plumbing for the commands agents use.",
    ].join("\n"),
    claude: [
        "Usage: ostrom claude [<options>] [<agent-args>]...",
        "",
        "  Start Claude Code",
        "",
        "Options:",
        "  --repo=<text>  Repository (else read from origin)",
        "  --cloud        Run the session in Ostrom's cloud, attached to this",
        "                 terminal (coming soon)",
        "  --dry-run      Check the launch",
        "  -h, --help     Show this message and exit",
        "",
        "Arguments:",
        "  <agent-args>  Passed to the agent as they are",
    ].join("\n"),
    harness: [
        "Usage: ostrom harness [<options>] <command> [<args>]...",
        "",
        "Options:",
        "  -h, --help  Show this message and exit",
        "",
        "Commands:",
        "  list  Harnesses applied to this repository (coming soon)",
    ].join("\n"),
    "harness list": "Usage: ostrom harness list [<options>]\n\nOptions:\n  --available  The library\n  -h, --help   Show this message and exit\n",
    login: "Usage: ostrom login [<options>]\n\nOptions:\n  --no-browser  Print the link\n  -h, --help    Show this message and exit\n",
    help: "Usage: ostrom help [<topic>]\n\nOptions:\n  -h, --help  Show this message and exit\n",
    wait: "Usage: ostrom wait [<options>] <id>\n\nOptions:\n  --timeout=<int>  Seconds\n  -h, --help       Show this message and exit\n",
    hook: "Usage: ostrom hook [<options>] <event>\n\nOptions:\n  -h, --help  Show this message and exit\n",
    plumbing: [
        "Used by agents and their hooks (hidden from ostrom --help):",
        "  ostrom wait <id> [--timeout S]           wait for a yes",
        "  ostrom hook gate|session                 answer an agent hook",
        "  ostrom check <node>                      run a workflow check",
        "  ostrom login                             also in ostrom --help",
    ].join("\n"),
}

/** `run(args)` of a binary whose help is [HELP]; `ostrom check` is listed as plumbing but has no help of its own. */
export function fakeBinary({plumbing = true} = {}) {
    return (args) => {
        if (args[0] === "help" && args[1] === "plumbing") {
            if (!plumbing) throw new Error("exit 64")
            return HELP.plumbing
        }
        const path = args.slice(0, -1).join(" ")
        const help = path === "" ? HELP.root : HELP[path]
        if (help === undefined) throw new Error(`exit 64: ${path}`)
        return help
    }
}
