package com.vinylmusic.gui;

import net.minecraft.client.Minecraft;

/** Single place for raw input reads, so a Minecraft rename needs one edit. */
final class Input {
	private Input() {
	}

	static boolean rightDown() {
		return Minecraft.getInstance().mouseHandler.isRightPressed();
	}

	static boolean leftDown() {
		return Minecraft.getInstance().mouseHandler.isLeftPressed();
	}
}
