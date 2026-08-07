# CLAUDE.md — PA9 TextureBuilder

Knowledge base for future Claude runs on this project. Read this first.

## What this is
A **client-side Fabric mod** for **Minecraft Java 26.2** that, while toggled ON, weighted-randomly
switches the active hotbar slot immediately before each block placement and restores it right
after, so manual building produces blended textures (e.g. 70/20/10 stone-brick variants). Built
2026-08-06 from `../PA9_TextureBuilder_ModRequirements_v1.docx` (SRS v1.0); FR/NFR/PKT/TC IDs
refer to that document. Structure and conventions deliberately mirror the author's ShulkerPickBlock
and ContainerSort projects (`../../Shulker Pick Block/shulker-pick-block`,
`../../Chest Sort/containersort`).

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
  simple item-icon call, so FR-07's "preview" is the name) / Included CycleButton / **weight
  slider** / effective-% column (`auto_normalize_display`). Total line green at 100, else red +
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
- `mixin/client/MultiPlayerGameModeMixin` — `useItemOn` HEAD+TAIL, non-cancelling (PKT-02).
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
  **Note:** `InventoryScreen.init()` swaps creative players to `CreativeModeInventoryScreen` and
  returns early, so **the TB button does not appear in the creative inventory** — creative users
  reach the config screen via `/texturebuilder config` or Mod Menu. FR-05 says "the vanilla player
  inventory screen", so this is spec-compliant, but see TODO 2.
- `hud/TextureBuilderHud` — self-expiring above-hotbar text (toggle confirmations FR-02, restock
  misses FR-15); duration = `restock_message_duration_ms`.
- `command/TextureBuilderCommands` — `/texturebuilder config|reload|status` via
  `fabric-command-api-v2`; `config` defers `gui.setScreen` through `Minecraft.execute`.
- `compat/modmenu/ModMenuIntegration` — `modmenu` entrypoint (FR-22); Mod Menu is `compileOnly`
  (`20.0.0-beta.4`, Terraformers maven), never loaded when absent.

## How to build
```bash
cd texturebuilder
java -version       # must report 25
gradlew.bat build   # wrapper (9.5.1) is committed; no `gradle wrapper` step needed
# => build/libs/texture-builder - 26.2 - 1.0.0.jar
```

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

## TODO / next steps
1. Launch a 26.2 dev client (`gradlew runClient`) or install the jar; walk TC-01..TC-12.
2. **Creative-mode config access** — the TB button can't appear in creative (see mixin note).
   Since creative is a primary use case for this mod (TC-03 assumes unlimited creative stacks),
   consider a second mixin adding the same button to `CreativeModeInventoryScreen`'s inventory tab.
   Not done yet: it's beyond FR-05's wording and needs the user's call.
3. If the TB button crowds other inventory mods, make its corner configurable (SRS §12 risk).
4. Consider a repeat-suppression window for the FR-15 message (SRS §12 "future version").
