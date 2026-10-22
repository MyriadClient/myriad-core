package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.harness.Harness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every setting type, one row each: a value other than its default survives JSON both ways and {@code encode}/{@code
 * decode}, what it shows ({@code valueString}) parses back to it, bad JSON leaves it as it is without throwing, and a
 * save keeps what it doesn't know. A new type gets a row here.
 */
class SettingRoundTripTest {
	enum Mode {
		NEAREST, LOWEST_HEALTH, LONGEST_NAME
	}

	/**
	 * One type: how to make it in a group, a value other than its default, and why its {@code valueString} doesn't
	 * parse back (null if it does).
	 */
	record Row(String type, Function<SettingGroup, Setting<?>> make, Object value, String showsSummary) {
		@Override
		public String toString() {
			return type;
		}
	}

	static List<Row> rows() {
		return List.of(
			new Row("bool", g -> g.bool("Rotate").build(), true, null),
			new Row("integer", g -> g.integer("Delay").defaultValue(3).build(), 7, null),
			new Row("decimal", g -> g.decimal("Range").defaultValue(4.5).build(), 2.5, null),
			new Row("oneOf", g -> g.oneOf("Target", Mode.NEAREST).build(), Mode.LOWEST_HEALTH, null),
			new Row("enumSet", g -> g.enumSet("Targets", Mode.class).build(), EnumSet.of(Mode.NEAREST, Mode.LONGEST_NAME), null),
			new Row("enumSet empty", g -> g.enumSet("Targets", Mode.class).defaultValue(EnumSet.of(Mode.NEAREST)).build(), EnumSet.noneOf(Mode.class), null),
			new Row("string", g -> g.string("Greeting").build(), "hello there", null),
			new Row("icon", g -> g.icon("Icon").build(), "", null),
			new Row("stringList", g -> g.stringList("Servers").build(), new ArrayList<>(List.of("2b2t.org", "6b6t.org")), null),
			new Row("intList", g -> g.intList("Slots").build(), new ArrayList<>(List.of(1, 5, 9)), null),
			new Row("color", g -> g.color("Fill").build(), SettingColor.of(0x80FF3366), null),
			new Row("color role", g -> g.color("Fill").build(), SettingColor.role(SettingColor.Mode.RAINBOW), null),
			new Row("keybind", g -> g.keybind("Bind").build(), new Keybind(GLFW.GLFW_MOUSE_BUTTON_5, true, GLFW.GLFW_MOD_CONTROL), null),
			new Row("keybind none", g -> g.keybind("Bind").defaultValue(new Keybind(GLFW.GLFW_MOUSE_BUTTON_5, true, 0)).build(), Keybind.NONE, null),
			new Row("item", g -> g.item("Food", Items.BREAD).build(), Items.GOLDEN_CARROT, null),
			new Row("block", g -> g.block("Fill", Blocks.STONE).build(), Blocks.OBSIDIAN, null),
			new Row("sound", g -> g.sound("Ping", SoundEvents.EXPERIENCE_ORB_PICKUP).build(), SoundEvents.NOTE_BLOCK_PLING.value(), null),
			new Row("blockPos", g -> g.blockPos("Origin").build(), new BlockPos(-120, 64, 3000), null),
			new Row("choice", g -> g.choice("Schematic", () -> List.of("castle.litematic", "farm.litematic")).defaultValue("farm.litematic").build(),
				"castle.litematic", null),
			new Row("file", g -> g.file("Schematic").build(), "/tmp/schematics/castle.litematic", "shows the file's name, not its path"),
			new Row("modules", g -> g.modules("Pause With").build(), new LinkedHashSet<>(List.of(MyriadId.parse("myriad-essentials:freecam"))),
				"shows how many"),
			new Row("blocks", g -> g.blocks("Ores").build(), new LinkedHashSet<>(List.of(Blocks.DIAMOND_ORE, Blocks.ANCIENT_DEBRIS)), "shows how many and the first few"),
			new Row("items", g -> g.items("Junk").build(), new LinkedHashSet<>(List.of(Items.DIRT)), "shows how many and the first few"),
			new Row("entityTypes", g -> g.entityTypes("Mobs").build(), new LinkedHashSet<>(List.<EntityType<?>>of(EntityTypes.HUSK)), "shows how many and the first few"),
			new Row("statusEffects", g -> g.statusEffects("Effects").build(), new LinkedHashSet<>(List.of(MobEffects.SPEED.value())), "shows how many and the first few"),
			new Row("registry", g -> g.registry("Landing", BuiltInRegistries.SOUND_EVENT, SoundEvents.ANVIL_LAND).build(), SoundEvents.BELL_BLOCK, null),
			new Row("registryList", g -> g.registryList("Sounds", BuiltInRegistries.SOUND_EVENT).build(),
				new LinkedHashSet<>(List.of(SoundEvents.ANVIL_LAND)), "shows how many and the first few")
		);
	}

	@BeforeAll
	static void registries() {
		Harness.bootRegistries();
	}

	@SuppressWarnings("unchecked")
	private static <T> Setting<T> make(Row row) {
		return (Setting<T>) row.make().apply(new Settings().group("General"));
	}

	@SuppressWarnings("unchecked")
	private static <T> T value(Row row) {
		return (T) row.value();
	}

	@ParameterizedTest
	@MethodSource("rows")
	<T> void jsonBothWays(Row row) {
		Setting<T> a = make(row);
		T v = value(row);
		assertNotEquals(v, a.get(), "the row's value is the default");
		a.set(v);
		assertEquals(v, a.get());
		Setting<T> b = make(row);
		b.fromJson(a.toJson());
		assertEquals(v, b.get());
		assertFalse(b.isDefault());
	}

	@ParameterizedTest
	@MethodSource("rows")
	<T> void encodeAndDecodeLeaveTheSettingAlone(Row row) {
		Setting<T> a = make(row);
		T v = value(row);
		T before = a.get();
		JsonElement json = a.encode(v);
		assertEquals(before, a.get());
		assertEquals(v, a.decode(json));
		assertEquals(before, a.get());
		a.set(v);
		assertEquals(a.toJson(), json);
	}

	@ParameterizedTest
	@MethodSource("rows")
	<T> void whatItShowsParsesBack(Row row) {
		if (row.showsSummary() != null) return;
		Setting<T> a = make(row);
		T v = value(row);
		a.set(v);
		String shown = a.valueString();
		Setting<T> b = make(row);
		assertTrue(b.parse(shown), "parse(\"" + shown + "\")");
		assertEquals(v, b.get(), "parse(\"" + shown + "\")");
		assertEquals(v, make(row).decode(shown));
	}

	/** Shown with the player's own number format, then typed back the same way. */
	@ParameterizedTest
	@MethodSource("rows")
	<T> void whatItShowsParsesBackInAnyLocale(Row row) {
		Locale was = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			whatItShowsParsesBack(row);
		} finally {
			Locale.setDefault(was);
		}
	}

	@ParameterizedTest
	@MethodSource("rows")
	<T> void badJsonLeavesTheValueAndDoesntThrow(Row row) {
		Setting<T> a = make(row);
		T v = value(row);
		a.set(v);
		JsonArray junk = new JsonArray();
		junk.add(new JsonObject());
		junk.add(JsonNull.INSTANCE);
		junk.add(new JsonPrimitive("not:a thing!"));
		JsonArray three = new JsonArray();
		for (int i = 0; i < 3; i++) three.add("x");
		JsonObject object = new JsonObject();
		object.addProperty("color", "#zz");
		for (JsonElement bad : List.of(JsonNull.INSTANCE, new JsonObject(), object, junk, three, new JsonPrimitive("not:a thing!"), new JsonPrimitive(1.5e300))) {
			assertDoesNotThrow(() -> a.fromJson(bad), bad.toString());
			assertDoesNotThrow(() -> a.decode(bad), bad.toString());
		}
		// What bad JSON may do is keep the value or reset it to something valid: never a value that isn't one.
		JsonElement saved = a.toJson();
		Setting<T> b = make(row);
		b.fromJson(saved);
		assertEquals(a.get(), b.get());
	}

	@ParameterizedTest
	@MethodSource("rows")
	<T> void aSaveKeepsWhatItDoesntKnow(Row row) {
		Settings settings = new Settings();
		@SuppressWarnings("unchecked")
		Setting<T> a = (Setting<T>) row.make().apply(settings.group("General"));
		a.set(value(row));
		JsonObject previous = new JsonObject();
		JsonObject general = new JsonObject();
		general.addProperty("from_a_newer_version", 12);
		previous.add("general", general);
		previous.addProperty("other_group", "kept");
		JsonObject saved = settings.toJson(previous);
		assertEquals(12, saved.getAsJsonObject("general").get("from_a_newer_version").getAsInt());
		assertEquals("kept", saved.get("other_group").getAsString());
		Settings loaded = new Settings();
		@SuppressWarnings("unchecked")
		Setting<T> b = (Setting<T>) row.make().apply(loaded.group("General"));
		loaded.fromJson(saved);
		assertEquals(value(row), b.get());
	}

	/** Every type the group can build has a row (by the builder methods' names). */
	@org.junit.jupiter.api.Test
	void everyTypeHasARow() {
		Set<String> covered = new LinkedHashSet<>();
		for (Row r : rows()) covered.add(r.type().split(" ")[0]);
		List<String> missing = new ArrayList<>();
		for (var m : SettingGroup.class.getDeclaredMethods()) {
			if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()) || !Setting.Builder.class.isAssignableFrom(m.getReturnType())) continue;
			if (m.isAnnotationPresent(Deprecated.class) || m.getName().equals("action")) continue;
			if (!covered.contains(m.getName())) missing.add(m.getName());
		}
		assertEquals(List.of(), missing, "setting builders without a row");
	}
}
