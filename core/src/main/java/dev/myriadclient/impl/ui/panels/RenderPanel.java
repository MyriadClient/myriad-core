package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.impl.render.CoreRenderSettings;
import net.minecraft.world.entity.EntityTypes;

/**
 * How every module draws what it shows: Shader or Box, with a preview to see the difference on, and addons' fills' own
 * options. Each module's own look (colours, fill, widths) is in its Render options.
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
		// Addons' fills' own options: each fill's, the same in every module using it.
		for (HighlightStyle.Fill fill : HighlightStyle.Fill.all()) {
			SettingGroup options = fill.options();
			if (options == null || options.settings().isEmpty()) continue;
			VBox body = new VBox(3, 0);
			for (Setting<?> s : options.settings()) {
				Widget row = Myriad.ui().settingWidgets().create(s);
				if (row != null) body.add(row);
			}
			content.add(new Collapsible(() -> fill.name() + " fill", body, options::isExpanded, options::setExpanded).indent(10));
		}
		content.add(Preview.entity(EntityTypes.ZOMBIE).height(140));
	}
}
