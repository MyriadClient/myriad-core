package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A kind of panel that can be opened in a window. {@code args} parameterise an instance (e.g. which module a
 * settings panel shows); two windows with the same type and args are the same window, so opening it again focuses
 * the existing one. Register one with {@code AddonContext.registerPanel} (or its {@code registerPanel(name, icon,
 * factory)} and {@code registerHud} shortcuts); saved windows of a type that isn't registered are kept until it is.
 *
 * <pre>{@code
 * PanelType notes = ctx.registerPanel(PanelType.builder(ctx.id("notes"), "Notes")
 *     .icon(Icons.PENCIL)
 *     .factory(args -> args.has("file") ? new NotesPanel(args.get("file").getAsString()) : null)
 *     .build());
 * }</pre>
 */
public final class PanelType implements Identified {
	private final MyriadId id;
	private final String name;
	private final @Nullable String icon;
	private final Function<JsonObject, @Nullable Panel> factory;
	private final boolean hud;
	private final boolean listed;
	private final @Nullable HudPlacement defaultHud;

	private PanelType(Builder b) {
		this.id = b.id;
		this.name = b.name;
		this.icon = b.icon;
		this.factory = b.factory;
		this.hud = b.hud || b.defaultHud != null;
		this.listed = b.listed;
		this.defaultHud = b.defaultHud;
	}

	/**
	 * Starts a panel type.
	 *
	 * @param id   its registry id, also what saved windows refer to
	 * @param name the name players see in the launcher and the HUD's element list
	 */
	public static Builder builder(MyriadId id, String name) {
		return new Builder(id, name);
	}

	@Override
	public MyriadId id() {
		return id;
	}

	/** The name players see in the launcher and the HUD's element list. */
	public String name() {
		return name;
	}

	/** The Nerd Font glyph shown for it (launcher, title bar when the panel has no icon of its own), or null. */
	public @Nullable String icon() {
		return icon;
	}

	/**
	 * Creates an instance; returns null if {@code args} don't resolve (e.g. the module no longer exists). Null
	 * {@code args} count as an empty object.
	 */
	public @Nullable Panel create(@Nullable JsonObject args) {
		return factory.apply(args == null ? new JsonObject() : args);
	}

	/** Whether this panel is a HUD element: it lives on the HUD workspace only, drawn in game without window chrome. */
	public boolean isHud() {
		return hud;
	}

	/** Whether the launcher offers this panel (false for panels that need args). */
	public boolean isListed() {
		return listed;
	}

	/** If set, a fresh install places this panel on the HUD at this position; null otherwise. */
	public @Nullable HudPlacement defaultHud() {
		return defaultHud;
	}

	/**
	 * Where a HUD element is anchored; offsets are in UI units from that anchor. The anchor point is the screen's edge
	 * or middle less the theme's HUD edge padding, and the element is aligned to it the same way (a
	 * {@link Anchor#BOTTOM_RIGHT} element's bottom-right corner sits at the bottom-right of the screen).
	 *
	 * @param anchor  which ninth of the screen it's aligned to
	 * @param offsetX how far right of the anchor point, in UI units
	 * @param offsetY how far below it, in UI units
	 */
	public record HudPlacement(Anchor anchor, float offsetX, float offsetY) {
	}

	/**
	 * One of the nine places a HUD element aligns to: a screen corner, an edge's middle or the centre. Players re-anchor
	 * an element by dragging it into another third of the screen.
	 */
	public enum Anchor {
		/** The top-left corner. */
		TOP_LEFT,
		/** The middle of the top edge. */
		TOP,
		/** The top-right corner. */
		TOP_RIGHT,
		/** The middle of the left edge. */
		LEFT,
		/** The screen's centre. */
		CENTER,
		/** The middle of the right edge. */
		RIGHT,
		/** The bottom-left corner. */
		BOTTOM_LEFT,
		/** The middle of the bottom edge. */
		BOTTOM,
		/** The bottom-right corner. */
		BOTTOM_RIGHT;

		/** 0, 0.5 or 1 for the horizontal position: left, middle, right. */
		public float fx() {
			return switch (this) {
				case TOP_LEFT, LEFT, BOTTOM_LEFT -> 0f;
				case TOP, CENTER, BOTTOM -> 0.5f;
				default -> 1f;
			};
		}

		/** 0, 0.5 or 1 for the vertical position: top, middle, bottom. */
		public float fy() {
			return switch (this) {
				case TOP_LEFT, TOP, TOP_RIGHT -> 0f;
				case LEFT, CENTER, RIGHT -> 0.5f;
				default -> 1f;
			};
		}

		/** The anchor in column {@code col} and row {@code row} of the 3&times;3 grid (each clamped to 0-2). */
		public static Anchor of(int col, int row) {
			return values()[Math.clamp(row, 0, 2) * 3 + Math.clamp(col, 0, 2)];
		}
	}

	/** Builds a {@link PanelType}; a factory is required. */
	public static final class Builder {
		private final MyriadId id;
		private final String name;
		private @Nullable String icon;
		private @Nullable Function<JsonObject, @Nullable Panel> factory;
		private boolean hud;
		private boolean listed = true;
		private @Nullable HudPlacement defaultHud;

		private Builder(MyriadId id, String name) {
			this.id = id;
			this.name = name;
		}

		/** The Nerd Font glyph shown for it ({@link Icons}). */
		public Builder icon(String icon) {
			this.icon = icon;
			return this;
		}

		/** Factory for panels that take no args: every window gets a new panel from it. The launcher lists the type. */
		public Builder factory(Supplier<Panel> factory) {
			this.factory = args -> factory.get();
			return this;
		}

		/**
		 * Factory for parameterised panels; return null when args are invalid. Also takes the type off the launcher's
		 * list (call {@link #listed} after it to put it back).
		 */
		public Builder factory(Function<JsonObject, @Nullable Panel> factory) {
			this.factory = factory;
			listed = false;
			return this;
		}

		/** Makes it a HUD element: it lives on the HUD workspace, sized to {@link Panel#preferredSize}, drawn in game. */
		public Builder hud() {
			this.hud = true;
			return this;
		}

		/** Makes it a HUD element that a fresh install puts on the HUD at {@code anchor}, offset in UI units. */
		public Builder defaultHud(Anchor anchor, float offsetX, float offsetY) {
			this.defaultHud = new HudPlacement(anchor, offsetX, offsetY);
			return this;
		}

		/** Whether the launcher offers it; true unless {@link #factory(Function)} was used. */
		public Builder listed(boolean listed) {
			this.listed = listed;
			return this;
		}

		/** The panel type. Throws {@link IllegalStateException} if no factory was given. */
		public PanelType build() {
			if (factory == null) throw new IllegalStateException("PanelType " + id + " has no factory");
			return new PanelType(this);
		}
	}
}
