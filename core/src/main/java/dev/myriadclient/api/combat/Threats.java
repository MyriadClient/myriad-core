package dev.myriadclient.api.combat;

import dev.myriadclient.api.Myriad;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

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
		return worstBlock(p, range, s -> s.getBlock() instanceof BedBlock, pos -> Damage.bed(p, pos));
	}

	/** The worst charged respawn anchor within {@code range} blocks; 0 in the Nether, where anchors work. */
	public static float anchors(int range) {
		LocalPlayer p = player();
		if (p.level().dimension() == Level.NETHER) return 0;
		return worstBlock(p, range, s -> s.getBlock() instanceof RespawnAnchorBlock && s.getValue(RespawnAnchorBlock.CHARGE) > 0,
			pos -> Damage.anchor(p, pos));
	}

	/**
	 * The most {@code damage} from a block within {@code range} of {@code p} that matches {@code filter}. Each 16-block
	 * section's palette says whether it could hold one, so the cube's ~5,000 blocks are read only in a section that might
	 * (usually none): asked every tick, reading them all was ~100,000 block reads a second.
	 */
	private static float worstBlock(LocalPlayer p, int range, Predicate<BlockState> filter, ToDoubleFunction<BlockPos> damage) {
		Level level = p.level();
		BlockPos at = p.blockPosition();
		int minX = at.getX() - range, maxX = at.getX() + range, minZ = at.getZ() - range, maxZ = at.getZ() + range;
		int minY = Math.max(at.getY() - range, level.getMinY()), maxY = Math.min(at.getY() + range, level.getMaxY());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		float max = 0;
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null) continue;
				LevelChunkSection[] sections = chunk.getSections();
				for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
					int index = chunk.getSectionIndexFromSectionY(sy);
					if (index < 0 || index >= sections.length) continue;
					LevelChunkSection section = sections[index];
					if (section == null || !section.maybeHas(filter)) continue;
					for (int y = Math.max(minY, sy << 4); y <= Math.min(maxY, sy << 4 | 15); y++) {
						for (int z = Math.max(minZ, cz << 4); z <= Math.min(maxZ, cz << 4 | 15); z++) {
							for (int x = Math.max(minX, cx << 4); x <= Math.min(maxX, cx << 4 | 15); x++) {
								if (!filter.test(section.getBlockState(x & 15, y & 15, z & 15))) continue;
								max = Math.max(max, (float) damage.applyAsDouble(pos.set(x, y, z).immutable()));
							}
						}
					}
				}
			}
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
