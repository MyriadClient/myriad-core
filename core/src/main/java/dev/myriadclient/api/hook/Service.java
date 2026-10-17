package dev.myriadclient.api.hook;

import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.MyriadId;

import java.util.List;

/**
 * A core service an addon can take over with its own ({@link AddonContext#replace}):
 * {@code Myriad.rotations()} and the rest then use yours (for everyone, or only for your own modules), so you keep
 * Myriad's menu, binds and modules and bring your own rotations, inventory handling, placement or breaking.
 * <p>
 * Extend the service's {@code Forwarding} class: it hands every call you don't override to the service it was given
 * (core's), so you change only what you do differently, and methods later versions add keep working.
 *
 * <pre>{@code
 * // Only this addon's modules turn this way, and only on Grim; everyone else keeps core's rotations.
 * ctx.replace(Service.ROTATIONS)
 *     .named("Humanised rotations")
 *     .when(Match.addon("my-addon").on(AntiCheat.Profile.GRIM))
 *     .with(MyRotations::new);
 *
 * final class MyRotations extends Rotations.Forwarding {
 *     MyRotations(Rotations core) { super(core); }
 *     @Override public void request(Object owner, float yaw, float pitch, int priority, Options options, Runnable afterSent) {
 *         super.request(owner, yaw + jitter(), pitch, priority, options, afterSent);
 *     }
 * }
 * }</pre>
 * A replacement applies where its {@link Match} says. Without a caller in it, it's for everyone: the most specific
 * such one is what every module gets. With {@link Match#addon} (or a module), it's only for those callers: your
 * addon's modules use your rotations while Essentials and everyone else go on using core's (or the one for everyone),
 * with no conflict. {@code Myriad.rotations()} and the rest send each call that says who it's for (a rotation
 * request, a placement, a held slot: the methods that take an {@code owner}) to the implementation for that caller;
 * calls that don't (where the server thinks you look, which slot it holds) go to the one for everyone.
 * <p>
 * There's still one player, one movement packet and one selected slot: a replacement for some callers should change
 * what its callers ask for and hand it on ({@code super.request(...)}) to core's, which arbitrates between everyone.
 * Only a replacement for everyone should take over sending things itself.
 */
public final class Service<S> {
	public static final Service<Rotations> ROTATIONS = new Service<>("rotations", "Rotations", Rotations.class);
	public static final Service<Inventory> INVENTORY = new Service<>("inventory", "Inventory", Inventory.class);
	public static final Service<Placement> PLACEMENT = new Service<>("placement", "Placement", Placement.class);
	public static final Service<Breaking> BREAKING = new Service<>("breaking", "Breaking", Breaking.class);

	private static final List<Service<?>> ALL = List.of(ROTATIONS, INVENTORY, PLACEMENT, BREAKING);

	private final MyriadId id;
	private final String name;
	private final Class<S> type;

	private Service(String path, String name, Class<S> type) {
		this.id = MyriadId.of("myriad", path);
		this.name = name;
		this.type = type;
	}

	public static List<Service<?>> all() {
		return ALL;
	}

	public MyriadId id() {
		return id;
	}

	public String name() {
		return name;
	}

	public Class<S> type() {
		return type;
	}

	@Override
	public String toString() {
		return "Service[" + id + "]";
	}
}
