package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.util.MyriadId;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A kind of panel that can be opened in a window. {@code args} parameterise an instance (e.g. which module a
 * settings panel shows); two windows with the same type and args are the same window, so opening it again focuses
 * the existing one.
 */
public final class PanelType implements Identified {
	private final MyriadId id;
	private final String name;
	private final String icon;
	private final Function<JsonObject, Panel> factory;
	private final boolean hud;
	private final boolean listed;
	private final HudPlacement defaultHud;

	private PanelType(Builder b) {
		this.id = b.id;
		this.name = b.name;
		this.icon = b.icon;
		this.factory = b.factory;
		this.hud = b.hud || b.defaultHud != null;
		this.listed = b.listed;
		this.defaultHud = b.defaultHud;
	}

	public static Builder builder(MyriadId id, String name) {
		return new Builder(id, name);
	}

	@Override
	public MyriadId id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String icon() {
		return icon;
	}

	/** Creates an instance; returns null if {@code args} don't resolve (e.g. the module no longer exists). */
	public Panel create(JsonObject args) {
		return factory.apply(args == null ? new JsonObject() : args);
	}

	/** Whether this panel can be placed on the HUD workspace (drawn in game, without window chrome). */
	public boolean isHud() {
		return hud;
	}

	/** Whether the launcher offers this panel (false for panels that need args). */
	public boolean isListed() {
		return listed;
	}

	/** If set, a fresh install places this panel on the HUD at this position. */
	public HudPlacement defaultHud() {
		return defaultHud;
	}

	/** Where a HUD element is anchored; offsets are in UI units from that anchor. */
	public record HudPlacement(Anchor anchor, float offsetX, float offsetY) {
	}

	public enum Anchor {
		TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT;

		/** 0, 0.5 or 1 for the horizontal position. */
		public float fx() {
			return switch (this) {
				case TOP_LEFT, LEFT, BOTTOM_LEFT -> 0f;
				case TOP, CENTER, BOTTOM -> 0.5f;
				default -> 1f;
			};
		}

		public float fy() {
			return switch (this) {
				case TOP_LEFT, TOP, TOP_RIGHT -> 0f;
				case LEFT, CENTER, RIGHT -> 0.5f;
				default -> 1f;
			};
		}

		public static Anchor of(int col, int row) {
			return values()[Math.clamp(row, 0, 2) * 3 + Math.clamp(col, 0, 2)];
		}
	}

	public static final class Builder {
		private final MyriadId id;
		private final String name;
		private String icon;
		private Function<JsonObject, Panel> factory;
		private boolean hud;
		private boolean listed = true;
		private HudPlacement defaultHud;

		private Builder(MyriadId id, String name) {
			this.id = id;
			this.name = name;
		}

		public Builder icon(String icon) {
			this.icon = icon;
			return this;
		}

		public Builder factory(Supplier<Panel> factory) {
			this.factory = args -> factory.get();
			return this;
		}

		/** Factory for parameterised panels; return null when args are invalid. */
		public Builder factory(Function<JsonObject, Panel> factory) {
			this.factory = factory;
			listed = false;
			return this;
		}

		public Builder hud() {
			this.hud = true;
			return this;
		}

		public Builder defaultHud(Anchor anchor, float offsetX, float offsetY) {
			this.defaultHud = new HudPlacement(anchor, offsetX, offsetY);
			return this;
		}

		public Builder listed(boolean listed) {
			this.listed = listed;
			return this;
		}

		public PanelType build() {
			if (factory == null) throw new IllegalStateException("PanelType " + id + " has no factory");
			return new PanelType(this);
		}
	}
}
