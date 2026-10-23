package dev.myriadclient.impl.dev;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code .packets log}: every packet both ways, with its fields, to {@code .minecraft/myriad/packets/<time>.log}, for
 * seeing what the client really sent (and in what order) when a server flags or refuses something. Sent packets are
 * logged as they leave, after every module had its say (a cancelled one isn't sent, so isn't logged); received ones as
 * they arrive, before any module sees them. One line each: {@code t<tick> +<ms> > Name {field=value, ...}}, {@code >}
 * sent and {@code <} received. Only subscribed while logging.
 */
public final class PacketLog {
	/** Received packets left out unless asked for by name or with "all": chunks, entities moving, sounds, particles. */
	private static final Set<String> NOISY = Set.of("LevelChunkWithLightPacket", "ForgetLevelChunkPacket", "LightUpdatePacket",
		"MoveEntityPacket$Pos", "MoveEntityPacket$Rot", "MoveEntityPacket$PosRot", "RotateHeadPacket", "SetEntityMotionPacket",
		"EntityPositionSyncPacket", "TeleportEntityPacket", "SoundPacket", "SoundEntityPacket", "LevelParticlesPacket", "SetTimePacket",
		"BossEventPacket", "PlayerInfoUpdatePacket", "TabListPacket", "UpdateAttributesPacket", "SetEquipmentPacket", "AnimatePacket",
		"ChunkBatchStartPacket", "ChunkBatchFinishedPacket", "SetChunkCacheCenterPacket", "AddEntityPacket", "RemoveEntitiesPacket",
		"SetEntityDataPacket");
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();
	private static @Nullable PacketLog current;

	private final Path file;
	private final BufferedWriter out;
	private final boolean all;
	private final List<String> only;
	private final long startMs = System.currentTimeMillis();
	private volatile long tick;
	private int lines;

	private PacketLog(Path file, boolean all, List<String> only) throws IOException {
		this.file = file;
		this.all = all;
		this.only = only;
		Files.createDirectories(file.getParent());
		out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
		String server = Myriad.server().address();
		out.write("# " + LocalDateTime.now() + " on " + (server == null ? "singleplayer" : server) + " as " + JoinedVersion.get().describe()
			+ (only.isEmpty() ? all ? ", everything" : ", without the noisy received packets" : ", only " + only) + "\n");
	}

	/**
	 * Starts logging. {@code filter}: "all" keeps the noisy received packets; other words keep only packets whose names
	 * contain one of them ("useitem", "action"). Returns what to tell the player.
	 */
	public static String start(List<String> filter) {
		return start(Minecraft.getInstance().gameDirectory.toPath().resolve("myriad").resolve("packets"), filter);
	}

	/** Starts logging to a file in {@code dir}. */
	static synchronized String start(Path dir, List<String> filter) {
		if (current != null) return "Already logging to " + current.file;
		boolean all = filter.stream().anyMatch(w -> w.equalsIgnoreCase("all"));
		List<String> only = filter.stream().filter(w -> !w.equalsIgnoreCase("all")).map(w -> w.toLowerCase(Locale.ROOT)).toList();
		Path file = dir.resolve(LocalDateTime.now().format(FILE_TIME) + ".log");
		try {
			PacketLog log = new PacketLog(file, all, only);
			current = log;
			Myriad.events().subscribe(log);
			return "Logging packets to " + file;
		} catch (IOException e) {
			return "Couldn't log packets: " + e.getMessage();
		}
	}

	public static synchronized String stop() {
		PacketLog log = current;
		if (log == null) return "Not logging packets";
		current = null;
		Myriad.events().unsubscribe(log);
		synchronized (log) {
			try {
				log.out.close();
			} catch (IOException ignored) {
				// What was written is on disk; nothing more to save.
			}
		}
		return log.lines + (log.lines == 1 ? " packet in " : " packets in ") + (System.currentTimeMillis() - log.startMs) / 1000 + " s, logged to " + log.file;
	}

	public static synchronized String status() {
		PacketLog log = current;
		return log == null ? "Not logging packets" : "Logging packets: " + log.lines + " so far, to " + log.file;
	}

	@Subscribe(priority = Priority.LOWEST)
	private void onSend(PacketEvent.Send e) {
		// Logged as made (see clicked): ViaFabricPlus sends clicks to older servers itself, past this event.
		if (e.packet() instanceof ServerboundContainerClickPacket) return;
		write('>', e.packet());
	}

	/**
	 * A click as the game makes it, from the mixin on its click handler: logged there because ViaFabricPlus sends clicks
	 * to servers of 1.21.4 and older itself, past the connection where other packets are seen.
	 */
	public static void clicked(int containerId, int slot, int button, ContainerInput input) {
		PacketLog log = current;
		if (log == null) return;
		log.line('>', "ContainerClickPacket", "{containerId=" + containerId + ", slotNum=" + slot + ", buttonNum=" + button + ", containerInput=" + input + "}");
	}

	@Subscribe(priority = Priority.HIGHEST + 1000)
	private void onReceive(PacketEvent.Receive e) {
		write('<', e.packet());
	}

	/** Counts ticks for the lines, and puts what's written on disk once a second. */
	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (++tick % 20 != 0) return;
		synchronized (this) {
			try {
				out.flush();
			} catch (IOException ignored) {
				// The next flush tries again.
			}
		}
	}

	private void write(char way, Packet<?> packet) {
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> p : bundle.subPackets()) write(way, p);
			return;
		}
		Class<?> type = packet.getClass();
		String name = (type.getEnclosingClass() != null ? type.getEnclosingClass().getSimpleName() + "$" : "") + type.getSimpleName();
		name = name.replace("Serverbound", "").replace("Clientbound", "");
		if (only.isEmpty() && way == '<' && !all && NOISY.contains(name)) return;
		line(way, name, fields(packet));
	}

	/** Writes one line, unless a filter leaves {@code name} out. */
	private void line(char way, String name, String fields) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (!only.isEmpty() && only.stream().noneMatch(lower::contains)) return;
		String line = "t" + tick + " +" + (System.currentTimeMillis() - startMs) + " " + way + " " + name + " " + fields;
		synchronized (this) {
			if (current != this) return;
			try {
				out.write(line);
				out.write('\n');
				lines++;
			} catch (IOException ignored) {
				// A full disk loses lines, not the game.
			}
		}
	}

	// ---- formatting ---------------------------------------------------------------------------------------------

	static String fields(Object o) {
		List<Field> fields = FIELDS.computeIfAbsent(o.getClass(), PacketLog::fieldsOf);
		if (fields.isEmpty()) return "{}";
		StringBuilder s = new StringBuilder("{");
		for (Field f : fields) {
			if (s.length() > 1) s.append(", ");
			s.append(f.getName()).append('=');
			try {
				s.append(value(f.get(o), 0));
			} catch (ReflectiveOperationException | RuntimeException e) {
				s.append('?');
			}
		}
		return s.append('}').toString();
	}

	private static List<Field> fieldsOf(Class<?> type) {
		List<Field> fields = new ArrayList<>();
		for (Class<?> c = type; c != null && c != Object.class && c != Record.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					fields.add(f);
				} catch (RuntimeException notOpen) {
					// A field the module system keeps closed is left out.
				}
			}
		}
		return fields;
	}

	private static String value(@Nullable Object v, int depth) {
		String s = switch (v) {
			case null -> "null";
			case Float f -> String.format(Locale.ROOT, "%.3f", f);
			case Double d -> String.format(Locale.ROOT, "%.4f", d);
			case Number n -> n.toString();
			case Boolean b -> b.toString();
			case Enum<?> e -> e.name();
			case CharSequence cs -> "\"" + cs + "\"";
			case BlockPos p -> p.toShortString();
			case SectionPos p -> "section " + p.x() + "," + p.y() + "," + p.z();
			case Vec3 p -> String.format(Locale.ROOT, "%.3f %.3f %.3f", p.x, p.y, p.z);
			case BlockHitResult h -> h.getBlockPos().toShortString() + " " + h.getDirection() + " at " + value(h.getLocation(), depth + 1) + (h.isInside() ? " inside" : "");
			case ItemStack stack -> stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount()
				+ (stack.isDamageableItem() ? " damage " + stack.getDamageValue() + "/" + stack.getMaxDamage() : "");
			case BlockState state -> state.toString();
			case SynchedEntityData.DataValue<?> d -> "#" + d.id() + "=" + value(d.value(), depth + 1);
			case Component c -> "\"" + c.getString() + "\"";
			case Holder<?> h -> h.getRegisteredName();
			case byte[] b -> "bytes[" + b.length + "]";
			case int[] a -> a.length > 16 ? "ints[" + a.length + "]" : Arrays.toString(a);
			case long[] a -> "longs[" + a.length + "]";
			case Collection<?> c -> c.size() > 12 || depth > 1 ? "[" + c.size() + " items]" : c.stream().map(x -> value(x, depth + 1)).toList().toString();
			case Map<?, ?> m -> m.size() > 12 || depth > 1 ? "{" + m.size() + " entries}" : m.toString();
			case Record r when depth < 2 -> r.getClass().getSimpleName() + fields(r);
			default -> v.toString();
		};
		// Long values (a book's pages, a big map) are cut: the log is for reading.
		return s.length() > 240 ? s.substring(0, 240) + "..." : s;
	}
}
