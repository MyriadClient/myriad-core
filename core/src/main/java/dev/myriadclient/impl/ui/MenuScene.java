package dev.myriadclient.impl.ui;

import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.render.title.TitleSceneRenderer;
import dev.myriadclient.impl.render.title.TitleSceneState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What goes on in the themed menus' background (ThemedMenus.background), as on the title screen: motes rising, and you
 * now and then. Mostly you pop in at an edge, look round and duck back out (a peek), from another side and at another
 * angle each time, shy of the mouse; sometimes you stroll along the bottom from one side to the other (a walk), now and
 * then stopping to look out and wave. Either way with something in your hand, or not. Drawn through the title screen's
 * picture (TitleScenePicture) with no scene, over the whole screen, its view centred where you are at its edge, so the
 * edge cuts you off.
 */
final class MenuScene {
	private enum Side {
		LEFT, RIGHT, BOTTOM, TOP
	}

	/** Seconds: popping in, looking round (the shortest and the longest), ducking out. */
	private static final float IN = 0.5f, LOOK_MIN = 3.2f, LOOK_MAX = 5.2f, OUT = 0.35f;
	/** Seconds between one appearance and the next, and before the first once the menus come up. */
	private static final float GAP_MIN = 5, GAP_MAX = 12, FIRST_MIN = 1, FIRST_MAX = 2.5f;
	/** Blocks past the edge: how far in a peek comes (head and shoulders; more with something in hand, so it's seen), and where it starts, out of sight however it leans. */
	private static final float SHOWN = 0.95f, SHOWN_HOLDING = 1.3f, HIDDEN = -0.45f;
	/** Blocks of you above the bottom on a walk: from the waist up. */
	private static final float WALK_SHOWN = 1.4f;
	/** The top of your head, in the picture's heights (TitleSceneState.lookAt). */
	private static final float HEAD_TOP = 2.9f;
	/** How big you are: as if in a picture this much of the screen's shorter side high; the mouse this near where you are at the edge (of it) sends you off. */
	private static final float PICTURE = 0.55f, SHY = 0.4f;
	/** Blocks up that picture, as the title screen's (TitleSceneState.SHOW_HIGH). */
	private static final float PICTURE_BLOCKS = 4.2f;
	/** Seconds a glance or a turn takes, and a swing. */
	private static final float TURN = 0.28f, SWING = 0.32f;
	/** How often it's a walk rather than a peek, and something in hand rather than nothing. */
	private static final float WALKS = 0.3f, HOLDING = 0.7f;
	/** Motes rising through the whole screen. */
	private static final int MOTES = 34;
	/** What might be in your hand. */
	private static final Item[] HELD = {Items.DIAMOND_SWORD, Items.NETHERITE_PICKAXE, Items.TOTEM_OF_UNDYING, Items.TORCH, Items.GOLDEN_APPLE,
		Items.ENDER_PEARL, Items.COOKIE, Items.POPPY, Items.FIREWORK_ROCKET, Items.SHULKER_BOX, Items.LANTERN, Items.CAKE, Items.FISHING_ROD,
		Items.BREAD, Items.SPYGLASS, Items.END_CRYSTAL, Items.OBSIDIAN, Items.COMPASS, Items.BONE, Items.MAP};

	/** Where your head turns, from {@code at} seconds in. */
	private record Glance(float at, float yaw, float pitch) {
	}

	/**
	 * A peek: the side, how far along it (0 to 1), how far you lean from straight in, your turn and the camera's tilt
	 * (degrees), how long you look round, your glances, when you swing (seconds in; -1 for not), and what you hold.
	 */
	private record Peek(Side side, float along, float lean, float turn, float tilt, float look, List<Glance> glances, float swingAt, ItemStack held) {
	}

	/**
	 * A walk: rightwards or not, at {@code speed} blocks a second; how far across you stop (0 to 1; -1 for not) and for
	 * how long (seconds), whether you wave then, and what you hold.
	 */
	private record Walk(boolean rightwards, float speed, float stopAt, float stopFor, boolean wave, ItemStack held) {
	}

	private static final long BORN = System.nanoTime();
	private static @Nullable Peek peek;
	private static @Nullable Walk walk;
	private static @Nullable Side lastSide;
	private static long started, next, lastDrawn;
	/** Seconds into a peek when the mouse came near and you started off; -1 while it hasn't. */
	private static float leaveAt = -1;
	/** You were drawn this frame as the frame's blur (picture). */
	private static boolean frosted;
	/** This frame's: whether frosted buttons may blur you (not over the pause menu's frosted world). */
	private static boolean frost;

	private MenuScene() {
	}

	/**
	 * Draws this frame of it over a {@code w} by {@code h} screen (GUI pixels): the motes (with {@code motes}), then
	 * {@code betweenMotesAndYou} (what goes over them but not over you), and you if you're about, as what frosted buttons
	 * blur (with {@code frost}).
	 */
	static void draw(UiRenderer c, float w, float h, int accent, boolean motes, boolean frost, @Nullable Runnable betweenMotesAndYou) {
		frosted = false;
		MenuScene.frost = frost;
		if (motes) motes(c, w, h, accent);
		if (betweenMotesAndYou != null) betweenMotesAndYou.run();
		long now = System.nanoTime();
		// The menus just came up: you soon, not halfway through something.
		if (now - lastDrawn > 1_000_000_000L) {
			end();
			next = now + seconds(FIRST_MIN, FIRST_MAX);
		}
		lastDrawn = now;
		if (peek == null && walk == null) {
			if (now < next) return;
			ThreadLocalRandom r = ThreadLocalRandom.current();
			if (r.nextFloat() < WALKS) walk = walk(r);
			else peek = peek(r);
			started = now;
			leaveAt = -1;
		}
		float t = (now - started) / 1e9f, size = Math.min(w, h) * PICTURE;
		boolean done = walk != null ? walk(c, walk, t, w, h, size) : peek(c, peek, t, w, h, size);
		if (done) {
			end();
			next = now + seconds(GAP_MIN, GAP_MAX);
		}
	}

	/** Motes, each on its own slow way up the screen; the same ones every time, as the title screen's are. Blurred behind buttons. */
	private static void motes(UiRenderer c, float w, float h, int accent) {
		float seconds = (System.nanoTime() - BORN) / 1e9f;
		for (int i = 0; i < MOTES; i++) {
			float a = MyriadTitleScreen.hash(i, 11), b = MyriadTitleScreen.hash(i, 12), d = MyriadTitleScreen.hash(i, 13);
			float life = (seconds * (0.012f + d * 0.02f) + a) % 1;
			float x = b * w + (float) Math.sin(seconds * 0.3 + i) * 6;
			float y = h + 6 - life * (h + 12);
			float fade = (float) Math.sin(life * Math.PI);
			int alpha = (int) (fade * (35 + d * 55));
			// Behind a button, frosted: a soft smudge of it, as the button's blur would leave it.
			if (ThemedMenus.covered(x, y)) c.shadow(x - 0.5f, y - 0.5f, 1, 1, 0.5f, 4 + d * 2, ColorUtil.withAlpha(accent, alpha / 2));
			else c.circle(x, y, 0.9f + d * 0.7f, ColorUtil.withAlpha(accent, alpha));
		}
	}

	/** A peek's frame at {@code t} seconds in; true once it's over. */
	private static boolean peek(UiRenderer c, Peek p, float t, float w, float h, float size) {
		float leave = leaveAt >= 0 ? leaveAt : IN + p.look;
		if (t >= leave + OUT) return true;

		// Where you come in: around the middle of a side, off to one end of the top or bottom.
		float roll, ex, ey, half = size / 2;
		switch (p.side) {
			case LEFT -> {
				roll = -90;
				ex = 0;
				ey = half + p.along * (h - size);
			}
			case RIGHT -> {
				roll = 90;
				ex = w;
				ey = half + p.along * (h - size);
			}
			case BOTTOM -> {
				roll = 0;
				ex = half + p.along * (w - size);
				ey = h;
			}
			default -> {
				roll = 180;
				ex = half + p.along * (w - size);
				ey = 0;
			}
		}

		// Shy of the mouse once you've had a look round.
		if (leaveAt < 0 && t > IN + 0.25f && t < leave) {
			Minecraft mc = Minecraft.getInstance();
			double mx = mc.mouseHandler.getScaledXPos(mc.getWindow()) - ex, my = mc.mouseHandler.getScaledYPos(mc.getWindow()) - ey;
			if (mx * mx + my * my < size * SHY * size * SHY) {
				leaveAt = t;
				leave = t;
			}
		}

		// In with a bob past where you stop, out quicker.
		float in;
		if (t < IN) in = backOut(t / IN);
		else if (t < leave) in = 1;
		else {
			float from = leave < IN ? backOut(leave / IN) : 1, k = (t - leave) / OUT;
			in = from * (1 - k * k);
		}
		float shown = HIDDEN + ((p.held.isEmpty() ? SHOWN : SHOWN_HOLDING) - HIDDEN) * in;

		// Your head: from glance to glance, each turned to quickly and held.
		float yaw = p.glances.getFirst().yaw * 0.5f, pitch = 0;
		for (Glance g : p.glances) {
			if (t < g.at) break;
			float k = smooth(Math.min(1, (t - g.at) / TURN));
			yaw += (g.yaw - yaw) * k;
			pitch += (g.pitch - pitch) * k;
		}
		float sway = (float) Math.sin(t * 1.7) * 2;
		picture(c, new TitleSceneState.View(null, t, p.turn + sway, p.tilt, roll + p.lean, yaw, pitch, swing(t, p.swingAt), 0, 1, ex / w * 2 - 1,
			1 - ey / h * 2, HEAD_TOP - shown, PICTURE_BLOCKS * h / size, 0, 0, p.held, frost), w, h);
		return false;
	}

	/** A walk's frame at {@code t} seconds in; true once you're off the other side. */
	private static boolean walk(UiRenderer c, Walk k, float t, float w, float h, float size) {
		float block = size / PICTURE_BLOCKS, margin = size * 0.35f, across = w + 2 * margin;
		float speed = k.speed * block, stopAt = k.stopAt * across / speed;
		// How far you've come (pixels), and how stopped you are (0 walking, 1 stood looking out).
		float gone, still = 0, stood = -1;
		if (k.stopAt < 0 || t < stopAt) gone = speed * t;
		else if (t < stopAt + k.stopFor) {
			gone = k.stopAt * across;
			stood = t - stopAt;
			still = smooth(Math.min(1, Math.min(stood, k.stopFor - stood) / TURN));
		} else gone = speed * (t - k.stopFor);
		if (gone >= across) return true;

		float x = k.rightwards ? gone - margin : w + margin - gone;
		float dir = k.rightwards ? 1 : -1;
		// Three-quarters on to where you're going, turned to face out while stood; your head looking about as you go.
		float turn = dir * 72 * (1 - still);
		float headYaw = (float) Math.sin(t * 0.7) * 14 * (1 - still), headPitch = -3 + (float) Math.sin(t * 0.45) * 4;
		float swing = k.wave && stood >= 0 ? swing(stood, k.stopFor / 2 - SWING / 2) : 0;
		// Legs as far through their stride as you've walked (Minecraft's walk animation: 4 a block, as hard as you're quick).
		float walked = (k.stopAt < 0 || t < stopAt ? speed * t : speed * stopAt + (t >= stopAt + k.stopFor ? speed * (t - stopAt - k.stopFor) : 0)) / block;
		picture(c, new TitleSceneState.View(null, t, turn, 5, 0, headYaw, headPitch, swing, 0, 1, x / w * 2 - 1, -1, HEAD_TOP - WALK_SHOWN,
			PICTURE_BLOCKS * h / size, walked * 4, k.speed / 5 * (1 - still), k.held, frost), w, h);
		return false;
	}

	/**
	 * You, over the whole screen: the picture's the screen's size, so frosted buttons over it (ThemedMenus.button) can
	 * blur what's behind them, as the title screen's do over its scene (not over the pause menu's frosted world).
	 */
	private static void picture(UiRenderer c, TitleSceneState.View view, float w, float h) {
		c.titleScene(view, 0, 0, w, h);
		frosted = view.frosted();
	}

	/** Whether you're on screen this frame as what frosted buttons blur (see {@link #picture}). */
	static boolean frosted() {
		return frosted;
	}

	/** How far through a swing started at {@code at} seconds you are at {@code t} (0 outside it). */
	private static float swing(float t, float at) {
		return at >= 0 && t >= at && t < at + SWING ? (t - at) / SWING : 0;
	}

	/** Lets go of the picture after you've gone: nothing's drawn for a while. */
	private static void end() {
		if (peek != null || walk != null) TitleSceneRenderer.release();
		peek = null;
		walk = null;
	}

	/** A peek from another side than the last, the sides more often than the top and bottom (where a menu's title and buttons are). */
	private static Peek peek(ThreadLocalRandom r) {
		Side side;
		do {
			float s = r.nextFloat();
			side = s < 0.36f ? Side.LEFT : s < 0.72f ? Side.RIGHT : s < 0.88f ? Side.BOTTOM : Side.TOP;
		} while (side == lastSide);
		lastSide = side;
		boolean upright = side == Side.LEFT || side == Side.RIGHT;
		// Along a side, around its middle; along the top or bottom, off to one end, clear of the title and buttons.
		float along = upright ? 0.1f + r.nextFloat() * 0.8f : r.nextBoolean() ? 0.05f + r.nextFloat() * 0.25f : 0.7f + r.nextFloat() * 0.25f;
		float lean = (r.nextFloat() * 2 - 1) * (upright ? 22 : 15);
		float turn = (r.nextFloat() * 2 - 1) * 25, tilt = -4 + r.nextFloat() * 14;
		float look = LOOK_MIN + r.nextFloat() * (LOOK_MAX - LOOK_MIN);

		// Three or four glances round, one way then the other, mostly in at the screen, then at you.
		List<Glance> glances = new ArrayList<>();
		int n = 3 + r.nextInt(2);
		float step = look / n, sign = r.nextBoolean() ? 1 : -1;
		for (int i = 0; i < n - 1; i++) {
			glances.add(new Glance(IN * 0.6f + i * step + r.nextFloat() * 0.2f, sign * (25 + r.nextFloat() * 30), -20 + r.nextFloat() * 28));
			sign = -sign;
		}
		float atYou = IN + (n - 1) * step;
		glances.add(new Glance(atYou, (r.nextFloat() * 2 - 1) * 6, -4 + r.nextFloat() * 10));
		float swingAt = r.nextFloat() < 0.4f ? atYou + 0.5f : -1;
		return new Peek(side, along, lean, turn, tilt, look, glances, swingAt, held(r));
	}

	/** A stroll one way or the other, stopping to look out half the time. */
	private static Walk walk(ThreadLocalRandom r) {
		boolean stops = r.nextBoolean();
		return new Walk(r.nextBoolean(), 2.2f + r.nextFloat() * 0.8f, stops ? 0.25f + r.nextFloat() * 0.5f : -1, 1.6f + r.nextFloat() * 1.2f,
			stops && r.nextFloat() < 0.6f, held(r));
	}

	private static ItemStack held(ThreadLocalRandom r) {
		return r.nextFloat() < HOLDING ? new ItemStack(HELD[r.nextInt(HELD.length)]) : ItemStack.EMPTY;
	}

	private static long seconds(float min, float max) {
		return (long) ((min + ThreadLocalRandom.current().nextFloat() * (max - min)) * 1e9);
	}

	/** 0 to 1, past 1 a little on the way. */
	private static float backOut(float x) {
		float c1 = 1.70158f, c3 = c1 + 1, d = x - 1;
		return 1 + c3 * d * d * d + c1 * d * d;
	}

	private static float smooth(float x) {
		return x * x * (3 - 2 * x);
	}
}
