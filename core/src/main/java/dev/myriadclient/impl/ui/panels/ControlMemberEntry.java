package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.control.ControlPanelImpl;

import java.util.Set;

/**
 * A module in a control panel's window: a module card like any (click toggles it, its settings unfold), with a badge
 * when the panel saves settings for it, and, at the top of its unfolded settings, saving them or letting it go back to
 * its own.
 */
final class ControlMemberEntry extends ModuleEntry {
	private final ControlPanelImpl control;

	ControlMemberEntry(Module module, Set<Module> expanded, ControlPanelImpl control) {
		super(module, expanded);
		this.control = control;
	}

	@Override
	protected float drawBadges(Canvas c, float right, float cy) {
		right = super.drawBadges(c, right, cy);
		int n = control.rememberedCount(module);
		if (n > 0) right = badge(c, " " + n, right, cy, theme().accent.argb(), true) - 4;
		return right;
	}

	@Override
	protected void buildBody(VBox body) {
		HBox saved = new HBox(4);
		saved.addWeighted(new Label(() -> {
			int n = control.rememberedCount(module);
			return n == 0 ? "Runs with its own settings" : "  " + control.name() + " sets " + n + " of these while it's on";
		}).dim(), 1);
		saved.addFixed(new Button("Save", () -> control.remember(module))
			.tooltip("Save these settings as they are now: " + module.name() + " takes them whenever " + control.name()
				+ " turns on, and gets its own back when it turns off."), 36);
		saved.addFixed(new Button("Clear", () -> control.forget(module)).tooltip("Let " + module.name() + " run with its own settings."), 36)
			.visible(() -> control.rememberedCount(module) > 0);
		body.add(saved);
		super.buildBody(body);
	}
}
