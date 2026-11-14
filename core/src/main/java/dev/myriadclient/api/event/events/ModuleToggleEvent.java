package dev.myriadclient.api.event.events;

import dev.myriadclient.api.module.Module;

/**
 * A module was turned on or off, by the player, a bind, a command or code. Posted on the render thread after the
 * module's own {@code onEnable} or {@code onDisable} has run (and, when turning off, after its handlers, jobs and
 * service requests were released). Not posted when enabling failed. Not cancellable.
 *
 * @param module  the module
 * @param enabled whether it's now on
 */
public record ModuleToggleEvent(Module module, boolean enabled) {
}
