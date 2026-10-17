package dev.myriadclient.impl;

import dev.myriadclient.api.event.events.GameReadyEvent;
import dev.myriadclient.api.event.events.Render2DEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.ShutdownEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.render.Projection;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.dev.DevConsole;
import dev.myriadclient.impl.keybind.VanillaKeys;
import dev.myriadclient.impl.render.HighlightRenderer;
import dev.myriadclient.impl.render.MeshRenderer;
import dev.myriadclient.impl.render.MyriadPipelines;
import dev.myriadclient.impl.render.WorldRenderQueue;
import dev.myriadclient.impl.ui.MyriadTitleScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.nio.file.Path;

/** Fabric entrypoint: boots Myriad and bridges Fabric API callbacks onto the Myriad event bus. */
public class MyriadClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MyriadImpl myriad = MyriadImpl.bootstrap();
		VanillaKeys.register();
		MyriadPipelines.init();
		var bus = myriad.events();

		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			Ticks.advance();
			bus.post(TickEvent.Pre.INSTANCE);
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			bus.post(TickEvent.Post.INSTANCE);
			myriad.windowManager().tick();
			VanillaKeys.sync();
			myriad.configImpl().tick();
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("myriad", "hud"), (ctx, tickCounter) -> {
			if (bus.hasListeners(Render2DEvent.class)) {
				myriad.windowManager().draw(ctx, canvas -> bus.post(new Render2DEvent(ctx, canvas, tickCounter.getGameTimeDeltaPartialTick(false))));
			}
			myriad.windowManager().renderHud(ctx);
		});

		// Myriad's own title screen in place of Minecraft's (Theme → Preferences → Title Screen).
		bus.listen(ScreenOpenEvent.class, e -> {
			// Closing a screen with no world open is Minecraft's way back to its title screen too: it makes one after this.
			boolean title = e.screen() == null ? Minecraft.getInstance().level == null : e.screen().getClass() == TitleScreen.class;
			if (title && myriad.windowManager().theme().titleScreen.get()) {
				e.setScreen(new MyriadTitleScreen(myriad.windowManager()));
			}
		});
		// With it off, Minecraft's shows Myriad's logo, version and how to open the menu in a corner.
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof TitleScreen) ScreenEvents.afterExtract(screen).register((s, ctx, mx, my, delta) -> myriad.windowManager().renderTitleScreen(ctx));
		});

		// The camera is settled once the level is extracted, before the HUD is: labels projected from the HUD use it.
		LevelExtractionEvents.END_EXTRACTION.register(ctx -> {
			var camera = ctx.levelState().cameraRenderState;
			Projection.update(camera.viewRotationMatrix, camera.projectionMatrix, camera.pos);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			Minecraft mc = Minecraft.getInstance();
			var camera = mc.gameRenderer.mainCamera();
			WorldRenderQueue.INSTANCE.begin(ctx.levelState().cameraRenderState.pos);
			// Same tick delta the entities were drawn with this frame.
			float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			if (bus.hasListeners(Render3DEvent.class)) {
				bus.post(new Render3DEvent(ctx.poseStack(), camera, tickDelta, ctx.submitNodeCollector()));
			}
			WorldRenderQueue.INSTANCE.submit(ctx.submitNodeCollector(), ctx.poseStack(), camera);
			HighlightRenderer.INSTANCE.collectShapes(ctx.submitNodeCollector(), ctx.poseStack(), tickDelta);
		});

		// Retained meshes (WorldMesh, ChunkCache) draw straight after the per-frame shapes, in the same place in the frame.
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx -> MeshRenderer.draw(ctx.levelState().cameraRenderState));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> bus.post(WorldEvent.Join.INSTANCE));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> bus.post(WorldEvent.Leave.INSTANCE));

		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> bus.post(GameReadyEvent.INSTANCE));

		// Dev convenience: -Dmyriad.openDesktop=true opens the desktop the first time the title screen appears.
		if (Boolean.getBoolean("myriad.openDesktop")) {
			boolean[] done = {false};
			bus.listen(ScreenOpenEvent.class, e -> {
				if (!done[0] && e.screen() instanceof TitleScreen) {
					done[0] = true;
					Minecraft.getInstance().schedule(() -> myriad.windowManager().open());
				}
			});
		}
		// Dev convenience: -Dmyriad.screenshotEvery=N saves a screenshot every N seconds (screenshots/myriad-dev-*.png),
		// to check rendering without touching the window.
		int screenshotEvery = Integer.getInteger("myriad.screenshotEvery", 0);
		if (screenshotEvery > 0) {
			int[] ticks = {0, 0};
			ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (++ticks[0] % (screenshotEvery * 20) != 0) return;
				Screenshot.grab(mc.gameDirectory, "myriad-dev-" + ticks[1]++ + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
				});
			});
		}
		// Dev scripting: -Dmyriad.devConsole=<file> runs lines appended to that file (see DevConsole), so tests can drive
		// the game from outside it.
		String devConsole = System.getProperty("myriad.devConsole");
		if (devConsole != null && !devConsole.isBlank()) {
			bus.subscribe(new DevConsole(Path.of(devConsole), myriad.commandManager()));
		}
		// Dev check: -Dmyriad.auditMixins=true applies every mixin (Myriad's and addons') once the game has started.
		// Mixins into classes that load late (chat, screens) otherwise only fail when you first open them.
		if (Boolean.getBoolean("myriad.auditMixins")) {
			ClientLifecycleEvents.CLIENT_STARTED.register(mc -> MixinEnvironment.getCurrentEnvironment().audit());
		}
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			bus.post(ShutdownEvent.INSTANCE);
			myriad.configImpl().shutdown();
		});
	}
}
