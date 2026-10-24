package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.render.CoreRenderSettings;
import net.minecraft.world.entity.EntityTypes;

/**
 * How every module draws what it shows: Shader or Box, with a preview to see the difference on. Each module's own look
 * (colours, fill, widths) is in its Render options.
 */
final class RenderPanel extends WidgetPanel {
	@Override
	public String title() {
		return "Render";
	}

	@Override
	public String icon() {
		return Icons.EYE;
	}

	@Override
	protected void build(VBox content) {
		content.add(new Label("How every module draws what it shows. Each module's colours, fill and widths are in its own Render options, which show the ones for this mode.").dim());
		content.add(SettingsView.build(CoreRenderSettings.settings().settings()));
		content.add(Preview.entity(EntityTypes.ZOMBIE).height(140));
	}
}
