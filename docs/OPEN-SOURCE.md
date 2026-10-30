# How the Ostrom repositories are run in the open

Every repository of the `ostrom-hq` organization (core, cli, server, web, app, e2e, site, helm) follows the same
open source standard. This page records what we took from the best open source projects, and why. Studied on
30/10/2026 from shallow clones of their default branch (root files and `.github/` only):
[Supabase](https://github.com/supabase/supabase), [PostHog](https://github.com/PostHog/posthog),
[Cal.com](https://github.com/calcom/cal.com), [Biome](https://github.com/biomejs/biome),
[Bun](https://github.com/oven-sh/bun), [uv](https://github.com/astral-sh/uv) and [Ruff](https://github.com/astral-sh/ruff)
(Astral), [Tailscale](https://github.com/tailscale/tailscale), [React Email](https://github.com/resend/react-email)
(Resend), [Linear's SDK](https://github.com/linear/linear), [Dub](https://github.com/dubinc/dub) and
[Zed](https://github.com/zed-industries/zed).

The engineering rules themselves (architecture, Kotlin, tests, CI) are in [CONVENTIONS.md](CONVENTIONS.md). This page
is about the repository as a public product.

## What every repository has

| File | Content | Taken from |
| --- | --- | --- |
| `README.md` | Banner (light and dark through `<picture>`), the locked value proposition, badges, three-command start, a Mermaid architecture diagram, honest status | PostHog and Supabase (banner, links row), uv (one sentence then proof), Biome (status honesty) |
| `LICENSE` | Exact SPDX text, detected by GitHub: Apache-2.0 (core, cli, e2e, site), AGPL-3.0 (server, web, app, helm) | all |
| `ee/LICENSE`, `LICENSING.md` | server, web and app, once their `ee/` exists: commercial license of `ee/` and the table of what is under which license | PostHog and GitLab (`ee/` model), Cal.com |
| `NOTICE` | Apache-2.0 repositories only, "The Ostrom Authors" | Apache Software Foundation practice |
| `CONTRIBUTING.md` | Setup, the test bar, Conventional Commits, DCO, license headers, AI policy, releases | uv (concise), Biome (AI disclosure), PostHog (AI policy) |
| `CODE_OF_CONDUCT.md` | Contributor Covenant 2.1, verbatim, contact `conduct@ostrom.run` | Biome, Bun, Zed, Cal.com |
| `SECURITY.md` | Private reporting (`security@ostrom.run` and GitHub advisories), deadlines, scope written for Ostrom, safe harbor | Cal.com and Supabase (scope lists), Bun (acknowledgement deadline), Tailscale (coordinated disclosure) |
| `GOVERNANCE.md`, `MAINTAINERS.md` | Roles, lazy consensus, the open core stewardship promise | Biome (roles), GitLab (buyer-based open core, "we never move an open feature into ee") |
| `SUPPORT.md` | Where to ask what | Supabase, PostHog |
| `CHANGELOG.md` | Written by release-please, never by hand. Not in ostrom-core nor ostrom-helm, which already publish without tags: core's `build.yml` publishes `0.1.<run number>` on every push to `main`, helm's `release.yml` publishes the chart when `Chart.yaml` changes version. A release-please tag would name a version no artifact carries; release-please arrives there with tag-based publication | uv, Ruff, Biome |

## `.github/`

| What | Choice | Why |
| --- | --- | --- |
| Issue forms | YAML forms (bug, feature, question), blank issues off, links to security, docs and discussions | uv, Bun, Tailscale: structured reports are reproducible reports |
| Discussion forms | `ideas` and `q-a` categories | Zed |
| Pull request template | What changes for a person, why, how it was tested, checklist, **Agent context** | PostHog (problem first, agent context), Biome (AI disclosure). Ostrom sells trust in agent work: we disclose ours |
| `CODEOWNERS` | `@ostrom-hq/maintainers` on everything, `.github/` and `LICENSE` explicit | Supabase, Bun, Ruff |
| Dependencies | Renovate (`config:best-practices`, actions pinned by digest, 3-day release age, grouped minor updates, dependency dashboard) plus Dependabot security alerts | uv, Ruff, Biome, Zed, PostHog use Renovate; the release age floor is uv's `cargo-cooldowns` idea |
| Labels | Declared in `.github/labels.yml`, synced by a workflow, brand colours (garnet is kept for "a person has to decide") | Biome |

## Workflows

All third party actions are pinned by commit SHA with the version in a comment, `permissions: {}` at the top (Scorecard: `read-all`,
as its action requires) and the minimum per job, `persist-credentials: false` on every checkout, `timeout-minutes` on every job. These are the rules
of [zizmor](https://docs.zizmor.sh) and of the [OpenSSF Scorecard](https://scorecard.dev), followed by Linear, uv,
Ruff, Supabase and Tailscale.

The organization has 19 concurrent jobs for every repository together, so a pull request costs one job of these
workflows (`community`), none on a draft, and everything else runs weekly or by hand (orchestrator, 04:15).

| Workflow | Check name | When | What it does |
| --- | --- | --- | --- |
| `community.yml` | `community` | every pull request, not drafts | One job. The title is a Conventional Commit, since with squash merge it is the commit on `main` and the changelog line (Resend, Cal.com); every commit of a contributor without write access is signed off (Developer Certificate of Origin instead of a CLA, like GitLab since 2017; collaborators with write, maintain or admin permission and bots are exempt, a read-only organization member is checked); every source file carries its `SPDX-License-Identifier`, the right one for its path (`ee/` is `LicenseRef-Ostrom-Enterprise`), `LICENSE` exists, and `ee/LICENSE` wherever `ee/` exists (Tailscale's `license_test.go`); zizmor reads the workflows (Linear, uv, Supabase, Tailscale) |
| `links.yml` | `links` | weekly, by hand | lychee checks every Markdown link (external links break without any commit) |
| `codeql.yml` | `analyze (actions)`, plus `analyze (javascript-typescript)` in web, app, e2e and site | weekly, by hand | CodeQL `security-extended` on the workflows and the TypeScript code. Kotlin is not analysed: CodeQL needs a JVM build of Kotlin and our product is native |
| `scorecard.yml` | `analysis` | weekly, by hand, once public | OpenSSF Scorecard. On a private repository `GITHUB_TOKEN` cannot read what it needs, so the job is skipped until `OSTROM_PUBLIC` is `true` |
| `release-please.yml` | `release-please` | by hand | All repositories but ostrom-core and ostrom-helm (see `CHANGELOG.md` above). Opens or updates the release pull request from the Conventional Commit titles; merging it and running the workflow again tags the bare version (CONVENTIONS.md section 12), writes the changelog and creates the release. No release pull request is merged before Lyes's go (orchestrator, 06:05) |
| `labels.yml` | `labels` | when `.github/labels.yml` changes on `main` | Syncs the labels |

The SPDX check covers every source file (`scope: all` in `.github/spdx.json`) in cli, server, site, helm and e2e;
in core, web and app it still covers only the files a pull request adds (`scope: added`) until their existing files
get a header, when no other pull request is touching them. Generated code is excluded per repository (`exclude`).

### Exceptions to close before going public

zizmor runs at `high` severity in every pull request. These temporary exceptions live in each repository's
`.github/zizmor.yml`; none survives the launch. Each owner removes its line in the pull request that fixes the
workflow.

| Repository | File | Finding | Owner | Fix |
| --- | --- | --- | --- | --- |
| ostrom-web | `.github/workflows/image.yml` | `cache-poisoning`: Docker build cache in a workflow that also runs on release tags | DEPLOY | no cache on tag runs, or a separate release job |
| ostrom-app | `.github/workflows/ci.yml` | `cache-poisoning`: npm cache in a workflow that also runs on release tags | APP | same |
| ostrom-web, ostrom-app | `.github/workflows/ci.yml` | `unpinned-uses`: actions by tag (`@v4`, `@v5`), against CONVENTIONS.md section 11 | WEB, APP (QA template) | pin each action by commit SHA |
| ostrom-e2e | `.github/workflows/ci.yml` | `cache-poisoning`: dependency cache, QA template of the single `ci` job | QA | same, in the template too |
| ostrom-e2e | `.github/actions/*/action.yml` | `template-injection`: inputs expanded inside `run:` | QA | pass each input through `env:` |
| ostrom-site | `.github/workflows/ci.yml`, `docs.yml` | `cache-poisoning`: dependency cache in workflows that also run on release tags | SITE | no cache on tag runs |

### Before the launch

Everything stays private until Lyes says go. One organization variable switches the public behaviour on:
**`OSTROM_PUBLIC`** (`false` today). Set to `true` at launch, it makes CodeQL upload its results to GitHub code
scanning, turns Scorecard on (results in code scanning, badge data published; add the Scorecard badge to the READMEs then). The CLI release is cut by its own tag-triggered workflow (DEPLOY), which needs a tag pushed by the release GitHub App,
not by `GITHUB_TOKEN`. Until the launch the CodeQL SARIF reports are kept as workflow artifacts and an
error-level CodeQL alert fails the job. Also at launch: remove the two "until the public launch" lines of
`.lycheeignore`, and turn on GitHub private vulnerability reporting.

## Repository settings

Description and topics set, homepage `https://ostrom.run`, Discussions on, wiki off (the docs live on the site),
squash merge only with the pull request title as the commit message, branches deleted after merge, Dependabot
security alerts on. Visibility is never changed by an agent. Required checks on `main` are set by the QA owner of
the branch protection.

## What we did not take

- **A CLA** (Cal.com's old one, Supabase's): the DCO is lighter for contributors and enough for an Apache/AGPL split.
- **Bots that comment on every pull request** (welcome messages, stale closers, AI reviewers): noise for a young
  project. A stale workflow can come when the issue volume needs it.
- **Shields.io badges that need a public repository** for counts (contributors, downloads): added at launch, when
  they mean something.
- **A `.github` organization repository for shared health files**: it must be public to apply, and nothing is public
  yet. Each repository carries its own copy, generated from the same source.
