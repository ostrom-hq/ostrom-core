#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Tests of scripts/served-routes.py, on small Kotlin trees written to a temporary folder (no build, no network).

    python3 scripts/test_served_routes.py

Runs the tests under the standard library's `trace` and fails when a line of served-routes.py is not run: the script
says what the contract may call `served`, so every line of it is tested.
"""
import importlib.util
import io
import pathlib
import runpy
import sys
import tempfile
import trace
import unittest

SCRIPT = pathlib.Path(__file__).resolve().with_name("served-routes.py")


def load():
    spec = importlib.util.spec_from_file_location("served_routes", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


served = load()

ROUTING = "import io.ktor.server.routing.*\nimport io.ktor.server.resources.*\n"


def tree(files):
    """A checkout with the given files under modules/<name>/src/commonMain/kotlin/."""
    root = pathlib.Path(tempfile.mkdtemp(prefix="served-routes-"))
    for name, text in files.items():
        module, _, file = name.partition("/")
        path = root / "modules" / module / "src/commonMain/kotlin" / file
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    return root


def run(files):
    out, err = io.StringIO(), io.StringIO()
    served.main([str(tree(files))], out, err)
    return out.getvalue().split(), err.getvalue()


def routes(files):
    lines = run(files)[0]
    return {" ".join(lines[i:i + 2]) for i in range(0, len(lines), 2)}


class ServedRoutesTest(unittest.TestCase):
    def test_nested_resources_take_the_path_of_each_parent(self):
        groups = '''
@Resource("/api/v1/groups")
class GroupsApi {
    @Resource("{id}")
    class Id(val parent: GroupsApi = GroupsApi(), val id: String) {
        @Resource("members/{login}")
        class Member(val parent: Id, val login: String) {
            @Resource("pause")
            class Pause(val parent: Member)
        }
    }
}
'''
        handlers = ROUTING + '''
fun Route.groupRoutes() {
    get<GroupsApi> { call.respond(1) }
    get<GroupsApi.Id> { params -> call.respond(1) }
    put<GroupsApi.Id.Member> { params -> call.respond(1) }
    post<GroupsApi.Id.Member.Pause> { params -> call.respond(1) }
}
'''
        self.assertEqual(routes({"sharing/GroupsApi.kt": groups, "sharing/GroupRoutes.kt": handlers}), {
            "GET /api/v1/groups", "GET /api/v1/groups/{}", "PUT /api/v1/groups/{}/members/{}",
            "POST /api/v1/groups/{}/members/{}/pause"})

    def test_route_blocks_nest_their_paths(self):
        text = ROUTING + '''
fun Route.things() {
    route("/api/v1/things") {
        get { call.respond(1) }
        route("{id}") {
            get { call.respond(1) }
            delete { call.respond(1) }
            route("/parts") {
                post("{part}") { call.respond(1) }
            }
        }
        put("/") { call.respond(1) }
    }
    get("/api/v1/after") { call.respond(1) }
}
'''
        self.assertEqual(routes({"things/Routes.kt": text}), {
            "GET /api/v1/things", "GET /api/v1/things/{}", "DELETE /api/v1/things/{}",
            "POST /api/v1/things/{}/parts/{}", "PUT /api/v1/things", "GET /api/v1/after"})

    def test_a_typed_resource_inside_a_route_block_is_under_its_path(self):
        text = ROUTING + '''
@Resource("items")
class ItemsApi
fun Route.items() {
    route("/api/v1/shop") {
        authenticate("session") {
            get<ItemsApi> { call.respond(1) }
        }
    }
    get<ItemsApi> { call.respond(1) }
}
'''
        self.assertEqual(routes({"shop/Routes.kt": text}), {"GET /api/v1/shop/items", "GET /items"})

    def test_a_path_built_from_string_constants_and_a_verb_imported_under_another_name(self):
        text = '''import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.post as routePost
const val INFERENCE_BASE: String = "/api/v1/inference"
private const val V = "v1"
fun Route.gateway() {
    bodyLimit(32) {
        routePost("$INFERENCE_BASE/${V}/messages") { infer() }
    }
    routeGet("$INFERENCE_BASE/v1/models") { call.respond(1) }
    routeGet(INFERENCE_BASE) { call.respond(1) }
    routePost("$UNKNOWN/x") { infer() }
    routePost(notAConstant) { infer() }
}
'''
        lines, err = run({"gateway/GatewayRoutes.kt": text})
        self.assertEqual(" ".join(lines), "GET /api/v1/inference GET /api/v1/inference/v1/models "
                         "POST /api/v1/inference/v1/messages")
        self.assertIn("UNKNOWN is not a string constant", err)
        self.assertIn("UNRESOLVED path notAConstant", err)

    def test_a_route_that_only_answers_405_is_not_served_and_sockets_are_gets(self):
        text = ROUTING + '''import io.ktor.server.routing.delete as routeDelete
import io.ktor.server.websocket.webSocket
fun Route.mcp() {
    route("/api/v1/mcp/{server}") {
        post { relay() }
        get { call.respond(HttpStatusCode.MethodNotAllowed) }
        routeDelete { call.respond(HttpStatusCode.MethodNotAllowed) }
        webSocket("stream") { send() }
    }
    route("/api/v1/legacy", HttpMethod.Patch) { handle { call.respond(1) } }
    route { get("/api/v1/bare") { call.respond(1) } }
}
'''
        self.assertEqual(routes({"mcp/Routes.kt": text}), {
            "POST /api/v1/mcp/{}", "GET /api/v1/mcp/{}/stream", "PATCH /api/v1/legacy", "GET /api/v1/bare"})

    def test_health_paths_from_a_list_and_a_constant(self):
        text = ROUTING + '''
val HEALTH_PATHS = listOf("/healthz", "/livez")
const val READY_PATH = "/readyz"
fun Route.health() {
    for (path in HEALTH_PATHS) get(path) { call.respond(1) }
    get(READY_PATH) { call.respond(1) }
}
'''
        self.assertEqual(routes({"kernel/Health.kt": text}), {"GET /healthz", "GET /livez", "GET /readyz"})

    def test_client_calls_json_builders_and_koin_lookups_are_not_routes(self):
        client = ROUTING + '''
const val OPENAI = "https://api.openai.com/auth"
class HttpApi(val client: HttpClient) {
    suspend fun send() = client.post("$OPENAI/token") { setBody(1) }
    fun json() = buildJsonObject { put("code", 1); put("/tmp", "rw") }
}
'''
        module = '''
class Module { fun wire() = single { Thing(get<Config>(), get(OPENAI)) } }
'''
        lines, err = run({"identity/HttpApi.kt": client, "identity/Module.kt": module})
        self.assertEqual(lines, [])
        self.assertIn("UNRESOLVED GET Config", err)

    def test_a_resource_is_found_in_its_module_then_anywhere_and_an_ambiguous_name_stops(self):
        api = '''
@Resource("/api/v1/a")
class AApi
'''
        other = '''
@Resource("/api/v1/b")
class Outer {
    @Resource("x")
    class AApi(val parent: Outer)
}
'''
        handlers = ROUTING + "fun Route.r() { get<AApi> { } }\n"
        # AApi and Outer.AApi in the same module: the exact name wins.
        self.assertEqual(routes({"one/Api.kt": api, "one/Other.kt": other, "one/Routes.kt": handlers}),
                         {"GET /api/v1/a"})
        # Only in another module: found anywhere.
        self.assertEqual(routes({"two/Api.kt": api, "one/Routes.kt": handlers}), {"GET /api/v1/a"})
        # Two candidates in one pool, neither of them the exact name: ambiguous.
        twice = '''
@Resource("/api/v1/c")
class P {
    @Resource("y")
    class AApi(val parent: P)
}
@Resource("/api/v1/d")
class Q {
    @Resource("z")
    class AApi(val parent: Q)
}
'''
        with self.assertRaises(SystemExit):
            run({"three/Api.kt": twice, "three/Routes.kt": handlers})

    def test_the_command_line_prints_the_routes(self):
        root = tree({"x/Routes.kt": ROUTING + 'fun Route.r() { get("/api/v1/x") { } }\n'})
        argv, stdout = sys.argv, sys.stdout
        sys.argv, sys.stdout = [str(SCRIPT), str(root)], io.StringIO()
        try:
            with self.assertRaises(SystemExit) as done:
                runpy.run_path(str(SCRIPT), run_name="__main__")
            printed = sys.stdout.getvalue()
        finally:
            sys.argv, sys.stdout = argv, stdout
        self.assertEqual(done.exception.code, 0)
        self.assertEqual(printed, "GET /api/v1/x\n")


def executable(path):
    """The line numbers of the code objects compiled from `path`, docstrings left out (they never run as a line)."""
    code = compile(path.read_text(), str(path), "exec")
    out, todo = set(), [code]
    while todo:
        c = todo.pop()
        out |= {line for (_, _, line) in c.co_lines() if line}  # None: no line; 0: the RESUME of 3.11+
        todo += [k for k in c.co_consts if isinstance(k, type(code))]
    return out


def main():
    tracer = trace.Trace(count=1, trace=0)
    result = tracer.runfunc(lambda: unittest.main(module=__name__, exit=False, argv=[sys.argv[0]]).result)
    if not result.wasSuccessful():
        return 1
    # Newer Pythons also count events without a line (None, or 0 for RESUME): only real lines are compared.
    ran = {line for (name, line) in tracer.results().counts
           if line and pathlib.Path(name).resolve() == SCRIPT}
    lines = executable(SCRIPT)
    missing = sorted(lines - ran)
    if missing:
        print(f"served-routes.py: lines not run by the tests: {missing}", file=sys.stderr)
        return 1
    print(f"served-routes.py: {len(lines)} lines, all run by the tests")
    return 0


if __name__ == "__main__":
    sys.exit(main())
