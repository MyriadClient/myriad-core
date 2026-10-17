package dev.myriadclient.impl.hook;

import dev.myriadclient.api.hook.Contribution;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.HookHandler;
import dev.myriadclient.api.hook.Match;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/** A hook handler ({@link #hook} set) or a service replacement ({@link #service} set) from one addon. */
final class ContributionImpl implements Contribution {
	private final HookManager manager;
	private final MyriadId id;
	private final String label;
	private final Match match;
	final @Nullable Hook<?, ?> hook;
	final @Nullable HookHandler<?, ?> handler;
	final @Nullable Service<?> service;
	final @Nullable Function<Object, Object> factory;
	/** Registration order: ties between equally specific contributions go to the earlier. */
	final int order;
	/** The replacement, made the first time it's chosen. */
	@Nullable Object instance;
	volatile boolean failed;

	ContributionImpl(HookManager manager, MyriadId id, String label, Match match, @Nullable Hook<?, ?> hook, @Nullable HookHandler<?, ?> handler,
					 @Nullable Service<?> service, @Nullable Function<Object, Object> factory, int order) {
		this.manager = manager;
		this.id = id;
		this.label = label;
		this.match = match;
		this.hook = hook;
		this.handler = handler;
		this.service = service;
		this.factory = factory;
		this.order = order;
	}

	@Override
	public MyriadId id() {
		return id;
	}

	@Override
	public MyriadId target() {
		return hook != null ? hook.id() : service.id();
	}

	/** The hook's or service's name. */
	String targetName() {
		return hook != null ? hook.name() : service.name();
	}

	@Override
	public String label() {
		return label;
	}

	@Override
	public Match match() {
		return match;
	}

	@Override
	public boolean enabled() {
		return !manager.isDisabled(id);
	}

	@Override
	public void setEnabled(boolean enabled) {
		manager.setEnabled(this, enabled);
	}

	@Override
	public boolean failed() {
		return failed;
	}

	boolean usable() {
		return !failed && enabled();
	}

	@Override
	public String toString() {
		return id + " (" + label + ", " + match + ")";
	}
}
