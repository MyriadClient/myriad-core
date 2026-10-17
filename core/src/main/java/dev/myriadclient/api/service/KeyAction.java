package dev.myriadclient.api.service;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;

/** A named, rebindable global key action (e.g. "Open Menu"). */
public final class KeyAction implements Identified {
	private final MyriadId id;
	private final String name;
	private final Keybind defaultBind;
	private final Runnable action;
	private Keybind bind;

	public KeyAction(MyriadId id, String name, Keybind defaultBind, Runnable action) {
		this.id = id;
		this.name = name;
		this.defaultBind = defaultBind;
		this.bind = defaultBind;
		this.action = action;
	}

	@Override
	public MyriadId id() {
		return id;
	}

	public String name() {
		return name;
	}

	public Keybind bind() {
		return bind;
	}

	public Keybind defaultBind() {
		return defaultBind;
	}

	public void setBind(Keybind bind) {
		this.bind = bind == null ? Keybind.NONE : bind;
	}

	public void run() {
		action.run();
	}
}
