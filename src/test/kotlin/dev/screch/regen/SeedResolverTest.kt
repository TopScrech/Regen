package dev.screch.regen

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SeedResolverTest {
    @Test fun parsedNumericSeedsIncludingZeroAndBounds() {
        assertEquals(0L, SeedResolver.parse("0"))
        assertEquals(-1234L, SeedResolver.parse("-1234"))
        assertEquals(Long.MIN_VALUE, SeedResolver.parse("-9223372036854775808"))
        assertEquals(Long.MAX_VALUE, SeedResolver.parse("9223372036854775807"))
    }

    @Test fun hashedTextSeedsUsingVanillaRules() {
        for (seed in listOf("my seed with spaces", "世界 🌍", "9223372036854775808")) {
            assertEquals(seed.hashCode().toLong(), SeedResolver.parse(seed))
        }
        assertThrows(IllegalArgumentException::class.java) { SeedResolver.parse("   ") }
    }

    @Test fun greedyCommandArgumentKeptAllSpaces() {
        val dispatcher = CommandDispatcher<String>()
        dispatcher.register(LiteralArgumentBuilder.literal<String>("regen")
            .then(RequiredArgumentBuilder.argument<String, String>("seed", StringArgumentType.greedyString())
                .executes {
                    assertEquals("my seed  with spaces", StringArgumentType.getString(it, "seed"))
                    1
                }))
        assertEquals(1, dispatcher.execute("regen my seed  with spaces", "test"))
    }
}
