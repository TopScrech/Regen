package dev.screch.regen;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeedResolverTest {
    @Test
    void parsedNumericSeedsIncludingZeroAndBounds() {
        assertEquals(0, SeedResolver.parse("0"));
        assertEquals(-1234, SeedResolver.parse("-1234"));
        assertEquals(Long.MIN_VALUE, SeedResolver.parse("-9223372036854775808"));
        assertEquals(Long.MAX_VALUE, SeedResolver.parse("9223372036854775807"));
    }

    @Test
    void hashedTextSeedsUsingVanillaRules() {
        assertEquals("my seed with spaces".hashCode(), SeedResolver.parse("my seed with spaces"));
        assertEquals("世界 🌍".hashCode(), SeedResolver.parse("世界 🌍"));
        assertEquals("9223372036854775808".hashCode(), SeedResolver.parse("9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> SeedResolver.parse("   "));
    }

    @Test
    void greedyCommandArgumentKeptAllSpaces() throws Exception {
        CommandDispatcher<String> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<String>literal("regen")
            .then(RequiredArgumentBuilder.<String, String>argument("seed", StringArgumentType.greedyString())
                .executes(context -> {
                    assertEquals("my seed  with spaces", StringArgumentType.getString(context, "seed"));
                    return 1;
                })));
        assertEquals(1, dispatcher.execute("regen my seed  with spaces", "test"));
    }
}
