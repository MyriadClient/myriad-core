package dev.myriadclient.harness;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The world as the harness's client sees it: blocks and entities a test put there, air elsewhere, every chunk loaded. Made with
 * {@link Fields#allocate} (never by its constructor), so only what's overridden here works.
 */
public final class FakeLevel extends ClientLevel {
	private Map<BlockPos, BlockState> blocks;
	private List<Entity> entities;
	private BlockStatePredictionHandler predictions;
	private FakeChunks chunks;

	@SuppressWarnings("unused")
	private FakeLevel() {
		super(null, null, null, null, 0, 0, null, false, 0, 0);
	}

	static FakeLevel create(Holder<DimensionType> dimension, Holder<Biome> biome) {
		FakeLevel level = Fields.allocate(FakeLevel.class);
		level.blocks = new HashMap<>();
		level.entities = new ArrayList<>();
		Fields.set(level, "isClientSide", true);
		Fields.set(level, "dragonParts", new ArrayList<>());
		level.predictions = new BlockStatePredictionHandler();
		Fields.set(level, "blockStatePredictionHandler", level.predictions);
		Fields.set(level, "worldBorder", new WorldBorder());
		Fields.set(level, "dimensionTypeRegistration", dimension);
		level.chunks = FakeChunks.create(level, biome);
		Fields.set(level, "random", RandomSource.create(0));
		Fields.set(level, "soundSeedGenerator", RandomSource.create(0));
		return level;
	}

	/** Puts {@code state} at {@code pos} (air removes it). */
	public void set(BlockPos pos, BlockState state) {
		if (state.isAir()) blocks.remove(pos);
		else blocks.put(pos.immutable(), state);
	}

	/** Adds {@code entity} to the world (the harness adds the player), with a network id as the server would give it. */
	public void add(Entity entity) {
		entity.setId(entities.size() + 1);
		entities.add(entity);
	}

	public void remove(Entity entity) {
		entities.remove(entity);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	/**
	 * The client's own changes land in the block map too, and while it's guessing (a placement awaiting the server's
	 * answer) the state before is kept for that answer, as the client's level does.
	 */
	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
		BlockStatePredictionHandler predictions = predictions();
		BlockState before = getBlockState(pos);
		set(pos, state);
		if (predictions.isPredicting() && before != state) predictions.retainKnownServerState(pos, before, Minecraft.getInstance().player);
		return true;
	}

	/** A block the server sent: filed away while the client's guess there is unanswered, else put in at once. */
	@Override
	public void setServerVerifiedBlockState(BlockPos pos, BlockState state, int flags) {
		if (!predictions().updateKnownServerState(pos, state)) set(pos, state);
	}

	private BlockStatePredictionHandler predictions() {
		return predictions;
	}

	/** Silent: there's no sound engine. */
	@Override
	public void playSeededSound(@Nullable Entity source, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume,
	                            float pitch, long seed) {
	}

	@Override
	public void playSeededSound(@Nullable Entity source, Entity at, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
	}

	@Override
	public void playLocalSound(double x, double y, double z, SoundEvent sound, SoundSource category, float volume, float pitch, boolean delayed) {
	}

	/** No sounds or particles for level events (a block breaking). */
	@Override
	public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
	}

	/** No particles to draw. */
	@Override
	public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
	}

	@Override
	public void addParticle(ParticleOptions particle, boolean always, boolean decreased, double x, double y, double z, double dx, double dy, double dz) {
	}

	/** No crack overlay to draw. */
	@Override
	public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
	}

	/** No block entities (chests and the like are only blocks here). */
	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public boolean hasChunk(int chunkX, int chunkZ) {
		return true;
	}

	@Override
	public ClientChunkCache getChunkSource() {
		return chunks;
	}

	/** Collisions read blocks through each chunk: this level answers for all of them. */
	@Override
	public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
		return this;
	}

	@Override
	public Iterable<Entity> entitiesForRendering() {
		return List.copyOf(entities);
	}

	@Override
	public int getEntityCount() {
		return entities.size();
	}

	@Override
	public @Nullable Entity getEntity(int id) {
		for (Entity e : entities) if (e.getId() == id) return e;
		return null;
	}

	@Override
	protected LevelEntityGetter<Entity> getEntities() {
		return new LevelEntityGetter<>() {
			@Override
			public @Nullable Entity get(int id) {
				return getEntity(id);
			}

			@Override
			public @Nullable Entity get(UUID id) {
				for (Entity e : entities) if (e.getUUID().equals(id)) return e;
				return null;
			}

			@Override
			public Iterable<Entity> getAll() {
				return List.copyOf(entities);
			}

			@Override
			public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> out) {
				for (Entity e : List.copyOf(entities)) {
					U u = test.tryCast(e);
					if (u != null && out.accept(u).shouldAbort()) return;
				}
			}

			@Override
			public void get(AABB box, Consumer<Entity> out) {
				for (Entity e : List.copyOf(entities)) if (e.getBoundingBox().intersects(box)) out.accept(e);
			}

			@Override
			public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB box, AbortableIterationConsumer<U> out) {
				for (Entity e : List.copyOf(entities)) {
					U u = test.tryCast(e);
					if (u != null && e.getBoundingBox().intersects(box) && out.accept(u).shouldAbort()) return;
				}
			}
		};
	}
}
