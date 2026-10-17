package dev.myriadclient.api.combat;

import dev.myriadclient.api.util.Entities;
import dev.myriadclient.api.util.ItemInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import java.util.function.Predicate;

/** Client-side estimates of damage the server would deal, for predicting lethal hits. */
public final class Damage {
	private static Minecraft mc() {
		return Minecraft.getInstance();
	}
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private Damage() {
	}

	/** Damage an explosion of {@code power} at {@code center} would deal to {@code target} after armour and effects. */
	public static float explosion(LivingEntity target, Vec3 center, float power) {
		return explosion(target, target.position(), target.getBoundingBox(), center, power, NO_OVERRIDES);
	}

	/**
	 * The full form: the target standing at {@code targetPos} with box {@code targetBox} (pass a predicted position
	 * from {@code Entities.predict} to aim where a player is going), and {@code asAir} naming blocks to treat as gone
	 * when working out how much of the blast reaches them ("what if I break this surround block first?").
	 */
	public static float explosion(LivingEntity target, Vec3 targetPos, AABB targetBox, Vec3 center, float power, Predicate<BlockPos> asAir) {
		double radius = power * 2.0;
		double distance = targetPos.distanceTo(center);
		if (distance > radius) return 0;
		float exposure = asAir == NO_OVERRIDES && targetBox.equals(target.getBoundingBox())
			? ServerExplosion.getSeenPercent(center, target)
			: exposure(center, targetBox, asAir);
		double impact = (1.0 - distance / radius) * exposure;
		float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1.0);
		if (target instanceof Player && mc().level != null) {
			Difficulty difficulty = mc().level.getDifficulty();
			damage = switch (difficulty) {
				case PEACEFUL -> 0;
				case EASY -> Math.min(damage / 2 + 1, damage);
				case NORMAL -> damage;
				case HARD -> damage * 1.5f;
			};
		}
		return reduce(target, damage, true);
	}

	public static float crystal(LivingEntity target, Vec3 crystal) {
		return explosion(target, crystal, 6);
	}

	/** Crystal damage against where {@code target} will be in {@code ticks} ticks. */
	public static float crystal(LivingEntity target, Vec3 crystal, int predictTicks, Predicate<BlockPos> asAir) {
		return explosion(target, Entities.predict(target, predictTicks), Entities.predictBox(target, predictTicks), crystal, 6, asAir);
	}

	/** A bed exploding at {@code bed} (in the Nether or End). */
	public static float bed(LivingEntity target, BlockPos bed) {
		// The bed (both halves) is gone by the time it explodes: its own blocks don't shield anyone.
		BlockPos other = bed;
		if (mc().level != null) {
			BlockState state = mc().level.getBlockState(bed);
			if (state.getBlock() instanceof BedBlock) other = bed.relative(BedBlock.getConnectedDirection(state));
		}
		BlockPos half = other;
		return explosion(target, target.position(), target.getBoundingBox(), Vec3.atCenterOf(bed), 5, p -> p.equals(bed) || p.equals(half));
	}

	/** A respawn anchor exploding at {@code anchor} (outside the Nether). */
	public static float anchor(LivingEntity target, BlockPos anchor) {
		// The anchor is gone by the time it explodes: it doesn't shield anyone from itself.
		return explosion(target, target.position(), target.getBoundingBox(), Vec3.atCenterOf(anchor), 5, p -> p.equals(anchor));
	}

	private static final Predicate<BlockPos> NO_OVERRIDES = p -> false;

	/** Vanilla's exposure: the share of sample points on the box with a clear line to the blast, skipping {@code asAir}. */
	private static float exposure(Vec3 source, AABB box, Predicate<BlockPos> asAir) {
		double dx = 1.0 / ((box.maxX - box.minX) * 2 + 1), dy = 1.0 / ((box.maxY - box.minY) * 2 + 1), dz = 1.0 / ((box.maxZ - box.minZ) * 2 + 1);
		if (dx < 0 || dy < 0 || dz < 0 || mc().level == null) return 0;
		double ox = (1 - Math.floor(1 / dx) * dx) / 2, oz = (1 - Math.floor(1 / dz) * dz) / 2;
		int clear = 0, total = 0;
		for (double x = 0; x <= 1; x += dx) {
			for (double y = 0; y <= 1; y += dy) {
				for (double z = 0; z <= 1; z += dz) {
					Vec3 point = new Vec3(Mth.lerp(x, box.minX, box.maxX) + ox, Mth.lerp(y, box.minY, box.maxY), Mth.lerp(z, box.minZ, box.maxZ) + oz);
					if (!blocked(point, source, asAir)) clear++;
					total++;
				}
			}
		}
		return total == 0 ? 0 : clear / (float) total;
	}

	private static boolean blocked(Vec3 from, Vec3 to, Predicate<BlockPos> asAir) {
		var world = mc().level;
		BlockHitResult hit = BlockGetter.traverseBlocks(from, to, null, (ctx, pos) -> {
			if (asAir.test(pos)) return null;
			BlockState state = world.getBlockState(pos);
			return world.clipWithInteractionOverride(from, to, pos, state.getCollisionShape(world, pos), state);
		}, ctx -> null);
		return hit != null;
	}

	/** Applies armour, toughness, resistance and protection enchantments. */
	public static float reduce(LivingEntity target, float damage, boolean blast) {
		var source = mc().level.damageSources().generic();
		damage = CombatRules.getDamageAfterAbsorb(target, damage, source, target.getArmorValue(), (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
		if (resistance != null) damage = Math.max(damage * (1 - 0.2f * (resistance.getAmplifier() + 1)), 0);
		int protection = 0;
		for (EquipmentSlot slot : ARMOR) {
			ItemStack s = target.getItemBySlot(slot);
			protection += ItemInfo.enchantmentLevel(s, Enchantments.PROTECTION);
			if (blast) protection += ItemInfo.enchantmentLevel(s, Enchantments.BLAST_PROTECTION) * 2;
		}
		return CombatRules.getDamageAfterMagicAbsorb(damage, Math.min(protection, 20));
	}

	/** Fall damage for falling {@code distance} blocks, after armour. */
	public static float fall(LivingEntity target, float distance) {
		var effect = target.getEffect(MobEffects.JUMP_BOOST);
		float safe = (float) target.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) + (effect == null ? 0 : effect.getAmplifier() + 1);
		float damage = (float) Math.ceil((distance - safe) * target.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
		if (damage <= 0) return 0;
		int featherFalling = ItemInfo.enchantmentLevel(target.getItemBySlot(EquipmentSlot.FEET), Enchantments.FEATHER_FALLING);
		int protection = 0;
		for (EquipmentSlot slot : ARMOR) protection += ItemInfo.enchantmentLevel(target.getItemBySlot(slot), Enchantments.PROTECTION);
		return CombatRules.getDamageAfterMagicAbsorb(damage, Math.min(protection + featherFalling * 3, 20));
	}

	/** A rough melee hit from {@code attacker} right now (charge, sharpness, crits). */
	public static float melee(Player attacker, LivingEntity target) {
		float base = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
		int sharpness = ItemInfo.enchantmentLevel(attacker.getMainHandItem(), Enchantments.SHARPNESS);
		float bonus = sharpness > 0 ? 1 + 0.5f * (sharpness - 1) : 0;
		float charge = attacker.getAttackStrengthScale(0.5f);
		base *= 0.2f + charge * charge * 0.8f;
		bonus *= charge;
		if (charge > 0.9f && attacker.fallDistance > 0 && !attacker.onGround() && !attacker.onClimbable() && !attacker.isInWater()
			&& !attacker.hasEffect(MobEffects.BLINDNESS) && !attacker.isPassenger()) base *= 1.5f;
		return reduce(target, base + bonus, false);
	}
}
