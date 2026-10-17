/**
 * Hooks: places where an addon can change how Myriad (or another addon) behaves, without changing its code.
 * <ul>
 *   <li><b>Services</b> ({@link dev.myriadclient.api.hook.Service}): take over a whole core service (rotations,
 *   inventory, placement, breaking) with your own, keeping Myriad's menu, binds and modules. Extend the service's
 *   {@code Forwarding} class (e.g. {@link dev.myriadclient.api.service.Rotations.Forwarding}) and override what you
 *   do differently; everything else still goes to core's.</li>
 *   <li><b>Hooks</b> ({@link dev.myriadclient.api.hook.Hook}): one decision inside a service or module (how a
 *   rotation turns, where a placement clicks), handled by whichever contributions match the module asking and the
 *   server you're on. Addons can declare hooks of their own for others to build on.</li>
 * </ul>
 * Both are contributed through {@link dev.myriadclient.api.addon.AddonContext#replace} and
 * {@link dev.myriadclient.api.addon.AddonContext#hook}, with a {@link dev.myriadclient.api.hook.Match} saying when
 * they apply, and both show in Myriad's Hooks window, where the player can turn any of them off.
 * <p>
 * Use an event when you want to react to or adjust something alongside everyone else; use a hook when there's one
 * answer to give, and the most specific contribution should give it.
 */
package dev.myriadclient.api.hook;
