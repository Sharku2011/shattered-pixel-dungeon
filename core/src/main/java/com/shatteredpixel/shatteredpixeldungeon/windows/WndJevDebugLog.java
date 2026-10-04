/*
 * Copyright (C) 2026 Shattered Pixel Dungeon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.shatteredpixel.shatteredpixeldungeon.windows;

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.JevMobAI;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.DeviceCompat;

import java.util.List;
import java.util.ArrayList;

/** Development-only in-game view of recent Jev requests and responses. */
public class WndJevDebugLog extends Window {

	private static final int MAX_WIDTH = 220;
	private static final int MAX_HEIGHT = 170;
	private ScrollPane pane;
	private Component content;
	private RenderedTextBlock body;
	private List<String> lastEntries = new ArrayList<>();
	private int bodyWidth;

	public WndJevDebugLog() {
		if (!DeviceCompat.isDebug()) {
			resize(120, 32);
			return;
		}

		int width = Math.min(MAX_WIDTH, (int) PixelScene.uiCamera.width - 12);
		int height = Math.min(MAX_HEIGHT, (int) PixelScene.uiCamera.height - 20);
		resize(width, height);

		pane = new ScrollPane(new Component());
		add(pane);
		pane.setRect(4, 4, width - 8, height - 8);

		content = pane.content();
		IconTitle title = new IconTitle(Icons.get(Icons.INFO), Messages.get(this, "title"));
		title.setRect(0, 0, width - 16, 0);
		content.add(title);

		body = PixelScene.renderTextBlock(6);
		bodyWidth = width - 16;
		body.setPos(0, title.bottom() + 4);
		content.add(body);
		refreshEntries(JevMobAI.debugLogEntries());
	}

	@Override
	public void update() {
		super.update();
		if (DeviceCompat.isDebug()) {
			List<String> entries = JevMobAI.debugLogEntries();
			if (!entries.equals(lastEntries)) refreshEntries(entries);
		}
	}

	private void refreshEntries(List<String> entries) {
		lastEntries = new ArrayList<>(entries);
		StringBuilder text = new StringBuilder();
		if (entries.isEmpty()) {
			text.append(Messages.get(this, "empty"));
		} else {
			for (String entry : entries) {
				if (text.length() > 0) text.append("\n\n");
				text.append(entry);
			}
		}
		body.text(text.toString(), bodyWidth);
		content.setSize(bodyWidth, body.bottom() + 4);
		pane.scrollTo(0, Math.max(0, content.height() - pane.height()));
	}
}
