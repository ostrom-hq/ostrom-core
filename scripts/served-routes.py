#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Lists the routes an ostrom-server checkout serves, read from its Kotlin sources (no build, no network): typed Ktor
resources (`@Resource` and their `parent`), the string routes (`route("…")`, `webSocket("…")`) and the health
probes. A route that only answers 405 is not served. One "METHOD /path" per line, path parameters written {}.

    python3 scripts/served-routes.py ../ostrom-server > api/contract/served-routes.txt

Then keep the `!` lines (routes on main whose shape differs from the contract, kept `planned`) and the header, and run
`npm run contract` in api-types: it fails when an operation's `x-ostrom-status` disagrees with the list.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
files = [p for p in root.rglob("*.kt") if "/src/commonMain/" in str(p)]

STRING = re.compile(r'"(?:\\.|[^"\\])*"')
COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)


def blank(text):
    """Strings and comments replaced by spaces of the same length (braces inside them do not count)."""
    def sub(m):
        return re.sub(r"[^\n]", " ", m.group(0))
    return STRING.sub(lambda m: '"' + " " * (len(m.group(0)) - 2) + '"', COMMENT.sub(sub, text))


classes = {}  # qualified name -> dict(file, module, seg, params, scope)
handlers = []  # (method, ref, file, module)
strings = []  # (method, path)

CLASS = re.compile(r"\bclass\s+([A-Z][A-Za-z0-9_]*)\s*(\(([^)]*)\))?")
RESOURCE = re.compile(r'@Resource\(\s*"([^"]*)"\s*\)')
HANDLER = re.compile(r"\b(get|post|put|patch|delete|head)<\s*([A-Z][A-Za-z0-9_.]*)\s*(?:,[^>]*)?>\s*[{(]")

for f in files:
    raw = f.read_text()
    clean = blank(raw)
    module = str(f.relative_to(root)).split("/src/")[0]
    stack = []  # (name, depth at which its body opened)
    depth = 0
    pending = None  # class whose body has not opened yet
    i = 0
    resource_at = {m.end(): m.group(1) for m in RESOURCE.finditer(raw)}
    resource_positions = sorted(resource_at)
    # walk characters, noting class declarations by position
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
                classes[qual] = dict(file=f, module=module, seg=seg, params=params, scope=scope, name=name)
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
    for m in HANDLER.finditer(clean):
        handlers.append((m.group(1).upper(), m.group(2), f, module))
    # string routes
    for m in re.finditer(r'webSocket\(\s*"([^"]+)"', raw):
        strings.append(("GET", m.group(1)))
    for m in re.finditer(r'route\(\s*"(/[^"]+)"\s*\)\s*\{', raw):
        start = m.end()
        d, k = 1, start
        while d and k < len(clean):
            d += {"{": 1, "}": -1}.get(clean[k], 0)
            k += 1
        block = raw[start:k]
        only_405 = r"(?!\s*call\.respond\(HttpStatusCode\.MethodNotAllowed\)\s*\})"
        for verb in re.findall(r"\broute(Get|Post|Put|Patch|Delete)\s*\{" + only_405, block):
            strings.append((verb.upper(), m.group(1)))
        for verb in re.findall(r"(?<![A-Za-z<])(get|post|put|patch|delete)\s*\{", block):
            strings.append((verb.upper(), m.group(1)))
    for m in re.finditer(r'\bget\(\s*"(/[^"]+)"\s*\)', raw):
        strings.append(("GET", m.group(1)))
    # get(CONSTANT) and for (path in CONSTANTS) get(path)
    for m in re.finditer(r"\bget\(\s*([A-Z_]+)\s*\)", raw):
        val = re.search(m.group(1) + r'\s*(?::\s*String)?\s*=\s*"([^"]+)"', raw)
        if val:
            strings.append(("GET", val.group(1)))
    for m in re.finditer(r"for\s*\(\s*(\w+)\s+in\s+([A-Z_]+)\s*\)\s*get\(\s*\1\s*\)", raw):
        val = re.search(m.group(2) + r"\s*(?::[^=]+)?=\s*(?:listOf|setOf)\(([^)]*)\)", raw)
        if val:
            for p in re.findall(r'"([^"]+)"', val.group(1)):
                strings.append(("GET", p))


def resolve(ref, file, module, scope=()):
    """The resource class a type reference names, looked up from the innermost scope outwards, then the file, the
    module and everything."""
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


def path_of(q, seen=()):
    c = classes[q]
    parent = None
    for _, typ in c["params"]:
        r = resolve(typ, c["file"], c["module"], c["scope"] + [c["name"]])
        if r is not None and r != q:
            parent = r
            break
    seg = c["seg"].strip("/")
    if parent is None:
        return "/" + seg
    base = path_of(parent, seen + (q,))
    return base.rstrip("/") + ("/" + seg if seg else "")


def norm(p):
    return re.sub(r"\{[^}]*\}", "{}", p.rstrip("/") or "/")


out = set()
missing = []
for method, ref, f, module in handlers:
    q = resolve(ref, f, module)
    if q is None:
        missing.append(f"{method} {ref} ({f.relative_to(root)})")
        continue
    out.add(f"{method} {norm(path_of(q))}")
for method, p in strings:
    out.add(f"{method} {norm(p)}")
for line in sorted(out):
    print(line)
for m in missing:
    print("UNRESOLVED", m, file=sys.stderr)
