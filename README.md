# PA9 Texture Builder

A **client-side Fabric mod** for **Minecraft Java Edition 1.21.9 – 1.21.10** that automates block variation
while building by hand. When toggled ON, each block placement first silently switches the active
hotbar slot to one chosen by **weighted random selection** from a configurable pool of hotbar
slots; vanilla placement then proceeds exactly as if you had pressed that number key, and your
previously-held slot is restored in the same instant. Blend e.g. 70% Stone Bricks / 20% Mossy /
10% Cracked without cycling the hotbar before every placement.

Built from `../PA9_TextureBuilder_ModRequirements_v1.docx` (SRS v1.0). FR/NFR/PKT/TC IDs in the
source refer to that document.

## Requirements

| Item | Version |
|---|---|
| Minecraft | Java Edition 1.21.9 or 1.21.10 (the jar refuses to load on 1.21.11+) |
| Fabric Loader | 0.17.0+ |
| Fabric API | 0.134.1+1.21.9 or later, matching your Minecraft version (in `.minecraft/mods/`) |
| Java | 21 to play (bundled with the launcher); JDK 25 to build (compiles with `--release 21`) |
| Mod Menu | optional — adds a gear shortcut to the config screen |

## Install

1. Install Fabric Loader 0.17.0+ for Minecraft 1.21.9 or 1.21.10 via the Fabric installer.
2. Put **Fabric API 0.134.1+1.21.9** (or the build for 1.21.10) in `.minecraft/mods/`.
3. Put **`texture-builder - 1.21.9 - 1.1.0.jar`** (from `build/libs/`) in `.minecraft/mods/`.
4. (Optional) add Mod Menu (16.0.x for 1.21.9–1.21.10).
5. Launch the matching `fabric-loader-…-1.21.9` / `-1.21.10` profile.

## Use

- **Toggle** — bind "Toggle Texture Builder" in Options → Controls (unbound by default), or set
  `toggle_keybind` in the config. Toggling shows a brief message above the hotbar that also names
  the active mode, e.g. "Texture Builder: ON (Gradient)" in green or "Texture Builder: OFF (Texture)"
  in red (matching Litematica/malilib's toggle coloring). The state is per-session; it only starts
  ON if `enabled = true` in the config.
- **Configure the pool (Texture mode)** — open your inventory and click the small **TB** button
  (just below the crafting output slot), or run `/texturebuilder config`, or use Mod Menu's gear.
  The **Mode** button at the bottom switches between *Texture* (a weighted random mix, the
  default) and *Gradient* (see below), and the screen changes to match. In Texture mode each hotbar
  slot row shows the item currently in it (live), an Included toggle, and a **weight slider** (0–100).
  Drag a slider to adjust, or click it and use the arrow keys for an exact value. Weights don't
  have to sum to 100 — selection normalizes proportionally, and the "Eff. %" column shows each
  slot's real resulting share; the total line turns red as a reminder. The **Reset** button next
  to the total puts every weight back to 10 (Included toggles are left as they are).
- **Build** — with the mod ON, place blocks normally. The hotbar selection follows each placement,
  so the highlighted slot always shows which block just went down. (Set
  `restore_slot_after_placement = true` in the config if you'd rather the selection stay put on the
  slot you chose manually.) Only the 9 hotbar slots are ever used.
  When a pooled slot runs out, the mod **triggers your Pick Block keybind** to restock it (instant
  refill in creative; pulled from your inventory in survival). Because it's a real pick-block
  press, other mods that extend pick block join in — notably **PA9 ShulkerPickBlock**, so a slot
  can restock straight out of a shulker box in your inventory. If nothing turns up within a second,
  a brief "No more … found" message appears and the slot stays in the pool as a miss. The press
  fires the instant the stack runs out, aimed at the block you just placed, so it always picks the
  block that actually ran out rather than whatever you happen to be looking at next.
  *Requires Pick Block to be bound* (it is by default, to the middle mouse button); if you've
  unbound it, restocking falls back to vanilla sources only.
- **Gradient mode** — instead of a random mix, fade a build from one block into the next along a
  straight line. It only applies while Texture Builder is ON.
  1. **Pick the mode.** In the config screen (inventory **TB** button), click the **Mode** button
     at the bottom until it says *Gradient*. The screen switches to the gradient settings right
     away. There is no keybind for the mode.
  2. **Set the two end points.** Bind **"Set Gradient Point"** in Options → Controls (unbound by
     default). Look at a block and press it once to set the **start**, look at another block and
     press again to set the **end**, and press a third time to **clear** both. A brief message
     confirms each press ("Gradient start set: x y z", …). When the end point gives a clear axis, the
     confirmation turns green and names it, e.g. "Gradient end set: 12 64 0, runs along X (13 blocks)". The fade runs along whichever of X, Y or
     Z the two points are farthest apart on. If two or three axes tie, for example opposite corners
     of a square, you'll see "Gradient axis unclear: X and Z both differ by 12 blocks"; move one
     point so a single axis is longest. Picking the same block twice shows "Gradient start and end
     are the same".
     **Until both points are set (in this dimension, on a clear axis), Gradient mode won't place
     blocks.** Trying to shows a warning such as "No gradient points selected". Right-clicking
     chests, doors, buttons and other usable blocks still works.
     Points are saved with the dimension they were set in and are ignored in other dimensions;
     setting a new start in another dimension replaces them.
  3. **Choose the blocks.** In Gradient mode the screen shows the same Slot / Item / Included
     columns. Turn **Included** on for the slots you want in the gradient. They run from the start
     point to the end point in hotbar order, left to right, so arrange your hotbar in the order you
     want the fade. You need at least 2. With fewer, Gradient mode won't place and shows a warning. Gradient
     mode remembers its own Included slots, so this doesn't change your Texture pool. The
     **Eff. %** column shows how much of the line each included slot gets.
  4. **Pick 1D, 2D or 3D.** The **Gradient: 1D** button next to Transition cycles the shape.
     *1D* fades along the one axis your points are farthest apart on. *2D* fades diagonally across
     the two axes that differ most and ignores the third, so opposite corners of a floor give a
     corner-to-corner fade. *3D* fades diagonally through all three axes; the points must differ
     on X, Y and Z. In 2D and 3D the bands are straight lines (or planes) at right angles to the
     start-to-end line. If the points don't suit the shape, the message says why, e.g. "2D
     gradient needs the points to differ on two axes (only X differs)".
  5. **Shape the fade.** Below the slot rows, the **distribution** bar shows each block's share of the line, labelled
     with its name. Drag a handle to move a boundary (handles can't pass each other), or select
     the bar and press Enter/Space to pick a handle and the arrow keys to nudge it (Shift for
     bigger steps). **Reset** spreads the blocks evenly again. **Transition** sets how soft each boundary is: 0% gives hard bands, 100% makes
     each block fade fully into the next. The gold marks on the bar show each blend zone.
     **Clear points** removes the start and end points. The line underneath shows the current
     points and axis.

  Blocks placed before the start or past the end use the first or last block. Restocking and the
  `restore_slot_after_placement` option work the same in both modes.
- **Commands** — `/texturebuilder config | reload | status`. `status` also shows the session
  state and mode, the gradient slots, transition, points and axis. Config file:
  `config/texturebuilder.toml`.

Works in survival and creative, single-player and on vanilla servers — every slot switch,
placement, and restock uses the exact packets a manual player would generate; no custom payloads.

## Build from source

```bash
cd texturebuilder
# needs JDK 25 installed (Gradle toolchain); javac targets Java 21 via --release 21
gradlew.bat build    # Windows (./gradlew build on macOS/Linux)
# Output: build/libs/texture-builder - 1.21.9 - 1.1.0.jar
```

See `CLAUDE.md` for build-environment findings (notably: **no Yarn mappings exist for 26.x** —
the code uses Mojang mappings and the build declares no `mappings` dependency at all).

## License

MIT
