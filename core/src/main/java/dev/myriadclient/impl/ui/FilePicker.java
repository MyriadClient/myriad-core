package dev.myriadclient.impl.ui;

import dev.myriadclient.api.setting.FileSetting;
import dev.myriadclient.api.util.Async;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.List;

/** Opens the system's "open file" dialog for a FileSetting, off the render thread so the game keeps drawing. */
public final class FilePicker {
	private FilePicker() {
	}

	public static void open(FileSetting setting) {
		Path start = setting.path() != null ? setting.path() : setting.directory();
		List<String> extensions = setting.extensions();
		Async.run(() -> {
			String chosen;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = null;
				if (!extensions.isEmpty()) {
					filters = stack.mallocPointer(extensions.size());
					for (String ext : extensions) filters.put(stack.UTF8("*." + ext));
					filters.flip();
				}
				String description = extensions.isEmpty() ? null : String.join(", ", extensions.stream().map(e -> "." + e).toList());
				chosen = TinyFileDialogs.tinyfd_openFileDialog(setting.name(), start == null ? null : start.toAbsolutePath() + "/", filters, description, false);
			}
			if (chosen != null) Async.onRenderThread(() -> setting.set(chosen));
		});
	}
}
