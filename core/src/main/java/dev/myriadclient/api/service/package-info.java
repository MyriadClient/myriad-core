/**
 * The services addons share instead of fighting over the same state: rotations, inventory, placing, breaking,
 * building, containers, packet limits, tick speed, server facts, notifications, friends and enemies, and the anti-cheat
 * profile. Reach them through {@link dev.myriadclient.api.Myriad}.
 * <p>
 * Methods that take an {@code Object owner} use it to tell requests apart and end them together: pass your module.
 * Turning a module off cancels its placements, breaks, builds, jobs and tick speed; holds, borrows and masks lapse on
 * their own soon after they stop being renewed; what's left (spared items, the swap bar) is withdrawn by the owner.
 * Unannotated types in this package are never null; what may be is marked {@code @Nullable}.
 */
@NullMarked
package dev.myriadclient.api.service;

import org.jspecify.annotations.NullMarked;
