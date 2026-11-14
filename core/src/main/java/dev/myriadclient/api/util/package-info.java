/**
 * General helpers with no better home: threads and timing, colours, formatting, search and chat text.
 * <ul>
 * <li>{@link dev.myriadclient.api.util.Client}: the player, world and connection, never null (throws instead).</li>
 * <li>{@link dev.myriadclient.api.util.Async} and {@link dev.myriadclient.api.util.Threads}: background work, and which
 * thread you're on.</li>
 * <li>{@link dev.myriadclient.api.util.Ticks}, {@link dev.myriadclient.api.util.TickCached},
 * {@link dev.myriadclient.api.util.TickBudget}, {@link dev.myriadclient.api.util.Timer},
 * {@link dev.myriadclient.api.util.RateCounter}: the tick counter, once-a-tick values, time budgets, cooldowns and
 * per-second rates.</li>
 * <li>{@link dev.myriadclient.api.util.MathUtil}: angles, directions and geometry in Minecraft's conventions
 * ({@code yawTo}, {@code anglesTo}, {@code angleDifference}, {@code direction}, {@code snap}, ...).</li>
 * <li>{@link dev.myriadclient.api.util.ColorUtil}: packed ARGB colours (alpha, mixing, fades, HSV).</li>
 * <li>{@link dev.myriadclient.api.util.Format}: distances, times and amounts written the same way everywhere.</li>
 * <li>{@link dev.myriadclient.api.util.FuzzyMatch}: search scoring for short names.</li>
 * <li>{@link dev.myriadclient.api.util.Texts} and {@link dev.myriadclient.api.util.ChatMessages}: clickable chat text,
 * and who a chat line is from.</li>
 * </ul>
 * The player's actions and reach are in {@code player}, items in {@code item}, blocks and entities in {@code world},
 * packets and web requests in {@code network}, keys in {@code input}, Baritone in {@code integration}.
 */
@NullMarked
package dev.myriadclient.api.util;

import org.jspecify.annotations.NullMarked;
