package com.vinylmusic.mixin;

import com.vinylmusic.audio.MusicManager;
import com.vinylmusic.client.Diagnostics;
import com.vinylmusic.config.VinylConfig;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales the final volume of every sound. Minecraft recalculates this value for playing
 * sounds every tick, so slider changes apply immediately. The injection only multiplies
 * the returned value and cancels nothing, which keeps it compatible with other sound mods.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Inject(
			method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
			at = @At("RETURN"),
			cancellable = true,
			require = 0
	)
	private void vinylmusicplayer$scaleVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		if (!Diagnostics.soundMixinHit) {
			Diagnostics.soundMixinHit = true;
			Diagnostics.LOG.info("[VinylMusicPlayer] Sound mixer hook is active.");
		}
		VinylConfig cfg = VinylConfig.get();
		String category = sound.getSource().name();
		float factor = cfg.soundFactor(category, String.valueOf(sound.getIdentifier()));
		if (cfg.muteVanillaMusic && "MUSIC".equals(category) && MusicManager.get().isAudible()) {
			factor = 0f;
		}
		if (factor != 1f) {
			cir.setReturnValue(cir.getReturnValueF() * factor);
		}
	}
}
