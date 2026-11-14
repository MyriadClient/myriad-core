/**
 * The local player's actions and reach, measured and done the way the server sees them:
 * {@link dev.myriadclient.api.player.Interactions} (use, attack, swing, the way vanilla does them),
 * {@link dev.myriadclient.api.player.Reach} (what you can reach and see, from the eyes),
 * {@link dev.myriadclient.api.player.Movement} (which way the keys point, speed along it) and
 * {@link dev.myriadclient.api.player.Mining} (break times as the server works them out). Placing and breaking through
 * the shared services is {@code Myriad.placement()} and {@code Myriad.breaking()}.
 */
@NullMarked
package dev.myriadclient.api.player;

import org.jspecify.annotations.NullMarked;
