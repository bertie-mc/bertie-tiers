> [!IMPORTANT]
> Development has moved to [`bertie-mc/bertie`](https://github.com/bertie-mc/bertie/tree/main/mods/bertie-tiers). This repository is retained read-only for historical tags, releases, and issues.

# Bertie Tiers

One authoritative, data-driven mining-tier system for the Bertie modpack.
Minecraft 1.21.1 / NeoForge 21.1.x / Java 21. Internal to Bertie — no public API, no other MC versions.

For every block listed in its config, Bertie Tiers decides whether the player gets the drops, and that
answer beats vanilla tool tags, another mod's mining level, a parallel tier system such as
Slag n' Embers, and any "only this pickaxe works" check. Blocks that are **not** listed are left
completely alone.

---

## The config file

`config/bertie_tiers.json` — written on first server start, hand-edited afterwards.

```json
{
  "tiers": [
    {
      "id": "stone",
      "level": 200,
      "tools": [ { "item": "minecraft:stone_pickaxe" } ],
      "blocks": [ "minecraft:iron_ore", "minecraft:deepslate_iron_ore" ]
    },
    {
      "id": "iron",
      "level": 300,
      "tools": [ "minecraft:iron_pickaxe" ],
      "blocks": [ "minecraft:diamond_ore" ]
    }
  ],
  "exceptions": [
    {
      "tool": { "item": "minecraft:stone_pickaxe" },
      "can_mine": [ "minecraft:diamond_ore" ]
    }
  ]
}
```

| field | meaning |
| --- | --- |
| `tiers[].id` | unique name, free text |
| `tiers[].level` | unique integer; higher mines lower. Ship them spaced (100, 200, …) so a new tier fits between two old ones without renumbering |
| `tiers[].tools` | items at this level. A bare string is shorthand for `{"item": "..."}` |
| `tiers[].blocks` | blocks that require this level. A `BlockItem` ID is accepted and converted to its block |
| `exceptions[]` | `tool → specific blocks`, always granted |
| `_comment` | allowed and ignored at every level |

### The decision order

1. A point exception naming this exact tool **and** this exact block → **allow**.
2. Otherwise, both in tiers → allow when `tool level >= block level`.
3. Otherwise, block is listed but the tool is in no tier → **deny**.
4. Otherwise, the block is not in the system → **Bertie does not interfere**.

An exception grants only the blocks it names. A stone pickaxe with an exception for diamond ore mines
diamond ore and nothing else in that tier.

"Can mine" means *gets the normal drops*. Dig speed, tool durability, block hardness, loot tables,
Fortune, Silk Touch and XP are untouched. A denied block still breaks normally, it just yields nothing —
the same semantics as swinging a wooden pickaxe at iron ore in vanilla.

Note the flip side of rule 3: **any tool you do not list cannot harvest any block you do list.**
That is the point of the system, but it means adding an ore also means adding every tool that should
be able to mine it.

---

## Telling apart tools that share one item ID

Slag n' Embers assembles every modular tool into a single `slag:modular_item`; what makes it an iron
pickaxe lives in its data components. Any tool entry may therefore carry an optional `components`
predicate:

```json
{
  "item": "slag:modular_item",
  "components": {
    "slag:modular_type": "slag:pickaxe",
    "slag:dynamic_parts": [
      { "components": { "slag:part_type": "slag:pickaxe_head", "slag:material_type": "slag:iron" } }
    ]
  }
}
```

The rule is generic — nothing about Slag is compiled in. Each key names a data-component type; that
component's own persistent codec encodes the stack's real value to JSON, and your JSON must be a
**subset** of it:

* primitive → must be equal;
* object → every key you wrote must match, extra keys are ignored;
* array → every element you wrote must match *some* actual element.

So the same mechanism works for any mod's components.

When several matchers for one item match a stack, the one that pinned down more values wins. Two
equally specific matchers in different tiers are rejected at load time unless they provably cannot
both match (a differing scalar somewhere).

---

## Commands

All at permission level 2.

```bash
/bertietiers reload
```

Re-reads the file. Parse → validate → index → publish happen in that order against a fresh object, so
a **rejected reload leaves the previous ruleset running untouched** and says so. Errors quote the exact
JSON path, e.g. `tiers[2].tools[0].item: unknown item 'minecraft:stone_pick'`.

```bash
/bertietiers status
```

File path, tier count, controlled block count, tool/exception counts.

```bash
/bertietiers explain minecraft:iron_ore
```

Why your **currently held** item can or cannot mine that block: the block's level, the tool's level,
and which rule fired. This is the fastest way to answer "why did that not drop".

```bash
/bertietiers dump
```

Writes the whole resolved table to the server log.

---

## What ships in the default config

* **All 19 vanilla ore blocks**, each at the tier vanilla itself requires (checked against
  `data/minecraft/tags/block/needs_{stone,iron,diamond}_tool.json` in the 1.21.1 client jar):
  * `wood` (100) — coal ore, deepslate coal ore, nether gold ore, nether quartz ore
  * `stone` (200) — copper, iron, lapis (+ deepslate)
  * `iron` (300) — gold, redstone, diamond, emerald (+ deepslate)
  * `diamond` (400) — ancient debris
  * `netherite` (500) — nothing yet; the top of the ladder, ready for post-netherite ores
* **All 6 vanilla pickaxes.** Gold sits in `wood` because that is where vanilla puts it
  (`Tiers.GOLD` → `#minecraft:incorrect_for_gold_tool`, which has the wooden set).
* **All 17 Slag n' Embers materials × 6 pickaxe-capable shapes = 102 matchers**, each at the tier
  Slag itself gives that material (`AllMaterials.setTier`), mapped onto the same ladder. Slag compares
  `getTier(stack) > i + 0.5` against the `#minecraft:incorrect_for_*_tool` tags, which makes its
  1–2 → wood, 3 → stone, 4 → iron, 5 → diamond, 6 → netherite.

Entries whose namespace has no installed mod are skipped at load with one summary line in the log, so
this file is valid with **or** without Slag n' Embers.

---

## How the override is made final

`ServerPlayerGameMode#destroyBlock` computes `boolean flag1 = blockstate.canHarvestBlock(...)` once and
uses it twice: it is passed to `onDestroyedByPlayer` as `willHarvest`, and it gates the
`Block#playerDestroy` call that rolls the loot table. `ServerPlayerGameModeMixin` redirects that single
invocation. It is the narrowest point that keeps both consumers consistent, and it sits strictly
downstream of everything that could disagree:

* vanilla's `Tool` component and `#incorrect_for_*_tool` tags;
* NeoForge's `PlayerEvent.HarvestCheck`;
* a mod's own `Block#canHarvestBlock` override — which never fires that event;
* Slag's `ModularToolsItem#isCorrectToolForDrops`, which computes its own float tier.

A second listener on `PlayerEvent.HarvestCheck` (lowest priority, logical server only) applies the same
verdict for callers that consult the harvest check outside the vanilla break path. Both hooks delegate
to `MiningAuthority` — the rules exist in exactly one place.

Everything runs on the logical server, so single-player and dedicated behave identically and the client
is never asked to decide who gets loot.

---

## Known limitations

Stated plainly rather than claimed away.

1. **Tool class is not checked.** The system is purely level-based, exactly as specified. If you list a
   Slag *shovel* in a tier, it will harvest that tier's ores. The shipped config therefore lists only
   pickaxe-capable Slag shapes (`pickaxe`, `prybar`, `mallet`, `hammer`, `maul`, `paxel`); axes, shovels,
   hoes and swords are deliberately absent. Add them only if you also want them mining ore.
2. **Slag's averaged tier is approximated by the head material.** Slag computes
   `getTier(stack)` as an average over the tool's *dynamic* parts. Rods are plain sticks and are not
   dynamic parts, so for a single-head shape (pickaxe) the average is exactly the head material and the
   config is exact. For a multi-head shape (hammer, paxel, …) built from **mixed** materials, Slag would
   average the heads while Bertie judges it by its pickaxe head. Uniform-material tools are exact.
3. **Tools are matched by item ID, not by tag.** That is what makes the "no matcher may ambiguously
   belong to two tiers" check decidable. A large modded pack means a long tool list.
4. **Overlap disjointness has one heuristic.** `[X]` vs `[Y]` is treated as disjoint when `X` and `Y`
   are — true for "one pickaxe head per tool", not provable from JSON alone. If it is ever wrong the
   runtime still resolves deterministically (most specific wins, then the lowest level) and logs a
   warning naming both matcher paths.
5. **Client-side previews are not overridden.** Jade and friends read the harvest check on the client,
   where no config is loaded, so a tooltip may show vanilla's answer while the server enforces Bertie's.
   Loot is never affected.
6. **Non-player breaking is out of scope by design.** Explosions, machines and block breakers that do
   not run a player's harvest check are untouched, as required. A mod that mines an area by calling
   `Block#dropResources` directly instead of going through the player path would bypass this system;
   `forbidden_arcanus`'s Demolishing modifier is the one such area-breaker found in the current Bertie
   mod list and has not been individually verified.
7. **The audit is of the mods installed today.** 485 jars were scanned for `canHarvestBlock`,
   `HarvestCheck`, `isCorrectToolForDrops` and `ServerPlayerGameMode` mixins; nothing found redirects the
   same call site. A future mod that patches `ServerPlayerGameMode#destroyBlock` itself could still
   conflict — `/bertietiers explain` is the diagnostic for that.

---

## Building

```bash
gradle build
```

Runs the JUnit suite (30 tests: the decision order, the JSON predicate, every validation rule, and the
shipped config validated both with and without Slag).

```bash
gradle runGameTestServer
```

Boots a headless dedicated server and runs 14 GameTests from the test-only `gameTest`
source set. Its Java classes and `empty.nbt` fixture are excluded from the release JAR. The tests drive the real
`ServerPlayerGameMode#destroyBlock` path with a survival fake player and inspect what actually dropped:
same-tier mines, higher tier mines lower, lower tier gets nothing, an exception grants only its named
block, an unassigned tool gets nothing, an unlisted block is unchanged, vanilla tags lose in **both**
directions, another mod's harvest veto loses, Silk Touch survives with no duplicated drops, a rejected
config keeps the previous one, a tier can be inserted by data alone, and two variants of one item ID are
told apart by their components.
