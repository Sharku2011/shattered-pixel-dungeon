package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import java.util.HashSet;
import java.util.Set;

/** Simple rectangular SquadWorld for standalone simulations: walled border, open floor, everything visible. */
final class SquadTestMap implements SquadWorld {
	final int width;
	final int height;
	final boolean[] walkable;
	final boolean[] visible;
	final boolean[] occupied;
	final boolean[] openSpace;
	final Set<Integer> large = new HashSet<>();

	SquadTestMap(int width, int height) {
		this.width = width;
		this.height = height;
		walkable = new boolean[width * height];
		visible = new boolean[width * height];
		occupied = new boolean[width * height];
		openSpace = new boolean[width * height];
		for (int y = 1; y < height - 1; y++) for (int x = 1; x < width - 1; x++) {
			walkable[cell(x, y)] = true;
			visible[cell(x, y)] = true;
			openSpace[cell(x, y)] = true;
		}
	}

	int cell(int x, int y) { return x + y * width; }
	int x(int cell) { return cell % width; }
	int y(int cell) { return cell / width; }
	void wall(int x, int y) { walkable[cell(x, y)] = false; }
	void occupy(int cell) { occupied[cell] = true; }
	void hide(int cell) { visible[cell] = false; }

	@Override public int width() { return width; }
	@Override public int height() { return height; }

	@Override public boolean passable(SquadMovementPlanner.Member mover, int cell) {
		if (!walkable[cell]) return false;
		return mover == null || !large.contains(mover.id) || openSpace[cell];
	}

	@Override public boolean occupied(int cell) { return occupied[cell]; }

	@Override public boolean visible(SquadMovementPlanner.Member mover, int cell) { return visible[cell]; }
}
