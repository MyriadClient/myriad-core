package dev.myriadclient.api.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Text for numbers people read, so every addon writes distances, times and amounts the same way. */
public final class Format {
	private Format() {
	}

	/** A distance in blocks as "12m", "350m", "1.4km" (one block is a metre; km from 1000 blocks). */
	public static String distance(double blocks) {
		return blocks >= 1000 ? String.format("%.1fkm", blocks / 1000) : String.format("%.0fm", blocks);
	}

	/** A duration in milliseconds as "45s", "3m 05s", "2h 14m"; rounds down, negative counts as 0. */
	public static String duration(long ms) {
		long s = Math.max(0, ms) / 1000;
		if (s < 60) return s + "s";
		if (s < 3600) return String.format("%dm %02ds", s / 60, s % 60);
		return String.format("%dh %02dm", s / 3600, s / 60 % 60);
	}

	/** How long ago something happened, given the milliseconds since: "just now", "5s ago", "3m ago", "2h ago", "4d ago". */
	public static String ago(long ms) {
		long s = Math.max(0, ms) / 1000;
		if (s < 2) return "just now";
		if (s < 60) return s + "s ago";
		if (s < 3600) return s / 60 + "m ago";
		if (s < 86400) return s / 3600 + "h ago";
		return s / 86400 + "d ago";
	}

	/** A count shortened for a small space: "999", "1.2k", "45k", "3.1M", "2B"; negative numbers keep their sign. */
	public static String compact(long n) {
		long a = Math.abs(n);
		if (a < 1000) return String.valueOf(n);
		if (a < 1_000_000) return trim(n / 1000.0) + "k";
		if (a < 1_000_000_000) return trim(n / 1_000_000.0) + "M";
		return trim(n / 1_000_000_000.0) + "B";
	}

	private static String trim(double v) {
		return Math.abs(v) < 10 ? String.format("%.1f", v).replace(".0", "") : String.valueOf(Math.round(v));
	}

	/**
	 * A block position as "x y z", the form commands take. Coordinates are private on most servers: keep them out of
	 * chat, toasts and anything else that ends up on screen or in a screenshot.
	 */
	public static String coords(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** A position as "x y z" to one decimal place. The same care as {@link #coords(BlockPos)} applies. */
	public static String coords(Vec3 pos) {
		return String.format("%.1f %.1f %.1f", pos.x, pos.y, pos.z);
	}

	/** A fraction as a whole percentage: "64%" for 0.64, rounded, not clamped. */
	public static String percent(double fraction) {
		return Math.round(fraction * 100) + "%";
	}
}
