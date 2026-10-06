package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import java.util.ArrayList;
import java.util.Arrays;

/** Bounded 8-way Dijkstra using a circular bucket queue (Dial's algorithm). */
final class SquadDijkstra {
	interface Graph {
		int width();
		int height();
		boolean passable(int cell);
		int enterCost(int cell);
	}

	static final int UNREACHABLE = Integer.MAX_VALUE;
	static final int MAX_ENTER_COST = 120;
	private static final int BUCKETS = MAX_ENTER_COST + 1;
	private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] DY = {-1, -1, 0, 1, 1, 1, 0, -1};
	private static int runCount;

	static final class Bounds {
		final int minX, minY, maxX, maxY;

		private Bounds(int minX, int minY, int maxX, int maxY) {
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
		}

		static Bounds around(int width, int height, int margin, int... cells) {
			int minX = width, minY = height, maxX = -1, maxY = -1;
			for (int c : cells) {
				minX = Math.min(minX, c % width);
				maxX = Math.max(maxX, c % width);
				minY = Math.min(minY, c / width);
				maxY = Math.max(maxY, c / width);
			}
			return new Bounds(Math.max(0, minX - margin), Math.max(0, minY - margin),
					Math.min(width - 1, maxX + margin), Math.min(height - 1, maxY + margin));
		}

		boolean contains(int cell, int width) {
			int x = cell % width, y = cell / width;
			return x >= minX && x <= maxX && y >= minY && y <= maxY;
		}
	}

	private SquadDijkstra() {}

	static int[] fromSource(Graph g, int source, Bounds b) { return run(g, source, b, false); }

	static int[] toTarget(Graph g, int target, Bounds b) { return run(g, target, b, true); }

	static int runCount() { return runCount; }

	static void resetRunCount() { runCount = 0; }

	private static int[] run(Graph g, int start, Bounds b, boolean reverse) {
		runCount++;
		int w = g.width(), h = g.height();
		int[] dist = new int[w * h];
		Arrays.fill(dist, UNREACHABLE);
		@SuppressWarnings("unchecked")
		ArrayList<Integer>[] buckets = new ArrayList[BUCKETS];
		for (int i = 0; i < BUCKETS; i++) buckets[i] = new ArrayList<>();
		dist[start] = 0;
		buckets[0].add(start);
		int pending = 1;
		for (int d = 0; pending > 0; d++) {
			ArrayList<Integer> bucket = buckets[d % BUCKETS];
			for (int i = 0; i < bucket.size(); i++) {
				int u = bucket.get(i);
				pending--;
				if (dist[u] != d) continue;
				int ux = u % w, uy = u / w;
				for (int k = 0; k < 8; k++) {
					int nx = ux + DX[k], ny = uy + DY[k];
					if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
					int n = nx + ny * w;
					if (!b.contains(n, w) || !g.passable(n)) continue;
					int cost = g.enterCost(reverse ? u : n);
					if (cost > MAX_ENTER_COST) throw new IllegalArgumentException("enterCost " + cost + " exceeds " + MAX_ENTER_COST);
					int nd = d + cost;
					if (nd < dist[n]) {
						dist[n] = nd;
						buckets[nd % BUCKETS].add(n);
						pending++;
					}
				}
			}
			bucket.clear();
		}
		return dist;
	}

	static ArrayList<Integer> tracePath(Graph g, int[] fromSourceDist, int source, int goal) {
		ArrayList<Integer> path = new ArrayList<>();
		if (goal == source || fromSourceDist[goal] == UNREACHABLE) return path;
		int w = g.width(), h = g.height();
		int cur = goal;
		while (cur != source) {
			path.add(cur);
			int want = fromSourceDist[cur] - g.enterCost(cur);
			int next = -1;
			for (int k = 0; k < 8 && next < 0; k++) {
				int nx = cur % w + DX[k], ny = cur / w + DY[k];
				if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
				int n = nx + ny * w;
				if (fromSourceDist[n] == want) next = n;
			}
			if (next < 0) return new ArrayList<>();
			cur = next;
		}
		java.util.Collections.reverse(path);
		return path;
	}
}
