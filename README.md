# MinMax Realms

Client-side Fabric mod for Minecraft `1.21.5` focused on DungeonRealms quality-of-life and theorycrafting.

## Beta Notice

MinMax Realms is still in beta.

Some parts of the mod are already very usable, but a few systems are still being refined, especially the stamina / energy-side assumptions used by the DPS simulator. I am still validating how stamina behaves in practice so the formulas and options will become smoother over time.

If something feels off, awkward, or unclear, please do not hesitate to leave feedback.

## Features

- `DR Item Rolls`
  - Detects item rarity from the visible tooltip
  - Shows roll quality inline for supported weapon, armor, shield, and many custom set stats
  - Handles enchant-added stat suffixes without breaking roll quality detection
  - Supports special rarity handling such as `Transmuted`
  - Can clean up durability, item id, and component lines

- `DR DPS Meter`
  - Simulates DPS from your weapon, gear, class, and target tier
  - Uses tier-scaled stamina cost by weapon family
  - Includes a melee session model with configurable `Melee APS`
  - Includes DungeonRealms-specific stats such as crit, shatter, execute, crushing, piercing, and energy sustain

- `Gem Meter`
  - Tracks session gem gains with a lightweight HUD
  - Supports `Inventory`, `Chat`, or `Hybrid` sources
  - Shows `Gem Find`, `Item Find`, and `Key Find`
  - Tracks slime kills and opened chests during the session

- `Build Optimizer`
  - Scans your equipped gear and compares simulated DPS across class profiles
  - Exports a standalone HTML build sheet with player snapshot, item cards, and grouped stats
  - Keeps the in-game flow simple with `Scan current gear` and `Export HTML`

- `Codex + Stats Wiki`
  - Browse indexed DR custom items from the bundled stats database
  - Search and filter by name, slot, and rarity
  - Includes a built-in wiki explaining roll quality and stat categories

- `HTML Export`
  - Export optimizer analysis to a standalone HTML report
  - Saves reports directly to `Downloads`
  - Still updates `build-latest.html` under `config/dr-standalone/exports` for quick reuse/testing

## Screenshots

### DPS Meter

![DPS Meter](docs/screenshots/dps-meter-ui.webp)

### DR Item Rolls

![DR Item Rolls](docs/screenshots/dr-rolls.webp)

### Gem Meter

![Gem Meter](docs/screenshots/gem-meter.webp)

### Module Config

![Module Config](docs/screenshots/module-config.png)

### Build Optimizer

![Build Optimizer](docs/screenshots/build-optimizer-ui.png)

### HTML Export File

![HTML Export File](docs/screenshots/html-export-file.png)

### HTML Export Preview

![HTML Export Preview](docs/screenshots/html-export-preview.png)

### Build Optimizer / Codex

The optimizer and codex screens are available in-game from the config screen.

## Controls

The mod registers a dedicated category in Minecraft `Controls`.

Default keys:

- `O` opens the config screen
- `K` opens the `Codex + Stats Wiki` screen
- extra toggle keys for `DPS Meter`, `Gem Meter`, and `Item Rolls` can be set in `Controls`

## Config

The config is stored in:

`config/dr-standalone.json`

You can configure feature toggles, HUD placement, DPS simulation settings, melee session APS, item roll display style, tooltip cleanup options, and codex access.

## Build

```powershell
.\gradlew.bat build
```

Built jar:

`build/libs/minmax-realms-v0.4.0-mc1.21.5.jar`

Exported workspace jar:

`../build/minmax-realms-v0.4.0-mc1.21.5.jar`

## Notes

- This is a client helper mod, not a hacked client
- The DPS meter is a theorycraft tool, not a live combat parser
- Roll detection is based on visible tooltip text plus bundled DR datasets
- Melee DPS now uses a session-style stamina model; bow behavior still needs a dedicated cadence model
