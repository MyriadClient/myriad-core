package dev.myriadclient.api.ui;

import dev.myriadclient.api.render.Animated;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A control in a window's title bar, beside its own float and close buttons: a switch (a control panel's on/off) or an
 * icon button (Addons' "check for updates"). Return them from {@link Panel#titleControls()}; make them once (a field)
 * and return the same ones, so a switch's slide carries on between frames. Hovering shows the tooltip.
 */
public final class TitleControl {
	private final BooleanSupplier on;
	private final Consumer<Boolean> set;
	private final Supplier<String> icon;
	private final Runnable action;
	private final Supplier<String> tooltip;
	private BooleanSupplier visible = () -> true;
	private final Animated knob = new Animated(0);

	private TitleControl(BooleanSupplier on, Consumer<Boolean> set, Supplier<String> icon, Runnable action, Supplier<String> tooltip) {
		this.on = on;
		this.set = set;
		this.icon = icon;
		this.action = action;
		this.tooltip = tooltip;
	}

	/** An on/off switch. */
	public static TitleControl toggle(BooleanSupplier on, Consumer<Boolean> set, Supplier<String> tooltip) {
		return new TitleControl(on, set, null, null, tooltip);
	}

	/** A Nerd Font icon that runs {@code action} when clicked. */
	public static TitleControl button(Supplier<String> icon, Runnable action, Supplier<String> tooltip) {
		return new TitleControl(null, null, icon, action, tooltip);
	}

	/** Shown only while {@code visible} says so. */
	public TitleControl visible(BooleanSupplier visible) {
		this.visible = visible;
		return this;
	}

	@ApiStatus.Internal
	public boolean isToggle() {
		return on != null;
	}

	@ApiStatus.Internal
	public boolean isVisible() {
		return visible.getAsBoolean();
	}

	@ApiStatus.Internal
	public boolean isOn() {
		return on != null && on.getAsBoolean();
	}

	@ApiStatus.Internal
	public String icon() {
		return icon == null ? "" : icon.get();
	}

	@ApiStatus.Internal
	public String tooltip() {
		return tooltip == null ? null : tooltip.get();
	}

	/** Its width in the title bar. */
	@ApiStatus.Internal
	public float width() {
		return isToggle() ? 18 : 11;
	}

	@ApiStatus.Internal
	public Animated knob() {
		return knob;
	}

	@ApiStatus.Internal
	public void click() {
		if (isToggle()) set.accept(!on.getAsBoolean());
		else action.run();
	}
}
