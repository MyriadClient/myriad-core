package dev.myriadclient.impl.ui;

import dev.myriadclient.api.setting.FileSetting;
import dev.myriadclient.api.util.Async;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opens the system's "open file" dialog for a FileSetting; the game keeps drawing, and the choice arrives when it's made. */
public final class FilePicker {
	private FilePicker() {
	}

	public static void open(FileSetting setting) {
		Path start = setting.path() != null ? setting.path() : setting.directory();
		List<String> extensions = setting.extensions();
		// What the dialog is given has to outlive this call, until it answers.
		List<ByteBuffer> strings = new ArrayList<>();
		SDL_DialogFileFilter.Buffer filters = null;
		if (!extensions.isEmpty()) {
			ByteBuffer name = MemoryUtil.memUTF8(String.join(", ", extensions.stream().map(e -> "." + e).toList()));
			ByteBuffer pattern = MemoryUtil.memUTF8(String.join(";", extensions));
			strings.add(name);
			strings.add(pattern);
			filters = SDL_DialogFileFilter.calloc(1);
			filters.get(0).name(name).pattern(pattern);
		}
		SDL_DialogFileFilter.Buffer shown = filters;
		SDL_DialogFileCallback[] callback = new SDL_DialogFileCallback[1];
		callback[0] = SDL_DialogFileCallback.create((userdata, files, filter) -> {
			String chosen = files == 0 || MemoryUtil.memGetAddress(files) == 0 ? null : MemoryUtil.memUTF8(MemoryUtil.memGetAddress(files));
			if (chosen != null) Async.onRenderThread(() -> setting.set(chosen));
			strings.forEach(MemoryUtil::memFree);
			if (shown != null) shown.free();
			callback[0].free();
		});
		SDLDialog.SDL_ShowOpenFileDialog(callback[0], 0, Minecraft.getInstance().getWindow().handle(), filters,
			start == null ? null : start.toAbsolutePath() + "/", false);
	}
}
