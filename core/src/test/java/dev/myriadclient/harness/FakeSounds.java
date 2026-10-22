package dev.myriadclient.harness;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The game's sound manager with no engine behind it: it keeps what it was asked to play, for tests that care. */
final class FakeSounds extends SoundManager {
	List<SoundInstance> played;

	@SuppressWarnings("unused")
	private FakeSounds() {
		super(null);
	}

	static FakeSounds create() {
		FakeSounds s = Fields.allocate(FakeSounds.class);
		s.played = new ArrayList<>();
		return s;
	}

	@Override
	public SoundEngine.PlayResult play(SoundInstance sound) {
		played.add(sound);
		return SoundEngine.PlayResult.STARTED_SILENTLY;
	}

	@Override
	public void queueTickingSound(TickableSoundInstance sound) {
		played.add(sound);
	}

	@Override
	public void playDelayed(SoundInstance sound, int ticks) {
		played.add(sound);
	}

	@Override
	public void stop() {
	}

	@Override
	public void stop(SoundInstance sound) {
	}

	@Override
	public void stop(@Nullable Identifier id, @Nullable SoundSource source) {
	}

	@Override
	public boolean isActive(SoundInstance sound) {
		return false;
	}
}
