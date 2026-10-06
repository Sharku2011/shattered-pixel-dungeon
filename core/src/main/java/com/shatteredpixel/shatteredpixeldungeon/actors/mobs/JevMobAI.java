/*
 * Copyright (C) 2026 Shattered Pixel Dungeon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.utils.DeviceCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Optional batched Jev policy for the standard monster hunting state. */
public final class JevMobAI {
	private static final String ENDPOINT = "https://api.typesafe.ai/v1/systemone";
	private static final String MODEL = "jev-latest";
	private static final int TIMEOUT_MS = 1500;
	private static final JsonReader JSON_READER = new JsonReader();
	private static final Map<Integer, Plan> plans = new LinkedHashMap<>();
	private static final int MAX_DEBUG_EVENTS = 40;
	private static final ArrayDeque<String> debugEvents = new ArrayDeque<>();
	private static final SimpleDateFormat DEBUG_TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
	private static Level cachedLevel;
	private static int cachedDepth = Integer.MIN_VALUE;
	private static final float PLAN_LIFETIME = 12f;

	private static final class Plan {
		String tactic = "advance";
		String composition;
		String heroHealthBand;
		int heroPosition;
		float createdAt;
		boolean requestAttempted;
		final Map<Integer, Integer> moveGoals = new HashMap<>();
		final Map<Integer, String> moveGoalTypes = new HashMap<>();
		SquadMovementPlanner.SquadPlan squadPlan;
		FormationPlanner.State formation;
		boolean degradeLogged;
		/** Tactic that squadPlan/formation were built for; a different tactic discards both. */
		String plannedTactic;
	}

	private JevMobAI() {}

	static String tacticFor(Mob mob) {
		if (mob.squadId < 0 || Dungeon.level == null) return "advance";
		ArrayList<Mob> members = MobSquads.members(Dungeon.level, mob.squadId);
		if (members.size() < 2) return "advance";
		String key = apiKey();
		if (Dungeon.hero == null || mob.state != mob.HUNTING) return "advance";
		if (cachedLevel != Dungeon.level || cachedDepth != Dungeon.depth) {
			plans.clear();
			cachedLevel = Dungeon.level;
			cachedDepth = Dungeon.depth;
		}

		int squadKey = mob.squadId;
		String signature = composition(members);
		String heroBand = healthBand(Dungeon.hero.HP, Dungeon.hero.HT);
		Plan plan = plans.get(squadKey);
		if (plan == null || !plan.composition.equals(signature) || !plan.heroHealthBand.equals(heroBand)
				|| Dungeon.level.distance(plan.heroPosition, Dungeon.hero.pos) >= 6
				|| Actor.now() - plan.createdAt >= PLAN_LIFETIME) {
			plan = new Plan();
			plan.composition = signature;
			plan.heroHealthBand = heroBand;
			plan.heroPosition = Dungeon.hero.pos;
			plan.createdAt = Actor.now();
			plans.put(squadKey, plan); // cache a safe local plan before attempting the network
			if (key != null && mob.enemy == Dungeon.hero) requestBatch(key);
		}
		return plan.tactic;
	}

	static synchronized void forgetSquad(int squadId) {
		plans.remove(squadId);
	}

	/** Local squad step for this mob: -1 = default AI, mob.pos = wait, otherwise the cell to move to. */
	static int tacticalStep(Mob mob, String tactic) {
		Level level = Dungeon.level;
		Hero hero = Dungeon.hero;
		if (mob == null || level == null || hero == null || tactic == null || mob.squadId < 0 || mob.enemy != hero) return -1;
		boolean[] fov = mob.fieldOfView;
		if (fov == null || hero.pos < 0 || hero.pos >= fov.length || !fov[hero.pos]) return -1;
		boolean squadTactic = "flank".equals(tactic) || "escort_ranged".equals(tactic);
		if (!squadTactic && !"formation".equals(tactic)) return -1;
		Plan plan = plans.get(mob.squadId);
		if (plan == null) return -1;
		ArrayList<Mob> mobs = MobSquads.members(level, mob.squadId);
		ArrayList<SquadMovementPlanner.Member> squad = new ArrayList<>();
		SquadMovementPlanner.Member mover = null;
		for (Mob member : mobs) {
			SquadMovementPlanner.Member planned = plannerMember(member);
			squad.add(planned);
			if (member == mob) mover = planned;
		}
		if (mover == null || squad.size() < 2) return -1;
		if (!tactic.equals(plan.plannedTactic)) {
			plan.plannedTactic = tactic;
			plan.squadPlan = null;
			plan.formation = null;
			plan.degradeLogged = false;
		}
		SquadWorld world = gameWorld(level, mobs);
		return squadTactic ? squadStep(plan, tactic, mob, mover, squad, mobs, hero.pos, world)
				: formationStep(plan, tactic, mob, mover, squad, hero.pos, world);
	}

	private static int squadStep(Plan plan, String tactic, Mob mob, SquadMovementPlanner.Member mover,
			ArrayList<SquadMovementPlanner.Member> squad, ArrayList<Mob> mobs, int heroCell, SquadWorld world) {
		if (plan.squadPlan == null) assignSquad(plan, tactic, squad, mobs, heroCell, world);
		SquadMovementPlanner.Assignment a = plan.squadPlan.byMember.get(mover.id);
		if (a != null && !SquadMovementPlanner.goalLegal(mover, a.goal, heroCell, world)) {
			SquadMovementPlanner.reassign(plan.squadPlan, mover, squad, heroCell, world);
			a = plan.squadPlan.byMember.get(mover.id);
			logReassigned(mob, a, "invalid");
		}
		if (a != null && a.heroCell != heroCell) {
			if (SquadMovementPlanner.retarget(plan.squadPlan, mover, squad, heroCell, world)) {
				logReassigned(mob, a, "hero_moved");
			} else {
				assignSquad(plan, tactic, squad, mobs, heroCell, world);
				a = plan.squadPlan.byMember.get(mover.id);
				logReassigned(mob, a, "sector_lost");
			}
		}
		if (a != null && "escort".equals(a.maneuver)) {
			SquadMovementPlanner.Member ally = SquadMovementPlanner.rangedAlly(mover, squad, world);
			if (ally == null || ally.cell != a.allyCell) {
				assignSquad(plan, tactic, squad, mobs, heroCell, world);
				a = plan.squadPlan.byMember.get(mover.id);
				logReassigned(mob, a, "ally_moved");
			}
		}
		if (a == null) return -1;
		// An escort standing on its goal holds the screen instead of drifting back to the default AI.
		if ("escort".equals(a.maneuver) && a.goal == mob.pos) return mob.pos;
		int step = SquadMovementPlanner.nextStep(plan.squadPlan, mover, squad, heroCell, world);
		if (step < 0 && a.goal != mob.pos) logMoveBlocked(mob, tactic, mob.pos);
		return step;
	}

	private static void assignSquad(Plan plan, String tactic, ArrayList<SquadMovementPlanner.Member> squad,
			ArrayList<Mob> mobs, int heroCell, SquadWorld world) {
		plan.squadPlan = SquadMovementPlanner.assign(tactic, squad, heroCell, world);
		for (SquadMovementPlanner.Assignment a : plan.squadPlan.byMember.values()) {
			log("move_goal", "member=" + memberLabel(a.memberId, mobs) + " maneuver=" + a.maneuver
					+ " destination=" + coordinates(a.goal));
		}
		logDegraded(plan, tactic, plan.squadPlan.degradeReason, mobs.get(0).squadId);
	}

	private static int formationStep(Plan plan, String tactic, Mob mob, SquadMovementPlanner.Member mover,
			ArrayList<SquadMovementPlanner.Member> squad, int heroCell, SquadWorld world) {
		String reason = FormationPlanner.degradeReason(squad);
		if ("too_few_melee".equals(reason)) {
			logDegraded(plan, tactic, reason, mob.squadId);
			return -1;
		}
		String before = null;
		if (plan.formation == null) {
			plan.formation = new FormationPlanner.State();
			logDegraded(plan, tactic, reason, mob.squadId);
		} else before = plan.formation.phase;
		int step = FormationPlanner.step(plan.formation, mover, squad, heroCell, world);
		String after = plan.formation.phase;
		String member = mob.getClass().getSimpleName() + "#" + mob.id();
		if ("released".equals(after)) {
			if (!"released".equals(before)) log("formation_released", "squad=" + mob.squadId + " member=" + member
					+ " reason=" + plan.formation.releaseReason);
		} else if (!after.equals(before)) {
			log("formation_phase", "squad=" + mob.squadId + " member=" + member + " phase=" + after);
		}
		return step == FormationPlanner.RELEASED ? -1 : step;
	}

	private static void logDegraded(Plan plan, String tactic, String reason, int squadId) {
		if (reason == null || plan.degradeLogged) return;
		plan.degradeLogged = true;
		log("tactic_degraded", "squad=" + squadId + " tactic=" + tactic + " reason=" + reason);
	}

	private static void logReassigned(Mob mob, SquadMovementPlanner.Assignment a, String reason) {
		log("move_goal_reassigned", "member=" + mob.getClass().getSimpleName() + "#" + mob.id()
				+ " maneuver=" + (a == null ? "none" : a.maneuver)
				+ " destination=" + (a == null ? "none" : coordinates(a.goal)) + " reason=" + reason);
	}

	private static String memberLabel(int id, List<Mob> mobs) {
		for (Mob m : mobs) if (m.id() == id) return m.getClass().getSimpleName() + "#" + id;
		return "Mob#" + id;
	}

	/** Game adapter for the pure planners; passability is copied per member because findPassable returns a shared array. */
	private static SquadWorld gameWorld(final Level level, List<Mob> squad) {
		final Map<Integer, Mob> byId = new HashMap<>();
		for (Mob m : squad) byId.put(m.id(), m);
		final Map<Integer, boolean[]> passable = new HashMap<>();
		return new SquadWorld() {
			@Override public int width() { return level.width(); }
			@Override public int height() { return level.height(); }
			@Override public boolean passable(SquadMovementPlanner.Member mover, int cell) {
				if (cell < 0 || cell >= level.length()) return false;
				boolean[] cells = passable.get(mover.id);
				if (cells == null) {
					Mob mob = byId.get(mover.id);
					if (mob == null) return false;
					cells = Dungeon.findPassable(mob, level.passable, mob.fieldOfView, false, true).clone();
					passable.put(mover.id, cells);
				}
				return cell < cells.length && cells[cell];
			}
			@Override public boolean occupied(int cell) {
				return cell >= 0 && cell < level.length() && Actor.findChar(cell) != null;
			}
			@Override public boolean visible(SquadMovementPlanner.Member mover, int cell) {
				Mob mob = byId.get(mover.id);
				boolean[] fov = mob == null ? null : mob.fieldOfView;
				return fov != null && cell >= 0 && cell < fov.length && fov[cell];
			}
		};
	}

	/** {maneuver, goal} for the move logs: the squad assignment, the hero cell for formation, else the tactic and none. */
	private static String[] stepContext(Mob mob, String tactic) {
		Plan plan = mob.squadId < 0 ? null : plans.get(mob.squadId);
		SquadMovementPlanner.Assignment a = plan == null || plan.squadPlan == null ? null : plan.squadPlan.byMember.get(mob.id());
		if (a != null) return new String[]{a.maneuver, coordinates(a.goal)};
		if ("formation".equals(tactic) && Dungeon.hero != null) return new String[]{"formation", coordinates(Dungeon.hero.pos)};
		return new String[]{tactic, "none"};
	}

	static void logMoveStep(Mob mob, String tactic, int from, int to) {
		String[] context = stepContext(mob, tactic);
		log("move_step", "member=" + mob.getClass().getSimpleName() + "#" + mob.id()
				+ " maneuver=" + context[0] + " from=" + coordinates(from) + " to=" + coordinates(to)
				+ " goal=" + context[1]);
	}

	static void logMoveBlocked(Mob mob, String tactic, int from) {
		String[] context = stepContext(mob, tactic);
		log("move_step_blocked", "member=" + mob.getClass().getSimpleName() + "#" + mob.id()
				+ " maneuver=" + context[0] + " from=" + coordinates(from) + " goal=" + context[1]);
	}

	private static String coordinates(int cell) {
		return Dungeon.level == null || Dungeon.level.width() <= 0 ? String.valueOf(cell)
				: cell % Dungeon.level.width() + ":" + cell / Dungeon.level.width();
	}

	private static String composition(ArrayList<Mob> members) {
		StringBuilder signature = new StringBuilder();
		for (Mob member : members) signature.append(member.id()).append(':').append(member.getClass().getSimpleName()).append(';');
		return signature.toString();
	}

	private static String healthBand(int hp, int maxHp) {
		return hp * 3 < maxHp ? "critical" : hp * 3 < maxHp * 2 ? "wounded" : "healthy";
	}

	private static String apiKey() {
		String key = System.getProperty("typesafe.api.key");
		if (key == null || key.trim().isEmpty()) key = System.getenv("TYPESAFE_API_KEY");
		if (key == null || key.trim().isEmpty()) {
			String keyFile = System.getProperty("typesafe.api.key.file", "jev_api_key(pd).txt");
			try {
				key = new String(Files.readAllBytes(Paths.get(keyFile)), StandardCharsets.UTF_8);
			} catch (Exception ignored) {
				// A missing local development key file leaves the integration disabled.
			}
		}
		return key == null || key.trim().isEmpty() ? null : key.trim();
	}

	private static void requestBatch(String key) {
		Level level = Dungeon.level;
		if (level == null || Dungeon.hero == null) return;
		ArrayList<Integer> requestedSquads = new ArrayList<>();
		Map<Integer, ArrayList<Mob>> candidatesBySquad = new LinkedHashMap<>();
		for (Mob candidate : level.mobs) {
			if (candidate == null || !candidate.isAlive() || !candidate.isActive() || candidate.state != candidate.HUNTING
					|| candidate.squadId < 0
					|| candidate.enemy != Dungeon.hero || level.distance(candidate.pos, Dungeon.hero.pos) > 12) continue;
			int squad = candidate.squadId;
			ArrayList<Mob> engaged = candidatesBySquad.get(squad);
			if (engaged == null) {
				engaged = new ArrayList<>();
				candidatesBySquad.put(squad, engaged);
			}
			if (!engaged.contains(candidate)) engaged.add(candidate);
		}
		for (Integer squad : candidatesBySquad.keySet()) requestedSquads.add(squad);
		requestedSquads.removeIf(squad -> {
			ArrayList<Mob> members = MobSquads.members(level, squad);
			if (members.isEmpty()) members.addAll(candidatesBySquad.get(squad));
			if (members.size() < 2) return true;
			Plan plan = plans.get(squad);
			if (plan == null) {
				plan = new Plan();
				plan.composition = composition(members);
				plan.heroHealthBand = healthBand(Dungeon.hero.HP, Dungeon.hero.HT);
				plan.heroPosition = Dungeon.hero.pos;
				plan.createdAt = Actor.now();
				plans.put(squad, plan);
			}
			if (!plan.requestAttempted) {
				plan.requestAttempted = true;
				return false;
			}
			return true;
		});
		if (requestedSquads.isEmpty()) return;
		candidatesBySquad.keySet().removeIf(squad -> !requestedSquads.contains(squad));

		Map<String, Object> state = new LinkedHashMap<>();
		state.put("floor", Dungeon.depth);
		state.put("branch", Dungeon.branch);
		state.put("floorType", level.getClass().getSimpleName());
		state.put("floorFeeling", level.feeling == null ? "NONE" : level.feeling.name());
		state.put("mapWidth", level.width());
		state.put("mapHeight", level.height());
		state.put("mapLegend", "Per-member maps show only that monster's current field of view; ? means not currently observed.");

		Map<String, Object> questions = new LinkedHashMap<>();
		Map<String, Map<String, Map<Integer, String>>> roleChoiceOptions = new LinkedHashMap<>();
		Map<Integer, String> roleQuestionKeys = new LinkedHashMap<>();
		Map<String, Map<String, SquadMovementPlanner.Candidate>> destinationChoiceOptions = new LinkedHashMap<>();
		Map<String, Integer> destinationQuestionSquads = new LinkedHashMap<>();
		Map<String, Integer> destinationQuestionMembers = new LinkedHashMap<>();
		for (Map.Entry<Integer, ArrayList<Mob>> entry : candidatesBySquad.entrySet()) {
			int squadId = entry.getKey();
			ArrayList<Mob> members = MobSquads.members(level, squadId);
			if (members.isEmpty()) members.addAll(entry.getValue());
			boolean hasRangedAlly = false;
			for (Mob member : members) {
				hasRangedAlly |= isRangedAttacker(member);
			}
			HashSet<Integer> flankDirections = new HashSet<>();
			int flankMembersWithCandidates = 0;
			boolean hasEscortCandidates = false;
			Map<Integer, ArrayList<SquadMovementPlanner.Candidate>> movementOptionsByMember = new LinkedHashMap<>();
			Map<String, Object> squadState = new LinkedHashMap<>();
			ArrayList<Map<String, Object>> memberStates = new ArrayList<>();
			for (Mob member : members) {
				Map<String, Object> memberObservation = observation(member, level);
				Map<String, Object> data = characterState(member, level);
				data.put("id", member.id());
				data.put("squadId", member.squadId);
				data.put("intelligence", member.tacticalIntelligence());
				data.put("attackSkill", member.attackSkill(Dungeon.hero));
				data.put("defenseSkill", member.defenseSkill(Dungeon.hero));
				data.put("balanceProfile", MonsterStats.jevBalanceProfile(member));
				data.put("isRangedAttacker", isRangedAttacker(member));
				data.put("observation", memberObservation);
				memberStates.add(data);

				ArrayList<SquadMovementPlanner.Candidate> candidates = movementCandidates(member, members, level);
				movementOptionsByMember.put(member.id(), candidates);
				boolean memberCanFlank = false;
				for (SquadMovementPlanner.Candidate candidate : candidates) {
					if ("flank".equals(candidate.maneuver)) {
						memberCanFlank = true;
						flankDirections.add(SquadMovementPlanner.directionBucket(
								candidate.cell, Dungeon.hero.pos, level.width()));
					}
					if ("escort".equals(candidate.maneuver)) hasEscortCandidates = true;
				}
				if (memberCanFlank) flankMembersWithCandidates++;
			}
			boolean flankAvailable = members.size() >= 2 && flankMembersWithCandidates >= 1;
			ArrayList<String> flankApproachDirections = new ArrayList<>();
			for (int direction = 0; direction < 8; direction++) {
				if (flankDirections.contains(direction)) {
					flankApproachDirections.add(SquadMovementPlanner.directionName(direction));
				}
			}
			squadState.put("members", memberStates);
			squadState.put("memberCount", members.size());
			squadState.put("flankMembersWithReachableGoals", flankMembersWithCandidates);
			squadState.put("flankApproachDirections", flankApproachDirections);
			squadState.put("escortGoalAvailable", hasEscortCandidates);
			state.put("squad_" + squadId, squadState);
			Map<String, Object> criteria = new LinkedHashMap<>();
			criteria.put("advance", "Press the hero, close distance, and attack when legal. Use this when no other offered maneuver creates a useful advantage.");
			if (flankAvailable) criteria.put("flank", "Send members with listed flank goals toward open positions around the hero; squadmates without a goal keep pressuring normally.");
			if (hasEscortCandidates) criteria.put("escort_ranged", "The tank screens a ranged squadmate by moving between that ally and the hero; other tactical members create pressure around the hero.");
			if (hasRangedAlly) criteria.put("hold_range", "Ranged monsters preserve distance and attack when a legal shot is available; others advance as needed.");
			Map<String, Object> question = new LinkedHashMap<>();
			question.put("type", "choice");
			question.put("instructions", "Choose the maneuver that gives this squad the clearest tactical advantage; do not select advance automatically when another offered maneuver is useful. "
					+ "Choose one short combat maneuver for the whole squad, not an individual attack. "
					+ "advance means close in and use each monster's normal legal attacks. "
					+ "flank means send members with listed goal cells toward open positions around the hero; other members pressure the hero normally. "
					+ "It is a coordinated approach, not a special attack, and a flanker may need several local pathfinding turns to reach a goal. "
					+ "escort_ranged means the tank moves between the hero and a ranged ally while other eligible members pressure from another angle. "
					+ "hold_range means ranged members keep distance when they can shoot; melee members still approach. "
					+ "Each member's observation map and visibleCharacters show only what that monster can currently see; '?' and unseen hero details are unknown. "
					+ "enemyDistance and listed goal distance are Chebyshev grid distances (a diagonal step counts as one), not route lengths. "
					+ "flankMembersWithReachableGoals and flankApproachDirections summarize legal, visible destinations; flank is offered when at least one member has a reachable flank goal. "
					+ "Member balanceProfile multipliers are fixed at spawn; role labels do not change stats. "
					+ "Use floor, visible terrain, squad roles and intelligence to choose. For flank or escort_ranged, coordinate with the separate destination questions. "
					+ "The game validates goals, pathfinds locally, and uses normal combat after movement. Never choose a maneuver omitted from criteria.");
			question.put("criteria", criteria);
			questions.put("squad_" + squadId, question);

			boolean roleSelectionPending = MobSquads.needsRoleSelection(members) && members.size() >= 2;
			if (flankAvailable || hasEscortCandidates) {
				for (Mob member : members) {
					ArrayList<SquadMovementPlanner.Candidate> candidates = movementOptionsByMember.get(member.id());
					if (candidates == null || candidates.isEmpty()) continue;
					Map<String, SquadMovementPlanner.Candidate> options = new LinkedHashMap<>();
					Map<String, Object> moveCriteria = new LinkedHashMap<>();
					boolean isTank = "tank".equals(member.squadRole);
					for (SquadMovementPlanner.Candidate candidate : candidates) {
						if (!SquadMovementPlanner.relevantForTactics(candidate, flankAvailable,
								hasEscortCandidates, roleSelectionPending, isTank)) continue;
						int x = candidate.cell % level.width();
						int y = candidate.cell / level.width();
						String choice = SquadMovementPlanner.choiceKey(candidate, level.width());
						options.put(choice, candidate);
						moveCriteria.put(choice, moveChoiceDescription(candidate, x, y, member, members, level));
					}
					if (options.isEmpty()) continue;
					String moveQuestionKey = "move_squad_" + squadId + "_member_" + member.id();
					Map<String, Object> moveQuestion = new LinkedHashMap<>();
					moveQuestion.put("type", "choice");
					moveQuestion.put("instructions", "Choose one listed goal cell for this member, only if it matches the squad maneuver. "
							+ "A flank_* goal moves toward a different compass sector around the hero; it does not attack by itself. "
							+ "An escort_* goal is for the tank to screen a visible ranged ally. The game takes one local pathfinding step per movement turn and attacks normally when able. "
							+ "The maneuver and goals are answered in parallel, so choose compatible options and different flank sectors for different members. "
							+ "Every listed goal is visible, passable and reachable now. Coordinates are (x,y); distances use Chebyshev steps (a diagonal counts as one), not route length. Never invent a goal.");
					moveQuestion.put("criteria", moveCriteria);
					questions.put(moveQuestionKey, moveQuestion);
					destinationChoiceOptions.put(moveQuestionKey, options);
					destinationQuestionSquads.put(moveQuestionKey, squadId);
					destinationQuestionMembers.put(moveQuestionKey, member.id());
				}
			}

			if (roleSelectionPending) {
				String roleQuestionKey = "roles_squad_" + squadId;
				ArrayList<String> roles = new ArrayList<>();
				roles.add("tank");
				roles.add("dealer");
				if (members.size() >= 3) roles.add("support");
				Map<String, Map<Integer, String>> options = new LinkedHashMap<>();
				Map<String, Object> roleCriteria = new LinkedHashMap<>();
				buildRoleOptions(0, members, roles, new boolean[members.size()], new LinkedHashMap<Integer, String>(),
						options, roleCriteria);
				Map<String, Object> roleQuestion = new LinkedHashMap<>();
				roleQuestion.put("type", "choice");
				roleQuestion.put("instructions", "Assign one distinct tactical role to each member of this squad. "
						+ "Select tank for the member best able to lead and absorb attacks, using fixed spawned HP and defense. "
						+ "Select dealer for the member with the strongest expected damage and accuracy, considering its type, attackSkill, "
						+ "and fixed spawn multipliers. Use support for any remaining member. These roles are labels only and do not change stats. "
						+ "Choose exactly one assignment from criteria.");
				roleQuestion.put("criteria", roleCriteria);
				questions.put(roleQuestionKey, roleQuestion);
				roleChoiceOptions.put(roleQuestionKey, options);
				roleQuestionKeys.put(squadId, roleQuestionKey);
			}
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", MODEL);
		body.put("state", state);
		body.put("questions", questions);
		String requestJson = toJson(body);
		log("request", requestJson);
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Authorization", "Bearer " + key);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			byte[] request = requestJson.getBytes(StandardCharsets.UTF_8);
			try (OutputStream output = connection.getOutputStream()) {
				output.write(request);
			}
			int statusCode = connection.getResponseCode();
			InputStream responseStream = statusCode == HttpURLConnection.HTTP_OK
					? connection.getInputStream() : connection.getErrorStream();
			String responseBody = "";
			if (responseStream != null) try (InputStream input = responseStream;
					BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) response.append(line);
				responseBody = response.toString();
			}
			log("response", "http=" + statusCode + " body=" + responseBody);
			if (statusCode != HttpURLConnection.HTTP_OK) return;
			{
				JsonValue answers = JSON_READER.parse(responseBody).get("answers");
				if (answers == null) return;
				Map<Integer, String> tacticAnnouncements = new LinkedHashMap<>();
				for (Integer squadId : requestedSquads) {
					JsonValue answer = answers.get("squad_" + squadId);
					if (answer != null && answer.has("choice")) {
						String choice = answer.getString("choice");
						float confidence = answer.has("confidence") ? answer.getFloat("confidence") : 0f;
						if ("advance".equals(choice) || "flank".equals(choice)
								|| "escort_ranged".equals(choice) || "hold_range".equals(choice)) {
							if (confidence >= 0.45f) {
								plans.get(squadId).tactic = choice;
								tacticAnnouncements.put(squadId, choice);
								log("decision", "squad=" + squadId + " tactic=" + choice + " confidence=" + confidence);
							}
							else log("rejected", "squad=" + squadId + " choice=" + choice + " confidence=" + confidence + " threshold=0.45");
						} else {
							log("rejected", "squad=" + squadId + " unknown_choice=" + choice);
						}
					}
				}
				for (Map.Entry<Integer, String> entry : roleQuestionKeys.entrySet()) {
					int squadId = entry.getKey();
					JsonValue answer = answers.get(entry.getValue());
					if (answer == null || !answer.has("choice")) continue;
					String choice = answer.getString("choice");
					float confidence = answer.has("confidence") ? answer.getFloat("confidence") : 0f;
					Map<Integer, String> assignment = roleChoiceOptions.get(entry.getValue()).get(choice);
					if (assignment != null && confidence >= 0.45f
							&& MobSquads.assignJevRoles(level, squadId, assignment)) {
						log("role_assignment", "squad=" + squadId + " roles=" + assignment + " confidence=" + confidence);
					} else {
						log("role_fallback", "squad=" + squadId + " choice=" + choice + " confidence=" + confidence);
					}
				}
				for (Map.Entry<Integer, String> entry : tacticAnnouncements.entrySet()) {
					Mob leader = MobSquads.leader(level, entry.getKey());
					if (leader != null && leader.isAlive()) {
						leader.yell(Messages.get(JevMobAI.class, "tactic_" + entry.getValue()));
					}
				}
				for (Map.Entry<String, Map<String, SquadMovementPlanner.Candidate>> entry : destinationChoiceOptions.entrySet()) {
					JsonValue answer = answers.get(entry.getKey());
					if (answer == null || !answer.has("choice")) continue;
					String choice = answer.getString("choice");
					float confidence = answer.has("confidence") ? answer.getFloat("confidence") : 0f;
					Integer squadId = destinationQuestionSquads.get(entry.getKey());
					Integer memberId = destinationQuestionMembers.get(entry.getKey());
					Plan plan = squadId == null ? null : plans.get(squadId);
					Mob member = memberId == null ? null : findMember(level, memberId);
					String maneuver = requiredManeuver(plan, member);
					SquadMovementPlanner.Candidate offered = entry.getValue().get(choice);
					SquadMovementPlanner.Candidate destination = SquadMovementPlanner.validateChoice(
							choice, entry.getValue(), member == null ? null : plannerMember(member), maneuver,
							member == null ? null : movementWorld(member, level));
					if (destination == null || confidence < 0.45f) {
						String reason = confidence < 0.45f ? "low_confidence"
								: offered == null ? "choice_not_offered"
								: maneuver == null || !maneuver.equals(offered.maneuver) ? "tactic_mismatch"
								: "candidate_invalidated";
						log("move_goal_rejected", "question=" + entry.getKey() + " choice=" + choice
								+ " confidence=" + confidence + " expectedManeuver=" + maneuver + " reason=" + reason);
						continue;
					}
					boolean duplicate = false;
					for (Map.Entry<Integer, Integer> assigned : plan.moveGoals.entrySet()) {
						if (assigned.getValue() == destination.cell && assigned.getKey() != destination.memberId) {
							duplicate = true;
							break;
						}
					}
					boolean sameApproach = SquadMovementPlanner.conflictsWithAssignedFlank(destination,
							plan.moveGoals, plan.moveGoalTypes, Dungeon.hero.pos, level.width());
					if (duplicate || sameApproach) {
						log("move_goal_rejected", "member=" + destination.memberId + " choice=" + choice
								+ " confidence=" + confidence + " reason=" + (duplicate ? "duplicate_destination" : "duplicate_flank_sector"));
						continue;
					}
					plan.moveGoals.put(destination.memberId, destination.cell);
					plan.moveGoalTypes.put(destination.memberId, destination.maneuver);
					log("move_goal", "member=" + member.getClass().getSimpleName() + "#" + member.id()
							+ " maneuver=" + destination.maneuver + " destination="
							+ destination.cell % level.width() + ":" + destination.cell / level.width()
							+ " confidence=" + confidence);
				}
				for (Integer squadId : requestedSquads) {
					Plan plan = plans.get(squadId);
					if (plan == null || !("flank".equals(plan.tactic) || "escort_ranged".equals(plan.tactic))) continue;
					ArrayList<Mob> members = MobSquads.members(level, squadId);
					for (Mob member : members) {
						String maneuver = requiredManeuver(plan, member);
						if (maneuver == null || plan.moveGoals.containsKey(member.id())) continue;
						for (SquadMovementPlanner.Candidate fallback : movementCandidates(member, members, level)) {
							if (!maneuver.equals(fallback.maneuver) || !validMovementGoal(member, fallback.cell, level)) continue;
							boolean duplicate = plan.moveGoals.containsValue(fallback.cell);
							boolean sameApproach = SquadMovementPlanner.conflictsWithAssignedFlank(fallback,
									plan.moveGoals, plan.moveGoalTypes, Dungeon.hero.pos, level.width());
							if (duplicate || sameApproach) continue;
							plan.moveGoals.put(member.id(), fallback.cell);
							plan.moveGoalTypes.put(member.id(), maneuver);
							log("move_goal_fallback", "member=" + member.getClass().getSimpleName() + "#" + member.id()
									+ " maneuver=" + maneuver + " destination=" + fallback.cell % level.width()
								+ ":" + fallback.cell / level.width() + " reason=missing_or_rejected_answer");
							break;
						}
					}
				}
			}
		} catch (Exception ignored) {
			// Keep the built-in Mob state machine as a no-network/failure fallback.
			log("fallback", "reason=" + ignored.getClass().getSimpleName());
		} finally {
			if (connection != null) connection.disconnect();
			for (Integer squadId : roleQuestionKeys.keySet()) MobSquads.markRoleSelectionAttempted(level, squadId);
		}
	}

	private static Mob findMember(Level level, int mobId) {
		if (level == null || level.mobs == null) return null;
		for (Mob member : level.mobs) if (member != null && member.id() == mobId && member.isAlive()) return member;
		return null;
	}

	private static String requiredManeuver(Plan plan, Mob member) {
		if (plan == null || member == null) return null;
		if ("flank".equals(plan.tactic)) return "flank";
		if ("escort_ranged".equals(plan.tactic)) return "tank".equals(member.squadRole) ? "escort" : "flank";
		return null;
	}

	private static boolean isRangedAttacker(Mob mob) {
		String type = mob.getClass().getSimpleName();
		return type.contains("Shaman") || type.contains("Necromancer") || type.contains("Warlock")
				|| type.equals("DM100") || type.equals("DM200") || type.equals("DM201")
				|| type.equals("Eye") || type.equals("YogEye") || type.equals("Scorpio") || type.equals("YogScorpio")
				|| type.equals("Succubus") || type.contains("Elemental");
	}

	private static SquadMovementPlanner.Member plannerMember(Mob mob) {
		return new SquadMovementPlanner.Member(mob.id(), mob.pos,
				!"instinctive".equals(mob.tacticalIntelligence()), isRangedAttacker(mob), mob.squadRole, mob.speed());
	}

	private static SquadMovementPlanner.World movementWorld(final Mob member, final Level level) {
		final boolean[] visible = member.fieldOfView;
		return new SquadMovementPlanner.World() {
			@Override public boolean visible(int cell) {
				return visible != null && cell >= 0 && cell < visible.length && visible[cell];
			}
			@Override public boolean legal(SquadMovementPlanner.Member mover, int cell) {
				return legalVisibleGoal(member, cell, level);
			}
			@Override public boolean reachable(SquadMovementPlanner.Member mover, int cell) {
				return cell == member.pos || Dungeon.findPath(member, cell, level.passable, visible, true) != null;
			}
		};
	}

	private static ArrayList<SquadMovementPlanner.Candidate> movementCandidates(Mob member,
			ArrayList<Mob> squad, Level level) {
		ArrayList<SquadMovementPlanner.Member> plannerSquad = new ArrayList<>();
		SquadMovementPlanner.Member plannerMover = null;
		for (Mob mob : squad) {
			SquadMovementPlanner.Member plannerMember = plannerMember(mob);
			plannerSquad.add(plannerMember);
			if (mob == member) plannerMover = plannerMember;
		}
		if (plannerMover == null || Dungeon.hero == null || level == null) return new ArrayList<>();
		return SquadMovementPlanner.candidates(plannerMover, plannerSquad, Dungeon.hero.pos,
				level.width(), level.height(), movementWorld(member, level));
	}

	private static boolean validMovementGoal(Mob member, int cell, Level level) {
		if (!legalVisibleGoal(member, cell, level)) return false;
		return cell == member.pos || Dungeon.findPath(member, cell, level.passable, member.fieldOfView, true) != null;
	}

	private static boolean legalVisibleGoal(Mob member, int cell, Level level) {
		if (!level.insideMap(cell) || !level.passable[cell] || level.avoid[cell]
				|| (Char.hasProp(member, Char.Property.LARGE) && !level.openSpace[cell])
				|| (Actor.findChar(cell) != null && cell != member.pos)) return false;
		if (member.fieldOfView == null || cell >= member.fieldOfView.length || !member.fieldOfView[cell]) return false;
		return true;
	}

	private static int squadSpacing(int cell, Mob member, ArrayList<Mob> squad, Level level) {
		int score = 0;
		for (Mob other : squad) if (other != member) score += level.distance(cell, other.pos);
		return score;
	}

	private static String moveChoiceDescription(SquadMovementPlanner.Candidate choice, int x, int y, Mob member,
			ArrayList<Mob> squad, Level level) {
		int heroDistance = SquadMovementPlanner.distance(choice.cell, Dungeon.hero.pos, level.width());
		String sector = SquadMovementPlanner.directionName(SquadMovementPlanner.directionBucket(
				choice.cell, Dungeon.hero.pos, level.width()));
		if ("escort".equals(choice.maneuver)) {
			return "Reachable goal (" + x + "," + y + "), " + sector + " of the hero at grid distance " + heroDistance
					+ "; screen the visible ranged squadmate by moving between that ally and the hero.";
		}
		return "Reachable flank goal (" + x + "," + y + "), " + sector + " of the hero at grid distance " + heroDistance
				+ "; use this approach sector and keep space from squadmates (spacing score "
				+ squadSpacing(choice.cell, member, squad, level) + ").";
	}

	private static void buildRoleOptions(int roleIndex, ArrayList<Mob> members, ArrayList<String> roles,
			boolean[] used, Map<Integer, String> assignment, Map<String, Map<Integer, String>> options,
			Map<String, Object> criteria) {
		if (roleIndex == roles.size()) {
			StringBuilder key = new StringBuilder("assign");
			StringBuilder description = new StringBuilder();
			for (Mob member : members) {
				String role = assignment.get(member.id());
				key.append('_').append(role).append('_').append(member.id());
				if (description.length() > 0) description.append(", ");
				description.append(role).append("=").append(member.getClass().getSimpleName())
						.append('#').append(member.id());
			}
			options.put(key.toString(), new LinkedHashMap<>(assignment));
			criteria.put(key.toString(), description.toString());
			return;
		}
		for (int i = 0; i < members.size(); i++) {
			if (used[i]) continue;
			Mob member = members.get(i);
			used[i] = true;
			assignment.put(member.id(), roles.get(roleIndex));
			buildRoleOptions(roleIndex + 1, members, roles, used, assignment, options, criteria);
			assignment.remove(member.id());
			used[i] = false;
		}
	}

	private static Map<String, Object> observation(Mob observer, Level level) {
		if (observer.fieldOfView == null || observer.fieldOfView.length != level.length()) {
			observer.fieldOfView = new boolean[level.length()];
		}
		level.updateFieldOfView(observer, observer.fieldOfView);

		int radius = Math.max(1, Math.min(observer.viewDistance, 10));
		ArrayList<String> rows = localMap(level, observer.pos, radius, observer.fieldOfView);
		ArrayList<Map<String, Object>> visibleCharacters = new ArrayList<>();
		for (Char character : Actor.chars()) {
			if (character == observer || !character.isAlive() || character.invisible > 0
					|| character.pos < 0 || character.pos >= observer.fieldOfView.length
					|| !observer.fieldOfView[character.pos]) continue;
			Map<String, Object> seen = new LinkedHashMap<>();
			seen.put("type", character.getClass().getSimpleName());
			seen.put("alignment", character.alignment.toString());
			seen.put("hp", character.HP);
			seen.put("maxHp", character.HT);
			seen.put("x", character.pos % level.width());
			seen.put("y", character.pos / level.width());
			visibleCharacters.add(seen);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("observerId", observer.id());
		result.put("centerX", observer.pos % level.width());
		result.put("centerY", observer.pos / level.width());
		result.put("radius", radius);
		result.put("map", rows);
		result.put("mapLegend", "Rows north to south; ? outside current vision, # wall, . floor, ~ water, O pit.");
		result.put("visibleCharacters", visibleCharacters);
		if (Dungeon.hero.isAlive() && Dungeon.hero.invisible <= 0
				&& Dungeon.hero.pos >= 0 && Dungeon.hero.pos < observer.fieldOfView.length
				&& observer.fieldOfView[Dungeon.hero.pos]) {
			result.put("hero", characterState(Dungeon.hero, level));
		}
		return result;
}

	private static ArrayList<String> localMap(Level level, int center, int radius, boolean[] fieldOfView) {
		ArrayList<String> rows = new ArrayList<>();
		int cx = center % level.width(), cy = center / level.width();
		for (int y = Math.max(0, cy - radius); y <= Math.min(level.height() - 1, cy + radius); y++) {
			StringBuilder row = new StringBuilder();
			for (int x = Math.max(0, cx - radius); x <= Math.min(level.width() - 1, cx + radius); x++) {
				int cell = x + y * level.width();
				boolean known = cell < fieldOfView.length && fieldOfView[cell];
				row.append(known ? terrainSymbol(level, cell) : '?');
			}
			rows.add(row.toString());
		}
		return rows;
	}

	private static synchronized void log(String event, String details) {
		if (!DeviceCompat.isDebug()) return;
		String path = System.getProperty("typesafe.api.debug.log", "jev-ai-debug.log");
		String line = System.currentTimeMillis() + " " + event + " " + details + System.lineSeparator();
		debugEvents.addLast(debugDisplayEntry(event, details));
		while (debugEvents.size() > MAX_DEBUG_EVENTS) debugEvents.removeFirst();
		DeviceCompat.log("JEV", event + " " + details);
		try {
			Files.write(Paths.get(path), line.getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			DeviceCompat.log("JEV", "Unable to write debug log (" + ignored.getClass().getSimpleName() + ")");
		}
	}

	public static synchronized List<String> debugLogEntries() {
		return new ArrayList<>(debugEvents);
	}

	private static String debugDisplayEntry(String event, String details) {
		String display = details;
		if ("request".equals(event)) {
			display = prettyJson(details);
		} else if ("response".equals(event)) {
			int bodyStart = details.indexOf(" body=");
			if (bodyStart >= 0) display = details.substring(0, bodyStart + 6) + prettyJson(details.substring(bodyStart + 6));
		}
		return "[" + DEBUG_TIME.format(new Date()) + "] " + event + "\n" + display;
	}

	private static String prettyJson(String json) {
		StringBuilder result = new StringBuilder(json.length() + 128);
		int indent = 0;
		boolean quoted = false;
		boolean escaped = false;
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (quoted) {
				result.append(c);
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (c == '"') quoted = false;
			} else if (c == '"') {
				quoted = true;
				result.append(c);
			} else if (c == '{' || c == '[') {
				result.append(c).append('\n');
				indent++;
				appendIndent(result, indent);
			} else if (c == '}' || c == ']') {
				result.append('\n');
				indent = Math.max(0, indent - 1);
				appendIndent(result, indent);
				result.append(c);
			} else if (c == ',') {
				result.append(c).append('\n');
				appendIndent(result, indent);
			} else if (c == ':') {
				result.append(": ");
			} else {
				result.append(c);
			}
		}
		return result.toString();
	}

	private static void appendIndent(StringBuilder result, int indent) {
		for (int i = 0; i < indent; i++) result.append("  ");
	}

	private static String toJson(Object value) {
		StringBuilder json = new StringBuilder();
		appendJson(json, value);
		return json.toString();
	}

	private static void appendJson(StringBuilder json, Object value) {
		if (value == null) {
			json.append("null");
		} else if (value instanceof String || value instanceof Character) {
			appendJsonString(json, value.toString());
		} else if (value instanceof Number || value instanceof Boolean) {
			json.append(value);
		} else if (value instanceof Map) {
			json.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				if (!first) json.append(',');
				first = false;
				appendJsonString(json, String.valueOf(entry.getKey()));
				json.append(':');
				appendJson(json, entry.getValue());
			}
			json.append('}');
		} else if (value instanceof Iterable) {
			json.append('[');
			boolean first = true;
			for (Object item : (Iterable<?>) value) {
				if (!first) json.append(',');
				first = false;
				appendJson(json, item);
			}
			json.append(']');
		} else {
			appendJsonString(json, value.toString());
		}
	}

	private static void appendJsonString(StringBuilder json, String value) {
		json.append('"');
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			switch (character) {
				case '"': json.append("\\\""); break;
				case '\\': json.append("\\\\"); break;
				case '\b': json.append("\\b"); break;
				case '\f': json.append("\\f"); break;
				case '\n': json.append("\\n"); break;
				case '\r': json.append("\\r"); break;
				case '\t': json.append("\\t"); break;
				default:
					if (character < 0x20) {
						json.append("\\u");
						String hex = Integer.toHexString(character);
						for (int padding = hex.length(); padding < 4; padding++) json.append('0');
						json.append(hex);
					} else {
						json.append(character);
					}
			}
		}
		json.append('"');
	}

	private static Map<String, Object> characterState(Char character, Level level) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", character.getClass().getSimpleName());
		result.put("alignment", character.alignment.toString());
		result.put("hp", character.HP);
		result.put("maxHp", character.HT);
		result.put("position", character.pos);
		result.put("x", character.pos % level.width());
		result.put("y", character.pos / level.width());
		ArrayList<String> statuses = new ArrayList<>();
		for (Buff buff : character.buffs()) statuses.add(buff.getClass().getSimpleName());
		result.put("statuses", statuses);
		if (character instanceof Hero) {
			Hero hero = (Hero) character;
			result.put("heroClass", hero.heroClass.name());
			result.put("heroSubclass", hero.subClass == null ? "NONE" : hero.subClass.name());
			result.put("level", hero.lvl);
			result.put("strength", hero.STR());
			if (hero.belongings != null) {
				result.put("weaponType", hero.belongings.weapon() == null ? "UNKNOWN" : hero.belongings.weapon().getClass().getSimpleName());
				result.put("armorType", hero.belongings.armor() == null ? "UNKNOWN" : hero.belongings.armor().getClass().getSimpleName());
			}
		}
		if (character instanceof Mob) {
			Mob mob = (Mob) character;
			boolean enemyVisible = mob.enemy != null && mob.enemy.isAlive()
					&& mob.fieldOfView != null && mob.enemy.pos >= 0 && mob.enemy.pos < mob.fieldOfView.length
					&& mob.fieldOfView[mob.enemy.pos] && mob.enemy.invisible <= 0;
			result.put("aiState", mob.state.getClass().getSimpleName());
			result.put("target", mob.target);
			result.put("enemyVisible", enemyVisible);
			result.put("enemyDistance", enemyVisible ? level.distance(mob.pos, mob.enemy.pos)
					: mob.target < 0 ? -1 : level.distance(mob.pos, mob.target));
			result.put("canAttackTargetNow", enemyVisible && mob.canAttack(mob.enemy));
			result.put("canAttackTargetAtRange", enemyVisible && mob.canAttack(mob.enemy)
					&& level.distance(mob.pos, mob.enemy.pos) > 1);
			result.put("speed", mob.speed());
			result.put("rooted", mob.rooted);
		}
		return result;
	}

	private static char terrainSymbol(Level level, int cell) {
		int terrain = level.map[cell];
		if (level.secret[cell] || terrain == Terrain.SECRET_DOOR || terrain == Terrain.SECRET_TRAP) return '?';
		if (level.pit[cell] || terrain == Terrain.CHASM) return 'O';
		if (level.water[cell]) return '~';
		switch (terrain) {
			case Terrain.WALL: return '#';
			case Terrain.DOOR:
			case Terrain.LOCKED_DOOR:
			case Terrain.CRYSTAL_DOOR: return 'd';
			case Terrain.OPEN_DOOR: return '/';
			case Terrain.ENTRANCE:
			case Terrain.ENTRANCE_SP: return '<';
			case Terrain.EXIT: return '>';
			case Terrain.LOCKED_EXIT: return 'X';
			case Terrain.TRAP:
			case Terrain.INACTIVE_TRAP: return '^';
			case Terrain.GRASS:
			case Terrain.HIGH_GRASS:
			case Terrain.FURROWED_GRASS: return 'g';
			case Terrain.BARRICADE: return 'b';
			default: return level.passable[cell] ? '.' : '#';
		}
	}
}
