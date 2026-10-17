package dev.myriadclient.api.event.events;

import dev.myriadclient.api.module.Module;

public record ModuleToggleEvent(Module module, boolean enabled) {
}
