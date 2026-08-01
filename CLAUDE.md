# CLAUDE.md

**Read [AGENTS.md](AGENTS.md) before doing any work in this repository.** It is the full
contract for agents here; this file is the always-loaded summary of the parts that are
most often got wrong.

This repo is one mod in the **bertie** modpack family, published under the
[`bertie-mc`](https://github.com/bertie-mc) org. NeoForge **1.21.1**, JDK **21**.

## Non-negotiables

1. **GitHub is the source of truth.** Finish every task with `git add -A`, commit,
   `git pull --rebase origin main`, `git push`. `git status` must be clean when you stop.
   If you could not push, say so explicitly — never finish silently with unpushed work.
2. **Never vendor jars.** Dependencies resolve from public mavens (Modrinth, NeoForged,
   Architectury, FTB). No `files("libs/…")`, no `'../other-mod/libs/…'`, no reading a
   sibling project's `build/libs`. A fresh clone on a clean machine must build.
3. **Do not move the toolchain.** NeoForge 21.1.217 / ModDevGradle 2.0.134 / Gradle 8.14.4 (Nixpkgs `gradle_8`) /
   JDK 21, Minecraft 1.21.1 only. Modrinth and FTB version strings are not semver — a
   "newer" version is often a different Minecraft version.
4. **Worktrees must not dangle.** If you create one, remove it. `git worktree list` must
   show only the main checkout before you finish. See AGENTS.md §5.
5. **Release = bump `mod_version`, commit, tag `vX.Y.Z`, push the tag.** The pack consumes
   GitHub Releases. Never hand-copy a jar into an instance or into the pack.
6. **Third-party assets must be recorded in `NOTICE`** and excluded from the Unlicense
   dedication. No LICENSE file upstream means all rights reserved, not permission.

## Conventions

Conventional Commits (`feat:`, `fix:`, `ci:`, `chore:`…). Say what you did **not** verify.
On NeoForge, mixins in the `client`/`server` sub-arrays of a `*.mixins.json` silently never
apply — put them in the common `mixins` array.

Full detail, including the worktree cleanup procedure and the release checklist:
**[AGENTS.md](AGENTS.md)**.
