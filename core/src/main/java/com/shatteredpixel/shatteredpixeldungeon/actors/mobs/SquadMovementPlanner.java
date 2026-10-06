package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure candidate generator for squad movement; the game adapter supplies visibility and path checks. */
final class SquadMovementPlanner {
	interface World {
		boolean visible(int cell);
		boolean legal(Member mover, int cell);
		boolean reachable(Member mover, int cell);
	}

	static final class Member {
		final int id;
		final int cell;
		final boolean tactical;
		final boolean ranged;
		final String role;
		final float speed;

		Member(int id, int cell, boolean tactical, boolean ranged, String role) {
			this(id, cell, tactical, ranged, role, 1f);
		}

		Member(int id, int cell, boolean tactical, boolean ranged, String role, float speed) {
			this.id = id;
			this.cell = cell;
			this.tactical = tactical;
			this.ranged = ranged;
			this.role = role;
			this.speed = speed;
		}
	}

	static final class Candidate {
		final int memberId;
		final int cell;
		final String maneuver;

		Candidate(int memberId, int cell, String maneuver) {
			this.memberId = memberId;
			this.cell = cell;
			this.maneuver = maneuver;
		}
	}

	private SquadMovementPlanner() {}

	static ArrayList<Candidate> candidates(Member mover, List<Member> squad, int heroCell,
			int width, int height, World world) {
		ArrayList<Candidate> result = new ArrayList<>();
		if (mover == null || world == null || width <= 0 || !world.visible(heroCell)) return result;
		if (mover.tactical) addFlank(result, mover, squad, heroCell, width, height, world);
		addEscort(result, mover, squad, heroCell, width, height, world);
		return result;
	}

	static String choiceKey(Candidate candidate, int width) {
		return candidate.maneuver + "_" + candidate.cell % width + "_" + candidate.cell / width;
	}

	static boolean relevantForTactics(Candidate candidate, boolean flankAvailable,
			boolean escortAvailable, boolean roleSelectionPending, boolean memberIsTank) {
		if (candidate == null) return false;
		if ("flank".equals(candidate.maneuver)) {
			return flankAvailable || (escortAvailable && (roleSelectionPending || !memberIsTank));
		}
		if ("escort".equals(candidate.maneuver)) {
			return escortAvailable && (roleSelectionPending || memberIsTank);
		}
		return false;
	}

	static int directionBucket(int cell, int heroCell, int width) {
		int dx = cell % width - heroCell % width;
		int dy = cell / width - heroCell / width;
		int absX = Math.abs(dx);
		int absY = Math.abs(dy);
		if (absX * 2 < absY) return dy < 0 ? 0 : 4;
		if (absY * 2 < absX) return dx > 0 ? 2 : 6;
		if (dx > 0) return dy < 0 ? 1 : 3;
		return dy < 0 ? 7 : 5;
	}

	static String directionName(int direction) {
		switch (direction) {
			case 0: return "north";
			case 1: return "northeast";
			case 2: return "east";
			case 3: return "southeast";
			case 4: return "south";
			case 5: return "southwest";
			case 6: return "west";
			default: return "northwest";
		}
	}

	/** Resolves only an offered key, then repeats visibility, legality, reachability and role checks. */
	static Candidate validateChoice(String key, Map<String, Candidate> offered, Member mover,
			String expectedManeuver, World world) {
		if (key == null || offered == null || mover == null || world == null) return null;
		Candidate candidate = offered.get(key);
		if (candidate == null || candidate.memberId != mover.id
				|| !candidate.maneuver.equals(expectedManeuver)
				|| !world.visible(candidate.cell) || !world.legal(mover, candidate.cell)
				|| !world.reachable(mover, candidate.cell)) return null;
		return candidate;
	}

	static boolean conflictsWithAssignedFlank(Candidate candidate, Map<Integer, Integer> assignedCells,
			Map<Integer, String> assignedManeuvers, int heroCell, int width) {
		if (candidate == null || !"flank".equals(candidate.maneuver)
				|| assignedCells == null || assignedManeuvers == null) return false;
		int direction = directionBucket(candidate.cell, heroCell, width);
		for (Map.Entry<Integer, Integer> assigned : assignedCells.entrySet()) {
			if (!"flank".equals(assignedManeuvers.get(assigned.getKey()))) continue;
			if (directionBucket(assigned.getValue(), heroCell, width) == direction) return true;
		}
		return false;
	}

	private static void addFlank(ArrayList<Candidate> result, Member mover, List<Member> squad,
			int heroCell, int width, int height, World world) {
		int hx = heroCell % width;
		int hy = heroCell / width;
		ArrayList<Integer> cells = new ArrayList<>();
		for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
			int heroDistance = Math.max(Math.abs(dx), Math.abs(dy));
			if (heroDistance == 0 || heroDistance > 4) continue;
			int x = hx + dx;
			int y = hy + dy;
			if (x < 0 || x >= width || y < 0 || y >= height) continue;
			int cell = x + y * width;
			if (cell != mover.cell && world.legal(mover, cell) && world.reachable(mover, cell)) cells.add(cell);
		}
		cells.sort((a, b) -> Integer.compare(flankScore(b, heroCell, mover, squad, width),
				flankScore(a, heroCell, mover, squad, width)));
		HashSet<Integer> sectors = new HashSet<>();
		for (int cell : cells) {
			if (sectors.add(directionBucket(cell, heroCell, width))) {
				result.add(new Candidate(mover.id, cell, "flank"));
				if (result.size() == 8) break;
			}
		}
	}

	private static int flankScore(int cell, int heroCell, Member mover, List<Member> squad, int width) {
		int heroDistance = distance(cell, heroCell, width);
		return spacing(cell, mover, squad, width) * 2 - Math.abs(heroDistance - 2) * 3;
	}

	private static void addEscort(ArrayList<Candidate> result, Member mover, List<Member> squad,
			int heroCell, int width, int height, World world) {
		Member rangedAlly = null;
		for (Member member : squad) {
			if (member != mover && member.ranged && world.visible(member.cell)) {
				if ("dealer".equals(member.role)) { rangedAlly = member; break; }
				if (rangedAlly == null) rangedAlly = member;
			}
		}
		if (rangedAlly == null) return;
		final Member screenTarget = rangedAlly;
		Set<Integer> cells = new HashSet<>();
		for (int cell : escortCells(heroCell, screenTarget.cell, width, height))
			if (world.legal(mover, cell) && world.reachable(mover, cell)) cells.add(cell);
		ArrayList<Integer> ordered = new ArrayList<>(cells);
		ordered.sort(Comparator.comparingInt(cell -> Math.abs(distance(cell, heroCell, width) - 3)
				+ Math.abs(distance(cell, screenTarget.cell, width) - 2)));
		for (int i = 0; i < Math.min(8, ordered.size()); i++) result.add(new Candidate(mover.id, ordered.get(i), "escort"));
	}

	/** Cells around the 30/45/60% points of hero-to-ally, at least 2 from the hero and nearer the ally than the hero is. */
	private static ArrayList<Integer> escortCells(int heroCell, int allyCell, int width, int height) {
		int hx = heroCell % width, hy = heroCell / width;
		int ax = allyCell % width, ay = allyCell / width;
		LinkedHashSet<Integer> cells = new LinkedHashSet<>();
		for (float t : new float[]{0.30f, 0.45f, 0.60f}) {
			int lineX = Math.round(hx + (ax - hx) * t);
			int lineY = Math.round(hy + (ay - hy) * t);
			for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dy == 0) continue;
				int x = lineX + dx, y = lineY + dy;
				if (x < 0 || x >= width || y < 0 || y >= height) continue;
				int cell = x + y * width;
				if (distance(cell, heroCell, width) >= 2
						&& distance(cell, allyCell, width) < distance(heroCell, allyCell, width)) cells.add(cell);
			}
		}
		return new ArrayList<>(cells);
	}

	// ---- Dijkstra goal assignment and per-turn steps (flank, escort_ranged) ----

	static final class Assignment {
		final int memberId;
		final String maneuver;
		int goal, sector, heroCell, allyCell = -1;
		ArrayList<Integer> path = new ArrayList<>();

		Assignment(int memberId, String maneuver) {
			this.memberId = memberId;
			this.maneuver = maneuver;
		}
	}

	static final class SquadPlan {
		final LinkedHashMap<Integer, Assignment> byMember = new LinkedHashMap<>();
		int frontId = -1;
		int axisFrom = -1;
		String degradeReason;
	}

	static int sectorDiff(int a, int b) {
		int d = Math.abs(a - b) % 8;
		return Math.min(d, 8 - d);
	}

	/** A goal must be walkable for the member, free, and both it and the hero must be visible to the member. */
	static boolean goalLegal(Member m, int cell, int heroCell, SquadWorld world) {
		return world.passable(m, cell) && !world.occupied(cell) && world.visible(m, cell) && world.visible(m, heroCell);
	}

	static int frontMember(List<Member> squad, int heroCell, SquadWorld world) {
		Member best = null;
		for (Member m : squad) if ("tank".equals(m.role) && (best == null || m.id < best.id)) best = m;
		if (best != null) return best.id;
		int bestCost = SquadDijkstra.UNREACHABLE;
		for (Member m : squad) {
			if (m.ranged || !m.tactical) continue;
			SquadCostField f = new SquadCostField(world, m, squad, heroCell, new SquadCostField.Penalties());
			int cost = SquadDijkstra.toTarget(f, heroCell, f.bounds())[m.cell];
			if (best == null || cost < bestCost || (cost == bestCost && m.id < best.id)) { best = m; bestCost = cost; }
		}
		return best == null ? -1 : best.id;
	}

	static SquadPlan assign(String tactic, List<Member> squad, int heroCell, SquadWorld world) {
		SquadPlan plan = new SquadPlan();
		plan.frontId = frontMember(squad, heroCell, world);
		Member front = find(squad, plan.frontId);
		if (front != null) plan.axisFrom = front.cell;
		boolean escorted = false;
		if ("escort_ranged".equals(tactic) && front != null) {
			Member ally = rangedAlly(front, squad, world);
			Assignment e = ally == null ? null : escortAssignment(plan, front, ally.cell, squad, heroCell, world);
			if (e != null) {
				plan.byMember.put(front.id, e);
				plan.axisFrom = e.goal;
				escorted = true;
			} else plan.degradeReason = "no_screen_position";
		}
		ArrayList<Member> flankers = new ArrayList<>();
		for (Member m : squad) if (m.tactical && !m.ranged && m.id != plan.frontId) flankers.add(m);
		int flanks = front == null ? 0 : assignFlanks(plan, flankers, squad, heroCell, world);
		if (flanks == 0 && !escorted && plan.degradeReason == null) plan.degradeReason = "no_open_sector";
		return plan;
	}

	/** Re-runs one assignment step for this member only, keeping other assignments and their sectors. */
	static boolean reassign(SquadPlan plan, Member member, List<Member> squad, int heroCell, SquadWorld world) {
		Assignment old = plan.byMember.remove(member.id);
		if (old != null && "escort".equals(old.maneuver)) {
			Member ally = rangedAlly(member, squad, world);
			Assignment e = ally == null ? null : escortAssignment(plan, member, ally.cell, squad, heroCell, world);
			if (e == null) return false;
			plan.byMember.put(member.id, e);
			plan.axisFrom = e.goal;
			return true;
		}
		if (!member.tactical || member.ranged || member.id == plan.frontId || plan.axisFrom < 0) return false;
		ArrayList<Member> one = new ArrayList<>();
		one.add(member);
		return assignFlanks(plan, one, squad, heroCell, world) > 0;
	}

	/** Hero moved: keep the sector, move the goal to the cheapest legal ring cell of that sector (escort: recompute screen). */
	static boolean retarget(SquadPlan plan, Member member, List<Member> squad, int heroCell, SquadWorld world) {
		Assignment a = plan.byMember.get(member.id);
		if (a == null) return false;
		if ("escort".equals(a.maneuver)) {
			Assignment e = escortAssignment(plan, member, a.allyCell, squad, heroCell, world);
			if (e == null) return false;
			a.goal = e.goal; a.sector = e.sector; a.heroCell = heroCell; a.path = e.path;
			plan.axisFrom = e.goal;
			return true;
		}
		ArrayList<Integer> cells = new ArrayList<>();
		for (int c : ring(heroCell, world.width(), world.height()))
			if (directionBucket(c, heroCell, world.width()) == a.sector && goalLegal(member, c, heroCell, world)) cells.add(c);
		if (cells.isEmpty()) return false;
		SquadCostField f = field(plan, member, squad, heroCell, world, plan.axisFrom, -1, false);
		int[] d = SquadDijkstra.fromSource(f, member.cell, f.bounds(toArray(cells)));
		int best = -1;
		for (int c : cells) if (d[c] != SquadDijkstra.UNREACHABLE && (best < 0 || d[c] < d[best])) best = c;
		if (best < 0) return false;
		a.goal = best; a.heroCell = heroCell; a.path = SquadDijkstra.tracePath(f, d, member.cell, best);
		return true;
	}

	/** One step toward the member's goal over the full per-turn cost field; -1 when no neighbour is free and no worse. */
	static int nextStep(SquadPlan plan, Member mover, List<Member> squad, int heroCell, SquadWorld world) {
		Assignment a = plan.byMember.get(mover.id);
		if (a == null || find(squad, mover.id) == null) return -1;
		SquadCostField f = field(plan, mover, squad, heroCell, world, "flank".equals(a.maneuver) ? plan.axisFrom : -1, a.goal, true);
		int[] d = SquadDijkstra.toTarget(f, a.goal, f.bounds(a.goal));
		int w = world.width(), h = world.height(), here = d[mover.cell], step = -1;
		for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int x = mover.cell % w + dx, y = mover.cell / w + dy;
			if ((dx == 0 && dy == 0) || x < 0 || y < 0 || x >= w || y >= h) continue;
			int n = x + y * w;
			if (d[n] == SquadDijkstra.UNREACHABLE || d[n] > here || !world.passable(mover, n) || world.occupied(n)) continue;
			if (step < 0 || d[n] < d[step]) step = n;
		}
		if (step < 0) return -1;
		ArrayList<Integer> path = new ArrayList<>();
		path.add(step);
		for (int cur = step; cur != a.goal; ) {
			int next = -1;
			for (int dy = -1; dy <= 1 && next < 0; dy++) for (int dx = -1; dx <= 1 && next < 0; dx++) {
				int x = cur % w + dx, y = cur / w + dy;
				if ((dx == 0 && dy == 0) || x < 0 || y < 0 || x >= w || y >= h) continue;
				int n = x + y * w;
				if (d[n] != SquadDijkstra.UNREACHABLE && d[n] + f.enterCost(n) == d[cur]) next = n;
			}
			if (next < 0) break;
			path.add(next);
			cur = next;
		}
		a.path = path;
		return step;
	}

	static ArrayList<String> openSectors(List<Member> squad, int heroCell, SquadWorld world) {
		ArrayList<String> names = new ArrayList<>();
		for (Assignment a : assign("flank", squad, heroCell, world).byMember.values())
			if ("flank".equals(a.maneuver)) names.add(directionName(a.sector));
		return names;
	}

	static boolean screenPositionAvailable(List<Member> squad, int heroCell, SquadWorld world) {
		for (Assignment a : assign("escort_ranged", squad, heroCell, world).byMember.values())
			if ("escort".equals(a.maneuver)) return true;
		return false;
	}

	/** Spec 5.4 steps 2-4: one Dijkstra per open flanker per round, cheapest (path + sector score) pair wins. */
	private static int assignFlanks(SquadPlan plan, List<Member> flankers, List<Member> squad, int heroCell, SquadWorld world) {
		int w = world.width();
		int front = directionBucket(plan.axisFrom, heroCell, w);
		HashSet<Integer> used = new HashSet<>();
		for (Assignment a : plan.byMember.values()) if ("flank".equals(a.maneuver)) used.add(a.sector);
		ArrayList<Integer> ring = ring(heroCell, w, world.height());
		ArrayList<Member> open = new ArrayList<>(flankers);
		open.sort(Comparator.comparingInt(m -> m.id));
		int assigned = 0;
		while (!open.isEmpty()) {
			Member bestMember = null;
			SquadCostField bestField = null;
			int[] bestDist = null;
			int bestCell = -1, bestScore = Integer.MAX_VALUE;
			for (Iterator<Member> it = open.iterator(); it.hasNext(); ) {
				Member m = it.next();
				ArrayList<Integer> cells = new ArrayList<>();
				for (int c : ring) {
					int s = directionBucket(c, heroCell, w);
					if (!used.contains(s) && sectorDiff(s, front) >= 2 && goalLegal(m, c, heroCell, world)) cells.add(c);
				}
				if (cells.isEmpty()) { it.remove(); continue; }
				SquadCostField f = field(plan, m, squad, heroCell, world, plan.axisFrom, -1, false);
				int[] d = SquadDijkstra.fromSource(f, m.cell, f.bounds(toArray(cells)));
				boolean reachable = false;
				for (int c : cells) {
					if (d[c] == SquadDijkstra.UNREACHABLE) continue;
					reachable = true;
					int score = d[c] + (4 - sectorDiff(directionBucket(c, heroCell, w), front)) * 20;
					if (score < bestScore) { bestScore = score; bestMember = m; bestCell = c; bestField = f; bestDist = d; }
				}
				if (!reachable) it.remove();
			}
			if (bestMember == null) break;
			Assignment a = new Assignment(bestMember.id, "flank");
			a.goal = bestCell;
			a.sector = directionBucket(bestCell, heroCell, w);
			a.heroCell = heroCell;
			a.path = SquadDijkstra.tracePath(bestField, bestDist, bestMember.cell, bestCell);
			plan.byMember.put(bestMember.id, a);
			used.add(a.sector);
			open.remove(bestMember);
			assigned++;
		}
		return assigned;
	}

	/** Cheapest escort cell by path cost + |heroDist-3| + |allyDist-2|; null when none is legal and reachable. */
	private static Assignment escortAssignment(SquadPlan plan, Member front, int allyCell, List<Member> squad,
			int heroCell, SquadWorld world) {
		int w = world.width();
		ArrayList<Integer> cells = new ArrayList<>();
		for (int c : escortCells(heroCell, allyCell, w, world.height())) if (goalLegal(front, c, heroCell, world)) cells.add(c);
		if (cells.isEmpty()) return null;
		SquadCostField f = field(plan, front, squad, heroCell, world, -1, -1, false);
		int[] d = SquadDijkstra.fromSource(f, front.cell, f.bounds(toArray(cells)));
		int best = -1, bestScore = Integer.MAX_VALUE;
		for (int c : cells) {
			if (d[c] == SquadDijkstra.UNREACHABLE) continue;
			int score = d[c] + Math.abs(distance(c, heroCell, w) - 3) + Math.abs(distance(c, allyCell, w) - 2);
			if (score < bestScore) { bestScore = score; best = c; }
		}
		if (best < 0) return null;
		Assignment a = new Assignment(front.id, "escort");
		a.goal = best;
		a.sector = directionBucket(best, heroCell, w);
		a.heroCell = heroCell;
		a.allyCell = allyCell;
		a.path = SquadDijkstra.tracePath(f, d, front.cell, best);
		return a;
	}

	/** Ranged squadmate visible to the front member, dealer first (same preference as addEscort). */
	private static Member rangedAlly(Member front, List<Member> squad, SquadWorld world) {
		Member ally = null;
		for (Member m : squad) {
			if (m.id == front.id || !m.ranged || !world.visible(front, m.cell)) continue;
			if ("dealer".equals(m.role)) return m;
			if (ally == null) ally = m;
		}
		return ally;
	}

	private static SquadCostField field(SquadPlan plan, Member m, List<Member> squad, int heroCell, SquadWorld world,
			int axisFrom, int ownGoal, boolean squadmateAdjacency) {
		SquadCostField.Penalties p = new SquadCostField.Penalties();
		p.heroProximity = true;
		p.ownGoal = ownGoal;
		p.axisFrom = axisFrom;
		p.squadmateAdjacency = squadmateAdjacency;
		for (Assignment a : plan.byMember.values()) if (a.memberId != m.id) p.reservations.add(a.path);
		return new SquadCostField(world, m, squad, heroCell, p);
	}

	private static ArrayList<Integer> ring(int heroCell, int width, int height) {
		ArrayList<Integer> cells = new ArrayList<>();
		int hx = heroCell % width, hy = heroCell / width;
		for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int x = hx + dx, y = hy + dy;
			if ((dx != 0 || dy != 0) && x >= 0 && y >= 0 && x < width && y < height) cells.add(x + y * width);
		}
		return cells;
	}

	private static Member find(List<Member> squad, int id) {
		for (Member m : squad) if (m.id == id) return m;
		return null;
	}

	private static int[] toArray(List<Integer> cells) {
		int[] out = new int[cells.size()];
		for (int i = 0; i < out.length; i++) out[i] = cells.get(i);
		return out;
	}

	private static int spacing(int cell, Member mover, List<Member> squad, int width) {
		int score = 0;
		for (Member other : squad) if (other != mover) score += distance(cell, other.cell, width);
		return score;
	}

	static int distance(int a, int b, int width) {
		return Math.max(Math.abs(a % width - b % width), Math.abs(a / width - b / width));
	}
}
