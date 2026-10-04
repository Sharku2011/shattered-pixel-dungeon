/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.scenes;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSquads;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.utils.DeviceCompat;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Group;
import com.watabou.noosa.SkinnedBlock;

import java.util.ArrayList;
import java.util.Map;

/** Draws a live axis-aligned bounding box around each multi-mob squad. */
public class MobSquadOverlay extends Group {

	private static final int LINE = 2;
	private static final int[] COLORS = {
			0xE8FF5555, 0xE855DDFF, 0xE8FFE14D, 0xE86BFF72,
			0xE8D28BFF, 0xE8FF9D45, 0xE8FF69B4, 0xE8FFFFFF
	};

	private final ArrayList<SkinnedBlock[]> frames = new ArrayList<>();

	/** Recalculates squad bounds once after a completed player turn. */
	public void refresh() {
		if (!DeviceCompat.isDebug()) {
			hideUnused(0);
			return;
		}
		updateFrames();
	}

	private void updateFrames() {
		if (Dungeon.level == null) {
			hideUnused(0);
			return;
		}

		int frameIndex = 0;
		for (Map.Entry<Integer, ArrayList<Mob>> entry : MobSquads.debugGroups(Dungeon.level).entrySet()) {
			ArrayList<Mob> squad = entry.getValue();

			int minX = Dungeon.level.width();
			int minY = Dungeon.level.height();
			int maxX = -1;
			int maxY = -1;
			for (Mob mob : squad) {
				int x = mob.pos % Dungeon.level.width();
				int y = mob.pos / Dungeon.level.width();
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
			}

			if (maxX < minX || maxY < minY) continue;
			ensureFrame(frameIndex);
			float x = minX * DungeonTilemap.SIZE;
			float y = minY * DungeonTilemap.SIZE;
			float width = (maxX - minX + 1) * DungeonTilemap.SIZE;
			float height = (maxY - minY + 1) * DungeonTilemap.SIZE;
			SkinnedBlock[] frame = frames.get(frameIndex);
			setBar(frame[0], x, y, width, LINE);
			setBar(frame[1], x, y + height - LINE, width, LINE);
			setBar(frame[2], x, y, LINE, height);
			setBar(frame[3], x + width - LINE, y, LINE, height);
			frameIndex++;
		}
		hideUnused(frameIndex);
	}

	private void ensureFrame(int index) {
		while (frames.size() <= index) {
			int color = COLORS[frames.size() % COLORS.length];
			SkinnedBlock[] frame = new SkinnedBlock[4];
			for (int i = 0; i < frame.length; i++) {
				frame[i] = new SkinnedBlock(1, 1, TextureCache.createSolid(color));
				frame[i].visible = false;
				add(frame[i]);
			}
			frames.add(frame);
		}
	}

	private void setBar(SkinnedBlock image, float x, float y, float width, float height) {
		image.x = x;
		image.y = y;
		image.size(width, height);
		image.visible = true;
	}

	private void hideUnused(int firstUnused) {
		for (int i = firstUnused; i < frames.size(); i++) {
			for (SkinnedBlock image : frames.get(i)) image.visible = false;
		}
	}
}
