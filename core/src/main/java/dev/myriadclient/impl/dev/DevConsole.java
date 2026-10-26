package dev.myriadclient.impl.dev;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.command.CommandManager;
import dev.myriadclient.impl.service.InventoryManager;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Dev runs only ({@code -Dmyriad.devConsole=<file>}): runs the lines appended to a file, one script step at a time, so
 * a test can drive the game from outside it (the Grim test server's {@code ./client} script writes here). Output goes to
 * the game log, prefixed {@code [dev]}.
 *
 * <pre>
 * .toggle packet_mine          a Myriad command
 * /gamemode survival           a server command, sent as the player
 * hold forward,sprint,jump 40  hold movement keys for that many ticks
 * look 90 30                   set yaw and pitch
 * select 3                     hotbar slot (1-9)
 * attack | use                 a left or right click on what the crosshair is on
 * middle                       a middle click (press and release), as modules see it
 * mouse 4 press|release|click  a mouse button (0 left, 1 right, 2 middle, 3+ side buttons) through vanilla's own
 *                              handler, as a real one arrives (other mods' hooks on it run too)
 * scroll 1                     the wheel, notches up (negative for down), the same way
 * wait 20                      pause the script for that many ticks
 * respawn                      respawn if dead (the death screen is closed)
 * connect localhost:25565      join a server (from a disconnect screen too: steps run outside a world)
 * selftest [ticks] [-module]   every module on for that many ticks (20) and off again, reporting failures; the
 *                              script waits for it. -module skips one (e.g. -auto_disconnect)
 * status                       log position, motion, rotation and state
 * echo text                    log the text (scripts use it to know they're done)
 * screenshot name              save screenshots/name.png (README pictures are taken this way)
 * menu open|close              the Myriad menu; workspace 3 switches; panel myriad:theme opens a panel
 * hud myriad-essentials:fps     puts a HUD element on the HUD (at its default spot)
 * launcher [query]             the launcher with the query typed; wm launcher|layout|close|... runs a desktop binding
 * settings kill_aura           a module's settings in a window of its own
 * </pre>
 */
public final class DevConsole {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Dev");

	private enum Key {
		FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final Path inbox;
	private final CommandManager commands;
	private final Deque<String> pending = new ArrayDeque<>();
	private final Set<Key> held = EnumSet.noneOf(Key.class);
	private long read;
	private int waitTicks, holdTicks, devTicks;
	private boolean sprintKeyDown;
	private SelfTest selfTest;

	public DevConsole(Path inbox, CommandManager commands) {
		this.inbox = inbox;
		this.commands = commands;
		try {
			// Start empty, so lines from an earlier run aren't replayed.
			Files.createDirectories(inbox.toAbsolutePath().getParent());
			Files.writeString(inbox, "");
		} catch (IOException e) {
			LOG.error("[dev] can't use {}", inbox, e);
		}
		LOG.info("[dev] console reading {}", inbox.toAbsolutePath());
	}

	/**
	 * First thing in the tick, before every module and core service, as real input arrives between ticks: a module
	 * toggled here doesn't run half of this tick.
	 */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 1000)
	private void onTick(TickEvent.Pre e) {
		devTicks++;
		poll();
		if (holdTicks > 0 && --holdTicks == 0) held.clear();
		// The sprint key itself too: ViaFabricPlus, joined as an older version, starts sprinting from the key as those did.
		boolean sprint = held.contains(Key.SPRINT);
		if (sprint != sprintKeyDown) {
			sprintKeyDown = sprint;
			mc.options.keySprint.setDown(sprint);
		}
		if (selfTest != null) {
			if (selfTest.tick()) return;
			selfTest = null;
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		while (!pending.isEmpty() && waitTicks == 0 && selfTest == null) run(pending.poll());
	}

	@Subscribe(priority = Priority.HIGHEST)
	private void onInput(InputEvent e) {
		if (held.isEmpty()) return;
		e.forward |= held.contains(Key.FORWARD);
		e.backward |= held.contains(Key.BACK);
		e.left |= held.contains(Key.LEFT);
		e.right |= held.contains(Key.RIGHT);
		e.jump |= held.contains(Key.JUMP);
		e.sneak |= held.contains(Key.SNEAK);
		e.sprint |= held.contains(Key.SPRINT);
	}

	/** Reads whole lines appended since last time. */
	private void poll() {
		try (RandomAccessFile f = new RandomAccessFile(inbox.toFile(), "r")) {
			if (f.length() < read) read = 0;
			if (f.length() == read) return;
			byte[] bytes = new byte[(int) (f.length() - read)];
			f.seek(read);
			f.readFully(bytes);
			String text = new String(bytes, StandardCharsets.UTF_8);
			int end = text.lastIndexOf('\n');
			if (end < 0) return;
			read += text.substring(0, end + 1).getBytes(StandardCharsets.UTF_8).length;
			for (String line : text.substring(0, end).split("\n")) if (!line.isBlank()) pending.add(line.strip());
		} catch (IOException ignored) {
		}
	}

	/** {@code slot}: an inventory index, or c and the open container's screen slot (c4). */
	private void openItem(String slot, boolean air, int keepTicks) {
		long started = Clock.millis();
		int startTick = devTicks;
		Placement.Options options = Placement.Options.forServer().withAirPlace(air);
		var opening = slot.startsWith("c") ? Myriad.containers().current()
			.map(from -> Myriad.containers().openItem(this, from, Integer.parseInt(slot.substring(1)), 100, options))
			.orElse(CompletableFuture.failedFuture(new IllegalStateException("no container open")))
			: Myriad.containers().openItem(this, Integer.parseInt(slot), 100, options);
		opening.whenComplete((session, t) -> {
			if (t != null) {
				LOG.info("[dev] openitem failed: {}", t.getCause() == null ? t.getMessage() : t.getCause().getMessage());
				return;
			}
			LOG.info("[dev] openitem open at {} after {} ms, {} ticks", session.where().toShortString(), Clock.millis() - started, devTicks - startTick);
			int until = devTicks + keepTicks;
			Myriad.jobs().start(this, Step.until("Holding", () -> devTicks >= until, keepTicks + 20).recover(why -> Step.nothing()))
				.result().whenComplete((r, t2) -> session.finish().whenComplete((v, t3) ->
					LOG.info("[dev] openitem back{}", t3 == null ? "" : ": " + (t3.getCause() == null ? t3.getMessage() : t3.getCause().getMessage()))));
		});
	}

	private void run(String line) {
		LOG.info("[dev] > {}", line);
		try {
			if (line.startsWith(".")) {
				commands.execute(line.substring(1));
				return;
			}
			if (line.startsWith("/")) {
				if (mc.getConnection() != null) mc.getConnection().sendCommand(line.substring(1));
				return;
			}
			String[] a = line.split("\\s+");
			switch (a[0].toLowerCase(Locale.ROOT)) {
				case "hold" -> {
					held.clear();
					for (String k : a[1].split(",")) held.add(Key.valueOf(k.toUpperCase(Locale.ROOT)));
					holdTicks = a.length > 2 ? Integer.parseInt(a[2]) : 1;
				}
				case "release" -> {
					held.clear();
					holdTicks = 0;
				}
				case "look" -> {
					if (mc.player == null) return;
					mc.player.setYRot(Float.parseFloat(a[1]));
					mc.player.setXRot(Float.parseFloat(a[2]));
				}
				case "select" -> Myriad.inventory().select(Integer.parseInt(a[1]) - 1);
				case "move" -> LOG.info("[dev] move {}", Myriad.inventory().move(Integer.parseInt(a[1]), Integer.parseInt(a[2])));
				case "uclick" -> {
					// As your own click in the inventory screen: it waits for a tick the server takes it, as those do.
					var inv = (InventoryManager) Myriad.inventory();
					int menu = mc.player.inventoryMenu.containerId, slot = Integer.parseInt(a[1]), button = Integer.parseInt(a[2]);
					var type = ContainerInput.valueOf(a[3].toUpperCase(Locale.ROOT));
					boolean waits = inv.holdScreenClick(menu, slot, button, type);
					if (!waits) mc.gameMode.handleContainerInput(menu, slot, button, type, mc.player);
					LOG.info("[dev] uclick {}", waits ? "waits" : "sent");
				}
				case "attack" -> attack();
				// As your own right click (a module's slot hold steps aside for it).
				case "use" -> asClick(this::use);
				case "middle" -> {
					Myriad.events().post(new MouseButtonEvent(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, GLFW.GLFW_PRESS, 0, false));
					Myriad.events().post(new MouseButtonEvent(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, GLFW.GLFW_RELEASE, 0, false));
				}
				// "mouse 0 press shift": a press held with Shift down (a drag keeps the press's modifiers).
				case "mouse" -> {
					int button = Integer.parseInt(a[1]);
					String how = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "click";
					int mods = a.length > 3 ? modifiers(a[3]) : 0;
					if (!how.equals("release")) mouseButton(button, GLFW.GLFW_PRESS, mods);
					if (!how.equals("press")) mouseButton(button, GLFW.GLFW_RELEASE, mods);
				}
				// Real key presses through vanilla's KeyboardHandler: "key 1 alt", "key space alt", "key enter".
				// "key g press" ... "key g release" holds it in between (a wheel's bind, say).
				case "key" -> {
					int key = keyCode(a[1]);
					String how = a.length > 2 && (a[a.length - 1].equals("press") || a[a.length - 1].equals("release")) ? a[a.length - 1] : "click";
					int mods = a.length > 2 && !a[2].equals(how) ? modifiers(a[2]) : 0;
					if (!how.equals("release")) keyboard(GLFW.GLFW_PRESS, key, mods);
					if (!how.equals("press")) keyboard(GLFW.GLFW_RELEASE, key, mods);
				}
				case "type" -> {
					for (int cp : line.substring(4).strip().codePoints().toArray()) {
						invokeKeyboard("charTyped", new Class<?>[]{long.class, CharacterEvent.class},
							mc.getWindow().handle(), new CharacterEvent(cp));
					}
				}
				// Moves the cursor to x y in window pixels, as screenshots show them: "cursor 400 220"; or over slot n of the
				// open container screen, wherever it is: "cursor slot 10".
				case "cursor" -> {
					double[] at = a[1].equals("slot") ? slotPixels(Integer.parseInt(a[2])) : new double[]{Double.parseDouble(a[1]), Double.parseDouble(a[2])};
					invokeMouse("onMove", new Class<?>[]{long.class, double.class, double.class}, mc.getWindow().handle(), at[0], at[1]);
				}
				case "scroll" -> invokeMouse("onScroll", new Class<?>[]{long.class, double.class, double.class},
					mc.getWindow().handle(), 0.0, Double.parseDouble(a[1]));
				case "connect" -> {
					if (mc.level != null) return;
					ServerData data = new ServerData("Dev", a[1], ServerData.Type.OTHER);
					ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(a[1]), data, false, null);
				}
				case "wait" -> waitTicks = Integer.parseInt(a[1]);
				case "respawn" -> {
					if (mc.player != null && mc.player.isDeadOrDying()) {
						mc.player.respawn();
						mc.gui.setScreen(null);
					}
				}
				case "selftest" -> {
					int ticks = 20;
					Set<String> skip = new HashSet<>();
					for (int i = 1; i < a.length; i++) {
						if (a[i].startsWith("-")) skip.add(a[i].substring(1));
						else ticks = Integer.parseInt(a[i]);
					}
					selfTest = new SelfTest(ticks, skip);
					selfTest.start();
				}
				// "openitem 14 air 40": opens the box at inventory index 14 (air placing where it may), logs how long it took to
				// open, and puts it back after 40 ticks.
				case "openitem" -> openItem(a[1], a.length > 2 && a[2].equals("air"), a.length > 3 ? Integer.parseInt(a[3]) : 20);
				case "status" -> status();
				// The swap bar's tabs, and a switch to one by its place on the bar (see ContainerTabs).
				case "themed" -> MyriadImpl.get().windowManager().theme().themedScreens.set(a[1].equals("on"));
				case "tabs" -> LOG.info("[dev] tabs: {}", MyriadImpl.get().containerTabs().tabs().stream()
					.map(t -> (MyriadImpl.get().containerTabs().isCurrent(t) ? "*" : "") + (t.you() ? "you" : t.pos().toShortString() + (t.box() != null ? " box" : ""))).toList());
				case "tab" -> {
					var tabs = MyriadImpl.get().containerTabs().tabs();
					int i = Integer.parseInt(a[1]);
					LOG.info("[dev] tab {}: {}", i, i < tabs.size() && MyriadImpl.get().containerTabs().switchTo(tabs.get(i)));
				}
				// Closes whatever screen is open (a container's as the game does, telling the server).
				case "closescreen" -> {
					if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
					else if (mc.gui.screen() != null) mc.gui.setScreen(null);
				}
				case "echo" -> LOG.info("[dev] echo {}", line.substring(4).strip());
				case "screenshot" -> Screenshot.grab(mc.gameDirectory, a[1] + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
				});
				case "menu" -> {
					if (a[1].equalsIgnoreCase("open")) Myriad.ui().open();
					else Myriad.ui().close();
				}
				case "workspace" -> Myriad.ui().switchWorkspace(Integer.parseInt(a[1]));
				case "panel" -> Myriad.ui().openPanel(MyriadId.parse(a[1]));
				case "hud" -> Myriad.panels().get(MyriadId.parse(a[1])).ifPresent(((WindowManager) Myriad.ui())::addHudElement);
				case "launcher" -> ((WindowManager) Myriad.ui()).showLauncher(line.length() > 8 ? line.substring(8).strip() : "");
				case "wm" -> ((WindowManager) Myriad.ui()).runBind(a[1]);
				case "settings" -> Myriad.modules().byName(a[1]).ifPresentOrElse(m -> CorePanels.openModuleSettings(m, true),
					() -> LOG.warn("[dev] no module {}", a[1]));
				default -> LOG.warn("[dev] unknown: {}", line);
			}
		} catch (RuntimeException ex) {
			LOG.warn("[dev] failed: {} ({})", line, ex.toString());
		}
	}

	private void mouseButton(int button, int action, int mods) {
		invokeMouse("onButton", new Class<?>[]{long.class, MouseButtonInfo.class, int.class}, mc.getWindow().handle(), new MouseButtonInfo(button, mods), action);
	}

	/** Calls one of vanilla's (private) GLFW input callbacks on the mouse handler. */
	private void keyboard(int action, int key, int mods) {
		invokeKeyboard("keyPress", new Class<?>[]{long.class, int.class, KeyEvent.class},
			mc.getWindow().handle(), action, new KeyEvent(key, GLFW.glfwGetKeyScancode(key), mods));
	}

	private static int keyCode(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return switch (n) {
			case "space" -> GLFW.GLFW_KEY_SPACE;
			case "enter" -> GLFW.GLFW_KEY_ENTER;
			case "escape", "esc" -> GLFW.GLFW_KEY_ESCAPE;
			case "tab" -> GLFW.GLFW_KEY_TAB;
			case "backspace" -> GLFW.GLFW_KEY_BACKSPACE;
			case "up" -> GLFW.GLFW_KEY_UP;
			case "down" -> GLFW.GLFW_KEY_DOWN;
			case "left" -> GLFW.GLFW_KEY_LEFT;
			case "right" -> GLFW.GLFW_KEY_RIGHT;
			case "left_alt" -> GLFW.GLFW_KEY_LEFT_ALT;
			default -> {
				char c = n.charAt(0);
				if (n.length() == 1 && c >= '0' && c <= '9') yield GLFW.GLFW_KEY_0 + (c - '0');
				if (n.length() == 1 && c >= 'a' && c <= 'z') yield GLFW.GLFW_KEY_A + (c - 'a');
				yield Integer.parseInt(n);
			}
		};
	}

	private static int modifiers(String names) {
		int mods = 0;
		for (String m : names.toLowerCase(Locale.ROOT).split("\\+")) {
			mods |= switch (m) {
				case "alt" -> GLFW.GLFW_MOD_ALT;
				case "shift" -> GLFW.GLFW_MOD_SHIFT;
				case "ctrl" -> GLFW.GLFW_MOD_CONTROL;
				case "super" -> GLFW.GLFW_MOD_SUPER;
				default -> throw new IllegalArgumentException("unknown modifier " + m);
			};
		}
		return mods;
	}

	private void invokeKeyboard(String name, Class<?>[] types, Object... args) {
		try {
			Method m = KeyboardHandler.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			m.invoke(mc.keyboardHandler, args);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void invokeMouse(String name, Class<?>[] types, Object... args) {
		try {
			Method m = MouseHandler.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			m.invoke(mc.mouseHandler, args);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void asClick(Runnable click) {
		var inventory = MyriadImpl.get().inventoryManager();
		inventory.userClick(true);
		try {
			click.run();
		} finally {
			inventory.userClick(false);
		}
	}

	private void attack() {
		if (mc.player == null || mc.gameMode == null) return;
		HitResult hit = mc.hitResult;
		if (hit instanceof EntityHitResult e) mc.gameMode.attack(mc.player, e.getEntity());
		else if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) mc.gameMode.startDestroyBlock(b.getBlockPos(), b.getDirection());
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	private void use() {
		if (mc.player == null || mc.gameMode == null) return;
		if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
			if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, b).consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
		} else {
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		}
	}

	private void status() {
		var p = mc.player;
		if (p == null) {
			LOG.info("[dev] status: not in a world");
			return;
		}
		var v = p.getDeltaMovement();
		String target = switch (mc.hitResult) {
			case BlockHitResult b when b.getType() == HitResult.Type.BLOCK -> "block " + b.getBlockPos().toShortString() + " " + b.getDirection();
			case EntityHitResult en -> "entity " + en.getEntity().getName().getString();
			case null, default -> "none";
		};
		LOG.info("[dev] status: pos {} {} {} motion {} {} {} ({} b/s) rot {} {} ground={} gliding={} sprinting={} sneaking={} health={} slot={} serverSlot={} item={} mode={} target={} fps={} ping={} carried={} screen={}",
			fmt(p.getX()), fmt(p.getY()), fmt(p.getZ()), fmt(v.x), fmt(v.y), fmt(v.z), fmt(v.length() * 20), fmt(p.getYRot()), fmt(p.getXRot()),
			p.onGround(), p.isFallFlying(), p.isSprinting(), p.isShiftKeyDown(), fmt(p.getHealth()), p.getInventory().getSelectedSlot() + 1, Myriad.inventory().serverSlot() + 1, p.getMainHandItem().getItem(),
			mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode().getName(), target, mc.getFps(), Myriad.server().ping(),
			p.containerMenu.getCarried().isEmpty() ? "none" : p.containerMenu.getCarried().getCount() + " " + p.containerMenu.getCarried().getItem(),
			mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
	}

	/** The window pixel at the middle of slot {@code index} of the open container screen. */
	private double[] slotPixels(int index) {
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) throw new IllegalStateException("no container screen open");
		var slot = screen.getMenu().getSlot(index);
		try {
			var left = AbstractContainerScreen.class.getDeclaredField("leftPos");
			var top = AbstractContainerScreen.class.getDeclaredField("topPos");
			left.setAccessible(true);
			top.setAccessible(true);
			double scale = mc.getWindow().getGuiScale();
			return new double[]{(left.getInt(screen) + slot.x + 8) * scale, (top.getInt(screen) + slot.y + 8) * scale};
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String fmt(double d) {
		return String.format(Locale.ROOT, "%.3f", d);
	}
}
