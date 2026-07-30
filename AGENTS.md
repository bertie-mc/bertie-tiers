# AGENTS.md

Instructions for AI coding agents working in this repository. Humans should read it too —
everything here is a real constraint, not a style preference.

This repo is one mod in the **bertie** modpack family, published under the
[`bertie-mc`](https://github.com/bertie-mc) GitHub organisation.

---

## 1. The single most important rule

**This repository on GitHub is the source of truth — not your local working copy.**

Work that is only on disk does not exist. It is invisible to every other agent and to
every other machine, and the modpack cannot consume it. Before you finish a task:

```bash
git add -A
git commit -m "type(scope): what changed"
git pull --rebase origin main
git push origin main
```

`git status` must be clean when you stop. If you cannot push, say so explicitly in your
final message — do not finish silently with unpushed commits.

**Always rebase, never merge, when pulling.** `git pull --rebase` keeps history linear.
If the rebase conflicts, resolve it; do not `git pull --no-rebase` to make the conflict
go away.

---

## 2. Never vendor jars

Compile dependencies are resolved from **public mavens** — Modrinth, NeoForged,
Architectury, FTB. They are never committed and never read from a local directory.

```gradle
repositories {
    maven { url = "https://api.modrinth.com/maven" }
}
dependencies {
    compileOnly "maven.modrinth:forbidden-arcanus:2.6.1"
}
```

**Do not** do any of these. Each one has broken CI in this org before:

| Anti-pattern | Why it breaks |
|---|---|
| `compileOnly files("libs/foo.jar")` | `libs/` is gitignored — CI has no such file |
| `compileOnly files('../other-mod/libs/foo.jar')` | depends on a sibling checkout existing |
| `fileTree(dir: '../other/build/libs')` | depends on someone having just built that mod |

If you need another **bertie** mod on the classpath, take it from its GitHub Release:

```gradle
repositories {
    ivy {
        url = "https://github.com/bertie-mc"
        patternLayout { artifact "/[organisation]/releases/download/v[revision]/[module]-[revision].jar" }
        metadataSources { artifact() }
        content { includeGroup "bertie-s1" }
    }
}
dependencies { compileOnly "bertie-s1:bertie_s1:0.21.1" }
```

**Test for whether you got it right:** could a fresh clone on a machine that has never
seen this project run `./gradlew build` successfully? If not, the dependency is wrong.

---

## 3. Toolchain — do not "upgrade" it

| | |
|---|---|
| Minecraft | **1.21.1** only |
| Loader | **NeoForge 21.1.217** |
| ModDevGradle | **2.0.134** |
| Gradle | **8.8** |
| JDK | **21** |

These are pinned across every bertie mod and move in lockstep or not at all. Dependabot
is configured to ignore them for exactly this reason. Do not bump the Gradle wrapper,
ModDevGradle, or the Minecraft/NeoForge version as a side effect of another task.

**Minecraft-ecosystem version strings are not semver.** `maven.modrinth:*`,
`dev.ftb.mods:*` and `dev.architectury:*` coordinates encode the Minecraft version, so a
"newer" version is often a jar for a different Minecraft version entirely. Never bump one
because a tool said it was out of date.

---

## 4. Releasing

1. Bump `mod_version` in `gradle.properties`
2. Commit
3. Tag and push:

```bash
git tag -a v1.2.3 -m "Release v1.2.3" && git push origin v1.2.3
```

`release.yml` builds the jar and attaches it to a GitHub Release. **That release is how
the modpack consumes this mod.** Never hand a built jar to the pack or drop one into a
Minecraft instance — see §6.

Wait for the release workflow to go green before reporting the release as done.

---

## 5. Worktrees — when several agents share this repo

If another agent may be working in this repository at the same time, or you need to work
on something that would conflict with the current checkout, **use a worktree**:

```bash
git worktree add ../<repo>-<task> -b <task-branch>
cd ../<repo>-<task>
# ... do the work, commit ...
git push -u origin <task-branch>
```

Then integrate and **clean up**:

```bash
cd <main checkout>
git fetch origin
git rebase origin/<task-branch>      # or merge the branch, or open a PR
git push origin main
git worktree remove ../<repo>-<task>
git branch -d <task-branch>
git worktree prune
```

### Dangling worktrees are forbidden

**Before you finish, `git worktree list` must show only the main checkout.** A left-over
worktree locks branches, confuses the next agent, and quietly consumes disk. If you
created one, you remove it — even if the task failed or was abandoned.

```bash
git worktree list            # verify: exactly one entry
git worktree prune           # clears records of manually deleted dirs
```

If `git worktree remove` refuses because the tree is dirty, deal with the changes
(commit them to the branch and push, or discard them deliberately) — then remove it.
Do not use `--force` to throw away work you have not looked at.

---

## 6. Do not sidetrack from the GitHub workflow

These shortcuts feel faster and each one has caused a real incident:

- **Do not copy a built jar into a Minecraft instance or into the packwiz pack.** A mod
  that is not in a pack's `index.toml` does not exist for players or for a fresh install,
  no matter what is sitting in an instance folder. The pack consumes GitHub Releases.
- **Do not edit another repository from this one.** If a change belongs to another mod,
  make it in that mod's repo and release it.
- **Do not delete or rewrite another agent's work** to make your own change apply. If you
  find modifications you did not make, stop and report them.
- **Do not leave `git status` dirty** and describe the task as complete.
- **Do not skip CI.** If the build fails, fix it or report it. Never `--no-verify`, never
  force-push over a failing state.

---

## 7. Licensing — check before adding any asset

Most bertie mods are dedicated to the public domain under **The Unlicense**. That
dedication only covers original work.

- **Never add a texture, model, sound or data file taken from another mod** without
  recording it. If a file is derived from someone else's work, it must be credited in
  `NOTICE` and explicitly excluded from the dedication.
- Writing JSON into another mod's namespace (`data/othermod/recipe/...`) is fine — those
  are your own files targeting their IDs.
- Copying their actual assets is not.
- Some upstream mods publish **no licence at all**, which means all rights reserved. Absence
  of a LICENSE file is not permission.

If you are unsure whether something is redistributable, do not add it — ask.

---

## 8. Conventions

- **Conventional Commits** — `feat:`, `fix:`, `docs:`, `ci:`, `chore:`, `refactor:`,
  `build:`. Scope optional: `fix(forge): …`.
- Match the surrounding code style. There is no formatter to run.
- Mixins: on NeoForge, mixins listed in the `client`/`server` **sub-arrays** of a
  `*.mixins.json` are **not reliably applied** and fail silently. Put client-targeting
  mixins in the common `mixins` array and guard with `@Mod(dist = CLIENT)` or a
  client-only target class. "Mixin applies" ≠ "effect visible" — verify the result.
- State what you did **not** verify. An unverified claim must be written as unverified.

---

## 9. Before you report a task complete

```
[ ] git status is clean
[ ] committed with a conventional-commit message
[ ] rebased on origin/main and pushed
[ ] git worktree list shows only the main checkout
[ ] ./gradlew build passes, or the failure is reported
[ ] no jar committed, no libs/ dependency introduced
[ ] any third-party asset added is recorded in NOTICE
[ ] if released: tag pushed and the release workflow went green
```
