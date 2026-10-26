# CLAUDE.md — PA9 Texture Builder

Knowledge base for future Claude runs on this project. Read this first.

## What this is
A **client-side Fabric mod** for **Minecraft Java 26.2** that, while toggled ON, weighted-randomly
switches the active hotbar slot immediately before each block placement and restores it right
after, so manual building produces blended textures (e.g. 70/20/10 stone-brick variants). Built
2026-08-06 from `../PA9_TextureBuilder_ModRequirements_v1.docx` (SRS v1.0); FR/NFR/PKT/TC IDs
refer to that document. Structure and conventions deliberately mirror the author's ShulkerPickBlock
and ContainerSort projects (`../../Shulker Pick Block/shulker-pick-block`,
`../../Chest Sort/containersort`).

## 1.21.1 backport (branch `1.21.1`, verified 2026-10-15)
This branch targets **Minecraft 1.21.1 / Fabric API 0.116.17+1.21.1 / Java 21**; the 26.x notes
below describe the `main`/`26.3-Release` branches and stay as history. Behaviour is unchanged.
- **Toolchain:** Loom 1.17.21 via plugin id **`net.fabricmc.fabric-loom-remap`** (1.21.1 is
  obfuscated), `mappings loom.officialMojangMappings()` so source stays Mojmap, `modImplementation`
  / `modCompileOnly`, Loader 0.19.5 (build) with a `>=0.15.11` runtime floor (Fabric API
  0.116.17's own floor), Mod Menu 11.0.3. No JDK 21 is installed on this machine, so the build
  keeps the **JDK 25 toolchain with `options.release = 21`** (Java 21 bytecode, major 65, and
  Java 21 API only); mixin `compatibilityLevel` is `JAVA_21`. Output is `remapJar` →
  `build/libs/texture-builder - 1.21.1 - 1.0.1.jar`; the unremapped jar goes to `build/devlibs`
  with a `-dev` classifier. No refmap — Loom rewrites mixin targets to intermediary in place.
- **API mapping 26.x → 1.21.1:** `minecraft.gui.setScreen` → `minecraft.setScreen`;
  `GuiGraphicsExtractor.text` → `GuiGraphics.drawString`; `HudElementRegistry` (Fabric API 1.21.6+)
  → `HudRenderCallback` `(GuiGraphics, DeltaTracker)`; `KeyMappingHelper.registerKeyMapping` →
  `KeyBindingHelper.registerKeyBinding` with category string `key.categories.gameplay`;
  `ClientCommands` → `ClientCommandManager`; `Inventory.get/setSelectedSlot` → public field
  `selected`; `CycleButton.builder(fn, initial)` → `builder(fn).withInitialValue(v)`;
  direct-restock fallback `handlePickItemFromBlock(pos)` → vanilla survival pick
  `handlePickItem(inventorySlot)`. Unlike 1.20.1, `HeaderAndFooterLayout.addTitleHeader` and
  `LinearLayout.horizontal().spacing()` exist in 1.21.1 and are unchanged.
- **Recipe-book reposition hook:** 1.21.1 has no `onRecipeBookButtonClick()`; the toggle logic is the
  button lambda `method_19891` (unnamed in Mojmap, so the same name in dev and production) —
  injected with `remap = false`. `InventoryScreen` extends `EffectRenderingInventoryScreen` here.
- **Verified:** dev `runClient` and two production launches via a temporary Loom
  `ClientProductionRunTask` (real Fabric Loader 0.19.5 + remapped jar + Fabric API 0.116.17) reach
  the title screen. A temporary probe force-loaded `InventoryScreen`/`CreativeModeInventoryScreen`/
  `MultiPlayerGameMode` (production: `class_490`/`class_481`/`class_636`) with all mixins applied.
  The final, probe-free jar was launched on the launcher-bundled Java 21.0.7 runtime
  (`java-runtime-delta`) to the title screen. Not verified: in-world gameplay.

## Build Environment Findings (verified 2026-08-06)

- **Toolchain (SRS §2 pins, all verified to exist and build):** Loader 0.19.3, Fabric API
  0.155.2+26.2, Loom `1.17-SNAPSHOT` (resolved 1.17.18), Gradle wrapper 9.5.1, JDK 25 (Temurin
  25.0.3 installed). `gradlew.bat build` succeeds directly; output
  `build/libs/texture-builder - 26.2 - 1.0.0.jar` (modpack naming convention
  `<name> - <mc_version> - <mod_version>.jar`, set via `jar.archiveFileName`).
- **Mappings: none.** The SRS says "Yarn", but no Yarn/intermediary exists for 26.x — 26.x jars
  ship **Mojmap names already baked in** (same empirical finding as ShulkerPickBlock's CLAUDE.md).
  There is **no `mappings` line in build.gradle**; do not add one. All code uses Mojmap names.
- **API names verified via `javap` against the Loom-cached 26.2 deobf jars**
  (`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-*-deobf/26.2/`), then by a
  clean compile:
  - `MultiPlayerGameMode.useItemOn(LocalPlayer, InteractionHand, BlockHitResult)` — single
    overload, declared on the class itself. This is the SRS's "`ClientPlayerInteractionManager.
    interactBlock`".
  - `MultiPlayerGameMode.handlePickItemFromBlock(BlockPos, boolean)`,
    `handleCreativeModeItemAdd(ItemStack, int)` (hotbar slot i = screen slot 36+i),
    `getPlayerMode()`.
  - `Inventory.getSelectedSlot()/setSelectedSlot(int)/getItem/setItem`, `INVENTORY_SIZE`,
    `SELECTION_SIZE`; `ServerboundSetCarriedItemPacket`.
  - Screens open via **`minecraft.gui.setScreen(...)`** (`Minecraft.setScreen` doesn't exist in
    26.x). HUD text drawn via Fabric `HudElementRegistry` + `GuiGraphicsExtractor.text(...)`
    (`displayClientMessage` is absent from the 26.x mapping — same approach as ShulkerPickBlock).
  - **`EditBox.setFilter` was removed in 26.2** (the one compile error hit). Replaced with
    `setMaxLength(4)` + digit-stripping inside `setResponder` (re-entrant `setValue` settles once
    clean). `EditBox.addFormatter(TextFormatter)` exists if richer filtering is ever needed.
  - In 26.2 concrete screens (`InventoryScreen` etc.) **no longer declare `init()`**;
    `AbstractContainerScreen` is the narrowest owner. See mixin notes below.
  - `KeyMapping.Category` constants: MOVEMENT/MISC/MULTIPLAYER/GAMEPLAY/INVENTORY/CREATIVE/
    SPECTATOR/DEBUG. `KeyMapping.setKey`, static `resetMapping()`, `InputConstants.getKey(String)`.

### ⚠️ Mixin lesson: `@Shadow` does NOT resolve inherited FIELDS (crash, 2026-08-07)
Shipping a mixin on `InventoryScreen` that `@Shadow`ed `leftPos`/`topPos`/`imageWidth` crashed the
game at class-load (exit code 1) with:
```
InvalidMixinException: @Shadow field leftPos was not located in the target class
  net.minecraft.client.gui.screens.inventory.InventoryScreen
  at MixinPreProcessorStandard.attachFields(...)
```
Those fields are declared on `AbstractContainerScreen`, not on `InventoryScreen`.
**`@Shadow` on a field requires the field to be declared on the target class itself — Mixin does
not walk the superclass hierarchy for fields.** (The earlier revision "worked" only because it
targeted `AbstractContainerScreen`, where they *are* declared.)
**Fix:** declare the superclass on the mixin instead and drop `@Shadow` entirely —
`@Mixin(InventoryScreen.class) abstract class X extends AbstractContainerScreen<InventoryMenu>` —
then the fields are genuinely inherited, javac resolves them, and the JVM looks them up normally.
The mixin's declared superclass only has to be *somewhere* in the target's hierarchy, and this is
safe as long as the mixin never calls `super.*`. A dummy constructor matching the superclass is
needed for javac; Mixin discards mixin constructors.
**Note:** a clean `gradlew build` does NOT catch this — mixin targets are resolved at class-load,
not compile time. Verify by launching (see "How to verify a mixin actually applies" below).

### How to verify a mixin actually applies (without playing the game)
`gradlew build` succeeding proves nothing about mixin binding. `gradlew runClient` only loads a
target class when something touches it (e.g. `InventoryScreen` loads when the inventory opens). To
force it, temporarily add to `TextureBuilderClient.onInitializeClient`:
```java
try { Class.forName("net.minecraft.client.gui.screens.inventory.InventoryScreen"); }
catch (Throwable t) { LOGGER.error("load FAILED", t); }
```
then `gradlew runClient` and grep `run/logs/latest.log` for `Mixin apply for mod texturebuilder`
/ `InvalidMixinException`. Remove the temp block before the release build.
Real logs for the user's install live in `%APPDATA%\.minecraft\logs\` (`latest.log`, plus rotated
`*.log.gz`); an early mixin crash produces a suspiciously small (~4KB) gz log.

## Architecture (all under `src/client/java/com/yourname/texturebuilder/`)
- `TextureBuilder` — mod id / name / logger constants, `HOTBAR_SIZE`.
- `TextureBuilderClient` — `ClientModInitializer`; loads config, session ON/OFF state (FR-03:
  session-only, seeded from `enabled`), toggle keybind (FR-01; unbound default; TOML
  `toggle_keybind` applied on **first tick** only if still unbound, so options.txt/Controls
  rebinds always win), NFR-04 `failSession` kill switch, HUD element + tick wiring, commands.
- `placement/PlacementRandomizer` — the core. `beforePlacement` (mixin HEAD): only acts when ON,
  main hand, and the held item is empty-or-BlockItem (tools/food stay vanilla); draws via helper,
  switches slot + sends `ServerboundSetCarriedItemPacket` (PKT-01). `afterPlacement` (mixin TAIL):
  restock if the pick emptied its slot, then restore in a `finally` **only if
  `restore_slot_after_placement`**. Restock
  (FR-13): creative = local refill + `handleCreativeModeItemAdd` (FR-18); survival = vanilla
  `handlePickItemFromBlock` at the placed pos (resolved as face-adjacent, else clicked pos for
  replaceables), fired **only when a match exists in main inventory 9–35** (FR-19) and **before**
  the restore packet so the server pick lands in the still-selected empty slot; no match anywhere
  → "No more X found" message (FR-15). A match only in another hotbar slot: no pick (vanilla would
  just switch selection, which restore undoes) and no message.
- `util/TextureBuilderHelper` — pure-Java weighted selection + normalization (NFR-12). Verified
  standalone: 100k draws on 70/20/10 → 69.95/20.13/9.92; excluded slots never drawn; degenerate
  pools → -1.
- `config/ModConfig` — flat hand-parsed TOML (`config/texturebuilder.toml`), house style; the
  `slots` array is one line of inline tables `{index, included, weight}` parsed by regex (SRS §6
  shape). Clamps duration 500–5000ms. Reload via `/texturebuilder reload` (FR-21).
- `gui/TextureBuilderConfigScreen` — vanilla-widgets-only (HeaderAndFooterLayout + GridLayout);
  9 rows: slot label / live item **name** (refreshed in `tick()`; `GuiGraphicsExtractor` has no
  simple item-icon call, so FR-07's "preview" is the name) / Included **CycleButton (colored, see
  below)** / **weight slider** / effective-% column (`auto_normalize_display`). Total line green
  at 100, else red +
  "normalizes at runtime" (FR-09, never blocks closing). Edits hit the live config immediately;
  TOML written in `onClose()` by every route (FR-10). Parent screen restored on close (FR-06).
  **Weight sliders (changed 2026-08-07 at the user's request, from `EditBox` text entry):**
  `WeightSlider extends AbstractSliderButton` (inner class), mapping the slider's 0.0-1.0 `value`
  onto `ModConfig.WEIGHT_MIN..WEIGHT_MAX` (0-100). This required **bounding the weight range**,
  which the old free-text field did not have — weights are now clamped to 0-100 in
  `ModConfig.load()` too. Spec-aligned: FR-07 calls them "weight (percentage)" and FR-09 frames the
  total around 100; only *ratios* matter (FR-11 normalizes), so a 100:1 ceiling costs nothing real.
  `applyValue()` writes through to the live config and calls `refreshDerived()`, because changing
  one slot's weight changes every other slot's normalized share. Column widths were rebalanced to
  294px + 8px spacing = **302px, deliberately under the 320px minimum scaled GUI width** Minecraft
  guarantees, so the row cannot overflow; the 100px weight column makes the slider ~1 unit/pixel.
  `refreshDerived()`/`refreshItemNames()` are null-guarded since sliders are constructed before the
  label widgets they update. 26.2 API confirmed by `javap`: `AbstractSliderButton(int,int,int,int,
  Component,double)`, `protected double value`, abstract `updateMessage()`/`applyValue()`, and
  `keyPressed` arrow-key stepping once Enter/Space arms `canChangeValue`.
- `mixin/client/MultiPlayerGameModeMixin` — `useItemOn` HEAD+TAIL. HEAD is `cancellable = true` since
  v1.1.0 and cancels (returns `FAIL`) **only** for blocked Gradient placement (see Gradient mode);
  otherwise non-cancelling (PKT-02).
- `mixin/client/InventoryScreenMixin` — inventory **TB** button (FR-05). Targets **`InventoryScreen`**
  (`init`, `onRecipeBookButtonClick`, `containerTick`, all TAIL), exactly as SRS §7.2 prescribes.
  **Correction (2026-08-06):** an earlier revision targeted `AbstractContainerScreen.init` on the
  belief that concrete screens no longer declare `init()` in 26.2. That is true of
  `ContainerScreen`/`ShulkerBoxScreen` (ContainerSort's targets) but **NOT of `InventoryScreen`,
  which does declare `init()`** — verified by `javap`. Targeting `InventoryScreen` directly is
  strictly better: NFR-11 satisfied, no `instanceof` guard needed, and **no shared target method
  with ContainerSort, so NFR-09 is now satisfied in letter as well as spirit.**
  **Button placement** (moved 2026-08-06 at the user's request, from the top-right corner to the
  empty panel space beneath the crafting output): 24x12 at `(leftPos + imageWidth - 30, topPos + 64)`
  → GUI-relative x 146-170, y 64-76. Verified clear against the 26.2 bytecode: result slot
  `(154, 28)` 16x16 [`InventoryMenu.addResultSlot`], recipe book button `(leftPos + 104, height/2 - 22)`
  20x18 = y 61-79 [`InventoryScreen.getRecipeBookButtonPosition`], shield slot `(77, 62)`, player
  inventory rows from y 84. `InventoryScreen.isBiggerResultSlot()` returns **false**, so the result
  slot is never enlarged here.
  **⚠️ `leftPos` moves — the position MUST be re-asserted, not set once.** Opening the recipe book
  shifts the panel right, and `leftPos` is reassigned in exactly two places (both verified in
  bytecode), neither of which an `AbstractContainerScreen.init` TAIL hook can see:
  1. `AbstractRecipeBookScreen.init()` calls `super.init()` **first**, *then* assigns
     `leftPos = recipeBookComponent.updateScreenPosition(...)` and calls `initButton()`. This was
     the reported bug — the button rendered ~72px left of target (over the player model) whenever
     the recipe book was open. Injecting at `InventoryScreen.init` TAIL fixes it because
     `InventoryScreen.init()`'s last `return` (offset 74) is *after* its `super.init()` call.
  2. The recipe book toggle button's own `onPress` lambda re-assigns `leftPos`, repositions its own
     button, then calls `onRecipeBookButtonClick()` — it **does not re-run `init()`**, so a
     create-time-only placement goes stale on every toggle. Hence the `onRecipeBookButtonClick`
     TAIL hook (`InventoryScreen` overrides it; the override just sets `buttonClicked = true`).
  `containerTick` TAIL is a cheap per-tick safety net for any path not enumerated above.

### Toggle/Included coloring: malilib's SUCCESS/ERROR convention (added 2026-08-07)
The ON/OFF toggle-hotkey message and the config screen's Included/Excluded label are colored green
(`ChatFormatting.GREEN`, `§a`) / red (`ChatFormatting.RED`, `§c`), at the user's request to match
"the conventional coloring... that Litematica uses for toggles". Litematica has no local color
constants of its own for this — it is built on **malilib**, whose message system defines
`Message.MessageType.SUCCESS`/`ERROR`/`WARNING`/`INFO`, each keyed to a lang-file formatting code.
**Confirmed directly from the installed `malilib-fabric-26.2-0.29.2.jar`'s
`assets/malilib/lang/en_us.json`:** `success = "§a"` (green), `error = "§c"` (red),
`warning = "§6"` (gold), `info = "§f"` (white) — i.e. exactly `ChatFormatting.GREEN`/`RED` in
Mojmap. (Could not trace the exact Litematica call site that maps ON→SUCCESS/OFF→ERROR — its
compiled classes don't reference `MessageType` by that literal string, likely because the mapping
happens through a shaded/relocated malilib copy — but the color *values* are unambiguous from
malilib's own lang file, and green-for-on/red-for-off is the universal convention regardless.)
- `TextureBuilderClient.toggle()` — lang key changed from two fixed strings
  (`message.texturebuilder.on`/`.off`) to one parameterized `message.texturebuilder.toggle` =
  `"Texture Builder: %s"`, with a colored `text.texturebuilder.on`/`.off` Component passed as the
  `%s` argument — the same "Component into a translatable's `%s` slot" pattern already used for
  `message.texturebuilder.no_more`'s item name.
- `TextureBuilderConfigScreen.includedToggle()` — replaced `CycleButton.onOffBuilder` (vanilla's
  fixed gray/white "On"/"Off") with the generic `CycleButton.<Boolean>builder(Function, initial)`
  factory so the label Component itself carries the color; reuses the same
  `text.texturebuilder.on`/`.off` keys as the toggle message for one source of truth.
  **Note:** `InventoryScreen.init()` swaps creative players to `CreativeModeInventoryScreen` and
  returns early, so this mixin's button never appears in creative — `CreativeModeInventoryScreenMixin`
  covers that screen.
- `mixin/client/CreativeModeInventoryScreenMixin` — the same **TB** button on the creative
  inventory's Survival Inventory tab (added 2026-10-11 at the user's request, v1.0.1). Targets
  `CreativeModeInventoryScreen` `init` / `selectTab` / `containerTick`, all TAIL, all declared on
  the class itself and each with a single `return` (26.3 bytecode). Tab switches don't re-run
  `init()`, so `selectTab` TAIL toggles `button.visible = isInventoryOpen()`; `init()` calls
  `selectTab` before the button exists (null guard). Placement: 24x12 at
  `(leftPos + imageWidth - 49, topPos + 28)` → GUI-relative x 146-170, y 28-40 on the 195-wide
  panel, right edge flush with the last inventory column. Clear of the inventory-tab layout in
  `selectTab`: armor slots `(54 + 54*col, 6 + 27*row)`, offhand `(35, 20)`, inventory rows from
  y 54, destroy slot `(173, 112)`. Fabric API's creative-tab mixin targets the same class; no
  conflict seen at class-load.
- `hud/TextureBuilderHud` — self-expiring above-hotbar text (toggle confirmations FR-02, restock
  misses FR-15); duration = `restock_message_duration_ms`.
- `command/TextureBuilderCommands` — `/texturebuilder config|reload|status` via
  `fabric-command-api-v2`; `config` defers `gui.setScreen` through `Minecraft.execute`.
- `compat/modmenu/ModMenuIntegration` — `modmenu` entrypoint (FR-22); Mod Menu is `compileOnly`
  (`20.0.0-beta.4`, Terraformers maven), never loaded when absent.

### Gradient mode (added 2026-10-22, v1.1.0, branch `Gradient`)
A second slot-selection mode: the slot is picked from **where** the block goes along a straight
X, Y or Z line between two saved points, so a build fades from one block to the next. The original
weighted-random mode is now called **Texture mode** (renamed at the user's request, same day) and
behaves exactly as before. Only the "which slot" decision in `PlacementRandomizer.beforePlacement`
differs; the held-item guard, restock (Pick Block / `RestockWatch`) and
`restore_slot_after_placement` are shared.
- `util/GradientHelper` — pure Java, no MC deps (like `TextureBuilderHelper`). `selectAxis` (largest
  |d|, **any tie → `AXIS_NONE`**, including identical points), `positionT` (clamped, either
  direction), `selectIndex(t, boundaries[], transition, random)`, `moveHandle` (keeps `MIN_GAP` =
  0.02 to neighbours/ends), `evenBoundaries`, `isValid`, `blendHalfWidth`, `segmentShare`.
  **Blend math:** boundary k's half-width = `transition × min(left seg, right seg) / 2`, so it is at
  most half the smaller adjacent segment and zones never overlap; P(right entry) rises linearly
  0 → 0.5 → 1 across `[b-hw, b+hw]`; outside zones the band's entry is certain. At 100% every
  boundary fades from the middle of one band to the middle of the next.
  **Verified standalone 2026-10-22** (54 checks, all pass): axis winner on each axis in both
  directions, X/Y, X/Z, Y/Z and 3-way ties, identical points; t clamping both directions; hard bands
  at transition 0 for every t; t=0 → entry 0 and t=1 → entry N-1 for N=2..9; N=2 zone edges;
  50/50 at each boundary (100k draws: 0.4990/0.5011/0.5001); uneven segments probed at 5 points per
  zone, 100k draws each, all within 0.01 of the linear ramp; no zone overlap at 100%; min-gap and
  zero-width segments stay in range. 2026-10-25: + `tiedAxes`/`largestDifference` (X/Z square corners,
  opposite signs, X/Y, Y/Z, cube corners → all three, clear winner → 0, identical → 0): 63 checks
  pass. The test lives outside the repo (scratchpad); recreate it with
  plain `javac` against `GradientHelper.java` if needed.
- `placement/GradientController` — keybind cycle, point state, slot pick, status line.
  `State` = NO_POINTS / OTHER_DIMENSION / START_ONLY / NO_AXIS / VALID; anything but VALID makes
  `selectSlot` return -1 and the weighted random pool decides (fallback = Texture-mode behaviour).
  Fewer than 2 gradient-included slots also blocks (added 2026-10-25 at the user's request; HUD
  "Include at least 2 slots in Gradient mode", checked after the point problems).
  **1D / 2D / 3D gradients (added 2026-10-25 at the user's request; design choices were the user's):**
  `ModConfig.gradientShape` (1-3, TOML `gradient_shape = "1d"|"2d"|"3d"`, default 1D) is cycled by
  a "Gradient: 1D/2D/3D" `CycleButton` on the Transition row ([Transition 162][shape 82][Clear
  points 95] = 343). `GradientHelper.checkAxes(shape, S, E)` returns an `AxisCheck(axes, problem,
  problemAxes, distance)`: 1D = the unique longest axis (tie → PROBLEM_TIE); **2D (lenient)** = the
  two axes that differ most, ignoring the smallest (PROBLEM_TIE if the smallest is tied, e.g. cube
  corners or dy = dz = 3; PROBLEM_TOO_FEW if only one axis differs); 3D = all three (PROBLEM_TOO_FEW
  naming the axes that are the same); identical → PROBLEM_SAME for every shape. **Position is a
  projection**: `projectT` = clamp((P−S)·(E−S)/|E−S|², 0, 1) over the shape's axes only, so 2D/3D
  fade diagonally with straight bands at right angles to the S→E line (the user picked this over
  "box corner to corner" and "rings from the start"); with one axis it equals `positionT`. Messages:
  "2D gradient unclear: Y and Z both differ by 3 blocks", "2D gradient needs the points to differ
  on two axes (only X differs)", "3D gradient needs the points to differ on X, Y and Z (Y is the
  same)"; green end-set "…, runs across X and Z (2D)"; points line "…, across X and Z (2D)" or
  "Start …, end …: <problem>". Changing the shape re-checks the stored points immediately (the
  points line refreshes every tick; placement checks on every click). Standalone suite now 92
  checks (validity per shape, 1D projection == positionT, 2D square/rectangle/reversed/ignored Y,
  3D cube). All 27 shape × point-pair messages were checked in a dev client.
  **Success confirmation (added 2026-10-25 at the user's request):** when the end point gives a
  clear axis, the HUD shows a **green** "Gradient end set: 12 64 0, runs along X (13 blocks)", the
  counterpart of the gold refusals; the length counts both endpoints (`largestDifference + 1`, so
  always ≥ 2).
  **NO_AXIS wording (changed 2026-10-25 at the user's request):** a tie is no longer reported as
  "start and end are the same". `GradientHelper.tiedAxes` returns the tied axes as a bit mask
  (0 for a clear winner *and* for identical points) and `largestDifference` the distance, so the HUD
  says "Gradient axis unclear: X and Z both differ by 12 blocks" or "...: X, Y and Z all differ
  by 12 blocks" ("1 block" singular). Only identical points keep "Gradient start and end are the
  same". Same text in the end-set HUD message, the blocked-placement warning, the config screen's
  points line ("Start …, end …: axis unclear, X and Z both differ by 12 blocks") and status.
  **Unusable points block placement (changed 2026-10-25 at the user's request):** NO_POINTS,
  START_ONLY, OTHER_DIMENSION, NO_AXIS and fewer than 2 slots no longer fall back to the Texture
  mix; `selectSlot`'s -1 → pool path is now only a safety net.
  `blockedReason()` gives a gold HUD warning ("No gradient points selected", "No gradient end point
  selected", "Gradient points are in <dim>", the NO_AXIS message below) and
  `PlacementRandomizer.beforePlacement` returns `true`, so the mixin cancels `useItemOn` at HEAD with
  `FAIL`. Cancelling at HEAD is the only way to stop the server placing: vanilla sends one
  `ServerboundUseItemOnPacket` from inside `useItemOn` and the server does both the block's own action
  and the item placement from it.
  **Chests/doors still work (user's choice):** before blocking, `blockGradientPlacement` lets the click
  through untouched (no slot switch) when the clicked block's class overrides
  `BlockBehaviour.useWithoutItem` (`hasUseAction`, reflection by Mojmap name, cached per class) and
  vanilla would run it, i.e. not `isSecondaryUseActive()` with an item in either hand (vanilla's own
  rule). Verified in a dev client 2026-10-25: true for chest, oak door, stone button, lever, crafting
  table, furnace, oak trapdoor; false for stone, grass, planks, glass, dirt. ⚠️ Blocks that override
  `useWithoutItem` but sometimes pass (e.g. redstone ore) still let the *held* block be placed.
  The mixin change was verified with the CLAUDE.md `Class.forName` launch check: target loaded, no
  mixin errors (config is `required` with `defaultRequire 1`, so a failed inject would crash).
  Placed position P is **predicted before placement** with the restock code's rule in pre-placement
  form: clicked pos if `BlockState.canBeReplaced()` (no-arg), else `clicked.relative(face)`.
  Dimension id = `level.dimension().identifier().toString()`.
  **Keybind cycle** (`key.texturebuilder.gradient_point`, unbound, GAMEPLAY): the cycle position comes
  from the stored points, so it survives restarts: no start → set start; start only → set end (HUD
  "Gradient start and end are the same" instead of "end set" when the axis is tied); both → clear.
  **Other dimension (user's choice, 2026-10-22):** if the stored points belong to another dimension,
  a press *starts fresh here*: sets a new start and drops the old end. Until then the old points are
  ignored, not deleted. Not looking at a block → HUD "Look at a block to set a gradient point" and
  the cycle does not advance (small addition, not in the spec). Every change calls `ModConfig.save()`.
- `ModConfig` — `mode = "texture"|"gradient"` (`Mode` enum `TEXTURE`/`GRADIENT`, default texture;
  **`"random"` still loads as Texture**, the pre-rename value), `gradient_included` (slot indices
  0-8, stored as `boolean[] gradientIncluded`, **separate from Texture mode's `included`**),
  `gradient_boundaries` (N-1 fractions), `gradient_transition` (0-100), `gradient_start`/
  `gradient_end` (`[x, y, z]` or `[]`), `gradient_dimension`. The gradient's blocks are
  `gradientPalette()` = the gradient-included slots **in hotbar order**, left to right = start to
  end (user's choice; no custom order, no repeated slots). `setGradientIncluded` redistributes the
  handles evenly whenever the count changes. `sanitizeGradient()` on load: boundaries that don't fit
  the included count → even (empty below 2), transition clamped, end dropped without a start.
  Defaults: slots 1 and 2 included, even split, transition 50%. String values are written quoted
  (`mode = "gradient"`); the existing `stripComment` already unquotes.
- **One config screen for both modes (changed 2026-10-22 at the user's request).** An earlier draft
  had a separate `GradientConfigScreen` reached by a "Gradient..." button, with -/+ block count and
  per-entry Slot buttons; it was **removed**. `TextureBuilderConfigScreen` now builds its contents
  from the current mode and the footer holds only **Mode** (120) + **Done** (120). The Mode button
  sets `rebuildPending`, and `tick()` calls `rebuildWidgets()`, so the click handler never removes
  its own button mid-event. No mode keybind (spec).
  - **Both modes share a 28/28 header/footer** (fixed after the user saw the title sit higher in
    Gradient mode, which then used 24 vs Texture's default 33).
  - **Both tables are exactly 343px wide, with explicit column widths** (user request: same
    horizontal footprint in both modes). 343 is what the Texture screen always *rendered*: it asked
    for 302, but its full-width total line sat inside the grid and stretched Slot 30→58 and
    Included 46→59 (see the GridLayout pitfall). Nothing spans the grid any more, so the constants
    now are the real widths. 343 fits any GUI ≥343 wide (1080p at scale 4 is 480) but not the
    320px minimum.
  - Texture mode: Slot 58 / Item 84 / Included 53 (46px button) / Weight 100 / Eff. % 40, 16px rows.
    Below the grid: the weight-total line (301) + **Reset** (40×16, under Eff. %), which sets every
    weight back to `ModConfig.DEFAULT_WEIGHT` (10, the shipped default) and leaves Included alone;
    it sets `rebuildPending` so all sliders redraw with the new value. Content 196px → 252 total.
  - Gradient mode: Slot 58 / Item 186 / Included 53 / Eff. % 40 (3×2 spacing = 343), bound to
    `gradientIncluded`. Same Slot→Item gap as Texture. Eff. % = each included slot's
    `segmentShare` (blend zones are symmetric, so that is also the expected share of blocks); "—"
    for excluded slots and for all while fewer than 2 are included; blank if
    `auto_normalize_display` is off. Below the grid: distribution bar (246) + **Reset** (95, spreads
    the handles evenly), Transition slider (246) + Clear points (95), points/axis line (10px). The
    buttons sit exactly under Included + Eff. %, so the bar and Transition end where Item ends.
    14px rows; content 214px → **270px total**, exactly the height a 1080p window gets at scale 4.
  - ⚠️ **GridLayout pitfall:** a child added with `RowHelper.addChild(w, span)` makes the grid split
    `w`'s width evenly over the spanned columns and **widen** any narrower column to match. A
    302px bar spanning 3 columns blew Slot and Included out to ~100px (the user's first
    screenshot), and Texture's 5-column total line had silently widened its table to 343px since
    v1.0. Full-width rows now live in a vertical `LinearLayout` *below* the grid in both modes.
  - Clear points saves at once; `onClose()` saves on every route.
  - **Measured in a 26.3 dev client at 1920×1080 (GUI 480×270, scale 4), 2026-10-22:** both tables
    span x=68–411. Texture: Slot 68, Item 128, Included 214, Weight 269, Eff. % 371, total line
    y=226 with Reset at x=371. Gradient: Slot 68, Item 128, Included 316, Eff. % 371; bar/Transition
    x=68 w=246, Reset/Clear x=316 w=95; points line y=232. Footer y=246 and title y=10 in both.
  - **How to measure/screenshot a screen without playing:** a temporary class ticked from
    `END_CLIENT_TICK` that, while `client.level == null`, calls `getWindow().setWindowed(1920,1080)`,
    opens the screen via `client.gui.setScreen(...)`, then `Screenshot.grab(client.gameDirectory,
    "name.png", client.gameRenderer.mainRenderTarget(), 1, msg -> {})` and logs every
    `client.gui.screen().children()` widget's x/y/w/h, then `client.stop()`. Run `gradlew runClient`;
    PNGs land in `run/screenshots/`. (26.x: the current screen is `Gui.screen()`, not
    `Minecraft.screen`.) Delete the class before the release build.
- `gui/GradientDistributionSlider extends AbstractWidget` — the custom N-1-handle slider. Draws with
  vanilla's `widget/slider[_highlighted]` and `widget/slider_handle[_highlighted]` sprites via
  `blitSprite(RenderPipelines.GUI_TEXTURED, …)` (same as `AbstractSliderButton`'s bytecode),
  alternating segment tints, a gold bar per blend zone, segment labels = live block names cut with
  `Font.plainSubstrByWidth`. Click grabs the nearest handle (tie → the side the cursor is on) and
  moves it; drag moves it; click sound on release. Keyboard: Enter/Space = next handle, Left/Right =
  ±1% (Shift ±5%). All moves go through `GradientHelper.moveHandle`. With fewer than 2 slots
  included it draws "Include at least 2 slots for a gradient" and ignores input.
- Toggle message: `message.texturebuilder.toggle` still `"Texture Builder: %s"`; the `%s` is now
  `text.texturebuilder.toggle_state` = `"%s (%s)"` (ON/OFF + `text.texturebuilder.mode.*`), styled
  green/red as one Component → "Texture Builder: ON (Gradient)". `/texturebuilder status` adds a
  `session = ON (Gradient)` line, mode, gradient slots with shares, transition, and the points/axis
  line from `GradientController.describePoints`.
- **26.3 API used by this feature, verified by `javap` 2026-10-22** against
  `minecraft-{clientonly,common}-deobf/26.3`: `AbstractWidget` abstract
  `extractWidgetRenderState(GuiGraphicsExtractor,int,int,float)` / `updateWidgetNarration(
  NarrationElementOutput)`, `onClick(MouseButtonEvent,boolean)`, `onDrag(MouseButtonEvent,double,
  double)`, `onRelease(MouseButtonEvent)`, `playDownSound(SoundManager)`, static
  `playButtonClickSound`, `protected float alpha`; `GuiEventListener.keyPressed(KeyEvent)`;
  `InputWithModifiers.isLeft/isRight/isSelection/hasShiftDown`; `MouseButtonEvent.x()`;
  `GuiGraphicsExtractor.fill/blitSprite/text/requestCursor`; `CursorTypes.RESIZE_EW`;
  `ARGB.white(float)`; `ResourceKey.identifier()`; `BlockStateBase.canBeReplaced()`;
  `HitResult.Type.BLOCK`; `Screen.rebuildWidgets()`; `HeaderAndFooterLayout(Screen,int,int)`;
  `StringWidget.setMaxWidth(int, TextOverflow.SCROLLING)`; `CycleButton.Builder.withValues(T...)`.
  Side finding: `GuiGraphicsExtractor.item(ItemStack,int,int)` **does exist in 26.3**, so decision 4
  (names instead of icons) could be revisited.
- Mixin: `MultiPlayerGameModeMixin` HEAD became cancellable on 2026-10-25 (blocked Gradient
  placement). No other mixin was added or changed.

## How to build
```bash
cd texturebuilder
java -version       # must report 25
gradlew.bat build   # wrapper (9.5.1) is committed; no `gradle wrapper` step needed
# => build/libs/texture-builder - 26.3 - 1.1.0.jar   (Gradient branch)
```

### Production launch test (26.3, 2026-10-22)
`prodlaunch.py` (from the `fabric-mod-version-port` skill) with profile
`fabric-loader-0.19.5-26.3`, the built jar + `fabric-api-0.160.6+26.3.jar` (from the Gradle cache)
in a scratch game dir, with `--java` set to the launcher's own runtime
`%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon\bin\java.exe`
(Microsoft OpenJDK 25.0.1, what 26.3's version JSON asks for).
⚠️ **On this machine the game often dies natively during the first resource reload** (exit 139, no
hs_err, no crash report), right after the unifont `.hex` lines. It is **intermittent and not the
mod**: 3 launches each gave 1/3 title screens for 1.1.0 and 2/3 for the old 1.0.1 jar, all with the
launcher runtime. Temurin 25.0.3 failed 3/3 earlier, so prefer the launcher runtime, and **retry
until "Sound engine started" appears** before reading anything into a failure.

## Design decisions / deviations from the SRS (all deliberate)
1. **Mojmap, not Yarn** — Yarn doesn't exist for 26.x (see findings).
2. ~~**Inventory button mixin targets `AbstractContainerScreen`**~~ — retracted 2026-08-06; it does
   target `InventoryScreen` per SRS §7.2 after all (see mixin notes).
3. **"Action-bar" messages render via a HUD element** — `displayClientMessage` is absent in 26.x;
   this is the verified pattern from ShulkerPickBlock. Same visual result: brief, disappearing,
   above the hotbar, no persistent overlay.
4. **FR-07 "live preview" is the item name, not an icon** — no verified item-icon draw call in the
   26.2 screen render model; name refreshes every tick.
5. **Selection is NOT restored after placement (changed 2026-08-07 at the user's request).**
   SRS §1/FR-12/NFR-06/TC-04 specified switch-then-restore so the visible hotbar never moved; in
   practice that made it ambiguous which block was being placed, since blocks came from slots other
   than the highlighted one. The chosen slot now stays selected. SRS §1 explicitly labelled the old
   behaviour "a design assumption made for v1 [that] can be revisited if undesired", so this is a
   sanctioned revision, not a spec violation. **`restore_slot_after_placement = true` restores the
   original behaviour** (and with it FR-12/NFR-06/TC-04 as literally written). Side benefit: no
   restore packet can race the FR-13 restock pick, since the depleted slot simply stays selected.
6. **Held-item guard** — the randomizer only engages when the held item is empty or a `BlockItem`,
   so right-clicking with tools/food/buckets stays fully vanilla even while ON (keeps NFR-05 safe
   in mixed play; the SRS is silent on non-block right-clicks).
7. **Restock fires the player's real Pick Block keybind (changed 2026-08-07 at the user's
   request).** Previously it called `MultiPlayerGameMode.handlePickItemFromBlock` directly. That is
   a method *inside* vanilla's pick flow, so **mods that hook pick block never ran** — concretely,
   sibling mod **PA9 ShulkerPickBlock injects at the TAIL of the private
   `Minecraft.pickBlockOrEntity()`** and so never restocked from inventory shulker boxes here.
   Verified in 26.2 bytecode that `Minecraft.handleKeybinds()` contains
   `while (options.keyPickItem.consumeClick()) { pickBlockOrEntity(); }`, and `pickBlockOrEntity`
   is **private** (cannot be called directly), so queueing a click via the public static
   `KeyMapping.click(InputConstants.getKey(keyPickItem.saveString()))` is the correct — and
   user-requested — way in. Creative and survival now share this one path; vanilla supplies the
   mode-specific behaviour (FR-18/FR-19) using only standard packets (PKT-03/04).
   - **Unbound guard (important):** `KeyMapping.click` dispatches to *every* mapping bound to the
     given key, so clicking the `UNKNOWN` key would fire **every unbound keybind in the game**.
     `isUnbound()` is checked first, falling back to `attemptDirectRestock` (the old direct path).
   - **Asynchronous outcome:** the click is consumed on the *next* tick, so success can't be seen
     inline. `RestockWatch` + `PlacementRandomizer.tick()` watch the slot for
     `RESTOCK_TIMEOUT_TICKS` (20 = 1s, generous to cover a survival server round-trip *and* another
     mod's work) and only then emit the FR-15 "no more X found" message. Waiting is required for
     correctness, not just latency: the item may arrive from a source this mod can't see (a shulker
     box). The old immediate `hasAnywhere()` inventory scan was **removed** precisely because it
     would have reported "no more found" for items sitting inside shulker boxes.
8. **Restock fires on depletion only, and the pick is aimed at the block just placed.**
   A brief experiment (same day) also retried the pick on every FR-16 miss, to stop a failed attempt
   leaving a slot dead for the session. **Reverted at the user's request**, because a miss places
   nothing, so the crosshair sits on whatever the player is aiming at rather than a block of the
   depleted type — retries were liable to pull an unrelated block into the pooled slot. Depletion is
   the one moment the just-placed block is guaranteed to be the item that ran out. `lastKnownItem[]`
   and `message.texturebuilder.no_more_unknown` existed only for that experiment and were removed.
   **Trade-off accepted: if the depletion-moment pick fails, that slot stays empty for the session.**
   - **`aimPickAtPlacedBlock` — why it is needed and why it is safe.** Vanilla's pick has no "which
     item" input: `pickBlockOrEntity()` is private, no-arg, and reads only the **public**
     `Minecraft.hitResult`. Verified tick order: `Minecraft.tick()` calls `gameRenderer.pick()` at
     offset 93 and `handleKeybinds()` at 181, so when our TAIL runs, `hitResult` still refers to the
     block placed *against* — picking it would restock the neighbour's item, not the depleted one.
     Within one `handleKeybinds()` pass, **`keyUse` is consumed at offset 581 and `keyPickItem` at
     601**, so a press queued in our TAIL is consumed a few instructions later *in the same tick* and
     reads whatever `hitResult` holds then. Overwriting the field there aims that single pick exactly.
     No restore is needed (the field is rebuilt next tick), and because other pick-block mods read
     the same field — ShulkerPickBlock resolves `getCloneItemStack` from `client.hitResult` — they
     are aimed correctly too, for free. No-op when the placed block can't be located.
   - ⚠️ **Held right-click nuance:** `handleKeybinds` also drives placement from the *continuous*
     `keyUse.isDown()` path at ~660-687, i.e. **after** the 601 pick loop. A press queued from that
     path is consumed on the *next* tick, by which time `gameRenderer.pick()` has overwritten our
     aim. It then falls back to the live crosshair, which usually still points at the just-placed
     block (it is now the nearest surface). Guaranteeing the aim there too would need a mixin at
     HEAD of the private `pickBlockOrEntity`.
   - `restockWatches[]` stays **per-slot**: two slots can each have an unresolved attempt at once,
     and a single shared watch would let them overwrite each other so neither reached its timeout.
   - **Deferred restore:** vanilla picks into the *selected* slot, so when
     `restore_slot_after_placement = true` the restore must not run before the pick resolves or it
     would divert the item to the wrong slot. `afterPlacement` skips the restore while a watch is
     live and hands the slot to `RestockWatch.restoreSlot`; `finishDeferredRestore` does it once the
     pick settles. (No-op in the default configuration, where nothing is restored.)
   - The crosshair target is stale during the TAIL but correct by the time the click is consumed —
     the same reason pressing pick block by hand right after placing grabs what you just placed.

## Verified vs needs in-game confirmation
- ✅ Compiles clean against real 26.2 (jar + sources jar). Weighted selector unit-verified.
- ⚠️ Runtime walk of TC-01..TC-12 not yet done. Watch specifically:
  - `useItemOn` TAIL restore ordering vs. vanilla's own carried-item resync (NFR-03/TC-04);
  - server-side pick behaviour when the survival restock match is in main inventory (TC-06) —
    the server should move it into the selected slot via `getSuitableHotbarSlot`;
  - Mixin `compatibilityLevel` JAVA_25 under Loader 0.19.3 (validated at game launch, not build);
  - config-screen layout fit at GUI scale 4+ on small windows (10 rows of 16px + header/footer).
- ⚠️ `KeyMapping.isUnbound()` compiled clean; first-tick TOML keybind application assumes options
  are loaded by then (they are — options load during `Minecraft.<init>`, ticks start after).
- **Gradient mode (v1.1.0):**
  - ✅ Clean `gradlew.bat build`; `GradientHelper` unit-verified standalone (see above).
  - ✅ Production launch of `texture-builder - 26.3 - 1.1.0.jar` (single-screen version) reached
    the title screen ("Sound engine started"), no mod warnings (see the intermittent native crash
    note under "Production launch test"). A pre-seeded TOML with the legacy `mode = "random"`,
    `gradient_included = [0, 3, 4]`, two boundaries, transition, points and dimension was rewritten
    as `mode = "texture"` with every gradient value unchanged. `options.txt` shows both keybinds
    registered, unbound. The jar contains no leftover `GradientConfigScreen` class.
  - ⚠️ Not yet checked in a world: placement along each axis, the keybind cycle and its HUD
    messages, other-dimension behaviour, the config screen in both modes (look, live mode swap,
    fit at GUI scale 4), the distribution slider's drag/keyboard feel, and restock of a depleted
    gradient slot. The GUI and placement classes only load when first used, so the launch does not
    exercise them.
  - ⚠️ P is predicted *before* placement with no-arg `canBeReplaced()`; item-dependent replacement
    (e.g. slab-into-slab, which places in the clicked block) may give a position one block off.

## TODO / next steps
1. Launch a 26.2 dev client (`gradlew runClient`) or install the jar; walk TC-01..TC-12.
2. ~~**Creative-mode config access**~~ — done 2026-10-11 (`CreativeModeInventoryScreenMixin`).
   Class-load verified in a 26.3 dev client; the button's on-screen look still wants an in-game glance.
3. If the TB button crowds other inventory mods, make its corner configurable (SRS §12 risk).
4. Consider a repeat-suppression window for the FR-15 message (SRS §12 "future version").
5. Walk the gradient-mode in-world checks listed under "Verified vs needs in-game confirmation".
