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

## Architecture (all under `src/client/java/com/yourname/texturebuilder/`)
- `TextureBuilder` — mod id / name / logger constants, `HOTBAR_SIZE`.
- `TextureBuilderClient` — `ClientModInitializer`; loads config, session ON/OFF state (FR-03:
  session-only, seeded from `enabled`), toggle keybind (FR-01; unbound default; TOML
  `toggle_keybind` applied on **first tick** only if still unbound, so options.txt/Controls
  rebinds always win), NFR-04 `failSession` kill switch, HUD element + tick wiring, commands.
- `placement/PlacementRandomizer` — the core. `beforePlacement` (mixin HEAD): only acts when ON,
  main hand, and the held item is empty-or-BlockItem (tools/food stay vanilla); draws via helper,
  switches slot + sends `ServerboundSetCarriedItemPacket` (PKT-01). `afterPlacement` (mixin TAIL):
  restock if the pick emptied its slot, then restore in a `finally` (FR-12, NFR-03/06). Restock
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
  simple item-icon call, so FR-07's "preview" is the name) / Included CycleButton / weight EditBox
  / effective-% column (`auto_normalize_display`). Total line green at 100, else red +
  "normalizes at runtime" (FR-09, never blocks closing). Edits hit the live config immediately;
  TOML written in `onClose()` by every route (FR-10). Parent screen restored on close (FR-06).
- `mixin/client/MultiPlayerGameModeMixin` — `useItemOn` HEAD+TAIL, non-cancelling (PKT-02).
- `mixin/client/AbstractContainerScreenMixin` — inventory **TB** button (FR-05). Targets
  `AbstractContainerScreen.init` TAIL with `instanceof InventoryScreen` guard, because the SRS's
  suggested `InventoryScreen.init` doesn't exist in 26.2. **ContainerSort injects the same method
  (priority 1100)** — both are additive TAILs, coexistence is safe; this one is priority 1200 for
  deterministic order. NFR-09's "no shared target methods" is thus violated in letter (impossible
  in 26.2) but not in spirit; documented here per the SRS risk table.
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
2. **Inventory button mixin targets `AbstractContainerScreen`** — `InventoryScreen.init` doesn't
   exist in 26.2 (see mixin notes).
3. **"Action-bar" messages render via a HUD element** — `displayClientMessage` is absent in 26.x;
   this is the verified pattern from ShulkerPickBlock. Same visual result: brief, disappearing,
   above the hotbar, no persistent overlay.
4. **FR-07 "live preview" is the item name, not an icon** — no verified item-icon draw call in the
   26.2 screen render model; name refreshes every tick.
5. **Held-item guard** — the randomizer only engages when the held item is empty or a `BlockItem`,
   so right-clicking with tools/food/buckets stays fully vanilla even while ON (keeps NFR-05 safe
   in mixed play; the SRS is silent on non-block right-clicks).
6. **Survival restock uses vanilla `handlePickItemFromBlock`** at the just-placed position —
   server-authoritative on vanilla servers, satisfying PKT-03/05 with zero custom logic. Fired
   before the slot restore so the server moves the match into the depleted (still selected) slot.

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
2. If the TB button crowds other inventory mods, make its corner configurable (SRS §12 risk).
3. Consider a repeat-suppression window for the FR-15 message (SRS §12 "future version").
