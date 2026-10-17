package dev.myriadclient.impl.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.ui.ThemeSettings.ModKey;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.impl.CoreAddon;
import dev.myriadclient.impl.ui.WindowManager;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Myriad's keys in Minecraft's own Controls → Key Binds, under "Myriad", so someone who doesn't know how to open the
 * menu finds them where they'd look: the mod key (mod+1..9 opens the menu) and Open Menu. Myriad's settings stay the
 * source of truth: they're shown there at start and whenever they change, and a key picked there is taken back into
 * them (the mod key only as Alt, Super or Ctrl, which is all it can be). Myriad still handles the presses itself.
 */
public final class VanillaKeys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("myriad", "myriad"));
	private static KeyMapping modKey, openMenu;
	/** What each mapping was last set to from Myriad's side; a difference is the player's pick in the Controls screen. */
	private static InputConstants.Key shownMod, shownOpen;

	private VanillaKeys() {
	}

	/** At client init, before the options load. */
	public static void register() {
		modKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.myriad.mod_key", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY));
		openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.myriad.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
	}

	/** Every client tick: a few comparisons. */
	public static void sync() {
		if (modKey == null || !Myriad.isReady()) return;
		var setting = ((WindowManager) Myriad.ui()).theme().modKey;
		InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(modKey);
		if (shownMod != null && !bound.equals(shownMod)) {
			ModKey picked = modKeyOf(bound);
			if (picked != null) {
				setting.set(picked);
				Myriad.config().markDirty();
			}
			else Myriad.notifications().toast("Mod key", "It can be Alt, Super or Ctrl: kept " + setting.get().name().charAt(0) + setting.get().name().substring(1).toLowerCase() + ".")
				.level(Notifications.Level.WARNING).lasting(4000).replacing("mod-key").show();
			shownMod = null;
		}
		if (shownMod == null || modKeyOf(shownMod) != setting.get()) {
			shownMod = keyOf(setting.get());
			show(modKey, shownMod);
		}

		KeyAction open = Myriad.keyActions().get(CoreAddon.OPEN_MENU).orElse(null);
		if (open == null) return;
		bound = KeyMappingHelper.getBoundKeyOf(openMenu);
		if (shownOpen != null && !bound.equals(shownOpen)) {
			open.setBind(bound.getType() == InputConstants.Type.MOUSE ? Keybind.mouse(bound.getValue())
				: bound.equals(InputConstants.UNKNOWN) ? Keybind.NONE : Keybind.key(bound.getValue()));
			Myriad.config().markDirty();
			shownOpen = bound;
		}
		InputConstants.Key wanted = keyOf(open.bind());
		if (!wanted.equals(shownOpen)) {
			shownOpen = wanted;
			show(openMenu, wanted);
		}
	}

	private static void show(KeyMapping mapping, InputConstants.Key key) {
		mapping.setKey(key);
		KeyMapping.resetMapping();
	}

	private static ModKey modKeyOf(InputConstants.Key key) {
		if (key.getType() != InputConstants.Type.KEYSYM) return null;
		return switch (key.getValue()) {
			case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> ModKey.ALT;
			case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> ModKey.SUPER;
			case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> ModKey.CTRL;
			default -> null;
		};
	}

	private static InputConstants.Key keyOf(ModKey mod) {
		return InputConstants.Type.KEYSYM.getOrCreate(switch (mod) {
			case ALT -> GLFW.GLFW_KEY_LEFT_ALT;
			case SUPER -> GLFW.GLFW_KEY_LEFT_SUPER;
			case CTRL -> GLFW.GLFW_KEY_LEFT_CONTROL;
		});
	}

	/** Minecraft's key mappings have no modifiers: a bind with some shows its key alone. */
	private static InputConstants.Key keyOf(Keybind bind) {
		if (!bind.isSet()) return InputConstants.UNKNOWN;
		return (bind.mouse() ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM).getOrCreate(bind.code());
	}
}
