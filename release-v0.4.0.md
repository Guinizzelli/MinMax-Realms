## Title

`v0.4.0 - DPS meter overhaul, stronger item roll parsing, and richer build export`

## What's Changed

* Rework the DPS Meter around more realistic DungeonRealms combat assumptions
  * add tier-scaled stamina cost by weapon family
  * make scythes scale with INT
  * switch melee APS toward a session-based model using a 100 stamina pool
  * replace the old `TTK` HUD label with a session duration summary
  * add a configurable `Melee APS` setting in the DPS config panel

* Improve generic item roll recognition across weapons, armor, shields, and custom sets
  * add missing stat support such as `Cooldown Recovery`, `Healing`, `Energy Drain`, `Absorption`, and `Key Find`
  * fix several outdated ranges using the public stat/equipment docs and spring wipe notes
  * add proper `Armor` and `DMG Reduction` tables by tier and rarity
  * improve custom set matching so valid `.set` items stop falling back to generic analysis
  * support more vanilla/custom type aliases such as `*_SPADE`

* Make enchant-aware stat handling much more reliable
  * ignore enchant suffixes for roll quality calculations when needed
  * still count enchant bonus values like `(+94)` in DPS totals and build exports
  * stop showing fake `overcap` states for stats that are really just enchant-adjusted
  * better handle upgrade-sensitive stats such as weapon `DMG`, armor `HP`, shield `HP/HP Regen`, and related display ranges

* Improve Build Optimizer and HTML export output
  * simplify the in-game Build Optimizer UI around `Scan current gear` and `Export HTML`
  * export HTML directly to `Downloads` while still updating legacy `build-latest.html`
  * enrich sidebar stats and always display tracked stat groups, including zeros
  * fix duplicate item meta labels and improve category/type/tier display
  * include more complete item stat output in exported build cards

* Improve rarity and special item handling
  * add support for `Transmuted` rarity handling
  * improve fallback rarity and tier detection across generic items
  * reduce false-positive custom item matches caused by overly fuzzy generic names

## Notes

This release focuses on making the mod much more trustworthy when reading real DungeonRealms gear.

The biggest improvements are:

* a more believable DPS Meter for melee weapons
* much stronger item roll recognition for generic and custom items
* better handling of enchant-added stats
* cleaner and more useful HTML build exports

## Artifact

`minmax-realms-v0.4.0-mc1.21.5.jar`
