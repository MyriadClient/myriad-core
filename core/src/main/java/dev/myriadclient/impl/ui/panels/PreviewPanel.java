package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Dropdown;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.RegistryPicker;
import dev.myriadclient.api.ui.widget.VBox;
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
 * {@code PreviewEvent}); pick what it shows at the top.
 */
final class PreviewPanel extends WidgetPanel {
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

	private final SettingGroup sg = settings.group("Preview");
	private final EnumSetting<Subject> subject = sg.oneOf("Show", Subject.MOB).description("What it shows.").build();
	private final RegistrySetting<EntityType<?>> mob = sg.registry("Mob", BuiltInRegistries.ENTITY_TYPE, (EntityType<?>) EntityTypes.ZOMBIE)
		.filter(t -> t.canSummon() && t != EntityTypes.PLAYER).visible(() -> subject.get() == Subject.MOB).build();
	private final EnumSetting<Storage> storage = sg.oneOf("Storage", Storage.CHEST).visible(() -> subject.get() == Subject.STORAGE).build();
	private final RegistrySetting<Block> block = sg.block("Block", Blocks.DIAMOND_ORE).filter(b -> b.asItem() != Items.AIR)
		.visible(() -> subject.get() == Subject.BLOCK).build();

	private Preview preview;

	PreviewPanel() {
		super(4);
		settings.onAnyChanged(s -> rebuild());
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

	@Override
	protected void build(VBox content) {
		HBox top = new HBox(4);
		top.addWeighted(new Dropdown<>(() -> List.of(Subject.values()), subject::get, subject::edit, EnumSetting::displayName), 1);
		switch (subject.get()) {
			case MOB -> top.addWeighted(RegistryPicker.single(mob), 1);
			case STORAGE -> top.addWeighted(new Dropdown<>(() -> List.of(Storage.values()), storage::get, storage::edit, EnumSetting::displayName), 1);
			case BLOCK -> top.addWeighted(RegistryPicker.single(block), 1);
			default -> {
			}
		}
		content.add(top);
		preview = switch (subject.get()) {
			case MOB -> Preview.entity(mob.get());
			case PLAYER -> Preview.player();
			case STORAGE -> Preview.block(storage.get().block);
			case BLOCK -> Preview.block(block.get());
			case VIEW_MODEL -> Preview.viewModel();
		};
		content.add(preview);
	}

	@Override
	public void render(Canvas canvas, float width, float height, float mouseX, float mouseY) {
		// The preview takes what the window has left under the picker (laid out last frame); an open list pushes it down.
		if (preview != null) preview.height(height - preview.y() - 4);
		super.render(canvas, width, height, mouseX, mouseY);
	}
}
