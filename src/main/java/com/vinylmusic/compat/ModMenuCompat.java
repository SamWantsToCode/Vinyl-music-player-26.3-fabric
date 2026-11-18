package com.vinylmusic.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.vinylmusic.gui.VinylScreen;

/** Optional. Adds the Configure button for this mod inside Mod Menu. */
public final class ModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return VinylScreen::new;
	}
}
