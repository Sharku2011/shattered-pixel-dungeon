package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.SquadMovementPlanner.Member;

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
		costFieldAppliesSpecPenalties();
		flankersAvoidAxisAndHeroRing();
		flankPathsSeparateInOpenRoom();
		sectorsAreNonFrontalAndDistinct();
		corridorYieldsNoFlankAndDegrades();
		escortScreensBetweenHeroAndAlly();
		randomizedAssignmentsAreLegalAndDeterministic();
		dijkstraRunsPerAssignmentAreBounded();
		heroMoveKeepsSector();
		nextStepFollowsGoalAndAvoidsOccupied();
		largeMemberGoalsRespectOpenSpace();
		singleSurvivorDegradesWithoutAssignments();
		heroHiddenFromMemberGivesNoGoal();
		memberStandingOnGoalStaysLegal();
		nextStepSkipsPenalizedMinDistanceNeighbour();
		nextStepAxisFollowsMovedFront();
		failedEscortReassignResetsAxis();
		fastMemberNeverLeadsByMoreThanOne();
		gatherThenAdvance();
		gatherTimeoutReleases();
		immobileAnchorReleasesAfterStall();
		degradeMatrixNeverThrows();
		diagonalChainReachesContact();
		formationStaysConnectedUntilContact();
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
		SquadDijkstra.Graph costlyDst = costed(map, c -> c == dst ? 50 : 10);
		check(SquadDijkstra.toTarget(costlyDst, dst, all)[src] == 5 * 10 + 50, "toTarget sums cells entered walking src to dst (includes dst, excludes src)");
		check(SquadDijkstra.fromSource(costlyDst, dst, all)[src] == 5 * 10 + 10, "fromSource reverse walk includes src, excludes dst");
		check(SquadDijkstra.toTarget(costlyDst, dst, all)[src] != SquadDijkstra.fromSource(costlyDst, dst, all)[src], "toTarget differs from fromSource under asymmetric cost");
	}

	private static void boundsClampAtMapCorner() {
		SquadTestMap map = new SquadTestMap(10, 10);
		SquadDijkstra.Bounds b = SquadDijkstra.Bounds.around(10, 10, 4, map.cell(1, 1));
		check(b.minX == 0 && b.minY == 0 && b.maxX == 5 && b.maxY == 5, "bounds clamp to map");
		int[] d = SquadDijkstra.fromSource(uniform(map), map.cell(1, 1), b);
		check(d[map.cell(8, 8)] == SquadDijkstra.UNREACHABLE, "outside bounds stays unreachable");
	}

	private static void costFieldAppliesSpecPenalties() {
		SquadTestMap map = new SquadTestMap(17, 17);
		int hero = map.cell(8, 8);
		map.occupy(hero);
		SquadMovementPlanner.Member front = member(1, map.cell(3, 8)), mover = member(2, map.cell(3, 10)), mate = member(3, map.cell(12, 12));
		SquadCostField.Penalties p = new SquadCostField.Penalties();
		p.heroProximity = true; p.ownGoal = map.cell(9, 9); p.axisFrom = front.cell;
		p.reservations.add(Arrays.asList(map.cell(5, 14), map.cell(6, 14)));
		p.squadmateAdjacency = true;
		ArrayList<SquadMovementPlanner.Member> squad = squad(front, mover, mate);
		SquadCostField f = new SquadCostField(map, mover, squad, hero, p);
		check(f.enterCost(map.cell(9, 9)) == 20, "own goal exempt from ring1 (10 + axis far 10)");
		check(f.enterCost(map.cell(9, 7)) == 60, "ring1 = 10+40, plus axis far 10");
		check(f.enterCost(map.cell(10, 10)) == 25, "ring2 = 10+15");
		check(f.enterCost(map.cell(5, 8)) == 40 && f.axisDistance(map.cell(5, 8)) == 0, "on axis = 10+30");
		check(f.axisDistance(hero) == 1, "hero cell is not an axis cell");
		check(f.enterCost(map.cell(5, 10)) == 20, "axis distance 2 = 10+10");
		check(f.enterCost(map.cell(5, 14)) == 35, "reserved = 10+25");
		check(f.enterCost(map.cell(4, 14)) == 20, "reserved-adjacent = 10+10");
		check(f.enterCost(map.cell(12, 13)) == 25, "squadmate-adjacent = 10+15");
		check(f.enterCost(map.cell(14, 2)) == 10, "plain floor = base");
		check(!f.passable(hero) && f.passable(mate.cell), "hero blocked, squadmate cell walkable");
		SquadMovementPlanner.Member other = member(4, map.cell(11, 12));
		SquadCostField two = new SquadCostField(map, mover, squad(front, mover, mate, other), hero, p);
		check(two.enterCost(map.cell(12, 13)) == 25 && two.enterCost(map.cell(11, 13)) == 25, "squadmate adjacency does not stack");
		check(two.enterCost(map.cell(5, 14)) == 35, "reserved cell gets RESERVED only");
		SquadCostField none = new SquadCostField(map, mover, squad, hero, new SquadCostField.Penalties());
		check(none.axisDistance(map.cell(5, 8)) == Integer.MAX_VALUE && none.enterCost(map.cell(5, 8)) == 10, "no axis, no penalties");
		SquadDijkstra.Bounds b = f.bounds(map.cell(9, 9));
		int[] d = SquadDijkstra.toTarget(f, map.cell(9, 9), b);
		check(d[map.cell(9, 9)] == 0 && d[mover.cell] != SquadDijkstra.UNREACHABLE, "toTarget over cost field reaches mover");
		check(d[hero] == SquadDijkstra.UNREACHABLE, "hero cell unreachable as destination");
		int[] s = SquadDijkstra.fromSource(f, mover.cell, b);
		check(s[hero] == SquadDijkstra.UNREACHABLE && s[mover.cell] == 0 && s[map.cell(9, 9)] != SquadDijkstra.UNREACHABLE, "fromSource over cost field terminates, hero unreachable");
		check(b.contains(hero, 17) && b.contains(mate.cell, 17) && b.contains(map.cell(9, 9), 17), "bounds cover hero, squad, extras");
	}

	private static void flankersAvoidAxisAndHeroRing() {
		SquadTestMap map = new SquadTestMap(17, 17);
		int hero = map.cell(8, 8);
		SquadMovementPlanner.Member tank = member(1, map.cell(4, 8), true, false, "tank");
		ArrayList<SquadMovementPlanner.Member> squad = squad(tank, member(2, map.cell(3, 8)), member(3, map.cell(2, 8)));
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		check(plan.frontId == 1 && plan.axisFrom == tank.cell && plan.degradeReason == null, "tank is front and axis origin");
		check(plan.byMember.size() == 2 && !plan.byMember.containsKey(1), "both flankers assigned, front has no goal");
		SquadCostField.Penalties p = new SquadCostField.Penalties();
		p.axisFrom = tank.cell;
		SquadCostField axis = new SquadCostField(map, squad.get(1), squad, hero, p);
		for (SquadMovementPlanner.Assignment a : plan.byMember.values()) {
			check("flank".equals(a.maneuver) && !a.path.isEmpty() && a.path.get(a.path.size() - 1) == a.goal, "flank path ends at goal");
			for (int i = 0; i < a.path.size() - 1; i++) {
				int c = a.path.get(i);
				check(SquadMovementPlanner.distance(c, hero, map.width) > 1, "path avoids hero ring before goal: " + pathString(map, a.path));
				if (i >= 2) check(axis.axisDistance(c) > 1, "path leaves axis band within two steps: " + pathString(map, a.path));
			}
		}
	}

	private static void flankPathsSeparateInOpenRoom() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(2, 7), true, false, "tank"),
				member(2, map.cell(2, 6)), member(3, map.cell(2, 8)));
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		check(plan.byMember.size() == 2, "two flankers assigned in open room");
		HashSet<Integer> first = new HashSet<>(plan.byMember.get(2).path);
		int shared = 0;
		for (int c : plan.byMember.get(3).path) if (first.contains(c)) shared++;
		check(shared <= 2, "flank paths share at most two cells: " + shared);
		SquadTestMap corridor = corridor(13);
		int cHero = corridor.cell(6, 6);
		ArrayList<SquadMovementPlanner.Member> line = squad(member(1, corridor.cell(6, 3), true, false, "tank"),
				member(2, corridor.cell(6, 2)), member(3, corridor.cell(6, 1)));
		occupyAll(corridor, cHero, line);
		check(SquadMovementPlanner.assign("flank", line, cHero, corridor).byMember.isEmpty(), "corridor assigns no flankers without failing");
	}

	private static void sectorsAreNonFrontalAndDistinct() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(3, 7), true, false, "tank"),
				member(2, map.cell(3, 4)), member(3, map.cell(3, 10)), member(4, map.cell(2, 7)));
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		check(plan.byMember.size() == 3, "three flankers assigned in open room: " + plan.byMember.size());
		checkSectors(map, plan, hero);
		ArrayList<String> names = new ArrayList<>();
		for (SquadMovementPlanner.Assignment a : plan.byMember.values()) names.add(SquadMovementPlanner.directionName(a.sector));
		check(names.equals(SquadMovementPlanner.openSectors(squad, hero, map)), "openSectors lists assigned sector names");
		check(SquadMovementPlanner.sectorDiff(0, 7) == 1 && SquadMovementPlanner.sectorDiff(2, 6) == 4
				&& SquadMovementPlanner.sectorDiff(5, 1) == 4 && SquadMovementPlanner.sectorDiff(3, 3) == 0, "circular sector difference");
	}

	private static void corridorYieldsNoFlankAndDegrades() {
		SquadTestMap map = corridor(13);
		int hero = map.cell(6, 6);
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(6, 3), true, false, "tank"),
				member(2, map.cell(6, 2)), member(3, map.cell(6, 1)));
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		check(plan.byMember.isEmpty() && "no_open_sector".equals(plan.degradeReason), "corridor degrades with no_open_sector");
		check(SquadMovementPlanner.openSectors(squad, hero, map).isEmpty(), "corridor exposes no open sectors");
	}

	private static void escortScreensBetweenHeroAndAlly() {
		SquadTestMap map = new SquadTestMap(15, 13);
		int hero = map.cell(2, 6);
		SquadMovementPlanner.Member tank = member(20, map.cell(4, 4), true, false, "tank");
		SquadMovementPlanner.Member ranged = member(21, map.cell(10, 6), true, true, "dealer");
		SquadMovementPlanner.Member support = member(22, map.cell(6, 9));
		ArrayList<SquadMovementPlanner.Member> squad = squad(tank, ranged, support);
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("escort_ranged", squad, hero, map);
		SquadMovementPlanner.Assignment e = plan.byMember.get(20);
		check(e != null && "escort".equals(e.maneuver) && e.allyCell == ranged.cell, "tank receives escort goal");
		int dx = map.x(e.goal) - map.x(hero), dy = map.y(e.goal) - map.y(hero);
		int ax = map.x(ranged.cell) - map.x(hero), ay = map.y(ranged.cell) - map.y(hero);
		int dot = dx * ax + dy * ay, span = ax * ax + ay * ay;
		check(dot > 0 && dot < span, "escort goal projects between hero and ally");
		check(SquadMovementPlanner.distance(e.goal, hero, map.width) >= 2, "escort goal keeps distance from hero");
		check(goalLegal(map, tank, e.goal, hero) && e.path.get(e.path.size() - 1) == e.goal, "escort goal legal and path ends there");
		check(plan.axisFrom == e.goal && plan.degradeReason == null && !plan.byMember.containsKey(21), "escort goal becomes the axis, ally gets nothing");
		check(SquadMovementPlanner.screenPositionAvailable(squad, hero, map), "screen position available");
		checkSectors(map, plan, hero);
		map.hide(ranged.cell);
		SquadMovementPlanner.SquadPlan hidden = SquadMovementPlanner.assign("escort_ranged", squad, hero, map);
		check("no_screen_position".equals(hidden.degradeReason) && !hidden.byMember.containsKey(20), "hidden ally degrades escort");
		check(hidden.axisFrom == tank.cell && hidden.byMember.containsKey(22) && "flank".equals(hidden.byMember.get(22).maneuver),
				"degraded escort continues with flank rules");
		check(!SquadMovementPlanner.screenPositionAvailable(squad, hero, map), "no screen position when ally hidden");
	}

	private static void randomizedAssignmentsAreLegalAndDeterministic() {
		Random random = new Random(0x5eed);
		int usable = 0;
		for (int trial = 0; trial < 500; trial++) {
			SquadTestMap map = new SquadTestMap(17, 17);
			int hero = map.cell(8, 8);
			map.occupy(hero);
			ArrayList<SquadMovementPlanner.Member> squad = new ArrayList<>();
			Set<Integer> placements = new HashSet<>();
			placements.add(hero);
			for (int i = 0; i < 3; i++) {
				int cell;
				do {
					cell = map.cell(2 + random.nextInt(13), 2 + random.nextInt(13));
				} while (!placements.add(cell));
				squad.add(member(100 + i, cell, true, i == 1, i == 0 ? "tank" : i == 1 ? "dealer" : "support"));
				map.occupy(cell);
			}
			for (int y = 2; y < 15; y++) for (int x = 2; x < 15; x++) {
				int cell = map.cell(x, y);
				if (!placements.contains(cell) && random.nextFloat() < 0.18f) map.wall(x, y);
			}
			for (String tactic : new String[]{"flank", "escort_ranged"}) {
				SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign(tactic, squad, hero, map);
				SquadMovementPlanner.SquadPlan again = SquadMovementPlanner.assign(tactic, squad, hero, map);
				check(plan.byMember.keySet().equals(again.byMember.keySet()) && plan.axisFrom == again.axisFrom
						&& String.valueOf(plan.degradeReason).equals(String.valueOf(again.degradeReason)), "deterministic assignment");
				for (SquadMovementPlanner.Assignment a : plan.byMember.values()) {
					SquadMovementPlanner.Assignment b = again.byMember.get(a.memberId);
					check(a.goal == b.goal && a.path.equals(b.path) && a.sector == b.sector, "deterministic goal and path");
					SquadMovementPlanner.Member m = null;
					for (SquadMovementPlanner.Member s : squad) if (s.id == a.memberId) m = s;
					check(goalLegal(map, m, a.goal, hero), "random goal legal");
					check(a.goal == m.cell ? a.path.isEmpty() : !a.path.isEmpty() && a.path.get(a.path.size() - 1) == a.goal, "random path ends at goal");
					int prev = m.cell;
					for (int c : a.path) {
						check(map.passable(m, c) && SquadMovementPlanner.distance(prev, c, map.width) == 1, "random path passable and contiguous");
						prev = c;
					}
				}
				if (plan.byMember.isEmpty()) check(plan.degradeReason != null, "empty plan reports a degrade reason");
				checkSectors(map, plan, hero);
				if ("flank".equals(tactic) && !plan.byMember.isEmpty()) usable++;
			}
		}
		System.out.println("randomized layouts with >=1 flank assignment: " + usable + "/500");
		check(usable >= 400, "most randomized layouts should keep a flank assignment: " + usable);
	}

	private static void dijkstraRunsPerAssignmentAreBounded() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(3, 7), true, false, "tank"),
				member(2, map.cell(3, 4)), member(3, map.cell(3, 10)), member(4, map.cell(2, 7)),
				member(5, map.cell(12, 7), true, true, "dealer"));
		occupyAll(map, hero, squad);
		int bound = 3 * 4 / 2 + 1;
		SquadDijkstra.resetRunCount();
		SquadMovementPlanner.SquadPlan flank = SquadMovementPlanner.assign("flank", squad, hero, map);
		check(SquadDijkstra.runCount() > 0 && SquadDijkstra.runCount() <= bound, "flank dijkstra runs bounded: " + SquadDijkstra.runCount());
		check(flank.byMember.size() == 3, "flank assigns three flankers");
		SquadDijkstra.resetRunCount();
		SquadMovementPlanner.SquadPlan escort = SquadMovementPlanner.assign("escort_ranged", squad, hero, map);
		check(SquadDijkstra.runCount() <= bound, "escort dijkstra runs bounded: " + SquadDijkstra.runCount());
		check(escort.byMember.containsKey(1) && "escort".equals(escort.byMember.get(1).maneuver), "escort assigned in bound test");
	}

	private static void heroMoveKeepsSector() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(2, 7), true, false, "tank"),
				member(2, map.cell(2, 5)), member(3, map.cell(2, 9)));
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		SquadMovementPlanner.Assignment a = plan.byMember.get(2);
		check(a != null, "member 2 assigned");
		int sector = a.sector, oldGoal = a.goal;
		map.occupied[hero] = false;
		int moved = map.cell(7, 6);
		map.occupy(moved);
		check(SquadMovementPlanner.retarget(plan, squad.get(1), squad, moved, map), "retarget succeeds after hero step");
		check(a.sector == sector && a.heroCell == moved && a.goal != oldGoal, "sector kept, goal and hero updated");
		check(SquadMovementPlanner.distance(a.goal, moved, map.width) == 1
				&& SquadMovementPlanner.directionBucket(a.goal, moved, map.width) == sector, "new goal on new ring in same sector");
		check(a.path.get(a.path.size() - 1) == a.goal, "retarget path ends at new goal");
		for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int c = map.cell(map.x(moved) + dx, map.y(moved) + dy);
			if (c != moved && SquadMovementPlanner.directionBucket(c, moved, map.width) == sector) map.wall(map.x(c), map.y(c));
		}
		check(!SquadMovementPlanner.retarget(plan, squad.get(1), squad, moved, map), "retarget fails when sector ring is walled");
	}

	private static void nextStepFollowsGoalAndAvoidsOccupied() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		SquadMovementPlanner.Member mover = member(2, map.cell(2, 5));
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(2, 7), true, false, "tank"), mover);
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		SquadMovementPlanner.Assignment a = plan.byMember.get(2);
		int step = SquadMovementPlanner.nextStep(plan, mover, squad, hero, map);
		check(step >= 0 && SquadMovementPlanner.distance(step, mover.cell, map.width) == 1 && !map.occupied(step), "next step is a free neighbour");
		SquadCostField.Penalties p = new SquadCostField.Penalties();
		p.heroProximity = true; p.ownGoal = a.goal; p.axisFrom = plan.axisFrom; p.squadmateAdjacency = true;
		SquadCostField f = new SquadCostField(map, mover, squad, hero, p);
		int[] d = SquadDijkstra.toTarget(f, a.goal, f.bounds(a.goal));
		check(f.enterCost(step) + d[step] == d[mover.cell], "next step lies on an optimal route");
		check(a.path.get(0) == step && a.path.get(a.path.size() - 1) == a.goal, "path refreshed from step to goal");
		map.occupy(step);
		int other = SquadMovementPlanner.nextStep(plan, mover, squad, hero, map);
		check(other != step && (other == -1 || (SquadMovementPlanner.distance(other, mover.cell, map.width) == 1 && !map.occupied(other))),
				"occupied step is replaced by another free neighbour or none");
	}

	private static void largeMemberGoalsRespectOpenSpace() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		SquadMovementPlanner.Member big = member(2, map.cell(2, 5));
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(2, 7), true, false, "tank"), big, member(3, map.cell(2, 9)));
		occupyAll(map, hero, squad);
		map.large.add(2);
		for (int y = 1; y <= 8; y++) map.openSpace[map.cell(8, y)] = false;
		map.openSpace[map.cell(5, 4)] = map.openSpace[map.cell(5, 5)] = map.openSpace[map.cell(7, 6)] = false;
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		SquadMovementPlanner.Assignment a = plan.byMember.get(2);
		check(a != null && map.openSpace[a.goal], "large member goal in open space");
		for (int c : a.path) check(map.openSpace[c], "large member path stays in open space");
	}

	private static void singleSurvivorDegradesWithoutAssignments() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(3, 7)));
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		check(plan.byMember.isEmpty() && "no_open_sector".equals(plan.degradeReason) && plan.frontId == 1, "single survivor degrades");
		check(SquadMovementPlanner.nextStep(plan, member(9, map.cell(5, 5)), squad, hero, map) == -1, "unknown member gets no step");
		check(SquadMovementPlanner.nextStep(plan, squad.get(0), squad, hero, map) == -1, "unassigned member gets no step");
	}

	private static void heroHiddenFromMemberGivesNoGoal() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(2, 7), true, false, "tank"),
				member(2, map.cell(2, 5)), member(3, map.cell(2, 9)));
		occupyAll(map, hero, squad);
		map.hide(hero);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		check(plan.byMember.isEmpty() && "no_open_sector".equals(plan.degradeReason), "hidden hero yields no goals");
	}

	private static void memberStandingOnGoalStaysLegal() {
		SquadTestMap map = new SquadTestMap(15, 13);
		int hero = map.cell(2, 6);
		SquadMovementPlanner.Member tank = member(20, map.cell(4, 4), true, false, "tank");
		ArrayList<SquadMovementPlanner.Member> squad = squad(tank, member(21, map.cell(10, 6), true, true, "dealer"), member(22, map.cell(6, 9)));
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("escort_ranged", squad, hero, map);
		SquadMovementPlanner.Assignment e = plan.byMember.get(20), f = plan.byMember.get(22);
		check(e != null && f != null && "flank".equals(f.maneuver), "escort and flank assigned before arrival");
		int escortGoal = e.goal, flankGoal = f.goal;
		ArrayList<SquadMovementPlanner.Member> arrived = new ArrayList<>();
		for (SquadMovementPlanner.Member m : squad) {
			int at = m.id == 20 ? escortGoal : m.id == 22 ? flankGoal : m.cell;
			map.occupied[m.cell] = false;
			map.occupy(at);
			arrived.add(new SquadMovementPlanner.Member(m.id, at, m.tactical, m.ranged, m.role));
		}
		for (SquadMovementPlanner.Member m : arrived) {
			if (m.id == 21) continue;
			SquadMovementPlanner.Assignment a = plan.byMember.get(m.id);
			check(map.occupied(m.cell) && SquadMovementPlanner.goalLegal(m, a.goal, hero, map), "own occupied goal stays legal");
			check(SquadMovementPlanner.retarget(plan, m, arrived, hero, map) && plan.byMember.get(m.id).goal == m.cell, "retarget keeps own cell as goal");
			check(SquadMovementPlanner.nextStep(plan, m, arrived, hero, map) == -1, "member on goal takes no step");
			check(SquadMovementPlanner.reassign(plan, m, arrived, hero, map) && plan.byMember.get(m.id).goal == m.cell
					&& plan.byMember.get(m.id).path.isEmpty(), "reassign keeps own cell as goal");
		}
		SquadMovementPlanner.Member other = arrived.get(1);
		check(!SquadMovementPlanner.goalLegal(other, escortGoal, hero, map), "another member's cell stays illegal");
	}

	private static void nextStepSkipsPenalizedMinDistanceNeighbour() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		SquadMovementPlanner.Member tank = member(1, map.cell(7, 2), true, false, "tank");
		SquadMovementPlanner.Member mover = member(2, map.cell(9, 9));
		ArrayList<SquadMovementPlanner.Member> squad = squad(tank, mover);
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = new SquadMovementPlanner.SquadPlan();
		plan.frontId = 1;
		plan.axisFrom = tank.cell;
		SquadMovementPlanner.Assignment a = new SquadMovementPlanner.Assignment(2, "flank");
		a.goal = map.cell(7, 8);
		a.sector = 4;
		a.heroCell = hero;
		plan.byMember.put(2, a);
		SquadCostField.Penalties p = new SquadCostField.Penalties();
		p.heroProximity = true; p.ownGoal = a.goal; p.axisFrom = tank.cell; p.squadmateAdjacency = true;
		SquadCostField f = new SquadCostField(map, mover, squad, hero, p);
		int[] d = SquadDijkstra.toTarget(f, a.goal, f.bounds(a.goal));
		int ring = map.cell(8, 8), side = map.cell(8, 9);
		check(d[ring] == d[side] && f.enterCost(ring) > f.enterCost(side), "setup: penalized ring cell ties on remaining cost");
		int step = SquadMovementPlanner.nextStep(plan, mover, squad, hero, map);
		check(step == side && SquadMovementPlanner.distance(step, hero, map.width) > 1, "step avoids penalized ring cell: " + step);
		check(f.enterCost(step) + d[step] == d[mover.cell], "chosen step is cost-optimal");
	}

	private static void nextStepAxisFollowsMovedFront() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(7, 7);
		SquadMovementPlanner.Member mover = member(2, map.cell(2, 5));
		ArrayList<SquadMovementPlanner.Member> squad = squad(member(1, map.cell(2, 7), true, false, "tank"), mover);
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("flank", squad, hero, map);
		SquadMovementPlanner.Assignment a = plan.byMember.get(2);
		map.occupied[map.cell(2, 7)] = false;
		SquadMovementPlanner.Member moved = member(1, map.cell(2, 3), true, false, "tank");
		map.occupy(moved.cell);
		ArrayList<SquadMovementPlanner.Member> now = squad(moved, mover);
		int step = SquadMovementPlanner.nextStep(plan, mover, now, hero, map);
		int[] costs = new int[2];
		int[] axes = {moved.cell, plan.axisFrom};
		for (int i = 0; i < 2; i++) {
			SquadCostField.Penalties p = new SquadCostField.Penalties();
			p.heroProximity = true; p.ownGoal = a.goal; p.axisFrom = axes[i]; p.squadmateAdjacency = true;
			SquadCostField f = new SquadCostField(map, mover, now, hero, p);
			int[] d = SquadDijkstra.toTarget(f, a.goal, f.bounds(a.goal));
			int sum = 0;
			for (int c : a.path) sum += f.enterCost(c);
			costs[i] = sum - d[mover.cell];
			if (i == 0) check(step >= 0 && f.enterCost(step) + d[step] == d[mover.cell], "step optimal under the moved front's axis");
		}
		check(costs[0] == 0, "refreshed path optimal under the moved front's axis");
		check(costs[1] != 0, "refreshed path is not the stale-axis route: " + pathString(map, a.path));
	}

	private static void failedEscortReassignResetsAxis() {
		SquadTestMap map = new SquadTestMap(15, 13);
		int hero = map.cell(2, 6);
		SquadMovementPlanner.Member tank = member(20, map.cell(4, 4), true, false, "tank");
		SquadMovementPlanner.Member ranged = member(21, map.cell(10, 6), true, true, "dealer");
		ArrayList<SquadMovementPlanner.Member> squad = squad(tank, ranged);
		occupyAll(map, hero, squad);
		SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign("escort_ranged", squad, hero, map);
		check(plan.byMember.containsKey(20) && plan.axisFrom == plan.byMember.get(20).goal, "escort axis set");
		map.hide(ranged.cell);
		check(!SquadMovementPlanner.reassign(plan, tank, squad, hero, map), "escort reassign fails without visible ally");
		check(!plan.byMember.containsKey(20) && plan.axisFrom == tank.cell, "failed escort reassign resets axis to front cell");
	}

	// ---- formation (spec 7, 10-6, 10-9) ----

	private static void formationStaysConnectedUntilContact() {
		int walled = formationContacts(0.12, "walled");
		int open = formationContacts(0.0, "no walls");
		// Controller ruling R12: the plan's 150/200 estimate was not met (measured 132 walled, 200 open), so the
		// walled floor is floor(0.9 * 132) = 118 and open rooms must stay >= 190.
		check(walled >= 118, "most random walled formations reach contact: " + walled);
		check(open >= 190, "nearly all open-room formations reach contact: " + open);
	}

	private static void diagonalChainReachesContact() {
		SquadTestMap map = new SquadTestMap(13, 13);
		int hero = map.cell(2, 2);
		ArrayList<Member> squad = squad(fm(1, map.cell(7, 7), 1f), fm(2, map.cell(8, 8), 1f), fm(3, map.cell(9, 9), 1f));
		occupyAll(map, hero, squad);
		FormationPlanner.State st = new FormationPlanner.State();
		String phase = runFormation(map, squad, hero, st, 30, null);
		check("contact".equals(phase), "diagonal chain reaches contact within 30 actions: " + phase + " " + st.releaseReason);
	}

	/** 200 seeded random runs (17x17, given wall density); returns how many reach contact. */
	private static int formationContacts(double wallRate, String label) {
		Random rng = new Random(20261007L);
		int contact = 0, released = 0;
		for (int run = 0; run < 200; run++) {
			SquadTestMap map = null;
			ArrayList<Member> squad = null;
			int hero = -1;
			while (squad == null) {
				map = new SquadTestMap(17, 17);
				for (int y = 1; y < 16; y++) for (int x = 1; x < 16; x++) if (rng.nextDouble() < wallRate) map.wall(x, y);
				hero = map.cell(1 + rng.nextInt(15), 1 + rng.nextInt(15));
				if (map.walkable[hero]) squad = randomFormation(rng, map, hero);
			}
			occupyAll(map, hero, squad);
			FormationPlanner.State st = new FormationPlanner.State();
			String phase = runFormation(map, squad, hero, st, 400, null);
			if ("contact".equals(phase)) contact++;
			else if ("released".equals(phase)) released++;
		}
		System.out.println("formation random runs (" + label + "): contact=" + contact + " released=" + released + " of 200");
		return contact;
	}

	/** 3-4 connected melee participants at least 3 from the hero, all able to reach it; null when placement fails. */
	private static ArrayList<Member> randomFormation(Random rng, SquadTestMap map, int hero) {
		int n = 3 + rng.nextInt(2);
		ArrayList<Integer> cells = new ArrayList<>();
		for (int tries = 0; tries < 200 && cells.size() < n; tries++) {
			int c;
			if (cells.isEmpty()) c = map.cell(1 + rng.nextInt(15), 1 + rng.nextInt(15));
			else {
				int base = cells.get(rng.nextInt(cells.size()));
				c = map.cell(map.x(base) + rng.nextInt(3) - 1, map.y(base) + rng.nextInt(3) - 1);
			}
			if (map.walkable[c] && !cells.contains(c) && SquadMovementPlanner.distance(c, hero, map.width) >= 3) cells.add(c);
		}
		if (cells.size() < n) return null;
		float[] speeds = {0.5f, 1f, 2f};
		ArrayList<Member> squad = new ArrayList<>();
		for (int i = 0; i < n; i++) squad.add(new Member(i + 1, cells.get(i), true, false, "melee", speeds[rng.nextInt(3)]));
		int[] h = FormationPlanner.heroMap(squad, hero, map);
		for (Member m : squad) if (h[m.cell] == SquadDijkstra.UNREACHABLE) return null;
		return squad;
	}

	/**
	 * Runs formation steps on the speed schedule (each member acts every 1/speed, ties by id) until the planner
	 * first returns RELEASED; checks move legality and connectivity on the way. Returns the phase at release.
	 */
	private static String runFormation(SquadTestMap map, ArrayList<Member> squad, int hero, FormationPlanner.State st,
			int maxActions, Runnable afterEach) {
		HashMap<Integer, Double> clock = new HashMap<>();
		for (Member m : squad) clock.put(m.id, 1.0 / m.speed);
		for (int action = 0; action < maxActions; action++) {
			Member mover = null;
			for (Member m : squad) {
				double t = clock.get(m.id);
				if (mover == null || t < clock.get(mover.id) || (t == clock.get(mover.id) && m.id < mover.id)) mover = m;
			}
			clock.put(mover.id, clock.get(mover.id) + 1.0 / mover.speed);
			int to = FormationPlanner.step(st, mover, squad, hero, map);
			if (to == FormationPlanner.RELEASED) {
				check("contact".equals(st.phase) || "released".equals(st.phase), "first RELEASED comes with contact/released: " + st.phase);
				return st.phase;
			}
			if (to != mover.cell) {
				check(SquadMovementPlanner.distance(to, mover.cell, map.width) == 1 && map.passable(mover, to) && !map.occupied(to),
						"formation step is a free adjacent passable cell");
				moveMember(map, squad, mover.id, to);
			}
			if ("gather".equals(st.phase) || "advance".equals(st.phase))
				check(FormationPlanner.connected(FormationPlanner.participants(squad), -1, -1, map.width), "formation stays connected");
			if (afterEach != null) afterEach.run();
		}
		return "timeout";
	}

	private static void moveMember(SquadTestMap map, ArrayList<Member> squad, int id, int to) {
		for (int i = 0; i < squad.size(); i++) {
			Member m = squad.get(i);
			if (m.id != id) continue;
			map.occupied[m.cell] = false;
			map.occupied[to] = true;
			squad.set(i, new Member(m.id, to, m.tactical, m.ranged, m.role, m.speed));
		}
	}

	private static Member fm(int id, int cell, float speed) {
		return new Member(id, cell, true, false, "melee", speed);
	}

	private static void fastMemberNeverLeadsByMoreThanOne() {
		final SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(12, 7);
		final ArrayList<Member> squad = squad(fm(1, map.cell(2, 7), 0.5f), fm(2, map.cell(2, 8), 2f));
		occupyAll(map, hero, squad);
		final FormationPlanner.State st = new FormationPlanner.State();
		final int[] advanceChecks = {0};
		runFormation(map, squad, hero, st, 200, () -> {
			if (!"advance".equals(st.phase)) return;
			advanceChecks[0]++;
			check(SquadMovementPlanner.distance(squad.get(0).cell, squad.get(1).cell, map.width) <= 1, "fast member stays within 1 of slow member");
		});
		check(advanceChecks[0] > 10, "slow+fast pair exercised advance: checks=" + advanceChecks[0]);
		check(st.anchorId == 1, "slowest member is the anchor");
	}

	private static void gatherThenAdvance() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(12, 12);
		ArrayList<Member> squad = squad(fm(1, map.cell(2, 7), 0.5f), fm(2, map.cell(7, 7), 1f));
		occupyAll(map, hero, squad);
		FormationPlanner.State st = new FormationPlanner.State();
		check(FormationPlanner.step(st, squad.get(0), squad, hero, map) == squad.get(0).cell && "gather".equals(st.phase), "anchor waits while gathering");
		for (int i = 0; i < 6 && !"advance".equals(st.phase); i++) {
			Member other = squad.get(1), anchor = squad.get(0);
			int before = SquadMovementPlanner.distance(other.cell, anchor.cell, map.width);
			int to = FormationPlanner.step(st, other, squad, hero, map);
			check(to != FormationPlanner.RELEASED && SquadMovementPlanner.distance(to, anchor.cell, map.width) < before, "non-anchor moves toward anchor");
			moveMember(map, squad, other.id, to);
			if (!"advance".equals(st.phase))
				check(FormationPlanner.step(st, anchor, squad, hero, map) == anchor.cell && "gather".equals(st.phase), "anchor keeps waiting");
		}
		check("advance".equals(st.phase) && FormationPlanner.connected(squad, -1, -1, map.width), "phase advance once connected");
		check(st.anchorId == 1, "anchor is slowest member");
	}

	private static void gatherTimeoutReleases() {
		SquadTestMap map = new SquadTestMap(15, 15);
		for (int y = 1; y < 14; y++) map.wall(7, y);
		int hero = map.cell(12, 12);
		ArrayList<Member> squad = squad(fm(1, map.cell(3, 7), 1f), fm(2, map.cell(10, 7), 1f));
		occupyAll(map, hero, squad);
		FormationPlanner.State st = new FormationPlanner.State();
		for (int turn = 1; turn <= 4; turn++) {
			check(FormationPlanner.step(st, squad.get(1), squad, hero, map) == squad.get(1).cell, "cut-off member waits");
			check(FormationPlanner.step(st, squad.get(0), squad, hero, map) == squad.get(0).cell, "anchor waits on gather turn " + turn);
		}
		check(FormationPlanner.step(st, squad.get(0), squad, hero, map) == FormationPlanner.RELEASED
				&& "released".equals(st.phase) && "gather_timeout".equals(st.releaseReason), "5th anchor gather turn releases");
		check(FormationPlanner.step(st, squad.get(1), squad, hero, map) == FormationPlanner.RELEASED, "released stays released");
	}

	private static void immobileAnchorReleasesAfterStall() {
		SquadTestMap map = new SquadTestMap(15, 15);
		int hero = map.cell(12, 7);
		ArrayList<Member> squad = squad(fm(1, map.cell(4, 7), 0.5f), fm(2, map.cell(3, 7), 1f));
		occupyAll(map, hero, squad);
		for (int y = 5; y <= 9; y++) for (int x = 2; x <= 5; x++) if (!map.occupied(map.cell(x, y))) map.occupy(map.cell(x, y));
		FormationPlanner.State st = new FormationPlanner.State();
		Member anchor = squad.get(0);
		check(FormationPlanner.step(st, anchor, squad, hero, map) == anchor.cell && "advance".equals(st.phase), "boxed anchor waits; best progress set");
		for (int turn = 1; turn <= 2; turn++) {
			check(FormationPlanner.step(st, squad.get(1), squad, hero, map) == squad.get(1).cell, "boxed follower waits");
			check(FormationPlanner.step(st, anchor, squad, hero, map) == anchor.cell && st.stallTurns == turn, "stall turn " + turn);
		}
		check(FormationPlanner.step(st, anchor, squad, hero, map) == FormationPlanner.RELEASED
				&& "released".equals(st.phase) && "stalled".equals(st.releaseReason), "3rd consecutive stall releases");
	}

	private static void degradeMatrixNeverThrows() {
		String[] tactics = {"advance", "flank", "escort_ranged", "hold_range", "formation"};
		String[] squads = {"melee_only", "melee_ranged", "single_ranged", "with_instinctive"};
		String[] terrains = {"open", "corridor", "walled"};
		for (String tactic : tactics) for (String kind : squads) for (String terrain : terrains) {
			SquadTestMap map = terrain.equals("corridor") ? corridor(13) : new SquadTestMap(13, 13);
			if (terrain.equals("walled")) for (int x = 1; x < 12; x++) map.wall(x, 6);
			int hero = map.cell(6, 3);
			ArrayList<Member> squad = new ArrayList<>();
			if (kind.equals("single_ranged")) squad.add(new Member(1, map.cell(6, 9), true, true, "dealer"));
			else {
				squad.add(new Member(1, map.cell(6, 8), true, false, "tank"));
				squad.add(new Member(2, map.cell(6, 9), true, false, "melee", 2f));
				if (kind.equals("melee_ranged")) squad.add(new Member(3, map.cell(6, 10), true, true, "dealer"));
				else if (kind.equals("with_instinctive")) squad.add(new Member(3, map.cell(6, 10), false, false, "melee"));
				else squad.add(new Member(3, map.cell(6, 10), true, false, "melee", 0.5f));
			}
			occupyAll(map, hero, squad);
			String label = tactic + "/" + kind + "/" + terrain;
			if (tactic.equals("advance") || tactic.equals("hold_range")) {
				check(true, label + ": advance/hold_range have no planner execution path");
			} else if (!tactic.equals("formation")) {
				SquadMovementPlanner.SquadPlan plan = SquadMovementPlanner.assign(tactic, squad, hero, map);
				check(!plan.byMember.isEmpty() || plan.degradeReason != null, label + ": assignment or degrade reason");
			} else {
				String reason = FormationPlanner.degradeReason(squad);
				String expected = kind.equals("melee_ranged") ? "mixed_squad" : kind.equals("single_ranged") ? "too_few_melee" : null;
				check(expected == null ? reason == null : expected.equals(reason), label + ": degrade reason " + reason);
				if ("too_few_melee".equals(reason)) continue;
				FormationPlanner.State st = new FormationPlanner.State();
				for (int i = 0; i < 30; i++) {
					Member mover = squad.get(i % squad.size());
					int to = FormationPlanner.step(st, mover, squad, hero, map);
					boolean participant = mover.tactical && !mover.ranged;
					if (!participant) check(to == FormationPlanner.RELEASED, label + ": non-participant gets default AI");
					if (to != FormationPlanner.RELEASED && to != mover.cell) {
						check(!map.occupied(to) && map.passable(mover, to), label + ": legal formation step");
						moveMember(map, squad, mover.id, to);
					}
				}
			}
		}
		ArrayList<Member> one = squad(fm(1, 20, 1f), new Member(2, 21, false, false, "melee"));
		check("too_few_melee".equals(FormationPlanner.degradeReason(one)), "instinctive member does not count as participant");
		check(FormationPlanner.participants(squad(fm(3, 5, 1f), fm(1, 6, 1f))).get(0).id == 1, "participants sorted by id");
	}

	private static void checkSectors(SquadTestMap map, SquadMovementPlanner.SquadPlan plan, int hero) {
		Set<Integer> sectors = new HashSet<>();
		int front = SquadMovementPlanner.directionBucket(plan.axisFrom, hero, map.width);
		for (SquadMovementPlanner.Assignment a : plan.byMember.values()) {
			if (!"flank".equals(a.maneuver)) continue;
			check(SquadMovementPlanner.distance(a.goal, hero, map.width) == 1, "flank goal on hero ring");
			check(a.sector == SquadMovementPlanner.directionBucket(a.goal, hero, map.width), "sector matches goal");
			check(SquadMovementPlanner.sectorDiff(a.sector, front) >= 2, "flank sector not frontal");
			check(sectors.add(a.sector), "flank sectors distinct");
		}
	}

	private static boolean goalLegal(SquadTestMap map, SquadMovementPlanner.Member m, int c, int hero) {
		return map.passable(m, c) && (c == m.cell || !map.occupied(c)) && map.visible(m, c) && map.visible(m, hero);
	}

	private static SquadTestMap corridor(int size) {
		SquadTestMap map = new SquadTestMap(size, size);
		for (int y = 1; y < size - 1; y++) for (int x = 1; x < size - 1; x++) if (x != size / 2) map.wall(x, y);
		return map;
	}

	private static void occupyAll(SquadTestMap map, int hero, ArrayList<SquadMovementPlanner.Member> squad) {
		map.occupy(hero);
		for (SquadMovementPlanner.Member m : squad) map.occupy(m.cell);
	}

	private static String pathString(SquadTestMap map, ArrayList<Integer> path) {
		StringBuilder s = new StringBuilder();
		for (int c : path) s.append('(').append(map.x(c)).append(',').append(map.y(c)).append(')');
		return s.toString();
	}

	private static SquadMovementPlanner.Member member(int id, int cell) {
		return new SquadMovementPlanner.Member(id, cell, true, false, "melee");
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
