package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Dropdown;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.command.CoreCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Core's own settings, shown where Myriad's row unfolds in the Addons window: the config profiles (switch, create,
 * delete; a profile holds module state and the UI layout) and the anti-cheat Myriad plays to.
 */
final class ProfilesSection {
	private ProfilesSection() {
	}

	/** Adds the sections to {@code body}; {@code rebuild} builds them again after the profiles change. */
	static void build(VBox body, Runnable rebuild, java.util.Set<String> folded) {
		String[] newName = {""};
		VBox profiles = new VBox(3, 0);
		VBox antiCheat = new VBox(3, 0);
		body.add(section("Profiles", profiles, folded, () -> String.valueOf(Myriad.config().profiles().size())));
		body.add(section("Anti-cheat", antiCheat, folded, () -> Optional.ofNullable(Myriad.antiCheat().choice()).map(AntiCheat.Profile::name).orElse("Auto")));
		body = profiles;
		for (String p : Myriad.config().profiles()) {
			boolean active = p.equals(Myriad.config().activeProfile());
			HBox row = new HBox(4);
			row.addWeighted(new Label((active ? "\uf00c  " : "     ") + p).color(() -> active ? theme().text.argb() : theme().textDim.argb()), 1);
			if (!active) {
				Toolbar t = new Toolbar()
					.add("\uf2f6", "Load", "Switch to " + p + ".", () -> {
						Myriad.config().switchProfile(p);
						rebuild.run();
					})
					.add("\uf1f8", "Delete", "Delete the profile " + p + ".", () -> Myriad.ui().confirm("Delete " + p, "Delete this profile for good?",
						"Delete", () -> {
							if (Myriad.config().deleteProfile(p)) rebuild.run();
						}, null), true, false, () -> true);
				row.addFixed(t, 110);
			}
			body.add(row);
		}
		HBox create = new HBox(4);
		create.addWeighted(new TextField(() -> newName[0]).placeholder("New profile, a copy of this one").onChange(s -> newName[0] = s), 1);
		create.addFixed(new Button("New", () -> {
			if (!newName[0].isBlank()) {
				Myriad.config().switchProfile(newName[0]);
				rebuild.run();
			}
		}).accent(), 36);
		body.add(create);
		body.add(new Label("A profile holds module settings and the menu layout; friends and key actions are shared.").dim());
		body = antiCheat;
		// Auto is the empty choice.
		body.add(new Dropdown<Optional<AntiCheat.Profile>>(ProfilesSection::antiCheatChoices, () -> Optional.ofNullable(Myriad.antiCheat().choice()),
			c -> Myriad.antiCheat().choose(c.orElse(null)), c -> c.map(AntiCheat.Profile::name).orElse("Auto")));
		body.add(new Label(() -> CoreCommands.antiCheatStatus() + ". Grim makes modules rotate before acting, click only faces you "
			+ "can see and wait for safe moments; Vanilla acts as fast as the vanilla server allows (picked here, also with no "
			+ "click, break or place limits, which Paper servers enforce); NoCheatPlus is Vanilla with a loosely checked glide.").dim());
	}

	/** A section that folds like a module's group, its hint said while folded. */
	private static Collapsible section(String name, VBox content, java.util.Set<String> folded, java.util.function.Supplier<String> hint) {
		String key = "myriad/" + name;
		return new Collapsible(() -> name, content, () -> !folded.contains(key), open -> {
			if (open) folded.remove(key);
			else folded.add(key);
		}).indent(10).hint(() -> folded.contains(key) ? hint.get() : "");
	}

	private static dev.myriadclient.api.ui.ThemeSettings theme() {
		return Myriad.ui().theme();
	}

	private static List<Optional<AntiCheat.Profile>> antiCheatChoices() {
		List<Optional<AntiCheat.Profile>> choices = new ArrayList<>();
		choices.add(Optional.empty());
		for (AntiCheat.Profile p : Myriad.antiCheat().profiles()) choices.add(Optional.of(p));
		return choices;
	}
}
