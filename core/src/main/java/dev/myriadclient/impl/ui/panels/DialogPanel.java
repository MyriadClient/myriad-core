package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.VBox;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A small floating question with confirm and cancel buttons (Desktop.confirm). Never saved with the layout. */
public final class DialogPanel extends WidgetPanel {
	private static final AtomicInteger IDS = new AtomicInteger();
	private static final Map<Integer, DialogPanel> PENDING = new HashMap<>();

	private final String title, message, confirmLabel;
	private final Runnable onConfirm, onCancel;
	private boolean answered;

	private DialogPanel(String title, String message, String confirmLabel, Runnable onConfirm, Runnable onCancel) {
		this.title = title;
		this.message = message;
		this.confirmLabel = confirmLabel;
		this.onConfirm = onConfirm;
		this.onCancel = onCancel;
	}

	/** Prepares a dialog and returns the args that open it. */
	public static JsonObject prepare(String title, String message, String confirmLabel, Runnable onConfirm, Runnable onCancel) {
		int id = IDS.incrementAndGet();
		PENDING.put(id, new DialogPanel(title, message, confirmLabel, onConfirm, onCancel));
		JsonObject args = new JsonObject();
		args.addProperty("id", id);
		return args;
	}

	/** Panel factory: the prepared dialog, or null (a dialog from a previous session isn't restored). */
	public static DialogPanel create(JsonObject args) {
		return args.has("id") ? PENDING.remove(args.get("id").getAsInt()) : null;
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public Rect preferredSize(Canvas c) {
		return new Rect(0, 0, Math.clamp(c.textWidth(message) + 16, 180, 320), 64);
	}

	@Override
	protected void build(VBox content) {
		content.add(new Label(message));
		HBox buttons = new HBox(4);
		buttons.add(new Button(confirmLabel, () -> answer(true)));
		buttons.add(new Button("Cancel", () -> answer(false)));
		content.add(buttons);
	}

	private void answer(boolean confirmed) {
		if (answered) return;
		answered = true;
		if (window() != null) window().close();
		Runnable r = confirmed ? onConfirm : onCancel;
		if (r != null) r.run();
	}

	/** Closing the window without answering counts as cancel. */
	@Override
	public void onClose() {
		if (answered) return;
		answered = true;
		if (onCancel != null) onCancel.run();
	}
}
