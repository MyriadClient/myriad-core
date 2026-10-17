package dev.myriadclient.api.event.events;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.event.Cancellable;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/** Entities being drawn, once per entity per frame. Posted on the render thread. */
public abstract class EntityRenderEvent {
	/** {@code entity} is about to be drawn: cancel to hide it (and its name, shadow and fire). */
	public static final class Visible extends Cancellable {
		private final Entity entity;

		@ApiStatus.Internal
		public Visible(Entity entity) {
			this.entity = entity;
		}

		public Entity entity() {
			return entity;
		}
	}

	/** {@code entity}'s vanilla name tag is about to be drawn: cancel to draw your own instead (hides the score line under it too). */
	public static final class Nametag extends Cancellable {
		private final Entity entity;

		@ApiStatus.Internal
		public Nametag(Entity entity) {
			this.entity = entity;
		}

		public Entity entity() {
			return entity;
		}
	}

	/**
	 * A living entity's model has just been submitted, with everything needed to submit it again another way: chams,
	 * wireframes, glows. {@code state} is the render state (its entity through {@code RenderStates.entity(state)}).
	 */
	public static final class Model extends EntityRenderEvent {
		private final LivingEntityRenderState state;
		private final net.minecraft.client.model.Model<?> model;
		private final PoseStack pose;
		private final SubmitNodeCollector submits;

		@ApiStatus.Internal
		public Model(LivingEntityRenderState state, net.minecraft.client.model.Model<?> model, PoseStack pose, SubmitNodeCollector submits) {
			this.state = state;
			this.model = model;
			this.pose = pose;
			this.submits = submits;
		}

		public LivingEntityRenderState state() {
			return state;
		}

		public net.minecraft.client.model.Model<?> model() {
			return model;
		}

		public PoseStack pose() {
			return pose;
		}

		public SubmitNodeCollector submits() {
			return submits;
		}
	}
}
