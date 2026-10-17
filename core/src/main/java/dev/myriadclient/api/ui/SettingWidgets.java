package dev.myriadclient.api.ui;

import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.ui.widget.Widget;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Function;

/**
 * Maps setting classes to editor widgets. Lookup walks the setting's class hierarchy, so registering a widget for
 * your custom {@code Setting} subclass is all it takes for it to show up in settings panels.
 */
@ApiStatus.NonExtendable
public interface SettingWidgets {
	/**
	 * @param stacked true if the editor is wide and should sit below the setting name rather than beside it
	 */
	<S extends Setting<?>> void register(Class<S> type, Function<S, Widget> factory, boolean stacked);

	/** Builds the row (label + editor) for a setting, or null if no widget is registered for its type. */
	Widget create(Setting<?> setting);
}
