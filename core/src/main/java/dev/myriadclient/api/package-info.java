/**
 * Myriad's public API: what addons build on. Start at {@link dev.myriadclient.api.Myriad}, the entry point to every service and
 * registry, and {@link dev.myriadclient.api.addon.MyriadAddon}, an addon's entrypoint. Everything outside this package tree is
 * internal and changes without notice.
 * <p>
 * The packages come in four tiers, which differ in what they're for, not in how they're used or how stable they are
 * (all of it is covered by the same compatibility promise):
 * <ul>
 * <li><b>Kernel</b>, the contract every addon is written against: {@code addon}, {@code module}, {@code setting},
 * {@code event}, {@code registry}, {@code config}, {@code hook}, {@code command}, {@code job}.</li>
 * <li><b>Systems</b>, each arbitrating something shared (one player, one look, one hand, one packet budget, one glide)
 * or running a feature every addon plugs into: {@code service}, {@code elytra}, {@code control}, {@code share}.</li>
 * <li><b>UI</b>, the window manager and the drawing primitives: {@code ui}, {@code ui.widget}, {@code ui.hud},
 * {@code ui.layout}, {@code render.Canvas}.</li>
 * <li><b>Libraries</b>, the shared vocabulary so addons don't each re-derive it, costing nothing until called:
 * {@code combat}, {@code world}, {@code build}, {@code map}, {@code render}, {@code util}.</li>
 * </ul>
 */
package dev.myriadclient.api;
