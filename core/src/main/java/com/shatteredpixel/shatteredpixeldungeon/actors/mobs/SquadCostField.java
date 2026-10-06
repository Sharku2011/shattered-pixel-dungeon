package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-cell enter-cost field for one squad member: base cost plus tactical penalties (one per category). */
final class SquadCostField implements SquadDijkstra.Graph {
	static final int BASE = 10, HERO_RING1 = 40, HERO_RING2 = 15, AXIS_NEAR = 30, AXIS_FAR = 10,
			RESERVED = 25, RESERVED_ADJ = 10, SQUADMATE_ADJ = 15, SEARCH_MARGIN = 4;

	static final class Penalties {
		boolean heroProximity;
		int ownGoal = -1;
		int axisFrom = -1;
		List<List<Integer>> reservations = new ArrayList<>();
		boolean squadmateAdjacency;
	}

	private final SquadWorld world;
	private final SquadMovementPlanner.Member mover;
	private final List<SquadMovementPlanner.Member> squad;
	private final int heroCell;
	private final Penalties penalties;
	private final int width, height;
	private final Set<Integer> squadCells = new HashSet<>();
	private final Set<Integer> reserved = new HashSet<>();
	private final List<Integer> axis = new ArrayList<>();

	SquadCostField(SquadWorld world, SquadMovementPlanner.Member mover, List<SquadMovementPlanner.Member> squad,
			int heroCell, Penalties penalties) {
		this.world = world;
		this.mover = mover;
		this.squad = squad;
		this.heroCell = heroCell;
		this.penalties = penalties;
		width = world.width();
		height = world.height();
		for (SquadMovementPlanner.Member m : squad) if (m.id != mover.id) squadCells.add(m.cell);
		for (List<Integer> r : penalties.reservations) reserved.addAll(r);
		if (penalties.axisFrom >= 0) {
			int x0 = penalties.axisFrom % width, y0 = penalties.axisFrom / width;
			int dx = heroCell % width - x0, dy = heroCell / width - y0;
			int n = Math.max(Math.abs(dx), Math.abs(dy));
			for (int i = 0; i < n; i++) {
				int x = (int) Math.round(x0 + (double) dx * i / n), y = (int) Math.round(y0 + (double) dy * i / n);
				axis.add(x + y * width);
			}
		}
	}

	@Override public int width() { return width; }

	@Override public int height() { return height; }

	@Override public boolean passable(int cell) {
		if (!world.passable(mover, cell)) return false;
		return !(world.occupied(cell) && world.visible(mover, cell) && !squadCells.contains(cell) && cell != mover.cell);
	}

	@Override public int enterCost(int cell) {
		int cost = BASE;
		if (penalties.heroProximity && cell != penalties.ownGoal) {
			int d = chebyshev(cell, heroCell);
			if (d == 1) cost += HERO_RING1;
			else if (d == 2) cost += HERO_RING2;
		}
		int a = axisDistance(cell);
		if (a <= 1) cost += AXIS_NEAR;
		else if (a == 2) cost += AXIS_FAR;
		if (reserved.contains(cell)) cost += RESERVED;
		else if (adjacentToAny(cell, reserved)) cost += RESERVED_ADJ;
		if (penalties.squadmateAdjacency && adjacentToAny(cell, squadCells)) cost += SQUADMATE_ADJ;
		return cost;
	}

	int axisDistance(int cell) {
		int best = Integer.MAX_VALUE;
		for (int c : axis) best = Math.min(best, chebyshev(cell, c));
		return best;
	}

	SquadDijkstra.Bounds bounds(int... extraCells) {
		int[] cells = new int[squad.size() + 1 + extraCells.length];
		int n = 0;
		cells[n++] = heroCell;
		for (SquadMovementPlanner.Member m : squad) cells[n++] = m.cell;
		for (int c : extraCells) cells[n++] = c;
		return SquadDijkstra.Bounds.around(width, height, SEARCH_MARGIN, cells);
	}

	private boolean adjacentToAny(int cell, Set<Integer> cells) {
		for (int c : cells) if (chebyshev(cell, c) == 1) return true;
		return false;
	}

	private int chebyshev(int a, int b) {
		return Math.max(Math.abs(a % width - b % width), Math.abs(a / width - b / width));
	}
}
