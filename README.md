# MinMax Realms

Client-side Fabric mod for Minecraft `1.21.5` focused on DungeonRealms quality-of-life and theorycrafting.

This standalone version contains only the DR-specific features and does not depend on Meteor Client.

## Features

- `DR Item Rolls`
  - Detects item rarity from the visible tooltip
  - Shows roll quality inline for supported weapon and armor stats
  - Can hide clutter such as durability, item id, and components line

- `DR DPS Meter`
  - Simulates theoretical DPS from your weapon, gear, class, and target tier
  - Includes DungeonRealms-specific stats such as crit, shatter, execute, crushing, piercing, and energy sustain

- `Gem Meter`
  - Tracks gems with a lightweight HUD

## Controls

The mod registers a `dr_standalone` category in Minecraft `Controls`.

Default keys:

- `O` opens the config screen
- extra toggle keys for `DPS Meter`, `Gem Meter`, and `Item Rolls` can be set in `Controls`

## Config

The config is stored in:

`config/dr-standalone.json`

You can configure:

- feature toggles
- HUD position and scale
- DPS simulation class and tier
- item roll display style
- tooltip cleanup options

## Build

```powershell
.\gradlew.bat build
```

Built jar:

`build/libs/minmax-realms-1.21.5-local.jar`

Exported workspace jar:

`../build/minmax-realms-1.21.5-local.jar`

## Notes

- This is a client helper mod, not a hacked client
- The DPS meter is a theorycraft tool, not a live combat parser
- Roll detection is based on visible tooltip text plus bundled DR datasets
