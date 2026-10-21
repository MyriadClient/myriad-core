package dev.myriadclient.api.module;

import java.util.List;

/**
 * The shared tags under their names before 0.7.0, kept so addons built against older cores keep loading.
 *
 * @deprecated use {@link Tags}
 */
@Deprecated(since = "0.7.0")
public final class Categories {
	public static final Category COMBAT = Category.of(Tags.COMBAT);
	public static final Category MOVEMENT = Category.of(Tags.MOVEMENT);
	public static final Category RENDER = Category.of(Tags.RENDER);
	public static final Category PLAYER = Category.of(Tags.PLAYER);
	public static final Category WORLD = Category.of(Tags.WORLD);
	public static final Category MISC = Category.of(Tags.MISC);

	public static final List<Category> DEFAULTS = List.of(COMBAT, MOVEMENT, RENDER, PLAYER, WORLD, MISC);

	private Categories() {
	}
}
