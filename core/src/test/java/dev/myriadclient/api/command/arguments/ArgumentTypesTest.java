package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgumentTypesTest {
	@Test
	void durations() {
		assertEquals(10_000L, DurationArgumentType.parse("10s"));
		assertEquals(10_000L, DurationArgumentType.parse("10"));
		assertEquals(90_000L, DurationArgumentType.parse("1m30s"));
		assertEquals(1_000L, DurationArgumentType.parse("20t"));
		assertEquals(250L, DurationArgumentType.parse("250ms"));
		assertEquals(7_200_000L, DurationArgumentType.parse("2h"));
		assertNull(DurationArgumentType.parse("soon"));
		assertNull(DurationArgumentType.parse("5x"));
		assertNull(DurationArgumentType.parse(""));
	}

	@Test
	void blockPositionsAbsoluteAndRelative() throws CommandSyntaxException {
		BlockPosArgumentType type = new BlockPosArgumentType(() -> new BlockPos(10, 64, -5));
		assertEquals(new BlockPos(1, 2, 3), type.parse(new StringReader("1 2 3")));
		assertEquals(new BlockPos(10, 64, -5), type.parse(new StringReader("~ ~ ~")));
		assertEquals(new BlockPos(12, 63, 0), type.parse(new StringReader("~2 ~-1 0")));
		StringReader rest = new StringReader("1 2 3 more");
		type.parse(rest);
		assertEquals(" more", rest.getRemaining());
		assertThrows(CommandSyntaxException.class, () -> type.parse(new StringReader("1 2")));
	}
}
