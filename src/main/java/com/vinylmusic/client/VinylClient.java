package com.vinylmusic.client;

import com.vinylmusic.audio.MusicManager;
import com.vinylmusic.config.VinylConfig;
import com.vinylmusic.gui.Overlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class VinylClient implements ClientModInitializer {
	public static final String MOD_ID = "vinylmusicplayer";

	private int saveTimer;
	private final Set<Screen> hooked = Collections.newSetFromMap(new WeakHashMap<>());

	@Override
	public void onInitializeClient() {
		Diagnostics.LOG.info("[VinylMusicPlayer] Starting {}",
				FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		Diagnostics.modMenuPresent = FabricLoader.getInstance().isModLoaded("modmenu");

		try {
			VinylConfig.load();
			MusicManager.get().init();
		} catch (Throwable t) {
			Diagnostics.error("startup", t);
		}

		// Disc on every screen. The overlay itself decides which screens show it.
		try {
			ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
				if (hooked.add(screen)) {
					ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) ->
							Overlay.onScreenRender(s, graphics, mouseX, mouseY));
				}
			});
			Diagnostics.screenHookRegistered = true;
		} catch (Throwable t) {
			Diagnostics.error("screen hook", t);
		}

		// Disc during normal play.
		try {
			HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
					Identifier.fromNamespaceAndPath(MOD_ID, "disc"),
					(graphics, tickCounter) -> Overlay.renderHud(graphics));
			Diagnostics.hudRegistered = true;
		} catch (Throwable t) {
			Diagnostics.error("HUD hook", t);
		}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				MusicManager.get().tick();
				Overlay.tick(client);
				if (VinylConfig.isDirty() && ++saveTimer >= 200) {
					saveTimer = 0;
					VinylConfig.save();
				}
			} catch (Throwable t) {
				Diagnostics.error("tick", t);
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			VinylConfig.save();
			MusicManager.get().shutdown();
		});

		Diagnostics.initDone = true;
		Diagnostics.LOG.info("[VinylMusicPlayer] Ready. Mod Menu present: {}. Screen hook: {}. HUD hook: {}.",
				Diagnostics.modMenuPresent, Diagnostics.screenHookRegistered, Diagnostics.hudRegistered);
	}
}
