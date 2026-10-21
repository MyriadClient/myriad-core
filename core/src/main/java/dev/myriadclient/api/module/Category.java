package dev.myriadclient.api.module;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.MyriadId;

/**
 * The name tags had before 0.7.0, kept so addons built against older cores keep loading: a category is a {@link Tag}
 * by another name, equal fields mean the same tag. New code uses {@link Tag} and {@link Tags}; where a module shows in
 * the menu is its {@link ModuleGroup}.
 *
 * @deprecated use {@link Tag}
 */
@Deprecated(since = "0.7.0")
public record Category(MyriadId id, String name, String icon, SettingColor tint) implements Identified {
	/** The same tag under its current name. */
	public static Category of(Tag tag) {
		return new Category(tag.id(), tag.name(), tag.icon(), tag.tint());
	}

	/** This category as the tag it is. */
	public Tag tag() {
		return new Tag(id, name, icon, tint);
	}

	/** The category colour right now (ARGB). */
	public int color() {
		return tint.argb();
	}

	@Override
	public String toString() {
		return name;
	}
}
