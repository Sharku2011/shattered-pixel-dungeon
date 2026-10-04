/*
 * Copyright (C) 2026 Shattered Pixel Dungeon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.utils.DeviceCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** Assigns nearby ordinary enemies to small, persistent combat groups. */
public final class MobSquads {
	private static final int MAX_MEMBERS = 3;
	private static final int LINK_DISTANCE = 5;
	private static final int FOLLOW_DISTANCE = 2;
	private static final HashSet<Integer> LEASHED_SQUADS = new HashSet<>();

	private MobSquads() {}

	public static synchronized void logSpawn(String event, String details) {
		if (!DeviceCompat.isDebug()) return;
		String path = System.getProperty("typesafe.mob.debug.log", "mob-squad-debug.log");
		String line = System.currentTimeMillis() + " " + event + " " + details + System.lineSeparator();
		DeviceCompat.log("MOB-SQUAD", event + " " + details);
		try {
			Files.write(Paths.get(path), line.getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			DeviceCompat.log("MOB-SQUAD", "Unable to write debug log (" + ignored.getClass().getSimpleName() + ")");
		}
	}

	public static void logSnapshot(Level level, String event) {
		if (level == null || level.mobs == null) return;
		Map<Integer, ArrayList<Mob>> groups = new LinkedHashMap<>();
		for (Mob mob : level.mobs) {
			if (mob == null || !mob.isAlive() || !isSquadEligible(mob)) continue;
			int key = mob.squadId >= 0 ? mob.squadId : mob.id();
			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(mob);
		}
		logSpawn(event + "_snapshot", "floor=" + Dungeon.depth + " groups=" + groups.size()
				+ " population=" + level.mobPopulationCount() + "/" + level.mobLimit());
		for (Map.Entry<Integer, ArrayList<Mob>> entry : groups.entrySet()) {
			StringBuilder members = new StringBuilder();
			for (Mob mob : entry.getValue()) {
				if (members.length() > 0) members.append(',');
				members.append(mob.getClass().getSimpleName()).append('#').append(mob.id())
						.append('@').append(mob.pos % level.width()).append(':').append(mob.pos / level.width());
			}
			logSpawn(event + "_squad", "floor=" + Dungeon.depth + " squadId=" + entry.getKey()
					+ " size=" + entry.getValue().size() + " members=" + members);
		}
	}

	/** Returns the living, eligible mobs grouped by their current squad key. */
	public static Map<Integer, ArrayList<Mob>> debugGroups(Level level) {
		Map<Integer, ArrayList<Mob>> groups = new LinkedHashMap<>();
		if (level == null || level.mobs == null) return groups;
		for (Mob mob : level.mobs) {
			if (mob == null || !mob.isAlive() || !isSquadEligible(mob)) continue;
			int key = mob.squadId >= 0 ? mob.squadId : mob.id();
			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(mob);
		}
		return groups;
	}

	public static void setSquadId(Mob mob, int squadId) {
		mob.squadId = squadId;
		mob.squadDisbanded = false;
	}

	/** Breaks up a squad when its selected tank/leader dies so survivors return to solo behavior. */
	public static void leaderDied(Mob leader) {
		if (leader == null || leader.squadId < 0 || !"tank".equals(leader.squadRole)) return;

		int squadId = leader.squadId;
		ArrayList<Mob> squad = members(Dungeon.level, squadId);
		if (squad.size() < 2) return;
		Mob successor = null;
		float bestToughness = Float.NEGATIVE_INFINITY;
		for (Mob member : squad) {
			if (member == leader || !MonsterStats.canSucceedLeader(member)) continue;
			float toughness = member.HT * 2f
					+ member.defenseSkill(Dungeon.hero) * member.spawnDefenseMultiplier
					+ 5f * member.spawnDrMultiplier;
			if (toughness > bestToughness) {
				bestToughness = toughness;
				successor = member;
			}
		}
		if (successor != null) {
			MonsterStats.assignRole(leader, "solo");
			MonsterStats.assignRole(successor, "tank");
			logSpawn("leader_succeeded", "floor=" + Dungeon.depth + " squadId=" + squadId
					+ " previous=" + leader.getClass().getSimpleName() + "#" + leader.id()
					+ " successor=" + successor.getClass().getSimpleName() + "#" + successor.id()
					+ " rewardBonus=false");
			return;
		}
		int survivors = 0;
		for (Mob member : squad) {
			if (member == leader) continue;
			member.squadId = -1;
			member.squadDisbanded = true;
			member.squadRoleSelectionPending = false;
			MonsterStats.assignRole(member, "solo");
			survivors++;
		}
		leader.squadId = -1;
		LEASHED_SQUADS.remove(squadId);
		JevMobAI.forgetSquad(squadId);
		logSpawn("squad_disbanded", "floor=" + Dungeon.depth + " squadId=" + squadId
				+ " leader=" + leader.getClass().getSimpleName() + "#" + leader.id()
				+ " survivors=" + survivors + " reason=leader_died");
	}

	public static void assign(Level level) {
		if (level == null || level.mobs == null) return;
		ArrayList<Mob> unassigned = new ArrayList<>();
		Map<Integer, Integer> assignedCounts = new HashMap<>();
		for (Mob mob : level.mobs) {
			if (mob != null) {
				if (!isSquadEligible(mob)) {
					mob.squadId = -1;
				} else if (mob.squadDisbanded) {
					mob.squadId = -1;
				} else if (mob.squadId >= 0
						&& assignedCounts.getOrDefault(mob.squadId, 0) < MAX_MEMBERS) {
					assignedCounts.put(mob.squadId, assignedCounts.getOrDefault(mob.squadId, 0) + 1);
				} else {
					mob.squadId = -1;
					unassigned.add(mob);
				}
			}
		}
		unassigned.sort(Comparator.comparingInt(Mob::id));
		while (!unassigned.isEmpty()) {
			Mob seed = unassigned.remove(0);
			ArrayList<Mob> squad = new ArrayList<>();
			squad.add(seed);
			while (squad.size() < MAX_MEMBERS) {
				Mob nearest = null;
				int nearestDistance = LINK_DISTANCE + 1;
				for (Mob candidate : unassigned) {
					int distance = LINK_DISTANCE + 1;
					for (Mob member : squad) {
						distance = Math.min(distance, level.distance(candidate.pos, member.pos));
					}
					if (distance < nearestDistance) {
						nearest = candidate;
						nearestDistance = distance;
					}
				}
				if (nearest == null || nearestDistance > LINK_DISTANCE) break;
				squad.add(nearest);
				unassigned.remove(nearest);
			}
			for (Mob member : squad) {
				member.squadId = seed.id();
				MonsterStats.assignRole(member, "solo");
			}
		}
		// Level.create() calls assign() while Dungeon.level is intentionally null.
		// Role scoring uses context-sensitive combat methods (for example, Piranha
		// defense checks its FOV through Dungeon.level), so defer it until
		// Dungeon.switchLevel() installs this level and calls assign() again.
		if (Dungeon.level == level) assignRoles(level);
	}

	/** Assigns a local fallback role from each member's realized spawn combat profile. */
	static void assignRoles(Level level) {
		for (ArrayList<Mob> squad : debugGroups(level).values()) assignRoles(squad);
	}

	public static void assignRoles(Mob... members) {
		ArrayList<Mob> squad = new ArrayList<>();
		java.util.Collections.addAll(squad, members);
		squad.sort(Comparator.comparingInt(Mob::id));
		assignRoles(squad);
	}

	/** Re-evaluates the local fallback after every member's spawn stats have been rolled. */
	static void refreshFallbackRoles(Mob spawned) {
		if (spawned == null || spawned.squadId < 0) return;
		ArrayList<Mob> squad = members(Dungeon.level, spawned.squadId);
		if (squad.size() < 2) return;
		for (Mob member : squad) if (member.firstAdded) return;
		for (Mob member : squad) MonsterStats.assignRole(member, "solo");
		assignRoles(squad);
	}

	private static void assignRoles(ArrayList<Mob> squad) {
		if (squad.isEmpty()) return;
		for (Mob member : squad) MonsterStats.migrateLoadedHP(member);
			if (squad.size() < 2) {
				MonsterStats.assignRole(squad.get(0), "solo");
				squad.get(0).squadRoleSelectionPending = false;
				return;
			}

			boolean hasAssignedRole = false;
			HashSet<String> usedRoles = new HashSet<>();
			for (Mob member : squad) {
				if (!"solo".equals(member.squadRole)) {
					hasAssignedRole = true;
					usedRoles.add(member.squadRole);
				}
			}
			if (!hasAssignedRole) {
				Mob tank = null;
				float bestToughness = Float.NEGATIVE_INFINITY;
				for (Mob member : squad) {
					float toughness = member.HT * 2f
							+ member.defenseSkill(Dungeon.hero) * member.spawnDefenseMultiplier
							+ 5f * member.spawnDrMultiplier;

					if (toughness > bestToughness) {
						bestToughness = toughness;
						tank = member;
					}
				}
				MonsterStats.assignRole(tank, "tank");
				usedRoles.add("tank");

				Mob dealer = null;
				float bestThreat = Float.NEGATIVE_INFINITY;
				for (Mob member : squad) {
					if (member == tank) continue;
					float threat = member.attackSkill(Dungeon.hero) * 2f * member.spawnAccuracyMultiplier
							* member.spawnDamageMultiplier + member.viewDistance;
					if (threat > bestThreat) {
						bestThreat = threat;
						dealer = member;
					}
				}
				if (dealer != null) {
					MonsterStats.assignRole(dealer, "dealer");
					usedRoles.add("dealer");
				}
				for (Mob member : squad) {
					if (member != tank && member != dealer) MonsterStats.assignRole(member, "support");
				}
			} else {
				String[] nextRoles = {"tank", "dealer", "support"};
				int nextRole = 0;
				for (Mob member : squad) {
					if (!"solo".equals(member.squadRole)) continue;
					while (nextRole < nextRoles.length && usedRoles.contains(nextRoles[nextRole])) nextRole++;
					String role = nextRole < nextRoles.length ? nextRoles[nextRole++] : "support";
					MonsterStats.assignRole(member, role);
					usedRoles.add(role);
				}
			}
			if (!hasAssignedRole || needsRoleSelection(squad)) {
				for (Mob member : squad) member.squadRoleSelectionPending = true;
			}
	}

	static boolean needsRoleSelection(ArrayList<Mob> squad) {
		for (Mob member : squad) if (member.squadRoleSelectionPending) return true;
		return false;
	}

	static void markRoleSelectionAttempted(Level level, int squadId) {
		ArrayList<Mob> squad = members(level, squadId);
		for (Mob member : squad) member.squadRoleSelectionPending = false;
		for (Mob member : squad) {
			if ("tank".equals(member.squadRole)) {
				MonsterStats.applyInitialLeaderBonus(member);
				break;
			}
		}
	}

	static boolean assignJevRoles(Level level, int squadId, Map<Integer, String> assignment) {
		ArrayList<Mob> squad = members(level, squadId);
		if (squad.size() < 2 || assignment.size() != squad.size()) return false;
		HashSet<String> uniqueRoles = new HashSet<>();
		for (Mob member : squad) {
			String role = assignment.get(member.id());
			if (role == null || !("tank".equals(role) || "dealer".equals(role) || "support".equals(role))
					|| !uniqueRoles.add(role)) return false;
		}
		for (Mob member : squad) {
			MonsterStats.assignRole(member, assignment.get(member.id()));
			member.squadRoleSelectionPending = false;
		}
		for (Mob member : squad) {
			if ("tank".equals(member.squadRole)) {
				MonsterStats.applyInitialLeaderBonus(member);
				break;
			}
		}
		return true;
	}

	/** Adds a newly spawned enemy to a nearby open squad, creating a squad if needed. */
	public static boolean assignSpawn(Level level, Mob mob) {
		return assignSpawn(level, mob, Integer.MAX_VALUE);
	}

	/**
	 * Assigns a spawn without exceeding the floor's squad cap. Existing squads
	 * are preferred; a new singleton squad is only created when capacity allows.
	 */
	public static boolean assignSpawn(Level level, Mob mob, int maxSquads) {
		mob.squadId = -1;
		mob.squadDisbanded = false;
		mob.squadRoleSelectionPending = false;
		if (level == null || !isSquadEligible(mob)) return true;
		Mob best = null;
		int bestDistance = LINK_DISTANCE + 1;
		for (Mob candidate : level.mobs) {
			if (candidate == null || candidate.squadId < 0 || !isSquadEligible(candidate)) continue;
			ArrayList<Mob> members = members(level, candidate.squadId);
			if (members.size() >= MAX_MEMBERS) continue;
			int distance = level.distance(mob.pos, candidate.pos);
			if (distance <= LINK_DISTANCE && distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		if (best != null) {
			mob.squadId = best.squadId;
			ArrayList<Mob> existing = members(level, best.squadId);
			existing.add(mob);
			for (Mob member : existing) MonsterStats.assignRole(member, "solo");
			assignRoles(existing);
			return true;
		}
		if (squadCount(level) >= maxSquads) return false;
		mob.squadId = mob.id();
		MonsterStats.assignRole(mob, "solo");
		return true;
	}

	/** Number of living squads, counting an eligible unassigned mob as a singleton. */
	public static int squadCount(Level level) {
		if (level == null || level.mobs == null) return 0;
		HashSet<Integer> squads = new HashSet<>();
		for (Mob mob : level.mobs) {
			if (mob == null || !mob.isAlive() || !isSquadEligible(mob)) continue;
			squads.add(mob.squadId >= 0 ? mob.squadId : mob.id());
		}
		return squads.size();
	}

	/** Keeps wandering members near their squad lead and pursues active fights together. */
	static void maintainCohesion(Mob mob) {
		Level level = Dungeon.level;
		if (level == null || mob.squadId < 0 || mob.state == mob.HUNTING || mob.state == mob.FLEEING) return;
		ArrayList<Mob> squad = members(level, mob.squadId);
		if (squad.size() < 2) return;

		Mob combatLead = null;
		for (Mob member : squad) {
			if (member != mob && member.state == member.HUNTING && member.enemy != null
					&& member.enemy.isAlive() && member.enemy.alignment != member.alignment) {
				combatLead = member;
				break;
			}
		}
		if (combatLead != null) {
			int target = combatLead.enemySeen ? combatLead.enemy.pos : combatLead.target;
			if (target >= 0 && target < level.length()) {
				if (mob.state != mob.WANDERING) mob.beckon(target);
				else mob.target = target;
			}
			return;
		}

		Mob lead = leader(squad);
		if (mob == lead && mob.state == mob.WANDERING) {
			Mob straggler = null;
			for (Mob member : squad) {
				if (member != lead
						&& (member.state == member.WANDERING || member.state == member.INVESTIGATING)
						&& level.distance(lead.pos, member.pos) > FOLLOW_DISTANCE) {
					straggler = member;
					break;
				}
			}
			if (straggler != null) {
				// Let active followers catch up before the leader chooses another
				// wandering destination. Otherwise the leader can walk away faster
				// than they can follow, especially through narrow corridors.
				mob.target = mob.pos;
				if (LEASHED_SQUADS.add(mob.squadId)) {
					logSpawn("squad_leash", "floor=" + Dungeon.depth + " squadId=" + mob.squadId
							+ " leader=" + lead.getClass().getSimpleName() + "#" + lead.id()
							+ "@" + lead.pos % level.width() + ":" + lead.pos / level.width()
							+ " straggler=" + straggler.getClass().getSimpleName() + "#" + straggler.id()
							+ "@" + straggler.pos % level.width() + ":" + straggler.pos / level.width());
				}
			} else {
				LEASHED_SQUADS.remove(mob.squadId);
			}
		}
		if (mob != lead && mob.state == mob.WANDERING
				&& level.distance(mob.pos, lead.pos) > FOLLOW_DISTANCE) {
			int followCell = nearestOpenFormationCell(level, mob, lead);
			if (followCell != -1 && mob.target != followCell) {
				mob.target = followCell;
				logSpawn("squad_follow", "floor=" + Dungeon.depth + " squadId=" + mob.squadId
						+ " member=" + mob.getClass().getSimpleName() + "#" + mob.id()
						+ "@" + mob.pos % level.width() + ":" + mob.pos / level.width()
						+ " leader=" + lead.getClass().getSimpleName() + "#" + lead.id()
						+ "@" + lead.pos % level.width() + ":" + lead.pos / level.width()
						+ " target=" + followCell % level.width() + ":" + followCell / level.width());
			}
		}
	}

	private static Mob leader(ArrayList<Mob> squad) {
		for (Mob member : squad) if ("tank".equals(member.squadRole)) return member;
		return squad.get(0);
	}

	private static int nearestOpenFormationCell(Level level, Mob mob, Mob lead) {
		int bestCell = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8) {
			int cell = lead.pos + offset;
			if (cell < 0 || cell >= level.length()
					|| Math.abs(cell % level.width() - lead.pos % level.width()) > 1
					|| Math.abs(cell / level.width() - lead.pos / level.width()) > 1
					|| !level.passable[cell] || level.avoid[cell] || Actor.findChar(cell) != null
					|| (Char.hasProp(mob, Char.Property.LARGE) && !level.openSpace[cell])) continue;
			int distance = level.distance(mob.pos, cell);
			if (distance < bestDistance) {
				bestCell = cell;
				bestDistance = distance;
			}
		}
		return bestCell;
	}

	static void alert(Mob source, int targetCell) {
		Level level = Dungeon.level;
		if (source.squadId < 0 || level == null) return;
		logSpawn("squad_alert", "floor=" + Dungeon.depth + " squadId=" + source.squadId
				+ " source=" + source.getClass().getSimpleName() + "#" + source.id()
				+ "@" + source.pos % level.width() + ":" + source.pos / level.width()
				+ " target=" + targetCell % level.width() + ":" + targetCell / level.width());
		for (Mob member : level.mobs) {
			if (member != source && member.squadId == source.squadId && member.isAlive()
					&& member.state != member.HUNTING && member.state != member.FLEEING
					&& level.distance(source.pos, member.pos) <= 7) {
				member.beckon(targetCell);
			}
		}
	}

	static ArrayList<Mob> members(Level level, int squadId) {
		ArrayList<Mob> result = new ArrayList<>();
		if (level == null) return result;
		for (Mob mob : level.mobs) {
			if (mob != null && mob.squadId == squadId && mob.isAlive()) result.add(mob);
		}
		result.sort(Comparator.comparingInt(Mob::id));
		return result;
	}

	private static boolean isSquadEligible(Mob mob) {
		// Room-authored Piranhas and Golems are fixed set pieces, not roaming squads.
		if (mob != null) {
			String type = mob.getClass().getSimpleName();
			if ("Piranha".equals(type) || "Golem".equals(type)) return false;
		}
		HashSet<Char.Property> properties = mob.properties();
		return mob.alignment == Char.Alignment.ENEMY
				&& !properties.contains(Char.Property.BOSS)
				&& !properties.contains(Char.Property.MINIBOSS)
				&& !properties.contains(Char.Property.IMMOVABLE);
	}
}
