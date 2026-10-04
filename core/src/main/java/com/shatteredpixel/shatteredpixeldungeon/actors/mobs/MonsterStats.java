package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.watabou.utils.Random;

import java.util.HashMap;
import java.util.Map;

/** Runtime per-monster balance multipliers loaded from the editable stats table. */
public final class MonsterStats {

	private static final String DATABASE = "balance/monster_stats.csv";
	private static final String ENCOUNTER_RULES = "balance/floor_balance.csv";
	private static final String LEADER_RULES = "balance/leader_succession.csv";
	private static final int HP = 0;
	private static final int DAMAGE = 1;
	private static final int ACCURACY = 2;
	private static final int DEFENSE = 3;
	private static final int DR = 4;
	private static final int SPEED = 5;
	private static final int LOOT = 6;
	private static final int ATTACK_DELAY = 7;
	private static final int VIEW_DISTANCE = 8;
	private static final int EXP = 9;
	private static final int HP_STDDEV = 10;
	private static final int DAMAGE_STDDEV = 11;
	private static final int ACCURACY_STDDEV = 12;
	private static final int SPEED_STDDEV = 13;
	private static final Map<String, float[]> records = new HashMap<>();
	private static float[] earlyFloor = {0.90f, 0.85f, 0.90f};
	private static final Map<String, Boolean> leaderSuccessionRecords = new HashMap<>();
	private static boolean loaded;

	private MonsterStats() {}

	private static synchronized void load() {
		if (loaded) return;

		String[] lines = Gdx.files.internal(DATABASE).readString("UTF-8").split("\\r?\\n");
		if (lines.length == 0) throw new IllegalStateException("Empty monster stats database: " + DATABASE);
		String[] headers = parseCsvLine(lines[0]);
		int classIndex = indexOf(headers, "class_name");
		int[] indices = {
			indexOf(headers, "hp_multiplier"), indexOf(headers, "damage_multiplier"),
			indexOf(headers, "accuracy_multiplier"), indexOf(headers, "defense_multiplier"),
			indexOf(headers, "dr_multiplier"), indexOf(headers, "speed_multiplier"),
			indexOf(headers, "loot_multiplier"), indexOf(headers, "attack_delay_multiplier"),
			indexOf(headers, "view_distance_multiplier"), indexOf(headers, "exp_multiplier"),
			indexOf(headers, "hp_stddev"), indexOf(headers, "damage_stddev"),
			indexOf(headers, "accuracy_stddev"), indexOf(headers, "speed_stddev")
		};
		if (classIndex < 0) throw new IllegalStateException("Missing class_name in " + DATABASE);

		for (int line = 1; line < lines.length; line++) {
			if (lines[line].isEmpty()) continue;
			String[] values = parseCsvLine(lines[line]);
			if (classIndex >= values.length || values[classIndex].isEmpty()) continue;
			float[] multipliers = new float[indices.length];
			for (int i = 0; i < indices.length; i++) {
				multipliers[i] = i < HP_STDDEV ? parseMultiplier(values, indices[i]) : parseStdDev(values, indices[i]);
			}
			records.put(values[classIndex], multipliers);
		}
		loadEncounterRules();
		loadLeaderRules();
		loaded = true;
	}

	private static void loadEncounterRules() {
		String[] lines = Gdx.files.internal(ENCOUNTER_RULES).readString("UTF-8").split("\\r?\\n");
		if (lines.length < 2) throw new IllegalStateException("Missing encounter balance rules: " + ENCOUNTER_RULES);
		String[] headers = parseCsvLine(lines[0]);
		int kindIndex = indexOf(headers, "kind");
		int hpIndex = indexOf(headers, "hp_multiplier");
		int damageIndex = indexOf(headers, "damage_multiplier");
		int accuracyIndex = indexOf(headers, "accuracy_multiplier");
		for (int i = 1; i < lines.length; i++) {
			if (lines[i].trim().isEmpty()) continue;
			String[] values = parseCsvLine(lines[i]);
			if (kindIndex < 0 || kindIndex >= values.length) continue;
			float[] profile = {
				parseMultiplier(values, hpIndex),
				parseMultiplier(values, damageIndex),
				parseMultiplier(values, accuracyIndex)
			};
			if ("floors_1_5".equals(values[kindIndex])) earlyFloor = profile;
		}
	}

	private static void loadLeaderRules() {
		String[] lines = Gdx.files.internal(LEADER_RULES).readString("UTF-8").split("\\r?\\n");
		if (lines.length < 2) throw new IllegalStateException("Missing leader succession rules: " + LEADER_RULES);
		String[] headers = parseCsvLine(lines[0]);
		int classIndex = indexOf(headers, "class_name");
		int allowedIndex = indexOf(headers, "can_succeed_leader");
		for (int line = 1; line < lines.length; line++) {
			if (lines[line].trim().isEmpty() || lines[line].startsWith("#")) continue;
			String[] values = parseCsvLine(lines[line]);
			if (classIndex < 0 || allowedIndex < 0 || classIndex >= values.length || allowedIndex >= values.length) continue;
			leaderSuccessionRecords.put(values[classIndex], Boolean.parseBoolean(values[allowedIndex]));
		}
	}

	private static int indexOf(String[] headers, String name) {
		for (int i = 0; i < headers.length; i++) if (name.equals(headers[i])) return i;
		return -1;
	}

	private static float parseMultiplier(String[] values, int index) {
		if (index < 0 || index >= values.length) return 1f;
		try {
			float value = Float.parseFloat(values[index]);
			return Float.isNaN(value) || Float.isInfinite(value) || value < 0f ? 1f : value;
		} catch (NumberFormatException ignored) {
			return 1f;
		}
	}

	private static float parseStdDev(String[] values, int index) {
		if (index < 0 || index >= values.length) return 0f;
		try {
			float value = Float.parseFloat(values[index]);
			return Float.isNaN(value) || Float.isInfinite(value) || value < 0f ? 0f : value;
		} catch (NumberFormatException ignored) {
			return 0f;
		}
	}

	private static String[] parseCsvLine(String line) {
		java.util.ArrayList<String> values = new java.util.ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
				cell.append('"');
				i++;
			} else if (c == '"') {
				quoted = !quoted;
			} else if (c == ',' && !quoted) {
				values.add(cell.toString());
				cell.setLength(0);
			} else {
				cell.append(c);
			}
		}
		values.add(cell.toString());
		return values.toArray(new String[0]);
	}

	private static float multiplier(Mob mob, int index) {
		load();
		float[] values = records.get(mob.getClass().getName().replace('$', '.'));
		return values == null ? 1f : values[index];
	}

	private static boolean isEarlyFloorEnemy(Mob mob) {
		return Dungeon.depth >= 1 && Dungeon.depth <= 5
				&& mob.alignment == Char.Alignment.ENEMY
				&& !Char.hasProp(mob, Char.Property.BOSS)
				&& !Char.hasProp(mob, Char.Property.MINIBOSS);
	}

	private static float floorMultiplier(Mob mob, int profileIndex) {
		load();
		return isEarlyFloorEnemy(mob) ? earlyFloor[profileIndex] : 1f;
	}

	/** Applies data-backed values after all subclass initializers have run. */
	static void applyTo(Mob mob) {
		mob.spawnHpMultiplier = spawnMultiplier(mob, HP, HP_STDDEV, 0);
		mob.spawnDamageMultiplier = spawnMultiplier(mob, DAMAGE, DAMAGE_STDDEV, 1);
		mob.spawnAccuracyMultiplier = spawnMultiplier(mob, ACCURACY, ACCURACY_STDDEV, 2);
		mob.spawnDefenseMultiplier = spawnMultiplier(mob, DEFENSE, -1, -1);
		mob.spawnDrMultiplier = spawnMultiplier(mob, DR, -1, -1);
		mob.spawnSpeedMultiplier = spawnMultiplier(mob, SPEED, SPEED_STDDEV, -1);
		mob.spawnAttackDelayMultiplier = spawnMultiplier(mob, ATTACK_DELAY, -1, -1);
		float hpMultiplier = mob.spawnHpMultiplier;
		if (hpMultiplier != 1f && mob.HT > 0) {
			float healthPercent = mob.HP / (float) mob.HT;
			mob.HT = Math.max(1, Math.round(mob.HT * hpMultiplier));
			mob.HP = Math.max(1, Math.round(mob.HT * healthPercent));
		}

		float expMultiplier = multiplier(mob, EXP);
		if (expMultiplier != 1f) mob.EXP = Math.max(0, Math.round(mob.EXP * expMultiplier));

		float viewMultiplier = multiplier(mob, VIEW_DISTANCE);
		if (viewMultiplier != 1f) mob.viewDistance = Math.max(1, Math.round(mob.viewDistance * viewMultiplier));
		mob.monsterBalanceVersion = 2;
	}

	private static float spawnMultiplier(Mob mob, int statIndex, int stddevIndex, int floorIndex) {
		float mean = multiplier(mob, statIndex);
		if (floorIndex >= 0) mean *= floorMultiplier(mob, floorIndex);
		if (!isVariableEnemy(mob)) return mean;
		float sigma = stddev(mob, stddevIndex);
		if (sigma <= 0f) return mean;
		// Box-Muller normal sample, truncated at three standard deviations to avoid extreme rolls.
		double u1 = Math.max(0.000001d, Random.Float());
		double u2 = Random.Float();
		float z = (float) (Math.sqrt(-2d * Math.log(u1)) * Math.cos(2d * Math.PI * u2));
		z = Math.max(-3f, Math.min(3f, z));
		return Math.max(0.10f, mean + z * sigma);
	}

	private static float stddev(Mob mob, int index) {
		load();
		float[] values = records.get(mob.getClass().getName().replace('$', '.'));
		return values == null || index < 0 || index >= values.length ? 0f : values[index];
	}

	private static boolean isVariableEnemy(Mob mob) {
		return mob.alignment == Char.Alignment.ENEMY
				&& !Char.hasProp(mob, Char.Property.BOSS)
				&& !Char.hasProp(mob, Char.Property.MINIBOSS)
				&& !Char.hasProp(mob, Char.Property.IMMOVABLE);
	}

	/** Applies the new floor HP rule once to monsters restored from older saves. */
	static void migrateLoadedHP(Mob mob) {
		if (mob.firstAdded || mob.monsterBalanceVersion >= 2) return;
		if (mob.monsterBalanceVersion < 1) {
			float floorHp = floorMultiplier(mob, 0);
			if (floorHp != 1f && mob.HT > 0) {
				float healthPercent = mob.HP / (float) mob.HT;
				mob.HT = Math.max(1, Math.round(mob.HT * floorHp));
				mob.HP = Math.max(1, Math.round(mob.HT * healthPercent));
			}
		}
		// Older saves stored HP but computed the other multipliers dynamically.
		// Freeze their existing class/floor means without rolling new variance.
		mob.spawnHpMultiplier = multiplier(mob, HP) * floorMultiplier(mob, 0);
		mob.spawnDamageMultiplier = multiplier(mob, DAMAGE) * floorMultiplier(mob, 1);
		mob.spawnAccuracyMultiplier = multiplier(mob, ACCURACY) * floorMultiplier(mob, 2);
		mob.spawnDefenseMultiplier = multiplier(mob, DEFENSE);
		mob.spawnDrMultiplier = multiplier(mob, DR);
		mob.spawnSpeedMultiplier = multiplier(mob, SPEED);
		mob.spawnAttackDelayMultiplier = multiplier(mob, ATTACK_DELAY);
		// Existing saves retain their already established stats; do not reroll them on load.
		mob.monsterBalanceVersion = 2;
	}

	public static float damageMultiplier(Mob mob) {
		return mob.spawnDamageMultiplier;
	}
	public static float accuracyMultiplier(Mob mob) {
		return mob.spawnAccuracyMultiplier;
	}
	public static float defenseMultiplier(Mob mob) { return mob.spawnDefenseMultiplier; }
	public static float drMultiplier(Mob mob) { return mob.spawnDrMultiplier; }
	public static float speedMultiplier(Mob mob) { return mob.spawnSpeedMultiplier; }
	public static float attackDelayMultiplier(Mob mob) { return mob.spawnAttackDelayMultiplier; }
	public static float lootMultiplier(Mob mob) { return multiplier(mob, LOOT) * mob.spawnLootMultiplier; }

	static boolean canSucceedLeader(Mob mob) {
		load();
		if (Dungeon.depth < 16) return false;
		String type = mob.getClass().getName().replace('$', '.');
		return leaderSuccessionRecords.getOrDefault(type,
				leaderSuccessionRecords.getOrDefault("default", true));
	}

	/** Adds the one-time initial-leader rewards. Successors deliberately do not receive these. */
	static void applyInitialLeaderBonus(Mob mob) {
		if (mob == null || mob.initialLeaderBonusApplied) return;
		mob.EXP = Math.max(0, Math.round(mob.EXP * 1.10f));
		mob.spawnLootMultiplier *= 1.20f;
		mob.initialLeaderBonusApplied = true;
	}

	/** Compact JSON-ready balance data for the active Jev squad member. */
	static Map<String, Object> jevBalanceProfile(Mob mob) {
		Map<String, Object> profile = new HashMap<>();
		profile.put("role", mob.squadRole);
		profile.put("hpMultiplier", mob.spawnHpMultiplier);
		profile.put("damageMultiplier", damageMultiplier(mob));
		profile.put("accuracyMultiplier", accuracyMultiplier(mob));
		profile.put("defenseMultiplier", defenseMultiplier(mob));
		profile.put("damageReductionMultiplier", drMultiplier(mob));
		profile.put("speedMultiplier", speedMultiplier(mob));
		profile.put("attackDelayMultiplier", mob.spawnAttackDelayMultiplier);
		return profile;
	}

	static void assignRole(Mob mob, String role) {
		if (role == null || role.isEmpty()) role = "solo";
		mob.squadRole = role;
	}
}
