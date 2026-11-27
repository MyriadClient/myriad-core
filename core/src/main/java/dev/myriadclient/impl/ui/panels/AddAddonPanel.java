package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Note;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.update.LinkInstall;
import dev.myriadclient.impl.update.UpdateManager;

/**
 * Adding an addon from a link, as a small floating card: a GitHub repo or an addon's feed is looked up (downloaded and
 * checked, nothing written), shown with who signed it, and installed only when the player says so.
 */
public final class AddAddonPanel extends WidgetPanel {
	private final UpdateManager updates = MyriadImpl.get().updates();
	private boolean looking;
	private String link = "", token = "";
	private LinkInstall.Offer offer;
	private String problem;
	private TextField linkField;

	/** Set while the card is being opened: a card left open isn't brought back with the layout at the next start. */
	private static boolean requested;

	/** Opens the card on {@code workspace} (where the window asking for it is), or brings it forward when it's open. */
	public static void open(int workspace) {
		WindowManager wm = (WindowManager) Myriad.ui();
		wm.find(CorePanels.ADD_ADDON, new JsonObject()).ifPresentOrElse(w -> w.focus(), () -> {
			if (!wm.isOpen()) wm.open();
			requested = true;
			try {
				wm.openPanel(CorePanels.ADD_ADDON, new JsonObject(), workspace, true);
			} finally {
				requested = false;
			}
		});
	}

	/** Panel factory: a card only when one was asked for, never one restored from a saved layout. */
	static AddAddonPanel create() {
		return requested ? new AddAddonPanel() : null;
	}

	@Override
	public String title() {
		return "Add an addon";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public Rect preferredSize(Canvas c) {
		return new Rect(0, 0, 300, offer == null ? 132 : 220);
	}

	@Override
	protected void build(VBox content) {
		content.add(new Label("A GitHub repo or an addon's feed. It's downloaded and checked first; nothing is installed until you say so.").dim());
		linkField = new TextField(() -> link).onChange(s -> link = s).onSubmit(s -> lookUp()).placeholder("github.com/owner/repo, or https://…/feed.json");
		content.add(linkField);
		content.add(new TextField(() -> token).onChange(s -> token = s).masked().placeholder("Access token, for a private repo or feed (optional)"));
		if (problem != null) content.add(new Note("Can't install it: " + problem + ".").error());
		LinkInstall.Offer o = offer;
		if (o == null) {
			HBox buttons = new HBox(4);
			buttons.add(new Button(() -> looking ? "Looking…" : "Look up", this::lookUp).accent());
			buttons.add(new Button("Cancel", this::close).quiet());
			content.add(buttons);
			return;
		}
		content.add(new Label(o.name() + " " + o.version()).bold());
		content.add(new Label(o.id() + "  ·  from " + o.from() + "  ·  " + (o.jar().length / 1024) + " KB").dim());
		if (o.signed()) {
			content.add(new Note("Signed with key " + o.keyId() + ". If its developer publishes their key, check it's this one: every update will have to be "
				+ "signed with it."));
		} else {
			content.add(new Note("Unsigned: whoever controls " + o.from() + " decides what you get, now and in every update. Install it only if you trust them.").error());
		}
		HBox act = new HBox(4);
		Button install = new Button("Install " + o.name(), () -> install(o));
		act.add(o.signed() ? install.accent() : install.danger());
		act.add(new Button("Don't install", () -> {
			offer = null;
			rebuild();
		}).quiet());
		content.add(act);
	}

	@Override
	public void onOpen() {
		if (linkField != null) linkField.requestFocus();
	}

	private void lookUp() {
		if (looking || link.isBlank()) return;
		looking = true;
		offer = null;
		problem = null;
		rebuild();
		updates.lookUp(link, token).whenComplete((o, err) -> Async.onRenderThread(() -> {
			looking = false;
			if (err != null) problem = UpdateManager.reasonOf(err);
			else offer = o;
			rebuild();
		}));
	}

	private void install(LinkInstall.Offer o) {
		try {
			updates.install(o);
		} catch (java.io.IOException e) {
			problem = e.getMessage();
			offer = null;
			rebuild();
			return;
		}
		Myriad.notifications().toast("Installed", o.name() + " " + o.version() + " loads when you restart the game").level(Notifications.Level.SUCCESS).show();
		close();
	}

	private void close() {
		if (window() != null) window().close();
	}
}
