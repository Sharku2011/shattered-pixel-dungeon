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
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.levels.rooms.connection;

import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.painters.Painter;
import com.watabou.utils.Point;

import java.util.ArrayList;

// A tunnel with a short split route that rejoins at the far end.
public class DoubleTunnelRoom extends TunnelRoom {

	@Override
	public void paint(Level level) {
		super.paint(level);

		if (connected.size() != 2) return;

		ArrayList<Door> doors = new ArrayList<>(connected.values());
		Door a = doors.get(0);
		Door b = doors.get(1);

		Point start = innerPoint(a);
		Point end = innerPoint(b);
		int floor = level.tunnelTile();

		// Keep the alternate path within the tunnel and separate from the center route.
		if (a.x == left && b.x == right || a.x == right && b.x == left) {
			if (height() < 5) return;
			int detourY = (start.y + end.y)/2 <= (top + bottom)/2 ? bottom - 1 : top + 1;
			Point detourStart = new Point(start.x, detourY);
			Point detourEnd = new Point(end.x, detourY);
			Painter.drawLine(level, start, detourStart, floor);
			Painter.drawLine(level, detourStart, detourEnd, floor);
			Painter.drawLine(level, detourEnd, end, floor);
		} else if (a.y == top && b.y == bottom || a.y == bottom && b.y == top) {
			if (width() < 5) return;
			int detourX = (start.x + end.x)/2 <= (left + right)/2 ? right - 1 : left + 1;
			Point detourStart = new Point(detourX, start.y);
			Point detourEnd = new Point(detourX, end.y);
			Painter.drawLine(level, start, detourStart, floor);
			Painter.drawLine(level, detourStart, detourEnd, floor);
			Painter.drawLine(level, detourEnd, end, floor);
		} else if (width() >= 5 && height() >= 5) {
			int detourX = a.x == left || b.x == left ? right - 1 : left + 1;
			int detourY = a.y == top || b.y == top ? bottom - 1 : top + 1;
			Point corner = new Point(detourX, detourY);
			Painter.drawLine(level, start, new Point(detourX, start.y), floor);
			Painter.drawLine(level, new Point(detourX, start.y), corner, floor);
			Painter.drawLine(level, corner, new Point(end.x, detourY), floor);
			Painter.drawLine(level, new Point(end.x, detourY), end, floor);
		}
	}

	private Point innerPoint(Door door) {
		Point point = new Point(door);
		if (point.x == left) point.x++;
		else if (point.x == right) point.x--;
		else if (point.y == top) point.y++;
		else if (point.y == bottom) point.y--;
		return point;
	}
}
