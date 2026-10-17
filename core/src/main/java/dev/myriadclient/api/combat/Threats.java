package dev.myriadclient.api.combat;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.combat.Damage;
import dev.myriadclient.api.util.Ticks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How much damage nearby dangers could do to you right now, after your armour and effects: end crystals, beds (which
 * explode outside the Overworld), charged respawn anchors (which explode outside the Nether), creepers about to
 * explode, players about to hit you, and the fall you're in. For anything that reacts before a hit could kill you:
 * totems, auto disconnect, surround, escaping.
 *
 * <pre>{@code
 * if (Threats.isLethal(2)) forceTotem();
 * }</pre>
 *
 * Call it in game only (with a player). {@link #worst()} and {@link #isLethal} are computed once per tick however many
 * modules ask; the others query the world each call.
 */
public final class Threats {
	private Threats() {
	}

	private static LocalPlayer player() {
		return Minecraft.getInstance().player;
	}

	/** Your health including absorption. */
	public static float health() {
		return player().getHealth() + player().getAbsorptionAmount();
	}

	/**
	 * The worst of every danger around you, with the usual ranges (crystals 12, beds and anchors 8, creepers 8, players
	 * 5). Crystals are added up, since several can go off at once.
	 */
	public static float worst() {
		long tick = Ticks.current();
		if (tick != worstTick) {
			worst = Math.max(Math.max(Math.max(crystals(12, true), beds(8)), Math.max(anchors(8), creepers(8))), Math.max(players(5), fall()));
			worstTick = tick;
		}
		return worst;
	}

	/** {@link #worst()} is six world queries; several modules ask every tick, so it's worked out once per tick. */
	private static long worstTick = -1;
	private static float worst;

	/** Whether {@link #worst()} plus {@code margin} could take all your health (including absorption). */
	public static boolean isLethal(float margin) {
		float worst = worst();
		return worst > 0 && worst + margin >= health();
	}

	/** The worst crystal within {@code range}, or all of them added up when {@code sum}. */
	public static float crystals(double range, boolean sum) {
		float max = 0, total = 0;
		LocalPlayer p = player();
		for (var e : p.level().getEntities(p, p.getBoundingBox().inflate(range), en -> en instanceof EndCrystal)) {
			float d = Damage.crystal(p, e.position());
			total += d;
			max = Math.max(max, d);
		}
		return sum ? total : max;
	}

	/** The worst bed within {@code range} blocks; 0 in the Overworld, where beds don't explode. */
	public static float beds(int range) {
		LocalPlayer p = player();
		if (p.level().dimension() == Level.OVERWORLD) return 0;
		float max = 0;
		for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-range, -range, -range), p.blockPosition().offset(range, range, range))) {
			if (p.level().getBlockState(pos).getBlock() instanceof BedBlock) max = Math.max(max, Damage.bed(p, pos));
		}
		return max;
	}

	/** The worst charged respawn anchor within {@code range} blocks; 0 in the Nether, where anchors work. */
	public static float anchors(int range) {
		LocalPlayer p = player();
		if (p.level().dimension() == Level.NETHER) return 0;
		float max = 0;
		for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-range, -range, -range), p.blockPosition().offset(range, range, range))) {
			BlockState s = p.level().getBlockState(pos);
			if (s.getBlock() instanceof RespawnAnchorBlock && s.getValue(RespawnAnchorBlock.CHARGE) > 0) max = Math.max(max, Damage.anchor(p, pos));
		}
		return max;
	}

	/** The hardest hit a non-friend within {@code range} could land. */
	public static float players(double range) {
		LocalPlayer p = player();
		float max = 0;
		for (Player other : p.level().getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(range), o -> o != p && !Myriad.friends().isFriend(o))) {
			max = Math.max(max, Damage.melee(other, p));
		}
		return max;
	}

	/** Damage from the fall you're in, if you landed now. */
	public static float fall() {
		LocalPlayer p = player();
		return p.fallDistance > 3 ? Damage.fall(p, (float) p.fallDistance) : 0;
	}

	/** The worst blast from a creeper within {@code range} that's about to explode (swelling, or lit with flint and steel). */
	public static float creepers(double range) {
		LocalPlayer p = player();
		float max = 0;
		for (Creeper c : p.level().getEntitiesOfClass(Creeper.class, p.getBoundingBox().inflate(range), c -> c.getSwelling(1) > 0 || c.isIgnited())) {
			// A creeper's blast is power 3, doubled when it's charged.
			max = Math.max(max, Damage.explosion(p, c.position(), c.isPowered() ? 6 : 3));
		}
		return max;
	}
}
