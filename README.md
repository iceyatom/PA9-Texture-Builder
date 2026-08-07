# PA9 TextureBuilder

A **client-side Fabric mod** for **Minecraft Java Edition 26.2** that automates block variation
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
| Minecraft | Java Edition 26.2 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.155.2+26.2 (in `.minecraft/mods/`) |
| Java | 25 (for building) |
| Mod Menu | optional — adds a gear shortcut to the config screen |

## Install

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2 via the Fabric installer.
2. Put **Fabric API 0.155.2+26.2** in `.minecraft/mods/`.
3. Put **`texture-builder - 26.2 - 1.0.0.jar`** (from `build/libs/`) in `.minecraft/mods/`.
4. (Optional) add Mod Menu.
5. Launch the `fabric-loader-26.2` profile.

## Use

- **Toggle** — bind "Toggle Texture Builder" in Options → Controls (unbound by default), or set
  `toggle_keybind` in the config. Toggling shows a brief "Texture Builder: ON/OFF" message above
  the hotbar. The state is per-session; it only starts ON if `enabled = true` in the config.
- **Configure the pool** — open your inventory and click the small **TB** button (just below the
  crafting output slot), or run `/texturebuilder config`, or use Mod Menu's gear. Each hotbar slot
  row shows the item currently in it (live), an Included toggle, and a **weight slider** (0–100).
  Drag a slider to adjust, or click it and use the arrow keys for an exact value. Weights don't
  have to sum to 100 — selection normalizes proportionally, and the "Eff. %" column shows each
  slot's real resulting share; the total line turns red as a reminder.
- **Build** — with the mod ON, place blocks normally. Only the 9 hotbar slots are ever used.
  When a pooled slot runs out, the mod fires a single vanilla pick-block at the block you just
  placed to restock the slot (instant refill in creative; pulled from your main inventory in
  survival). If none are found anywhere, a brief "No more … found" message appears and the slot
  stays in the pool as a miss.
- **Commands** — `/texturebuilder config | reload | status`. Config file:
  `config/texturebuilder.toml`.

Works in survival and creative, single-player and on vanilla servers — every slot switch,
placement, and restock uses the exact packets a manual player would generate; no custom payloads.

## Build from source

```bash
cd texturebuilder
java -version        # must report 25
gradlew.bat build    # Windows (./gradlew build on macOS/Linux)
# Output: build/libs/texture-builder - 26.2 - 1.0.0.jar
```

See `CLAUDE.md` for build-environment findings (notably: **no Yarn mappings exist for 26.x** —
the code uses Mojang mappings and the build declares no `mappings` dependency at all).

## License

MIT
