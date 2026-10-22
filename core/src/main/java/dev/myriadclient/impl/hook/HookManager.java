package dev.myriadclient.impl.hook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.hook.Contribution;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.HookBuilder;
import dev.myriadclient.api.hook.HookCall;
import dev.myriadclient.api.hook.HookHandler;
import dev.myriadclient.api.hook.Hooks;
import dev.myriadclient.api.hook.Match;
import dev.myriadclient.api.hook.Replacement;
import dev.myriadclient.api.hook.ReplacementBuilder;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.event.MyriadEventBus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Hook contributions and service replacements.
 * <p>
 * A hook with no usable contribution runs its default straight away; otherwise the contributions matching a call
 * (caller, its addon, anti-cheat profile, server) are put in order once per caller and place and kept until something
 * changes. Service replacements that apply here run side by side: the most specific without a caller serves everyone,
 * the scoped ones serve their callers, and {@link ServiceRouters} send each call to the right one. The anti-cheat
 * profile and server are checked every tick; a change picks the replacements again.
 */
public final class HookManager implements Hooks {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Hooks");
	private static final String FILE = "hooks.json";
	private static final ContributionImpl[] NONE = new ContributionImpl[0];

	/** Who a call is for, and where: what a chain is cached by. */
	private record Key(@Nullable MyriadId id, @Nullable Class<?> type, @Nullable String addon, AntiCheat.Profile profile, @Nullable String host) {
	}

	/** A service asked for by a caller: what a route is cached by. */
	private record Route(Service<?> service, Key key) {
	}

	/** One hook's usable contributions, and the chains made from them so far. */
	private static final class Chains {
		final List<ContributionImpl> all;
		final Map<Key, ContributionImpl[]> cache = new ConcurrentHashMap<>();

		Chains(List<ContributionImpl> all) {
			this.all = all;
		}

		ContributionImpl[] resolve(Key k) {
			List<ContributionImpl> matched = new ArrayList<>();
			for (ContributionImpl c : all) if (c.match().test(k.id, k.type, k.addon, k.profile, k.host)) matched.add(c);
			if (matched.isEmpty()) return NONE;
			matched.sort(BY_SPECIFICITY);
			return matched.toArray(NONE);
		}
	}

	/** Most specific first; the earlier registered first among equals. */
	private static final Comparator<ContributionImpl> BY_SPECIFICITY =
		Comparator.<ContributionImpl>comparingInt(c -> -c.match().specificity()).thenComparingInt(c -> c.order);

	private final Registry<ContributionImpl> contributions = new Registry<>("hook contribution");
	private final MyriadEventBus events;
	private final Set<MyriadId> disabled = ConcurrentHashMap.newKeySet();
	private final Map<Service<?>, Object> cores = new HashMap<>();
	/** Replacements running here (started, subscribed). */
	private final Set<ContributionImpl> live = ConcurrentHashMap.newKeySet();
	/** Per service: the replacement for every caller, if any. */
	private final Map<Service<?>, ContributionImpl> global = new ConcurrentHashMap<>();
	/** Per service: the running replacements that serve only some callers, most specific first. */
	private final Map<Service<?>, ContributionImpl[]> scoped = new ConcurrentHashMap<>();
	/** Which scoped replacement serves a caller here (empty: none does), worked out once per caller. */
	private final Map<Route, Optional<ContributionImpl>> routes = new ConcurrentHashMap<>();
	private final Map<Service<?>, Object> routers = Map.of(
		Service.ROTATIONS, new ServiceRouters.RotationsRouter(this),
		Service.INVENTORY, new ServiceRouters.InventoryRouter(this),
		Service.PLACEMENT, new ServiceRouters.PlacementRouter(this),
		Service.BREAKING, new ServiceRouters.BreakingRouter(this));
	private volatile Map<Hook<?, ?>, Chains> hooked = Map.of();
	private volatile AntiCheat.Profile profile = AntiCheat.Profile.VANILLA;
	private volatile @Nullable String host;
	private @Nullable AddonStorage storage;
	private final AtomicInteger order = new AtomicInteger();
	/** Which addon a class belongs to (set by core once addons are found), and the answers so far. */
	private volatile @Nullable Function<Class<?>, String> ownerResolver;
	private final Map<Class<?>, String> addonOfClass = new ConcurrentHashMap<>();

	public HookManager(MyriadEventBus events) {
		this.events = events;
		contributions.onAdd(c -> rebuild());
		contributions.onRemove(c -> {
			if (live.contains(c)) deactivate(c);
			rebuild();
		});
	}

	public Registry<ContributionImpl> registry() {
		return contributions;
	}

	/** Core's own implementation of {@code service}, used whenever no replacement is. */
	public <S> void registerCore(Service<S> service, S impl) {
		cores.put(service, impl);
	}

	/** Reads which contributions the player turned off. */
	public void load(AddonStorage storage) {
		this.storage = storage;
		storage.readJson(FILE).ifPresent(json -> {
			if (!json.isJsonObject() || !json.getAsJsonObject().has("disabled")) return;
			for (JsonElement e : json.getAsJsonObject().getAsJsonArray("disabled")) {
				if (e.isJsonPrimitive()) disabled.add(MyriadId.parse(e.getAsString()));
			}
		});
		rebuild();
	}

	private void save() {
		if (storage == null) return;
		JsonArray a = new JsonArray();
		disabled.stream().map(MyriadId::toString).sorted().forEach(a::add);
		var o = new JsonObject();
		o.add("disabled", a);
		storage.writeJson(FILE, o);
	}

	// ---- contributing -------------------------------------------------------------------------------------------------

	public <I, O> Contribution addHook(String owner, Hook<I, O> hook, String label, Match match, HookHandler<I, O> handler) {
		MyriadId id = MyriadId.of(owner, MyriadId.toPath(hook.id().path() + " " + label));
		return contributions.register(new ContributionImpl(this, id, label, match, hook, handler, null, null, order.getAndIncrement()), owner);
	}

	@SuppressWarnings("unchecked")
	public <S> Contribution addReplacement(String owner, Service<S> service, String label, Match match, Function<? super S, ? extends S> factory) {
		MyriadId id = MyriadId.of(owner, MyriadId.toPath(service.id().path() + " " + label));
		Function<Object, Object> f = core -> factory.apply((S) core);
		return contributions.register(new ContributionImpl(this, id, label, match, null, null, service, f, order.getAndIncrement()), owner);
	}

	public <I, O> HookBuilder<I, O> hookBuilder(String owner, Hook<I, O> hook) {
		return new HookBuilder<>() {
			private String label = hook.name();
			private Match match = Match.always();

			@Override
			public HookBuilder<I, O> named(String label) {
				this.label = Objects.requireNonNull(label);
				return this;
			}

			@Override
			public HookBuilder<I, O> when(Match match) {
				this.match = Objects.requireNonNull(match);
				return this;
			}

			@Override
			public Contribution handle(HookHandler<I, O> handler) {
				return addHook(owner, hook, label, match, Objects.requireNonNull(handler));
			}
		};
	}

	public <S> ReplacementBuilder<S> replacementBuilder(String owner, Service<S> service) {
		return new ReplacementBuilder<>() {
			private String label = service.name();
			private Match match = Match.always();

			@Override
			public ReplacementBuilder<S> named(String label) {
				this.label = Objects.requireNonNull(label);
				return this;
			}

			@Override
			public ReplacementBuilder<S> when(Match match) {
				this.match = Objects.requireNonNull(match);
				return this;
			}

			@Override
			public Contribution with(Function<? super S, ? extends S> factory) {
				return addReplacement(owner, service, label, match, Objects.requireNonNull(factory));
			}
		};
	}

	boolean isDisabled(MyriadId id) {
		return disabled.contains(id);
	}

	void setEnabled(ContributionImpl c, boolean enabled) {
		boolean changed = enabled ? disabled.remove(c.id()) : disabled.add(c.id());
		if (!changed) return;
		save();
		rebuild();
	}

	/** After any change: which hooks have usable contributions (chains made afresh), and which replacements are in use. */
	private synchronized void rebuild() {
		Map<Hook<?, ?>, List<ContributionImpl>> byHook = new HashMap<>();
		for (ContributionImpl c : contributions) {
			if (c.hook != null && c.usable()) byHook.computeIfAbsent(c.hook, h -> new ArrayList<>()).add(c);
		}
		Map<Hook<?, ?>, Chains> next = new HashMap<>();
		byHook.forEach((h, list) -> next.put(h, new Chains(List.copyOf(list))));
		hooked = Map.copyOf(next);
		selectServices();
	}

	// ---- calling ------------------------------------------------------------------------------------------------------

	@Override
	public boolean hooked(Hook<?, ?> hook) {
		return hooked.containsKey(hook);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <I, O> O call(Hook<I, O> hook, @Nullable Object caller, I input, Function<? super I, ? extends O> fallback) {
		Chains chains = hooked.get(hook);
		if (chains == null) return fallback.apply(input);
		ContributionImpl[] chain = chains.cache.computeIfAbsent(key(caller), chains::resolve);
		if (chain.length == 0) return fallback.apply(input);
		return new Call<>(chain, 0, caller, input, (Function<I, O>) fallback).run();
	}

	private Key key(@Nullable Object caller) {
		MyriadId id = caller instanceof Identified i ? i.id() : caller instanceof MyriadId m ? m : null;
		Class<?> type = caller == null || caller instanceof MyriadId ? null : caller.getClass();
		String addon = id != null ? id.namespace() : type != null ? addonOf(type) : null;
		return new Key(id, type, addon, profile, host);
	}

	public void setOwnerResolver(Function<Class<?>, String> resolver) {
		ownerResolver = resolver;
		addonOfClass.clear();
	}

	private @Nullable String addonOf(Class<?> type) {
		Function<Class<?>, String> r = ownerResolver;
		return r == null ? null : addonOfClass.computeIfAbsent(type, r);
	}

	/** A call at one point of the chain: running contribution {@code index}, or the default past the end. */
	private final class Call<I, O> implements HookCall<I, O> {
		private final ContributionImpl[] chain;
		private final int index;
		private final @Nullable Object owner;
		private final I input;
		private final Function<I, O> fallback;

		Call(ContributionImpl[] chain, int index, @Nullable Object owner, I input, Function<I, O> fallback) {
			this.chain = chain;
			this.index = index;
			this.owner = owner;
			this.input = input;
			this.fallback = fallback;
		}

		@SuppressWarnings("unchecked")
		O run() {
			for (int i = index; i < chain.length; i++) {
				ContributionImpl c = chain[i];
				if (c.failed) continue;
				try {
					return ((HookHandler<I, O>) c.handler).handle(i == index ? this : new Call<>(chain, i, owner, input, fallback));
				} catch (RuntimeException | LinkageError e) {
					fail(c, e);
				}
			}
			return fallback.apply(input);
		}

		@Override
		public I input() {
			return input;
		}

		@Override
		public @Nullable MyriadId caller() {
			return owner instanceof Identified i ? i.id() : owner instanceof MyriadId m ? m : null;
		}

		@Override
		public @Nullable String callerAddon() {
			return owner == null ? null : key(owner).addon;
		}

		@Override
		public @Nullable Object owner() {
			return owner instanceof MyriadId ? null : owner;
		}

		@Override
		public AntiCheat.Profile profile() {
			return profile;
		}

		@Override
		public @Nullable String server() {
			return host;
		}

		@Override
		public O proceed() {
			return proceed(input);
		}

		@Override
		public O proceed(I input) {
			return new Call<>(chain, index + 1, owner, input, fallback).run();
		}
	}

	/** A contribution threw: it's off until the game restarts, and the player hears about it once. */
	private void fail(ContributionImpl c, Throwable e) {
		if (c.failed) return;
		c.failed = true;
		String addon = contributions.ownerOf(c);
		LOG.error("Hook contribution {} from {} failed and was turned off", c.id(), addon, e);
		if (Myriad.isReady()) Myriad.notifications().warn("Hooks", c.label() + " (" + addon + ") failed and was turned off: " + e);
		rebuild();
	}

	// ---- services -----------------------------------------------------------------------------------------------------

	/** Where we are, refreshed each tick: a change picks the replacements again (and new chains, by key). */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 100)
	private void onTick(TickEvent.Pre e) {
		refreshWhere();
	}

	@Subscribe
	private void onJoin(WorldEvent.Join e) {
		refreshWhere();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		refreshWhere();
	}

	private void refreshWhere() {
		AntiCheat.Profile p = Myriad.antiCheat().profile();
		String h = Match.normalize(Myriad.server().address());
		if (p.equals(profile) && Objects.equals(h, host)) return;
		profile = p;
		host = h;
		synchronized (this) {
			selectServices();
		}
	}

	/**
	 * Starts every replacement that applies here (the server and profile match) and stops the rest; then which one each
	 * service uses for everyone (the most specific without a caller) and which serve only some callers.
	 */
	private void selectServices() {
		List<ContributionImpl> want = new ArrayList<>();
		for (ContributionImpl c : contributions) {
			if (c.service != null && c.usable() && c.match().testWhere(profile, host)) want.add(c);
		}
		for (ContributionImpl c : List.copyOf(live)) if (!want.contains(c)) deactivate(c);
		want.removeIf(c -> !live.contains(c) && !activate(c));
		want.sort(BY_SPECIFICITY);
		for (Service<?> s : Service.all()) {
			ContributionImpl best = null;
			List<ContributionImpl> scopedHere = new ArrayList<>();
			for (ContributionImpl c : want) {
				if (c.service != s) continue;
				if (c.match().hasCaller()) scopedHere.add(c);
				else if (best == null) best = c;
			}
			ContributionImpl was = global.get(s);
			if (best != null) global.put(s, best);
			else global.remove(s);
			if (best != was) LOG.info("{} for everyone now from {}", s.name(), best == null ? "core" : best.label() + " (" + contributions.ownerOf(best) + ")");
			scoped.put(s, scopedHere.toArray(NONE));
		}
		routes.clear();
	}

	private boolean activate(ContributionImpl c) {
		try {
			if (c.instance == null) {
				Object made = c.factory.apply(cores.get(c.service));
				if (!c.service.type().isInstance(made)) throw new IllegalStateException("Not a " + c.service.type().getSimpleName() + ": " + made);
				c.instance = made;
			}
			if (c.instance instanceof Replacement r) r.activated();
			events.subscribe(c.instance);
		} catch (RuntimeException | LinkageError e) {
			c.failed = true;
			LOG.error("Service replacement {} from {} failed to start", c.id(), contributions.ownerOf(c), e);
			if (Myriad.isReady()) Myriad.notifications().warn("Hooks", c.label() + " failed to start: " + e);
			return false;
		}
		live.add(c);
		return true;
	}

	private void deactivate(ContributionImpl c) {
		live.remove(c);
		global.remove(c.service, c);
		routes.clear();
		if (c.instance == null) return;
		events.unsubscribe(c.instance);
		try {
			if (c.instance instanceof Replacement r) r.deactivated();
		} catch (RuntimeException | LinkageError e) {
			LOG.error("Service replacement {} failed to stop", c.id(), e);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <S> S current(Service<S> service) {
		ContributionImpl c = global.get(service);
		return (S) (c != null && c.instance != null ? c.instance : cores.get(service));
	}

	@Override
	@SuppressWarnings("unchecked")
	public <S> S current(Service<S> service, @Nullable Object caller) {
		ContributionImpl c = route(service, caller);
		return c == null ? current(service) : (S) c.instance;
	}

	/** The replacement serving {@code caller} (one scoped to it, else the one for everyone), or empty for core's. */
	public Optional<Contribution> serving(Service<?> service, @Nullable Object caller) {
		ContributionImpl c = route(service, caller);
		return Optional.ofNullable(c != null ? c : global.get(service));
	}

	/** The scoped replacement for {@code caller}, or null. */
	private @Nullable ContributionImpl route(Service<?> service, @Nullable Object caller) {
		ContributionImpl[] here = scoped.get(service);
		if (here == null || here.length == 0 || caller == null) return null;
		Key k = key(caller);
		ContributionImpl c = routes.computeIfAbsent(new Route(service, k), r -> {
			for (ContributionImpl x : here) if (x.match().test(k.id, k.type, k.addon, k.profile, k.host)) return Optional.of(x);
			return Optional.empty();
		}).orElse(null);
		return c == null || c.instance == null ? null : c;
	}

	/** What {@code Myriad.rotations()} and the rest return: sends each call to the implementation for its caller. */
	@SuppressWarnings("unchecked")
	public <S> S router(Service<S> service) {
		return (S) routers.get(service);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <S> S core(Service<S> service) {
		return (S) cores.get(service);
	}

	@Override
	public Optional<Contribution> active(Service<?> service) {
		return Optional.ofNullable(global.get(service));
	}

	@Override
	public List<Contribution> live(Service<?> service) {
		List<Contribution> list = new ArrayList<>();
		for (ContributionImpl c : live) if (c.service == service) list.add(c);
		return list;
	}

	// ---- looking --------------------------------------------------------------------------------------------------------

	@Override
	public List<Contribution> contributions() {
		return List.copyOf(contributions.values());
	}

	@Override
	public List<Contribution> contributions(MyriadId target) {
		List<Contribution> list = new ArrayList<>();
		for (ContributionImpl c : contributions) if (c.target().equals(target)) list.add(c);
		return list;
	}

	@Override
	public List<Contribution> chain(Hook<?, ?> hook, @Nullable Object caller) {
		Chains chains = hooked.get(hook);
		return chains == null ? List.of() : List.of(chains.resolve(key(caller)));
	}

	/** Who contributed {@code c} (an addon's mod id). */
	public String ownerOf(Contribution c) {
		return c instanceof ContributionImpl i ? contributions.ownerOf(i) : null;
	}

	/** The hook or service {@code c} is for, by name. */
	public static String targetName(Contribution c) {
		return c instanceof ContributionImpl i ? i.targetName() : c.target().toString();
	}
}
