package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.MovementPacketsEvent;
import dev.myriadclient.api.event.events.PlayerMoveEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.elytra.ElytraManager;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link MovementPacketsEvent} handlers rewrite what {@code sendMovementPackets} sends, without touching the
 * client-side player (so the camera doesn't move).
 */
@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {
	@Unique
	private MovementPacketsEvent myriad$event;

	@Inject(method = "sendPosition", at = @At("HEAD"))
	private void myriad$preMovement(CallbackInfo ci) {
		if (!Myriad.isReady()) return;
		LocalPlayer self = (LocalPlayer) (Object) this;
		myriad$event = Myriad.events().post(new MovementPacketsEvent(self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot(), self.onGround()));
	}

	@Inject(method = "sendPosition", at = @At("RETURN"))
	private void myriad$postMovement(CallbackInfo ci) {
		if (myriad$event == null) return;
		myriad$event = null;
		Myriad.events().post(MovementPacketsEvent.Post.INSTANCE);
	}

	/**
	 * Whether the movement packet carries a rotation is judged against the rotation the server last got, not the last
	 * one sent from here: a setback's reply goes out on its own, with the camera's rotation, and vanilla doesn't count
	 * it. Judged its way, a rotation a module holds (Elytra Fly's pitch) wasn't sent again after a setback, so the
	 * server kept simulating the camera's, set you back again, and so on every round trip.
	 */
	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;yRotLast:F", opcode = Opcodes.GETFIELD))
	private float myriad$lastSentYaw(float original) {
		return Myriad.isReady() ? Myriad.rotations().serverYaw() : original;
	}

	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;xRotLast:F", opcode = Opcodes.GETFIELD))
	private float myriad$lastSentPitch(float original) {
		return Myriad.isReady() ? Myriad.rotations().serverPitch() : original;
	}

	/** PlayerMoveEvent: lets features change this tick's movement before collisions. */
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
	private Vec3 myriad$move(Vec3 movement, MoverType type, Vec3 original) {
		if (!Myriad.isReady() || !Myriad.events().hasListeners(PlayerMoveEvent.class)) return movement;
		return Myriad.events().post(new PlayerMoveEvent(type, movement)).movement();
	}

	/** Posts InputEvent right after the keyboard is read, and applies any changes for this tick. */
	@Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER))
	private void myriad$input(CallbackInfo ci) {
		ClientInput input = ((LocalPlayer) (Object) this).input;
		// Baritone's input builds the vector as old versions did: sneaking slowed already (the player slows it again
		// after this) and a diagonal never normalised. Grim simulates vanilla's, so Baritone sneaking to the edge to
		// bridge, or walking diagonally natively, was flagged.
		if (input.getClass().getName().startsWith("baritone.")) {
			Input keys = input.keyPresses;
			input.moveVector = myriad$moveVector(keys.forward(), keys.backward(), keys.left(), keys.right());
		}
		if (!Myriad.isReady() || !Myriad.events().hasListeners(InputEvent.class)) return;
		Input in = input.keyPresses;
		InputEvent e = Myriad.events().post(new InputEvent(in.forward(), in.backward(), in.left(), in.right(), in.jump(), in.shift(), in.sprint()));
		Input out = new Input(e.forward, e.backward, e.left, e.right, e.jump, e.sneak, e.sprint);
		if (out.equals(in)) return;
		input.keyPresses = out;
		input.moveVector = myriad$moveVector(e.forward, e.backward, e.left, e.right);
	}

	/**
	 * The movement vector the keyboard gives for these keys in the version the server sees. Up to 1.21.4 a diagonal
	 * stayed (1, 1) (ViaFabricPlus keeps it that way when joined as one): normalised, walking diagonally would be 2%
	 * slower than the server simulates, a Simulation flag every tick.
	 */
	@Unique
	private static Vec2 myriad$moveVector(boolean forward, boolean backward, boolean left, boolean right) {
		Vec2 move = new Vec2(left == right ? 0 : left ? 1 : -1, forward == backward ? 0 : forward ? 1 : -1);
		return JoinedVersion.get().protocol() <= SQUARE_MOVEMENT_UNTIL ? move : move.normalized();
	}

	/** 1.21.4's protocol: the last version whose diagonal input isn't normalised before the player moves. */
	@Unique
	private static final int SQUARE_MOVEMENT_UNTIL = 769;

	/** After vanilla (or ViaFabricPlus, from the sprint key) has started sprinting this tick, before the player moves. */
	@Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V"))
	private void myriad$beforeTravel(CallbackInfo ci) {
		if (Myriad.isReady()) MyriadImpl.get().inventoryManager().beforeTravel();
	}

	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
	private double myriad$x(double original) {
		return myriad$event != null ? myriad$event.x : original;
	}

	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
	private double myriad$y(double original) {
		return myriad$event != null ? myriad$event.y : original;
	}

	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
	private double myriad$z(double original) {
		return myriad$event != null ? myriad$event.z : original;
	}

	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
	private float myriad$yaw(float original) {
		return myriad$event != null ? myriad$event.yaw : original;
	}

	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
	private float myriad$pitch(float original) {
		return myriad$event != null ? myriad$event.pitch : original;
	}

	@ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;onGround()Z"))
	private boolean myriad$onGround(boolean original) {
		return myriad$event != null ? myriad$event.onGround : original;
	}

	/** Glide hold: the server stopping the glide is undone on the client while it's held. */
	@Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
	private void myriad$keepGliding(EntityDataAccessor<?> accessor, CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (!accessor.equals(Entity.DATA_SHARED_FLAGS_ID) || (self.getEntityData().get(Entity.DATA_SHARED_FLAGS_ID) & 0x80) != 0) return;
		if (ElytraManager.keepGliding()) self.startFallFlying();
	}
}
