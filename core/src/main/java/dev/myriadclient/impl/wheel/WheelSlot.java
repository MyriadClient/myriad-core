package dev.myriadclient.impl.wheel;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.macro.MacroManager;
import dev.myriadclient.impl.macro.MacroModule;
import dev.myriadclient.impl.ui.WindowManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One slot on a wheel: what it runs, saved as a kind and a target (an id, or a command's text), and looked up again
 * whenever it's shown or run, so it follows the module or panel as it is now and shows as missing if its addon is gone.
 * The player can give it an icon and a name of its own (empty: the module's or panel's own).
 */
public record WheelSlot(Kind kind, String target, String customIcon, String customName) {
	public enum Kind {
		/** Toggles a module (macros are modules too). */
		MODULE,
		/** Switches a control panel. */
		CONTROL,
		/** Runs an addon's key action. */
		KEY_ACTION,
		/** Opens a panel on the desktop. */
		PANEL,
		/** Runs a command line as a macro does: {@code .myriad}, {@code /server}, or chat. */
		COMMAND
	}

	public WheelSlot {
		customIcon = customIcon == null ? "" : customIcon;
		customName = customName == null ? "" : customName.strip();
	}

	public WheelSlot(Kind kind, String target) {
		this(kind, target, "", "");
	}

	public WheelSlot withIcon(String icon) {
		return new WheelSlot(kind, target, icon, customName);
	}

	public WheelSlot withName(String name) {
		return new WheelSlot(kind, target, customIcon, name);
	}

	/** Whether the two run the same thing, whatever they're called or look like. */
	public boolean sameTarget(WheelSlot other) {
		return kind == other.kind && target.equals(other.target);
	}

	public String label() {
		if (!customName.isEmpty()) return customName;
		return defaultLabel();
	}

	/** The name it has without one of its own. */
	public String defaultLabel() {
		return switch (kind) {
			case MODULE -> module().map(Module::name).orElse(target);
			case CONTROL -> control().map(ControlPanel::name).orElse(target);
			case KEY_ACTION -> keyAction().map(KeyAction::name).orElse(target);
			case PANEL -> panel().map(PanelType::name).orElse(target);
			case COMMAND -> target;
		};
	}

	/** What it is, for under the name: the module's group, "Control panel", the command kind... */
	public String hint() {
		return switch (kind) {
			case MODULE -> module().map(m -> m.hasTag(MacroManager.TAG) ? "Macro" : m.group().name()).orElse("Missing module");
			case CONTROL -> control().isPresent() ? "Control panel" : "Missing control panel";
			case KEY_ACTION -> keyAction().isPresent() ? "Action" : "Missing action";
			case PANEL -> panel().isPresent() ? "Panel" : "Missing panel";
			case COMMAND -> "Command";
		};
	}

	/** Its own icon if it has one, else a Nerd Font glyph for what it runs. */
	public String icon() {
		return customIcon.isEmpty() ? defaultIcon() : customIcon;
	}

	/** The icon it has without one of its own. */
	public String defaultIcon() {
		return switch (kind) {
			case MODULE -> module().map(m -> m instanceof MacroModule macro ? macro.icon.getOr(m.tag().icon()) : m.tag().icon()).filter(i -> !i.isEmpty()).orElse("");
			case CONTROL -> control().map(ControlPanel::icon).orElse("\uf1de");
			case KEY_ACTION -> "";
			case PANEL -> panel().map(PanelType::icon).filter(i -> i != null && !i.isEmpty()).orElse("");
			case COMMAND -> "";
		};
	}

	/** Whether what it switches is on now (a module or control panel); false for the rest. */
	public boolean isOn() {
		return switch (kind) {
			case MODULE -> module().map(Module::isEnabled).orElse(false);
			case CONTROL -> control().map(ControlPanel::isActive).orElse(false);
			default -> false;
		};
	}

	/** Whether what it points at is installed (a command always is). */
	public boolean exists() {
		return switch (kind) {
			case MODULE -> module().isPresent();
			case CONTROL -> control().isPresent();
			case KEY_ACTION -> keyAction().isPresent();
			case PANEL -> panel().isPresent();
			case COMMAND -> !target.isBlank();
		};
	}

	public void run() {
		switch (kind) {
			case MODULE -> module().ifPresent(Module::toggle);
			case CONTROL -> control().ifPresent(ControlPanel::toggle);
			case KEY_ACTION -> keyAction().ifPresent(KeyAction::run);
			case PANEL -> panel().ifPresent(t -> {
				WindowManager wm = (WindowManager) Myriad.ui();
				wm.open();
				wm.openPanel(t.id(), new JsonObject());
			});
			case COMMAND -> MacroModule.line(target);
		}
	}

	private Optional<Module> module() {
		MyriadId id = id();
		return id == null ? Optional.empty() : Myriad.modules().get(id);
	}

	private Optional<ControlPanel> control() {
		MyriadId id = id();
		return id == null ? Optional.empty() : Myriad.controls().get(id);
	}

	private Optional<KeyAction> keyAction() {
		for (KeyAction a : Myriad.keyActions()) if (a.id().toString().equals(target)) return Optional.of(a);
		return Optional.empty();
	}

	private Optional<PanelType> panel() {
		for (PanelType t : Myriad.panels()) if (t.id().toString().equals(target)) return Optional.of(t);
		return Optional.empty();
	}

	private @Nullable MyriadId id() {
		try {
			return MyriadId.parse(target);
		} catch (RuntimeException e) {
			return null;
		}
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
		o.addProperty("target", target);
		if (!customIcon.isEmpty()) o.addProperty("icon", customIcon);
		if (!customName.isEmpty()) o.addProperty("name", customName);
		return o;
	}

	public static @Nullable WheelSlot fromJson(JsonObject o) {
		if (!o.has("kind") || !o.has("target")) return null;
		try {
			return new WheelSlot(Kind.valueOf(o.get("kind").getAsString().toUpperCase(Locale.ROOT)), o.get("target").getAsString(),
				o.has("icon") ? o.get("icon").getAsString() : "", o.has("name") ? o.get("name").getAsString() : "");
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * What could go on a wheel matching {@code query} (every word somewhere in its name or kind): modules and macros,
	 * control panels, addons' key actions and panels; and the query itself as a command when it starts with the command
	 * prefix or {@code /}.
	 */
	public static List<WheelSlot> candidates(String query) {
		String q = query.strip();
		List<WheelSlot> out = new ArrayList<>();
		String prefix = Myriad.config().commandPrefix();
		if (q.length() > 1 && (q.startsWith(prefix) || q.startsWith("/"))) out.add(new WheelSlot(Kind.COMMAND, q));
		for (Module m : Myriad.modules()) if (!m.isMirror()) out.add(new WheelSlot(Kind.MODULE, m.id().toString()));
		for (ControlPanel c : Myriad.controls().all()) out.add(new WheelSlot(Kind.CONTROL, c.id().toString()));
		for (KeyAction a : Myriad.keyActions()) out.add(new WheelSlot(Kind.KEY_ACTION, a.id().toString()));
		for (PanelType t : Myriad.panels()) if (t.isListed() && !t.isHud()) out.add(new WheelSlot(Kind.PANEL, t.id().toString()));
		if (q.isEmpty()) return out;
		String[] words = q.toLowerCase(Locale.ROOT).split("\\s+");
		return out.stream().filter(s -> {
			if (s.kind == Kind.COMMAND) return true;
			String hay = (s.label() + " " + s.hint()).toLowerCase(Locale.ROOT);
			for (String w : words) if (!hay.contains(w)) return false;
			return true;
		}).toList();
	}
}
