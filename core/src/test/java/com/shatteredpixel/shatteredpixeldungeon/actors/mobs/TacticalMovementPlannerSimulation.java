package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** Standalone deterministic movement simulations; run with scripts/simulate_tactical_movement.ps1. */
public final class TacticalMovementPlannerSimulation {
	private static int assertions;

	private static final class Fixture implements SquadMovementPlanner.World {
		final int width;
		final int height;
		final boolean[] walkable;
		final boolean[] visible;
		final boolean[] avoided;
		final boolean[] occupied;

		Fixture(int width, int height) {
			this.width = width;
			this.height = height;
			walkable = new boolean[width * height];
			visible = new boolean[width * height];
			avoided = new boolean[width * height];
			occupied = new boolean[width * height];
			for (int y = 1; y < height - 1; y++) for (int x = 1; x < width - 1; x++) {
				walkable[cell(x, y)] = true;
				visible[cell(x, y)] = true;
			}
		}

		int cell(int x, int y) { return x + y * width; }
		int x(int cell) { return cell % width; }
		int y(int cell) { return cell / width; }
		void wall(int x, int y) { walkable[cell(x, y)] = false; }

		@Override public boolean visible(int cell) {
			return cell >= 0 && cell < visible.length && visible[cell];
		}

		@Override public boolean legal(SquadMovementPlanner.Member mover, int cell) {
			return cell >= 0 && cell < walkable.length && walkable[cell] && !avoided[cell]
					&& (!occupied[cell] || cell == mover.cell);
		}

		@Override public boolean reachable(SquadMovementPlanner.Member mover, int target) {
			if (!legal(mover, target) || !visible(target)) return false;
			boolean[] visited = new boolean[walkable.length];
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			queue.add(mover.cell);
			visited[mover.cell] = true;
			while (!queue.isEmpty()) {
				int current = queue.removeFirst();
				if (current == target) return true;
				for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dy == 0) continue;
					int x = x(current) + dx;
					int y = y(current) + dy;
					if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) continue;
					int next = cell(x, y);
					if (visited[next] || !visible(next) || !legal(mover, next)) continue;
					visited[next] = true;
					queue.addLast(next);
				}
			}
			return false;
		}
	}

	public static void main(String[] args) {
		dijkstraMatchesBfsWithUniformCost();
		weightedCostIsRespected();
		boundsClampAtMapCorner();
		System.out.println("Tactical movement simulations passed; assertions=" + assertions);
	}

	private static void dijkstraMatchesBfsWithUniformCost() {
		SquadTestMap map = new SquadTestMap(15, 15);
		map.wall(7, 3); map.wall(7, 4); map.wall(7, 5); map.wall(7, 6);
		SquadDijkstra.Graph g = uniform(map);
		int src = map.cell(2, 5);
		SquadDijkstra.Bounds all = SquadDijkstra.Bounds.around(15, 15, 20, src);
		int[] d = SquadDijkstra.fromSource(g, src, all);
		int[] bfs = bfsSteps(map, src);
		for (int c = 0; c < d.length; c++)
			check(bfs[c] < 0 ? d[c] == SquadDijkstra.UNREACHABLE : d[c] == bfs[c] * 10, "uniform dijkstra == 10*bfs at " + c);
		int[] r = SquadDijkstra.toTarget(g, src, all);
		for (int c = 0; c < d.length; c++) check(r[c] == d[c], "symmetric uniform cost");
		ArrayList<Integer> p = SquadDijkstra.tracePath(g, d, src, map.cell(12, 5));
		check(p.size() == bfs[map.cell(12, 5)] && p.get(p.size() - 1) == map.cell(12, 5), "trace path length and end");
	}

	private static void weightedCostIsRespected() {
		SquadTestMap map = new SquadTestMap(9, 5);
		int src = map.cell(1, 2), dst = map.cell(7, 2);
		SquadDijkstra.Bounds all = SquadDijkstra.Bounds.around(9, 5, 20, src);
		SquadDijkstra.Graph oneHot = costed(map, c -> c == map.cell(4, 2) ? 100 : 10);
		int[] d1 = SquadDijkstra.fromSource(oneHot, src, all);
		check(d1[dst] == 60, "detour around single expensive cell costs 6*10");
		check(!SquadDijkstra.tracePath(oneHot, d1, src, dst).contains(map.cell(4, 2)), "path avoids expensive cell");
		SquadDijkstra.Graph column = costed(map, c -> map.x(c) == 4 ? 100 : 10);
		check(SquadDijkstra.fromSource(column, src, all)[dst] == 5 * 10 + 100, "forced crossing pays once");
	}

	private static void boundsClampAtMapCorner() {
		SquadTestMap map = new SquadTestMap(10, 10);
		SquadDijkstra.Bounds b = SquadDijkstra.Bounds.around(10, 10, 4, map.cell(1, 1));
		check(b.minX == 0 && b.minY == 0 && b.maxX == 5 && b.maxY == 5, "bounds clamp to map");
		int[] d = SquadDijkstra.fromSource(uniform(map), map.cell(1, 1), b);
		check(d[map.cell(8, 8)] == SquadDijkstra.UNREACHABLE, "outside bounds stays unreachable");
	}

	private static SquadDijkstra.Graph uniform(SquadTestMap map) {
		return costed(map, c -> 10);
	}

	private static SquadDijkstra.Graph costed(final SquadTestMap map, final IntUnaryOperator cost) {
		return new SquadDijkstra.Graph() {
			public int width() { return map.width(); }
			public int height() { return map.height(); }
			public boolean passable(int cell) { return map.passable(null, cell); }
			public int enterCost(int cell) { return cost.applyAsInt(cell); }
		};
	}

	private static int[] bfsSteps(SquadTestMap map, int src) {
		int w = map.width();
		int[] steps = new int[w * map.height()];
		java.util.Arrays.fill(steps, -1);
		steps[src] = 0;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(src);
		while (!queue.isEmpty()) {
			int c = queue.poll();
			for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int nx = c % w + dx, ny = c / w + dy;
				if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= w || ny >= map.height()) continue;
				int n = nx + ny * w;
				if (steps[n] >= 0 || !map.passable(null, n)) continue;
				steps[n] = steps[c] + 1;
				queue.add(n);
			}
		}
		return steps;
	}

	private static void openRoomSupportsDistributedFlankGoals() {
		Fixture map = new Fixture(15, 15);
		int hero = map.cell(7, 7);
		SquadMovementPlanner.Member tank = member(1, map.cell(4, 7), true, false, "tank");
		SquadMovementPlanner.Member dealer = member(2, map.cell(3, 7), true, true, "dealer");
		SquadMovementPlanner.Member support = member(3, map.cell(4, 8), true, false, "support");
		ArrayList<SquadMovementPlanner.Member> squad = squad(tank, dealer, support);
		map.occupied[tank.cell] = map.occupied[dealer.cell] = map.occupied[support.cell] = true;
		Set<Integer> directions = new HashSet<>();
		Set<Integer> assigned = new HashSet<>();
		for (SquadMovementPlanner.Member member : squad) {
			ArrayList<SquadMovementPlanner.Candidate> candidates = candidates(map, member, squad, hero);
			int found = 0;
			for (SquadMovementPlanner.Candidate candidate : candidates) {
				if (!"flank".equals(candidate.maneuver)) continue;
				check(map.legal(member, candidate.cell) && map.reachable(member, candidate.cell), "flank goal must be legal and reachable");
				check(SquadMovementPlanner.distance(candidate.cell, hero, map.width) == 1, "flank goal must border hero");
				directions.add(SquadMovementPlanner.directionBucket(candidate.cell, hero, map.width));
				if (assigned.add(candidate.cell)) { found++; break; }
			}
			check(found == 1, "open room should provide a distinct flank destination to each squad member");
		}
		check(directions.size() >= 3, "open room should expose at least three approach sectors: " + directions);
	}

	private static void narrowCorridorDoesNotOfferFalseEncirclement() {
		Fixture map = new Fixture(13, 13);
		for (int y = 1; y < map.height - 1; y++) for (int x = 1; x < map.width - 1; x++) {
			if (x != 6) map.wall(x, y);
		}
		int hero = map.cell(6, 6);
		SquadMovementPlanner.Member front = member(10, map.cell(6, 3), true, false, "tank");
		SquadMovementPlanner.Member rear = member(11, map.cell(6, 2), true, false, "dealer");
		ArrayList<SquadMovementPlanner.Member> squad = squad(front, rear);
		map.occupied[front.cell] = map.occupied[rear.cell] = true;
		Set<Integer> directions = new HashSet<>();
		for (SquadMovementPlanner.Member member : squad) {
			for (SquadMovementPlanner.Candidate candidate : candidates(map, member, squad, hero)) {
				if ("flank".equals(candidate.maneuver)) directions.add(
						SquadMovementPlanner.directionBucket(candidate.cell, hero, map.width));
			}
		}
		check(directions.size() == 2, "one-cell corridor should expose only its two approach directions");
		check(directions.size() < 3, "narrow passage must not qualify as an encirclement tactic");
	}

	private static void escortGoalsScreenAVisibleRangedAlly() {
		Fixture map = new Fixture(15, 13);
		int hero = map.cell(2, 6);
		SquadMovementPlanner.Member tank = member(20, map.cell(4, 4), true, false, "tank");
		SquadMovementPlanner.Member ranged = member(21, map.cell(10, 6), true, true, "dealer");
		ArrayList<SquadMovementPlanner.Member> squad = squad(tank, ranged);
		map.occupied[tank.cell] = map.occupied[ranged.cell] = true;
		ArrayList<SquadMovementPlanner.Candidate> choices = candidates(map, tank, squad, hero);
		int count = 0;
		for (SquadMovementPlanner.Candidate candidate : choices) {
			if (!"escort".equals(candidate.maneuver)) continue;
			int dx = map.x(candidate.cell) - map.x(hero);
			int dy = map.y(candidate.cell) - map.y(hero);
			int ax = map.x(ranged.cell) - map.x(hero);
			int ay = map.y(ranged.cell) - map.y(hero);
			int dot = dx * ax + dy * ay;
			int span = ax * ax + ay * ay;
			check(dot > 0 && dot < span, "escort cell must project between hero and ranged ally");
			check(SquadMovementPlanner.distance(candidate.cell, hero, map.width) >= 2,
					"escort cell must leave the tank screening distance from hero");
			check(map.legal(tank, candidate.cell) && map.reachable(tank, candidate.cell), "escort goal must be legal and reachable");
			count++;
		}
		check(count > 0, "visible ranged dealer should yield escort-screen candidates");
	}

	private static void blockedAndUnseenGoalsAreNeverOffered() {
		Fixture map = new Fixture(13, 11);
		for (int y = 1; y < map.height - 1; y++) map.wall(6, y);
		int hero = map.cell(9, 5);
		SquadMovementPlanner.Member mover = member(30, map.cell(2, 5), true, false, "tank");
		ArrayList<SquadMovementPlanner.Member> squad = squad(mover);
		check(candidates(map, mover, squad, hero).isEmpty(), "sealed wall must make hero-side goals unreachable");

		Fixture hidden = new Fixture(13, 11);
		hidden.visible[hero] = false;
		check(candidates(hidden, mover, squad, hero).isEmpty(), "hidden hero coordinates must not produce candidate destinations");

		Fixture allyHidden = new Fixture(15, 13);
		int nearbyHero = allyHidden.cell(2, 6);
		SquadMovementPlanner.Member tank = member(31, allyHidden.cell(4, 4), true, false, "tank");
		SquadMovementPlanner.Member ranged = member(32, allyHidden.cell(10, 6), true, true, "dealer");
		ArrayList<SquadMovementPlanner.Member> pair = squad(tank, ranged);
		allyHidden.visible[ranged.cell] = false;
		for (SquadMovementPlanner.Candidate candidate : candidates(allyHidden, tank, pair, nearbyHero)) {
			check(!"escort".equals(candidate.maneuver), "unseen ranged ally must not determine a guard coordinate");
		}
	}

	private static void responseCoordinatesMustMatchOfferedChoicesAndRemainLegal() {
		Fixture map = new Fixture(13, 11);
		int hero = map.cell(7, 5);
		SquadMovementPlanner.Member mover = member(40, map.cell(3, 5), true, false, "tank");
		ArrayList<SquadMovementPlanner.Member> squad = squad(mover);
		ArrayList<SquadMovementPlanner.Candidate> candidates = candidates(map, mover, squad, hero);
		Map<String, SquadMovementPlanner.Candidate> offered = new HashMap<>();
		for (SquadMovementPlanner.Candidate candidate : candidates) {
			offered.put(SquadMovementPlanner.choiceKey(candidate, map.width), candidate);
		}
		String key = SquadMovementPlanner.choiceKey(candidates.get(0), map.width);
		check(SquadMovementPlanner.validateChoice(key, offered, mover, "flank", map) == candidates.get(0),
				"exact offered candidate should validate");
		check(SquadMovementPlanner.validateChoice("flank_999_999", offered, mover, "flank", map) == null,
				"invented coordinates must be rejected");
		check(SquadMovementPlanner.validateChoice(key, offered, mover, "escort", map) == null,
				"candidate with wrong tactical prefix must be rejected");
		map.occupied[candidates.get(0).cell] = true;
		check(SquadMovementPlanner.validateChoice(key, offered, mover, "flank", map) == null,
				"candidate occupied after request must be rejected on revalidation");
		Map<Integer, Integer> assignedCells = new HashMap<>();
		Map<Integer, String> assignedTypes = new HashMap<>();
		assignedCells.put(99, map.cell(6, 4));
		assignedTypes.put(99, "flank");
		SquadMovementPlanner.Candidate sameSector = new SquadMovementPlanner.Candidate(41, map.cell(8, 4), "flank");
		SquadMovementPlanner.Candidate differentSector = new SquadMovementPlanner.Candidate(42, map.cell(5, 5), "flank");
		check(SquadMovementPlanner.conflictsWithAssignedFlank(sameSector, assignedCells, assignedTypes, hero, map.width),
				"two flankers must not be assigned to the same approach sector");
		check(!SquadMovementPlanner.conflictsWithAssignedFlank(differentSector, assignedCells, assignedTypes, hero, map.width),
				"distinct approach sector should remain assignable");
	}

	private static void destinationQuestionsMustMatchAvailableTactics() {
		SquadMovementPlanner.Candidate flank = new SquadMovementPlanner.Candidate(50, 100, "flank");
		SquadMovementPlanner.Candidate escort = new SquadMovementPlanner.Candidate(50, 101, "escort");
		check(!SquadMovementPlanner.relevantForTactics(flank, false, false, false, false),
				"do not ask for coordinates when no movement tactic is available");
		check(!SquadMovementPlanner.relevantForTactics(flank, false, false, false, true),
				"do not ask a tank for unsupported flank coordinates");
		check(SquadMovementPlanner.relevantForTactics(flank, false, true, false, false),
				"escort tactic may ask non-tanks for flank support positions");
		check(SquadMovementPlanner.relevantForTactics(escort, false, true, false, true),
				"escort tactic may ask the assigned tank for a screen position");
		check(!SquadMovementPlanner.relevantForTactics(escort, true, false, false, true),
				"flank-only tactic must not include escort destinations");
		check(SquadMovementPlanner.relevantForTactics(escort, false, true, true, false),
				"pending role assignment must retain escort candidates until the tank is identified");
	}

	private static void randomizedTerrainAndPlacementReliability() {
		Random random = new Random(0x5eed);
		int usableLayouts = 0;
		int reachableFlankGoals = 0;
		for (int trial = 0; trial < 500; trial++) {
			Fixture map = new Fixture(17, 17);
			int hero = map.cell(8, 8);
			ArrayList<SquadMovementPlanner.Member> squad = new ArrayList<>();
			Set<Integer> placements = new HashSet<>();
			placements.add(hero);
			for (int i = 0; i < 3; i++) {
				int cell;
				do {
					cell = map.cell(2 + random.nextInt(13), 2 + random.nextInt(13));
				} while (!placements.add(cell));
				squad.add(member(100 + i, cell, true, i == 1, i == 0 ? "tank" : i == 1 ? "dealer" : "support"));
				map.occupied[cell] = true;
			}
			for (int y = 2; y < 15; y++) for (int x = 2; x < 15; x++) {
				int cell = map.cell(x, y);
				if (!placements.contains(cell) && random.nextFloat() < 0.18f) map.wall(x, y);
			}
			boolean hasFlank = false;
			for (SquadMovementPlanner.Member member : squad) {
				ArrayList<SquadMovementPlanner.Candidate> options = candidates(map, member, squad, hero);
				Set<Integer> local = new HashSet<>();
				for (SquadMovementPlanner.Candidate option : options) {
					check(map.visible(option.cell), "random option must be visible");
					check(map.legal(member, option.cell), "random option must be legal and unoccupied");
					check(map.reachable(member, option.cell), "random option must be path-reachable");
					check(local.add(option.cell), "one member must not receive duplicate destinations");
					if ("flank".equals(option.maneuver)) {
						check(SquadMovementPlanner.distance(option.cell, hero, map.width) == 1,
								"random flank goal must remain adjacent to visible hero");
						reachableFlankGoals++;
						hasFlank = true;
					}
				}
			}
			if (hasFlank) usableLayouts++;
		}
		check(usableLayouts >= 450, "most open randomized layouts should retain at least one flank target: " + usableLayouts);
		check(reachableFlankGoals >= 1000, "randomized test should exercise a broad set of reachable goals: " + reachableFlankGoals);
	}

	private static ArrayList<SquadMovementPlanner.Candidate> candidates(Fixture map,
			SquadMovementPlanner.Member member, ArrayList<SquadMovementPlanner.Member> squad, int hero) {
		return SquadMovementPlanner.candidates(member, squad, hero, map.width, map.height, map);
	}

	private static SquadMovementPlanner.Member member(int id, int cell, boolean tactical,
			boolean ranged, String role) {
		return new SquadMovementPlanner.Member(id, cell, tactical, ranged, role);
	}

	private static ArrayList<SquadMovementPlanner.Member> squad(SquadMovementPlanner.Member... members) {
		ArrayList<SquadMovementPlanner.Member> result = new ArrayList<>();
		for (SquadMovementPlanner.Member member : members) result.add(member);
		return result;
	}

	private static void check(boolean condition, String description) {
		assertions++;
		if (!condition) throw new AssertionError(description);
	}
}
