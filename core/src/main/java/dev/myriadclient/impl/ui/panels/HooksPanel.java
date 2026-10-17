package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.hook.Contribution;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.hook.HookManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What addons changed in how Myriad works: the services they replaced (and which replacement each is using here) and
 * the hooks they handle, as cards the player can switch off. Hooks nothing handles are listed at the bottom, for
 * addon developers looking for where they can step in.
 */
final class HooksPanel extends WidgetPanel {
	private final Set<MyriadId> unfolded = new HashSet<>();
	private String built = "";

	@Override
	public String title() {
		return "Hooks";
	}

	@Override
	public String icon() {
		return "";
	}

	/** Changes whenever what's shown would: contributions, their state, the replacements in use. */
	private String signature() {
		StringBuilder s = new StringBuilder();
		for (Contribution c : Myriad.hooks().contributions()) s.append(c.id()).append(c.enabled()).append(c.failed()).append(';');
		for (Service<?> v : Service.all()) s.append(Myriad.hooks().live(v)).append(';');
		return s.append(Hook.all().size()).toString();
	}

	@Override
	public void tick() {
		if (!built.equals(signature())) rebuild();
	}

	@Override
	protected void build(VBox content) {
		built = signature();
		HookManager hooks = MyriadImpl.get().hookManager();

		content.add(new Label("Services").bold());
		boolean anyReplaced = false;
		for (Service<?> s : Service.all()) {
			List<Contribution> list = hooks.contributions(s.id());
			if (list.isEmpty()) continue;
			anyReplaced = true;
			String forEveryone = hooks.active(s).map(Contribution::label).orElse("core's own");
			content.add(new Label(s.name() + " · " + forEveryone + " for everyone").dim());
			List<Contribution> live = hooks.live(s);
			for (Contribution c : list) content.add(new Card(c, hooks, live.contains(c)));
		}
		if (!anyReplaced) content.add(new Label("Rotations, inventory, placement and breaking are all core's own.").dim());

		content.add(new Label("Hooks").bold());
		List<String> unused = new ArrayList<>();
		Set<MyriadId> seen = new HashSet<>();
		for (Hook<?, ?> h : Hook.all()) {
			if (!seen.add(h.id())) continue;
			List<Contribution> list = hooks.contributions(h.id());
			if (list.isEmpty()) {
				unused.add(h.name());
				continue;
			}
			content.add(new Label(h.name() + (h.description().isEmpty() ? "" : " · " + h.description())).dim());
			for (Contribution c : list) content.add(new Card(c, hooks, false));
		}
		if (unused.size() == seen.size()) content.add(new Label("No addon handles any hook.").dim());
		if (!unused.isEmpty()) content.add(new Label("Not handled: " + String.join(", ", unused)).dim());
	}

	/** A contribution: click switches it on or off; unfolding says who made it and when it applies. */
	private final class Card extends CardEntry {
		private final Contribution c;
		private final HookManager hooks;
		private final boolean inUse;

		Card(Contribution c, HookManager hooks, boolean inUse) {
			super(c.enabled() && !c.failed(), unfolded.contains(c.id()));
			this.c = c;
			this.hooks = hooks;
			this.inUse = inUse;
		}

		@Override
		protected String tooltipText() {
			return "From " + AddonNames.of(hooks.ownerOf(c)) + " · click to turn " + (c.enabled() ? "off" : "on");
		}

		@Override
		protected String title() {
			return c.label();
		}

		@Override
		protected boolean isOn() {
			return c.enabled() && !c.failed();
		}

		@Override
		protected int accent() {
			return theme().accent.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(c.id());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(c.id());
			else unfolded.remove(c.id());
		}

		@Override
		protected void primary() {
			c.setEnabled(!c.enabled());
		}

		@Override
		protected float drawBadges(Canvas canvas, float right, float cy) {
			if (c.failed()) return badge(canvas, "failed", right, cy, theme().red.get().color(), true) - 4;
			if (inUse) right = badge(canvas, "in use", right, cy, theme().accent.argb(), true) - 4;
			// "always" says nothing: only a narrower match gets a badge.
			if (c.match().specificity() == 0) return right;
			return badge(canvas, c.match().toString(), right, cy, theme().textDim.argb(), false) - 4;
		}

		@Override
		protected void buildBody(VBox body) {
			body.add(new Label(HookManager.targetName(c) + " · from " + AddonNames.of(hooks.ownerOf(c))).dim());
			body.add(new Label("Applies " + c.match() + (c.failed() ? ". It threw an error and is off until the game restarts." : ".")).dim());
		}
	}
}
