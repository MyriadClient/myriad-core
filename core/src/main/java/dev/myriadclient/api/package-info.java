/**
 * Myriad's public API: what addons build on. Start at {@link dev.myriadclient.api.Myriad}, the entry point to every
 * service and registry, and {@link dev.myriadclient.api.addon.MyriadAddon}, an addon's entrypoint. Everything outside
 * this package tree ({@code dev.myriadclient.impl}) is internal and changes without notice; the addon plugin's
 * {@code checkApiOnly} fails a build that reaches into it.
 * <p>
 * The packages come in four tiers, which differ in what they're for, not in how stable they are (all of it is covered
 * by the same compatibility promise):
 * <ul>
 * <li><b>Kernel</b>, the contract every addon is written against: {@code addon}, {@code module}, {@code setting},
 * {@code event}, {@code registry}, {@code config}, {@code hook}, {@code command}, {@code job}, {@code annotation}.</li>
 * <li><b>Systems</b>, each arbitrating something shared (one player, one look, one hand, one packet budget, one glide)
 * or running a feature every addon plugs into: {@code service}, {@code elytra}, {@code control}, {@code share},
 * {@code input}.</li>
 * <li><b>UI</b>, the window manager and 2D drawing: {@code ui}, {@code ui.widget}, {@code ui.hud}, {@code ui.layout},
 * {@code ui.draw}.</li>
 * <li><b>Libraries</b>, the shared vocabulary so addons don't each re-derive it, costing nothing until called:
 * {@code player}, {@code item}, {@code world}, {@code combat}, {@code build}, {@code render}, {@code network},
 * {@code map}, {@code integration}, {@code util}.</li>
 * </ul>
 * <h2>Conventions</h2>
 * <ul>
 * <li><b>Threads:</b> call the API on the render thread (the game's own) unless a type or method is marked
 * {@link dev.myriadclient.api.annotation.AnyThread}; see {@link dev.myriadclient.api.annotation.RenderThread}.</li>
 * <li><b>Null:</b> a parameter or return that may be null is marked {@code @Nullable}; everything else isn't null.</li>
 * <li><b>Owners:</b> methods that take an {@code Object owner} (placements, holds, borrows, rotations) use it to tell
 * requests apart and to release them together: pass your module ({@code this}), and its requests end when it's
 * turned off.</li>
 * <li><b>Options:</b> services take an immutable {@code Options} object: start from a preset ({@code forServer()},
 * {@code DEFAULT}, {@code STRICT}) and change what you need with its {@code with...} methods, so options added later
 * keep their defaults in your code.</li>
 * <li><b>Since:</b> what a 2.x release adds is marked {@code @ApiStatus.AvailableSince("2.x")}.</li>
 * </ul>
 */
@NullMarked
package dev.myriadclient.api;

import org.jspecify.annotations.NullMarked;
