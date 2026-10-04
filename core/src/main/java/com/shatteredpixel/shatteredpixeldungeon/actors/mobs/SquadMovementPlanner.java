package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

		Member(int id, int cell, boolean tactical, boolean ranged, String role) {
			this.id = id;
			this.cell = cell;
			this.tactical = tactical;
			this.ranged = ranged;
			this.role = role;
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

		int hx = heroCell % width;
		int hy = heroCell / width;
		int ax = screenTarget.cell % width;
		int ay = screenTarget.cell / width;
		Set<Integer> cells = new HashSet<>();
		for (float t : new float[]{0.30f, 0.45f, 0.60f}) {
			int lineX = Math.round(hx + (ax - hx) * t);
			int lineY = Math.round(hy + (ay - hy) * t);
			for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dy == 0) continue;
				int x = lineX + dx;
				int y = lineY + dy;
				if (x < 0 || x >= width || y < 0 || y >= height) continue;
				int cell = x + y * width;
				int heroDistance = distance(cell, heroCell, width);
				int allyDistance = distance(cell, screenTarget.cell, width);
				if (heroDistance >= 2 && allyDistance < distance(heroCell, screenTarget.cell, width)
						&& world.legal(mover, cell) && world.reachable(mover, cell)) cells.add(cell);
			}
		}
		ArrayList<Integer> ordered = new ArrayList<>(cells);
		ordered.sort(Comparator.comparingInt(cell -> Math.abs(distance(cell, heroCell, width) - 3)
				+ Math.abs(distance(cell, screenTarget.cell, width) - 2)));
		for (int i = 0; i < Math.min(8, ordered.size()); i++) result.add(new Candidate(mover.id, ordered.get(i), "escort"));
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
