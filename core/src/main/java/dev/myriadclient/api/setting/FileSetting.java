package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** A file on disk (a schematic, a song, a list to import), chosen with the system's file picker or typed in. */
public class FileSetting extends Setting<String> {
	private final List<String> extensions;
	private final Supplier<Path> directory;

	public FileSetting(String name, String description, String defaultValue, Supplier<Boolean> visible, List<String> extensions, Supplier<Path> directory) {
		super(name, description, defaultValue, visible);
		this.extensions = extensions;
		this.directory = directory;
	}

	/** The chosen file, or null if none is set or the path isn't valid. */
	public @Nullable Path path() {
		if (get().isBlank()) return null;
		try {
			return Path.of(get());
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** Whether a file is chosen and exists. */
	public boolean exists() {
		Path p = path();
		return p != null && Files.isRegularFile(p);
	}

	/** File extensions the picker offers, without dots (empty = any). */
	public List<String> extensions() {
		return extensions;
	}

	/** Where the picker starts, or null for the system default. */
	public @Nullable Path directory() {
		return directory == null ? null : directory.get();
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive()) set(json.getAsString());
	}

	@Override
	public boolean parse(String input) {
		set(input.trim());
		return true;
	}

	@Override
	public String valueString() {
		Path p = path();
		return p == null ? "" : String.valueOf(p.getFileName());
	}

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.FileSetting.Builder, String, FileSetting> {
		private List<String> extensions = List.of();
		private Supplier<Path> directory;

		public Builder(String name) {
			super(name, "");
		}

		/** e.g. {@code extensions("litematic", "schem")}. */
		public dev.myriadclient.api.setting.FileSetting.Builder extensions(String... extensions) {
			this.extensions = List.of(extensions);
			return this;
		}

		/** Where the picker opens, e.g. the game's schematics folder. */
		public dev.myriadclient.api.setting.FileSetting.Builder directory(Supplier<Path> directory) {
			this.directory = directory;
			return this;
		}

		@Override
		protected FileSetting create() {
			return new FileSetting(name, description, defaultValue, visible, extensions, directory);
		}
	}
}
