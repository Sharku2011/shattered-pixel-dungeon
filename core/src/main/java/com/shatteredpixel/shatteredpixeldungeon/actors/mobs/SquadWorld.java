package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

/** Game-independent view of the level used by squad movement planning. */
interface SquadWorld {
	int width();
	int height();
	/** Terrain only; characters are never considered. */
	boolean passable(SquadMovementPlanner.Member mover, int cell);
	/** Any character, including the hero and squad members. */
	boolean occupied(int cell);
	boolean visible(SquadMovementPlanner.Member mover, int cell);
}
