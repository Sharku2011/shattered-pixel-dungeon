package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.SquadMovementPlanner.Member;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure planner for the formation tactic (spec 7): melee members advance as one connected group at the slowest pace. */
final class FormationPlanner {
	static final int GATHER_TIMEOUT = 4, STALL_LIMIT = 3, RELEASED = -1;

	static final class State {
		String phase = "gather";      // "gather" | "advance" | "contact" | "released"
		int anchorId = -1, gatherTurns, stallTurns, bestProgress = SquadDijkstra.UNREACHABLE;
		String releaseReason;         // "gather_timeout" | "stalled"
	}

	private FormationPlanner() {}

	/** Melee tactical members, ordered by id. */
	static ArrayList<Member> participants(List<Member> squad) {
		ArrayList<Member> out = new ArrayList<>();
		for (Member m : squad) if (m.tactical && !m.ranged) out.add(m);
		out.sort(Comparator.comparingInt(m -> m.id));
		return out;
	}

	static String degradeReason(List<Member> squad) {
		if (participants(squad).size() < 2) return "too_few_melee";
		for (Member m : squad) if (m.ranged) return "mixed_squad";
		return null;
	}

	/** Chebyshev-adjacency graph of the participants is one component; movedId (or -1) is taken to stand on movedTo. */
	static boolean connected(List<Member> participants, int movedId, int movedTo, int width) {
		int n = participants.size();
		if (n <= 1) return true;
		int[] cells = new int[n];
		for (int i = 0; i < n; i++) {
			Member m = participants.get(i);
			cells[i] = m.id == movedId ? movedTo : m.cell;
		}
		boolean[] seen = new boolean[n];
		int[] stack = new int[n];
		int top = 0, count = 1;
		seen[0] = true;
		stack[top++] = 0;
		while (top > 0) {
			int i = stack[--top];
			for (int j = 0; j < n; j++) {
				if (seen[j] || SquadMovementPlanner.distance(cells[i], cells[j], width) > 1) continue;
				seen[j] = true;
				stack[top++] = j;
				count++;
			}
		}
		return count == n;
	}

	/** Terrain-only distance to the hero (BASE per entered cell) over cells every participant can walk; characters ignored. */
	static int[] heroMap(final List<Member> participants, int heroCell, final SquadWorld world) {
		SquadDijkstra.Graph g = new SquadDijkstra.Graph() {
			@Override public int width() { return world.width(); }
			@Override public int height() { return world.height(); }
			@Override public boolean passable(int cell) {
				for (Member m : participants) if (!world.passable(m, cell)) return false;
				return true;
			}
			@Override public int enterCost(int cell) { return SquadCostField.BASE; }
		};
		return SquadDijkstra.toTarget(g, heroCell, bounds(participants, heroCell, world));
	}

	/** Returns the cell to move to, mover.cell to wait, or RELEASED to hand the mover back to the default AI. */
	static int step(State s, Member mover, List<Member> squad, int heroCell, SquadWorld world) {
		ArrayList<Member> ps = participants(squad);
		int w = world.width();
		boolean participant = false;
		for (Member p : ps) participant |= p.id == mover.id;
		if (!participant || "contact".equals(s.phase) || "released".equals(s.phase)) return RELEASED;
		for (Member p : ps) if (SquadMovementPlanner.distance(p.cell, heroCell, w) == 1) {
			s.phase = "contact";
			return RELEASED;
		}
		Member anchor = ps.get(0);
		for (Member p : ps) if (p.speed < anchor.speed) anchor = p; // ps is id-ordered, so ties keep the lowest id
		s.anchorId = anchor.id;
		// The phase follows connectivity on every call; only the anchor's own turns advance the counters.
		if (!connected(ps, -1, -1, w)) {
			if ("advance".equals(s.phase)) s.gatherTurns = 0;
			s.phase = "gather";
		} else if ("gather".equals(s.phase)) s.phase = "advance";
		int[] hero = null;
		if (mover.id == anchor.id) {
			if ("gather".equals(s.phase)) {
				if (++s.gatherTurns > GATHER_TIMEOUT) return release(s, "gather_timeout");
			} else {
				hero = heroMap(ps, heroCell, world);
				int progress = SquadDijkstra.UNREACHABLE;
				for (Member p : ps) progress = Math.min(progress, hero[p.cell]);
				if (progress < s.bestProgress) {
					s.bestProgress = progress;
					s.stallTurns = 0;
				} else if (++s.stallTurns >= STALL_LIMIT) return release(s, "stalled");
			}
		}
		if ("gather".equals(s.phase)) {
			if (mover.id == anchor.id) return mover.cell;
			int[] d = SquadDijkstra.toTarget(terrain(mover, world), anchor.cell, bounds(ps, heroCell, world));
			int best = mover.cell;
			for (int n : neighbours(mover.cell, world))
				if (d[n] < d[best] && world.passable(mover, n) && !world.occupied(n)) best = n;
			if (connected(ps, mover.id, best, w)) s.phase = "advance";
			return best;
		}
		if (hero == null) hero = heroMap(ps, heroCell, world);
		int others = SquadDijkstra.UNREACHABLE;
		for (Member p : ps) if (p.id != mover.id) others = Math.min(others, hero[p.cell]);
		int best = -1;
		for (int n : neighbours(mover.cell, world)) {
			if (hero[n] >= hero[mover.cell] || !world.passable(mover, n) || world.occupied(n) || !connected(ps, mover.id, n, w)) continue;
			if (best < 0 || hero[n] < hero[best] || (hero[n] == hero[best] && hero[n] == others && hero[best] != others)) best = n;
		}
		return best < 0 ? mover.cell : best;
	}

	private static int release(State s, String reason) {
		s.phase = "released";
		s.releaseReason = reason;
		return RELEASED;
	}

	private static SquadDijkstra.Graph terrain(final Member mover, final SquadWorld world) {
		return new SquadDijkstra.Graph() {
			@Override public int width() { return world.width(); }
			@Override public int height() { return world.height(); }
			@Override public boolean passable(int cell) { return world.passable(mover, cell); }
			@Override public int enterCost(int cell) { return SquadCostField.BASE; }
		};
	}

	private static SquadDijkstra.Bounds bounds(List<Member> participants, int heroCell, SquadWorld world) {
		int[] cells = new int[participants.size() + 1];
		cells[0] = heroCell;
		for (int i = 0; i < participants.size(); i++) cells[i + 1] = participants.get(i).cell;
		return SquadDijkstra.Bounds.around(world.width(), world.height(), SquadCostField.SEARCH_MARGIN, cells);
	}

	/** In-map 8-neighbours in ascending cell order. */
	private static ArrayList<Integer> neighbours(int cell, SquadWorld world) {
		ArrayList<Integer> out = new ArrayList<>();
		int w = world.width(), x0 = cell % w, y0 = cell / w;
		for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int x = x0 + dx, y = y0 + dy;
			if ((dx != 0 || dy != 0) && x >= 0 && y >= 0 && x < w && y < world.height()) out.add(x + y * w);
		}
		return out;
	}
}
