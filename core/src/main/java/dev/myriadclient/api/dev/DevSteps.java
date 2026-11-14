package dev.myriadclient.api.dev;

import dev.myriadclient.api.Myriad;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Core's dev console steps, for test frameworks and tools driving a dev client: the same script language the console
 * reads from {@code -Dmyriad.devConsole=<file>}, one step at a time. Only in dev runs ({@link Myriad#devSteps()} is empty
 * in a released game).
 *
 * <pre>{@code
 * Myriad.devSteps().ifPresent(dev -> dev.run("mouse 0 click"));
 * List<String> said = Myriad.devSteps().orElseThrow().run("tabs").join();   // ["tabs: ..."]
 * }</pre>
 *
 * <p>Steps (one per line in the console's file): {@code .command} (a Myriad command), {@code /command} (a server command
 * sent as the player), {@code hold forward,sprint,jump 40}, {@code release}, {@code look 90 30}, {@code select 3}
 * (hotbar 1-9), {@code attack}, {@code use}, {@code middle}, {@code mouse 0 press|release|click}, {@code key k [mods]
 * press|release|click}, {@code type text}, {@code cursor x y}, {@code drag ...}, {@code scroll n}, {@code wait 20},
 * {@code respawn}, {@code connect host:port}, {@code selftest [ticks] [-module]}, {@code openitem slot [air] [ticks]},
 * {@code status}, {@code tabs}, {@code tab n}, {@code closescreen}, {@code echo text}, {@code screenshot name},
 * {@code menu open|close}, {@code workspace n}, {@code panel id}, {@code hud id}, {@code launcher [query]},
 * {@code wm binding}, {@code settings module}, {@code faketab n}, {@code frametime seconds}. The console's class
 * comment in core describes each.
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("2.0")
@ApiStatus.NonExtendable
public interface DevSteps {
	/**
	 * Runs {@code step} at the start of a tick (the next one), on the render thread, after any steps before it. Completes
	 * once the script would go on to its next line ({@code wait 20} after 20 ticks, {@code selftest} when it's done),
	 * with what the step reported (the lines the console logs as {@code [dev] ...}, without the prefix); exceptionally
	 * if the step failed. Steps that keep going in the background ({@code hold}, {@code openitem}) complete at once.
	 * Any thread.
	 */
	CompletableFuture<List<String>> run(String step);
}
