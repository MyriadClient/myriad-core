package dev.myriadclient.api.ui;

import dev.myriadclient.api.ui.draw.Animated;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A control in a window's title bar, beside its own float and close buttons: a switch (a control panel's on/off) or an
 * icon button (Addons' "check for updates"). Return them from {@link Panel#titleControls()}; make them once (a field)
 * and return the same ones, so a switch's slide carries on between frames. Hovering shows the tooltip. In a narrow
 * window the controls that would crowd the title out are left off.
 *
 * <pre>{@code
 * private final List<TitleControl> controls = List.of(
 *     TitleControl.toggle(module::isEnabled, module::setEnabled, () -> "Turn " + module.name() + " on or off"),
    TitleControl.button(() -> Icons.REFRESH, this::reload, () -> "Reload"));
 *
 * @Override
 * public List<TitleControl> titleControls() { return controls; }
 * }</pre>
 */
public final class TitleControl {
	private final @Nullable BooleanSupplier on;
	private final @Nullable Consumer<Boolean> set;
	private final @Nullable Supplier<String> icon;
	private final @Nullable Runnable action;
	private final @Nullable Supplier<@Nullable String> tooltip;
	private BooleanSupplier visible = () -> true;
	private final Animated knob = new Animated(0);

	private TitleControl(@Nullable BooleanSupplier on, @Nullable Consumer<Boolean> set, @Nullable Supplier<String> icon, @Nullable Runnable action,
						 @Nullable Supplier<@Nullable String> tooltip) {
		this.on = on;
		this.set = set;
		this.icon = icon;
		this.action = action;
		this.tooltip = tooltip;
	}

	/**
	 * An on/off switch showing {@code on}; clicking it calls {@code set} with the opposite. The suppliers are read every
	 * frame. {@code tooltip} may return null for none.
	 */
	public static TitleControl toggle(BooleanSupplier on, Consumer<Boolean> set, Supplier<@Nullable String> tooltip) {
		return new TitleControl(on, set, null, null, tooltip);
	}

	/**
	 * A Nerd Font icon ({@link Icons}) that runs {@code action} when clicked. The suppliers are read every frame, so the
	 * icon can change (a spinner while busy). {@code tooltip} may return null for none.
	 */
	public static TitleControl button(Supplier<String> icon, Runnable action, Supplier<@Nullable String> tooltip) {
		return new TitleControl(null, null, icon, action, tooltip);
	}

	/** Shown only while {@code visible} says so (read every frame). Returns this control. */
	public TitleControl visible(BooleanSupplier visible) {
		this.visible = visible;
		return this;
	}

	/** Core's: whether this is a switch rather than a button. */
	@ApiStatus.Internal
	public boolean isToggle() {
		return on != null;
	}

	/** Core's: whether it shows this frame. */
	@ApiStatus.Internal
	public boolean isVisible() {
		return visible.getAsBoolean();
	}

	/** Core's: a switch's state. */
	@ApiStatus.Internal
	public boolean isOn() {
		return on != null && on.getAsBoolean();
	}

	/** Core's: a button's glyph, empty for a switch. */
	@ApiStatus.Internal
	public String icon() {
		return icon == null ? "" : icon.get();
	}

	/** Core's: the tooltip, or null. */
	@ApiStatus.Internal
	public @Nullable String tooltip() {
		return tooltip == null ? null : tooltip.get();
	}

	/** Core's: its width in the title bar, in UI units. */
	@ApiStatus.Internal
	public float width() {
		return isToggle() ? 18 : 11;
	}

	/** Core's: a switch's knob animation. */
	@ApiStatus.Internal
	public Animated knob() {
		return knob;
	}

	/** Core's: a click on it. */
	@ApiStatus.Internal
	public void click() {
		if (isToggle()) set.accept(!on.getAsBoolean());
		else action.run();
	}
}
