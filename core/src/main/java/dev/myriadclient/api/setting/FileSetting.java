package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * A file on disk (a schematic, a song, a list to import), chosen with the system's file picker or typed in. The value is
 * the path as text, empty for none; {@link #path()} gives it as a {@link Path}. Protected from shared configs unless the
 * builder says {@code protect(false)}, as a path can name the player.
 */
public class FileSetting extends Setting<String> {
	private final List<String> extensions;
	private final @Nullable Supplier<Path> directory;

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#file(String)}, or call this from a
	 * subclass.
	 *
	 * @param extensions what the picker offers, without dots; empty for any file
	 * @param directory  where the picker opens; null for the system's default
	 */
	public FileSetting(String name, @Nullable String description, String defaultValue, @Nullable Supplier<Boolean> visible, List<String> extensions, @Nullable Supplier<Path> directory) {
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

	/** Builds a {@link FileSetting}; no file by default. */
	public static class Builder extends Setting.Builder<FileSetting.Builder, String, FileSetting> {
		private List<String> extensions = List.of();
		private @Nullable Supplier<Path> directory;

		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#file(String)}, which adds it to the
		 * group.
		 */
		public Builder(String name) {
			super(name, "");
			// A path can name you (your home folder): private unless the addon says otherwise.
			protect = true;
		}

		/** e.g. {@code extensions("litematic", "schem")}. */
		public FileSetting.Builder extensions(String... extensions) {
			this.extensions = List.of(extensions);
			return this;
		}

		/** Where the picker opens, e.g. the game's schematics folder. */
		public FileSetting.Builder directory(Supplier<Path> directory) {
			this.directory = directory;
			return this;
		}

		@Override
		protected FileSetting create() {
			return new FileSetting(name, description, defaultValue, visible, extensions, directory);
		}
	}
}
