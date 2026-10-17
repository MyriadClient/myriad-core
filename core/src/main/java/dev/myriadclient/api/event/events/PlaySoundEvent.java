package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * A sound is about to play on the client: sounds the server sends, and the ones the client plays itself (your own
 * steps, clicks, music). Cancel it to keep it quiet. Posted on the render thread.
 *
 * <pre>{@code
 * @Subscribe
 * private void onSound(PlaySoundEvent e) {
 *     if (e.id().equals(SoundEvents.GENERIC_EXPLODE.value().location())) e.cancel();
 * }
 * }</pre>
 */
public final class PlaySoundEvent extends Cancellable {
	private final SoundInstance sound;

	@ApiStatus.Internal
	public PlaySoundEvent(SoundInstance sound) {
		this.sound = sound;
	}

	public SoundInstance sound() {
		return sound;
	}

	/** The sound event's id, e.g. {@code minecraft:entity.generic.explode}. */
	public Identifier id() {
		return sound.getIdentifier();
	}
}
