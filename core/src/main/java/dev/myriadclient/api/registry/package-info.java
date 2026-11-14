/**
 * Ids and registries: {@link dev.myriadclient.api.registry.MyriadId} names everything an addon registers
 * ({@code namespace:path}, the namespace being its mod id), and a {@link dev.myriadclient.api.registry.Registry} lists
 * one kind of thing (modules, commands, panels, themes...), read-only to addons, which register through their
 * {@code AddonContext}.
 */
@NullMarked
package dev.myriadclient.api.registry;

import org.jspecify.annotations.NullMarked;
