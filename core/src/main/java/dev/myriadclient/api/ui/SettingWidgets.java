package dev.myriadclient.api.ui;

import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.ui.widget.Widget;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Maps setting classes to editor widgets, from {@link Ui#settingWidgets()}. Lookup walks the setting's class hierarchy,
 * so registering a widget for your custom {@code Setting} subclass is all it takes for it to show up in settings panels
 * (a module's settings, a panel's settings window, {@link dev.myriadclient.api.ui.widget.SettingsView}).
 */
@ApiStatus.NonExtendable
public interface SettingWidgets {
	/**
	 * Makes {@code factory} the editor for settings of {@code type} and its subclasses (unless a subclass has its own),
	 * replacing any editor registered for exactly {@code type} before. The factory is called each time a settings view
	 * is built, once per setting; the editor it returns is put in a
	 * {@link dev.myriadclient.api.ui.widget.SettingRow} beside or under the setting's name.
	 *
	 * @param stacked true if the editor is wide and should sit below the setting name rather than beside it
	 */
	<S extends Setting<?>> void register(Class<S> type, Function<S, Widget> factory, boolean stacked);

	/**
	 * Builds the row (name and editor, a {@link dev.myriadclient.api.ui.widget.SettingRow}) for a setting, or null if
	 * no widget is registered for its class or any superclass. The row hides itself while the setting isn't visible.
	 */
	@Nullable Widget create(Setting<?> setting);
}
