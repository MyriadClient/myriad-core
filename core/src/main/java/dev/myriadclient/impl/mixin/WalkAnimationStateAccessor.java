package dev.myriadclient.impl.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A walk animation copied whole onto another entity (the preview's stand-in player, see PreviewSubjects). */
@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
	@Accessor("speedOld")
	float myriad$speedOld();

	@Accessor("speedOld")
	void myriad$setSpeedOld(float speedOld);

	@Accessor("speed")
	void myriad$setSpeed(float speed);

	@Accessor("position")
	void myriad$setPosition(float position);
}
