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
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small floating question with confirm and cancel buttons (Desktop.confirm), or one that answers itself as cancel
 * after a while unless it's confirmed (a change you have to say you keep). Never saved with the layout.
 */
public final class DialogPanel extends WidgetPanel {
	private static final AtomicInteger IDS = new AtomicInteger();
	private static final Map<Integer, DialogPanel> PENDING = new HashMap<>();

	/** Open dialogs by id, to be dismissed without an answer (see {@link #dismiss}). */
	private static final Map<Integer, DialogPanel> OPEN = new HashMap<>();

	private final String title, confirmLabel;
	private final Supplier<String> message;
	private final Runnable onConfirm, onCancel;
	/** When it answers itself as cancel (System.currentTimeMillis), or 0 for never. */
	private final long deadline;
	private final int id;
	private boolean answered;

	private DialogPanel(int id, String title, Supplier<String> message, String confirmLabel, Runnable onConfirm, Runnable onCancel, long deadline) {
		this.id = id;
		this.title = title;
		this.message = message;
		this.confirmLabel = confirmLabel;
		this.onConfirm = onConfirm;
		this.onCancel = onCancel;
		this.deadline = deadline;
	}

	/** Prepares a dialog and returns the args that open it. */
	public static JsonObject prepare(String title, String message, String confirmLabel, Runnable onConfirm, Runnable onCancel) {
		return prepare(title, s -> message, confirmLabel, onConfirm, onCancel, 0);
	}

	/**
	 * Prepares one that answers itself as cancel after {@code timeoutMs} (0: never), its message asked for again as it's
	 * drawn with the whole seconds left; returns the args that open it (its {@code id} is the one {@link #dismiss} takes).
	 */
	public static JsonObject prepare(String title, IntFunction<String> message, String confirmLabel, Runnable onConfirm, Runnable onCancel, long timeoutMs) {
		int id = IDS.incrementAndGet();
		long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;
		Supplier<String> text = () -> message.apply(deadline == 0 ? 0 : (int) Math.ceil(Math.max(0, deadline - System.currentTimeMillis()) / 1000.0));
		PENDING.put(id, new DialogPanel(id, title, text, confirmLabel, onConfirm, onCancel, deadline));
		JsonObject args = new JsonObject();
		args.addProperty("id", id);
		return args;
	}

	/** Closes dialog {@code id} if it's still open, without either answer running (another question took its place). */
	public static void dismiss(int id) {
		DialogPanel d = OPEN.remove(id);
		PENDING.remove(id);
		if (d == null || d.answered) return;
		d.answered = true;
		if (d.window() != null) d.window().close();
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
		return new Rect(0, 0, Math.clamp(c.textWidth(message.get()) + 16, 180, 320), 64);
	}

	@Override
	protected void build(VBox content) {
		OPEN.put(id, this);
		content.add(new Label(message));
		HBox buttons = new HBox(4);
		buttons.add(new Button(confirmLabel, () -> answer(true)));
		buttons.add(new Button("Cancel", () -> answer(false)));
		content.add(buttons);
	}

	/** Out of time: cancelled. */
	@Override
	public void tick() {
		if (deadline != 0 && !answered && System.currentTimeMillis() >= deadline) answer(false);
	}

	private void answer(boolean confirmed) {
		if (answered) return;
		answered = true;
		OPEN.remove(id);
		if (window() != null) window().close();
		Runnable r = confirmed ? onConfirm : onCancel;
		if (r != null) r.run();
	}

	/** Closing the window without answering counts as cancel. */
	@Override
	public void onClose() {
		OPEN.remove(id);
		if (answered) return;
		answered = true;
		if (onCancel != null) onCancel.run();
	}
}
