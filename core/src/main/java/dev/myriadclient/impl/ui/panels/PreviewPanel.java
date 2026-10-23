package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.widget.Container;
import dev.myriadclient.api.ui.widget.Dropdown;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.RegistryPicker;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.ui.widget.WidgetRoot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * A preview on its own, for a workspace: something to watch while tuning how things are drawn, without closing the
 * menu to look at the world. It shows what the modules on would draw on it (their highlights and shapes, through
 * {@code PreviewEvent}); pick what it shows at the top. The picker's lists open over the preview, on the window's own
 * background, rather than pushing it down, so the preview keeps its place and size while you choose.
 */
final class PreviewPanel extends Panel {
	enum Subject {
		MOB, PLAYER, STORAGE, BLOCK, VIEW_MODEL
	}

	/** Blocks that hold items, as Storage modules find them. */
	enum Storage {
		CHEST(Blocks.CHEST), TRAPPED_CHEST(Blocks.TRAPPED_CHEST), ENDER_CHEST(Blocks.ENDER_CHEST), BARREL(Blocks.BARREL),
		SHULKER_BOX(Blocks.SHULKER_BOX), HOPPER(Blocks.HOPPER), FURNACE(Blocks.FURNACE), DISPENSER(Blocks.DISPENSER), DROPPER(Blocks.DROPPER);

		final Block block;

		Storage(Block block) {
			this.block = block;
		}
	}

	private static final float PAD = 4;

	private final SettingGroup sg = settings.group("Preview");
	private final EnumSetting<Subject> subject = sg.oneOf("Show", Subject.MOB).description("What it shows.").build();
	private final RegistrySetting<EntityType<?>> mob = sg.registry("Mob", BuiltInRegistries.ENTITY_TYPE, (EntityType<?>) EntityTypes.ZOMBIE)
		.filter(t -> t.canSummon() && t != EntityTypes.PLAYER).visible(() -> subject.get() == Subject.MOB).build();
	private final EnumSetting<Storage> storage = sg.oneOf("Storage", Storage.CHEST).visible(() -> subject.get() == Subject.STORAGE).build();
	private final RegistrySetting<Block> block = sg.block("Block", Blocks.DIAMOND_ORE).filter(b -> b.asItem() != Items.AIR)
		.visible(() -> subject.get() == Subject.BLOCK).build();

	/** The picker, drawn over the preview; the preview under it. */
	private WidgetRoot top, view;
	private Preview preview;
	/** The picker's height with its lists shut: where the preview starts. */
	private float rowHeight = Widget.ROW;

	PreviewPanel() {
		settings.onAnyChanged(s -> top = null);
	}

	@Override
	public String title() {
		return "Preview";
	}

	@Override
	public String icon() {
		return Icons.EYE;
	}

	@Override
	public Rect preferredSize(Canvas canvas) {
		return new Rect(0, 0, 220, 240);
	}

	private void build() {
		HBox row = new HBox(4);
		row.addWeighted(new Dropdown<>(() -> List.of(Subject.values()), subject::get, subject::edit, EnumSetting::displayName), 1);
		switch (subject.get()) {
			case MOB -> row.addWeighted(RegistryPicker.single(mob), 1);
			case STORAGE -> row.addWeighted(new Dropdown<>(() -> List.of(Storage.values()), storage::get, storage::edit, EnumSetting::displayName), 1);
			case BLOCK -> row.addWeighted(RegistryPicker.single(block), 1);
			default -> {
			}
		}
		top = new WidgetRoot(row);
		preview = switch (subject.get()) {
			case MOB -> Preview.entity(mob.get());
			case PLAYER -> Preview.player();
			case STORAGE -> Preview.block(storage.get().block);
			case BLOCK -> Preview.block(block.get());
			case VIEW_MODEL -> Preview.viewModel();
		};
		// In a container: it's the container that marks the widget pressed, which drags go to.
		VBox holder = new VBox(0, 0);
		holder.add(preview);
		view = new WidgetRoot(holder);
		rowHeight = Widget.ROW;
	}

	@Override
	public void render(Canvas c, float width, float height, float mouseX, float mouseY) {
		if (top == null) build();
		float w = width - 2 * PAD, below = rowHeight + 2 * PAD;
		preview.height(height - below - PAD);
		c.push();
		c.translate(PAD, below);
		view.render(c, w, height - below - PAD, mouseX - PAD, mouseY - below);
		c.pop();
		c.push();
		c.translate(PAD, PAD);
		// An open list covers the preview with the window's own background (its frosted backdrop and colour, as the
		// window is drawn), so it reads as on a bare window while the rest of the preview stays as it was.
		for (Widget picker : ((Container) top.content()).children()) {
			if (picker.height() <= rowHeight + 0.5f) continue;
			c.backdrop(picker.x(), rowHeight, picker.width(), picker.height() - rowHeight, 4, 1);
			c.roundRect(picker.x(), rowHeight, picker.width(), picker.height() - rowHeight, 4, Myriad.ui().theme().windowBackground.argb());
		}
		top.render(c, w, height - 2 * PAD, mouseX - PAD, mouseY - PAD);
		c.pop();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (top == null) return false;
		if (top.mouseClicked(mx - PAD, my - PAD, button)) return true;
		return view.mouseClicked(mx - PAD, my - rowHeight - 2 * PAD, button);
	}

	@Override
	public boolean mouseReleased(float mx, float my, int button) {
		if (top == null) return false;
		boolean a = top.mouseReleased(mx - PAD, my - PAD, button);
		return view.mouseReleased(mx - PAD, my - rowHeight - 2 * PAD, button) || a;
	}

	@Override
	public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
		if (top == null) return false;
		boolean a = top.mouseDragged(mx - PAD, my - PAD, button, dx, dy);
		return view.mouseDragged(mx - PAD, my - rowHeight - 2 * PAD, button, dx, dy) || a;
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		if (top == null) return false;
		return top.mouseScrolled(mx - PAD, my - PAD, amount) || view.mouseScrolled(mx - PAD, my - rowHeight - 2 * PAD, amount);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return top != null && top.focused() != null && top.keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		return top != null && top.focused() != null && top.charTyped(chr, modifiers);
	}

	@Override
	public boolean capturesKeyboard() {
		return top != null && top.focused() != null;
	}

	@Override
	public String tooltip() {
		return top == null ? null : top.tooltip();
	}
}
