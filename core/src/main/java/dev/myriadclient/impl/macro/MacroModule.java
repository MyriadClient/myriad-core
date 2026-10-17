package dev.myriadclient.impl.macro;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.Tasks;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.StringListSetting;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * A macro the player made: a module whose Commands run when it turns on, one line at a time, so it has a bind, hold,
 * the launcher and control panels like any module. Run turns itself off when its lines are done; Toggle stays on and
 * runs Off Commands when turned off. A line is a Myriad command ({@code .toggle esp}), a server command
 * ({@code /home base}), {@code wait <ticks>}, or anything else, sent as chat.
 */
public final class MacroModule extends Module {
	public enum Mode {
		RUN, TOGGLE
	}

	private final EnumSetting<Mode> mode = sgGeneral.enumSetting("Mode", Mode.RUN)
		.description("Run: each press runs Commands once. Toggle: on runs Commands, off runs Off Commands.").build();
	private final StringListSetting commands = sgGeneral.stringList("Commands")
		.description("One per line: .myriad commands, /server commands, wait <ticks>, or chat.").build();
	private final StringListSetting offCommands = sgGeneral.stringList("Off Commands")
		.description("Run when it's turned off, the same way.").visible(() -> mode.get() == Mode.TOGGLE).build();
	{
		sgGeneral.action("Delete Macro", () -> MacroManager.delete(this)).description("Remove this macro for good.").build();
	}

	/** Owns the Off Commands' run, which outlives the module being on (turning off cancels the module's own tasks). */
	private final Object offRun = new Object();

	MacroModule(String name) {
		super(MacroManager.CATEGORY, name, "A macro: runs its commands when pressed.");
	}

	@Override
	protected void onEnable() {
		run(commands.get(), this, mode.get() == Mode.RUN ? () -> {
			// Run: off again once its lines are done (and not before, so waits in it aren't cut short).
			if (isEnabled()) setEnabledSilently(false);
		} : null);
	}

	@Override
	protected void onDisable() {
		if (mode.get() == Mode.TOGGLE) run(offCommands.get(), offRun, null);
	}

	private static void run(List<String> lines, Object owner, Runnable done) {
		Tasks.Sequence seq = Myriad.tasks().sequence(owner);
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty()) continue;
			String lower = line.toLowerCase(Locale.ROOT);
			if (lower.startsWith("wait ")) {
				try {
					seq.wait(Math.max(0, Integer.parseInt(line.substring(5).strip())));
				} catch (NumberFormatException e) {
					seq.run(() -> Myriad.chat(Component.literal("Macro: '" + line + "' isn't a number of ticks")));
				}
				continue;
			}
			seq.run(() -> line(line));
		}
		if (done != null) seq.onFinish(done);
		seq.start();
	}

	private static void line(String line) {
		var mc = Minecraft.getInstance();
		String prefix = Myriad.config().commandPrefix();
		if (line.startsWith(prefix)) {
			MyriadImpl.get().commandManager().execute(line.substring(prefix.length()));
		} else if (line.startsWith("/")) {
			if (mc.getConnection() != null) mc.getConnection().sendCommand(line.substring(1));
		} else {
			MyriadImpl.get().commandManager().sendRaw(line);
		}
	}
}
