# Monster balance data

`core/src/main/assets/balance/monster_stats.csv` is the editable per-monster balance table. It has one row per concrete monster class (including summoned and vault variants). The `*_init`, formula, and source columns inventory the current Java defaults; the `*_multiplier` columns are read by the game at runtime. A multiplier of `1.0` preserves the existing value. Tune rows individually instead of applying a single global adjustment.

The class's Java-defined combat stats remain the base. When an ordinary enemy is first added to the actor system, the game combines its per-class multipliers with the floor rule and rolls bounded normal-distribution multipliers for the stats whose per-monster standard deviations are nonzero in this table: `hp_stddev`, `damage_stddev`, `accuracy_stddev`, and `speed_stddev`. A value of `0.10` means a 10 percentage-point standard deviation around that monster's configured mean. The current encounter bands set HP/damage variation for floors 1–10, add accuracy variation for floors 11–20, and add speed variation for floors 21+. Every monster's values remain individually editable in its row. Defense, damage reduction, and attack delay do not receive spawn variance. Rolls are clamped at three standard deviations and saved on each monster, so they do not change between turns or after loading a save. Bosses, minibosses, and immovable enemies do not receive variance rolls.

`floor_balance.csv` contains the encounter-wide floors 1–5 mean adjustment for ordinary enemies' HP, damage, and accuracy. Squad role labels do not change combat stats: Jev selects the initial role assignment with the squad's first tactical request; a local stat-based assignment is used as the safe fallback. The initially assigned leader receives +10% experience and +20% loot chance once; a successor never receives those initial-leader bonuses. Leader succession requires both floor 16 or later and an allowed monster type in `leader_succession.csv` (the default allows ordinary squad members; class rows can opt out). Without an eligible successor the squad dissolves. Special phases, level-dependent formulas, and weapon-based damage remain in Java and are recorded in the source/formula columns for review. Formula cells are an inventory, not executable config: values such as `spawn(): (2 + level) * 4` document where a dynamic stat is set.

Regenerate the inventory after Java monster changes with:

```powershell
node scripts/export_monster_stats.mjs
```

The exporter preserves existing multiplier and variance columns by monster class name. New monster rows default all standard deviations to zero and should receive an explicit variance profile in the table. Do not edit the formula/source columns as configuration; those are source references. CSV is kept as the canonical spreadsheet-friendly table. For a Jev request, use the active monsters' runtime state and serialize only those selected rows as JSON rather than sending the full table.
