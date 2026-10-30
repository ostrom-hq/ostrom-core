// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.actions

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The average a command may take, in µs: 50 on the JVM (the brief's CI bound), looser on native debug builds. */
internal expect val MAX_AVERAGE_MICROS: Long

/** The CLI hook has 15 ms for a tool call: the classifier must take a small part of it. */
class PerformanceTest {

    private companion object {
        const val WARM_UP_ROUNDS = 2
        const val MEASURED_ROUNDS = 5
    }

    private val templates = listOf(
        "git push origin main", "git -C /work/core push origin HEAD:main", "git status", "git log --oneline -20",
        "git commit -m 'fix: the thing && more'", "cd /work && git push -u origin feature/%d", "ls -la src/%d",
        "kubectl -n production apply -f api-%d.yaml", "kubectl get pods -n staging",
        "helm upgrade api ./chart --set x=%d",
        "gh pr merge %d --squash", "gh api -X PUT repos/o/r/pulls/%d/merge", "npm test -- --runInBand", "pnpm install",
        "docker build -t api:%d .", "./gradlew :core:test --tests X%d", "grep -rn 'TODO' src | head -%d",
        "echo \"\$(git rev-parse HEAD)\" > sha-%d.txt", "bash -c 'cd /x && make build-%d'",
        "cat <<EOF > f%d\nhello\nEOF",
        "terraform plan -out p%d", "eas build --profile production", "rails db:migrate VERSION=%d",
        "curl -s https://api.github.com/repos/o/r/pulls/%d", "python3 - <<'PY'\nprint(%d)\nPY",
    )

    @Test
    fun tenThousandVariedCommandsAreClassifiedFast() {
        val release = DeclaredAction("custom-release", patterns = listOf("\\bmake release\\b"))
        val classifier = ActionClassifierImpl(listOf(release))
        val commands = (0 until 10_000).map { templates[it % templates.size].replace("%d", it.toString()) }
        val git = GitStateReader { GitState("main", GitConfig.of("remote.origin.url" to "git@github.com:o/core.git")) }
        // Warm the JIT up on the whole set, then keep the fastest of a few rounds: CI runs this test next to native
        // compilations, and a round slowed down by them says nothing about the classifier.
        repeat(WARM_UP_ROUNDS) { commands.forEach { command -> classifier.classify(ToolSubject.shell(command), git) } }
        var classes = 0
        val average = (1..MEASURED_ROUNDS).minOf {
            val start = TimeSource.Monotonic.markNow()
            for (command in commands) classes += classifier.classify(ToolSubject.shell(command), git).actions.size
            start.elapsedNow().inWholeMicroseconds / commands.size
        }
        assertTrue(classes > 0)
        assertTrue(average < MAX_AVERAGE_MICROS, "average $average µs per command, bound $MAX_AVERAGE_MICROS µs")
    }
}
