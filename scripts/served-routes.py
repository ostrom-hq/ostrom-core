#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Lists the routes an ostrom-server checkout serves, read from its Kotlin sources (no build, no network): typed Ktor
resources (`@Resource` and their `parent`), the string routes (`route("…") { … }` nested at any depth, `get("…")`,
`webSocket("…")`, a verb imported under another name, a path built from string constants) and the health probes. A
handler inside `route("…") { … }` is under its path, typed resources included. A route that only answers 405 is not
served. One "METHOD /path" per line, path parameters written {}; what cannot be resolved goes to stderr.

    python3 scripts/served-routes.py ../ostrom-server > api/contract/served-routes.txt

Then keep the `!` lines (routes on main whose shape differs from the contract, kept `planned`) and the header, and run
`npm run contract` in api-types: it fails when an operation's `x-ostrom-status` disagrees with the list.

Its tests: `python3 scripts/test_served_routes.py` (every line covered, checked by the same file).
"""
import pathlib
import re
import sys

STRING = re.compile(r'"(?:\\.|[^"\\])*"')
COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
CLASS = re.compile(r"\bclass\s+([A-Z][A-Za-z0-9_]*)\s*(\(([^)]*)\))?")
RESOURCE = re.compile(r'@Resource\(\s*"([^"]*)"\s*\)')
HANDLER = re.compile(r"\b(get|post|put|patch|delete|head)<\s*([A-Z][A-Za-z0-9_.]*)\s*(?:,[^>]*)?>\s*[{(]")
VERBS = ("get", "post", "put", "patch", "delete", "head")
SOCKETS = ("webSocket", "sse")
# `import io.ktor.server.routing.get as routeGet`: the alias is the verb.
ALIAS = re.compile(r"^import\s+io\.ktor\.server\.(?:routing|websocket|sse)\.(\w+)\s+as\s+(\w+)", re.M)
ROUTING_IMPORT = re.compile(r"^import\s+io\.ktor\.server\.(?:routing|resources|websocket|sse)\b", re.M)
CONSTANT = re.compile(r'\bval\s+([A-Z][A-Z0-9_]*)\s*(?::\s*String\s*)?=\s*"((?:\\.|[^"\\])*)"')
CONSTANT_LIST = re.compile(r"\bval\s+([A-Z][A-Z0-9_]*)\s*(?::[^=]+)?=\s*(?:listOf|setOf)\(([^)]*)\)")
TEMPLATE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")
ONLY_405 = re.compile(r"\s*call\.respond\(\s*HttpStatusCode\.MethodNotAllowed\s*\)\s*")
FOR_IN = r"for\s*\(\s*{}\s+in\s+([A-Z][A-Z0-9_]*)\s*\)\s*$"


def blank(text):
    """Strings and comments replaced by spaces of the same length (braces inside them do not count)."""
    def sub(m):
        return re.sub(r"[^\n]", " ", m.group(0))
    return STRING.sub(lambda m: '"' + " " * (len(m.group(0)) - 2) + '"', COMMENT.sub(sub, text))


def closing(clean, start):
    """The index just after the `}` that closes the `{` at `start`."""
    d, k = 0, start
    while k < len(clean):
        d += {"{": 1, "}": -1}.get(clean[k], 0)
        k += 1
        if d == 0:
            break
    return k


def join(prefix, path):
    if not prefix:
        return path
    return prefix.rstrip("/") + ("/" + path.lstrip("/") if path.strip("/") else "")


def norm(p):
    return re.sub(r"\{[^}]*\}", "{}", p.rstrip("/") or "/")


class Scan:
    def __init__(self, root):
        self.root = root
        self.files = sorted(p for p in root.rglob("*.kt") if "/src/commonMain/" in str(p))
        self.classes = {}  # qualified name -> dict(file, module, seg, params, scope, name)
        self.handlers = []  # (method, ref, file, module, prefix)
        self.strings = []  # (method, path)
        self.unresolved = []
        self.constants, self.lists = {}, {}
        for f in self.files:
            raw = f.read_text()
            for m in CONSTANT.finditer(raw):
                self.constants.setdefault(m.group(1), m.group(2))
            for m in CONSTANT_LIST.finditer(raw):
                self.lists.setdefault(m.group(1), re.findall(r'"([^"]+)"', m.group(2)))
        for f in self.files:
            self.read(f)

    def expand(self, literal, f):
        """A Kotlin string literal with `$CONSTANT` or `${CONSTANT}` in it, or None when a name is not a constant."""
        missing = []

        def sub(m):
            name = m.group(1) or m.group(2)
            if name not in self.constants:
                missing.append(name)
                return ""
            return self.constants[name]
        out = TEMPLATE.sub(sub, literal)
        if missing:
            self.unresolved.append(f"path {literal!r} ({f.relative_to(self.root)}): {', '.join(missing)} is not a string constant")
            return None
        return out

    def paths(self, arg, f, clean, at):
        """The paths a routing call's first argument names: a literal, a constant, or the loop variable of
        `for (path in CONSTANTS)` right before the call."""
        arg = arg.strip()
        if arg.startswith('"'):
            p = self.expand(arg[1:-1], f)
            return [] if p is None else [p]
        if arg in self.constants:
            return [self.constants[arg]]
        loop = re.search(FOR_IN.format(re.escape(arg)), clean[:at])
        if loop and loop.group(1) in self.lists:
            return list(self.lists[loop.group(1)])
        self.unresolved.append(f"path {arg} ({f.relative_to(self.root)})")
        return []

    def read(self, f):
        raw = f.read_text()
        clean = blank(raw)
        module = str(f.relative_to(self.root)).split("/src/")[0]
        self.read_classes(f, raw, clean, module)
        if ROUTING_IMPORT.search(raw):
            self.read_routes(f, raw, clean, module)
        else:
            for m in HANDLER.finditer(clean):
                self.handlers.append((m.group(1).upper(), m.group(2), f, module, ""))

    def read_classes(self, f, raw, clean, module):
        stack = []  # (name, depth at which its body opened)
        depth = 0
        pending = None  # class whose body has not opened yet
        i = 0
        resource_at = {m.end(): m.group(1) for m in RESOURCE.finditer(raw)}
        resource_positions = sorted(resource_at)
        class_matches = {m.start(): m for m in CLASS.finditer(clean)}
        while i < len(clean):
            c = clean[i]
            if i in class_matches:
                m = class_matches[i]
                name = m.group(1)
                scope = [s[0] for s in stack]
                qual = ".".join(scope + [name])
                # the closest @Resource before this class, with only annotations/whitespace between
                seg = None
                before = [p for p in resource_positions if p <= i]
                if before:
                    between = raw[before[-1]:i]
                    if re.fullmatch(r"(\s|@[A-Za-z0-9_.]+(\([^)]*\))?|public|internal|data|private)*", between):
                        seg = resource_at[before[-1]]
                params = re.findall(r"\bva[lr]\s+([a-zA-Z0-9_]+)\s*:\s*([A-Z][A-Za-z0-9_.]*)", raw[m.start():m.end()])
                if seg is not None:
                    self.classes[qual] = dict(file=f, module=module, seg=seg, params=params, scope=scope, name=name)
                # does a body follow?
                rest = clean[m.end():m.end() + 400]
                body = re.match(r"[^{}\n;]*?\{", rest.replace("\n", " "))
                pending = (name, m.end()) if body and "class" not in rest[:body.end()] else None
                i = m.end()
                continue
            if c == "{":
                depth += 1
                if pending is not None and i >= pending[1]:
                    stack.append((pending[0], depth))
                    pending = None
            elif c == "}":
                if stack and stack[-1][1] == depth:
                    stack.pop()
                depth -= 1
            i += 1

    def read_routes(self, f, raw, clean, module):
        """Walks the file's braces, keeping the paths of the enclosing `route("…") { … }` blocks."""
        names = {v: v for v in VERBS + SOCKETS + ("route",)}
        for verb, alias in ALIAS.findall(raw):
            if verb in names:
                names[alias] = verb
        call = re.compile(r"(?<![\w.])(" + "|".join(map(re.escape, names)) + r")\s*(?:\(([^()]*)\))?\s*\{")
        calls = {m.start(): m for m in call.finditer(clean)}
        typed = {m.start(): m for m in HANDLER.finditer(clean)}
        prefixes = []  # (path, depth at which its body opened)
        depth = 0
        i = 0
        while i < len(clean):
            prefix = prefixes[-1][0] if prefixes else ""
            if i in typed:
                m = typed[i]
                self.handlers.append((m.group(1).upper(), m.group(2), f, module, prefix))
            if i in calls:
                m = calls[i]
                kind, arg = names[m.group(1)], m.group(2)
                brace = m.end() - 1
                if arg is not None and arg.strip():
                    first = arg.split(",")[0]
                    span = raw[m.start(2):m.start(2) + len(first)]
                    paths = [join(prefix, p) for p in self.paths(span, f, clean, m.start())]
                else:
                    paths = [prefix] if kind != "route" else []
                if kind == "route":
                    verb = re.search(r"HttpMethod\.(\w+)", arg or "")
                    if verb:
                        kind = verb.group(1).lower()
                    elif paths:
                        depth += 1
                        prefixes.append((paths[0], depth))
                        i = brace + 1
                        continue
                if kind != "route" and paths and paths[0]:
                    body = clean[brace + 1:closing(clean, brace) - 1]
                    if kind in SOCKETS or not ONLY_405.fullmatch(body):
                        method = "GET" if kind in SOCKETS else kind.upper()
                        self.strings.extend((method, p) for p in paths)
            c = clean[i]
            if c == "{":
                depth += 1
            elif c == "}":
                if prefixes and prefixes[-1][1] == depth:
                    prefixes.pop()
                depth -= 1
            i += 1

    def resolve(self, ref, file, module, scope=()):
        """The resource class a type reference names, looked up from the innermost scope outwards, then the file, the
        module and everything."""
        classes = self.classes
        for k in range(len(scope), -1, -1):
            q = ".".join(list(scope[:k]) + [ref])
            if q in classes and classes[q]["file"] == file:
                return q
        for pool in (
            [q for q in classes if classes[q]["file"] == file],
            [q for q in classes if classes[q]["module"] == module],
            list(classes),
        ):
            hits = [q for q in pool if q == ref or q.endswith("." + ref)]
            if len(hits) == 1:
                return hits[0]
            if len(hits) > 1:
                exact = [q for q in hits if q == ref]
                if len(exact) == 1:
                    return exact[0]
                raise SystemExit(f"ambiguous {ref} in {file}: {hits}")
        return None

    def path_of(self, q):
        c = self.classes[q]
        parent = None
        for _, typ in c["params"]:
            r = self.resolve(typ, c["file"], c["module"], c["scope"] + [c["name"]])
            if r is not None and r != q:
                parent = r
                break
        seg = c["seg"].strip("/")
        if parent is None:
            return "/" + seg
        return self.path_of(parent).rstrip("/") + ("/" + seg if seg else "")

    def routes(self):
        out = set()
        for method, ref, f, module, prefix in self.handlers:
            q = self.resolve(ref, f, module)
            if q is None:
                # Koin's get<T>() looks like a typed handler: only a report, never a route.
                self.unresolved.append(f"{method} {ref} ({f.relative_to(self.root)})")
                continue
            out.add(f"{method} {norm(join(prefix, self.path_of(q)))}")
        for method, p in self.strings:
            out.add(f"{method} {norm(p)}")
        return sorted(out)


def main(argv, out=sys.stdout, err=sys.stderr):
    scan = Scan(pathlib.Path(argv[0]))
    for line in scan.routes():
        print(line, file=out)
    for m in scan.unresolved:
        print("UNRESOLVED", m, file=err)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
