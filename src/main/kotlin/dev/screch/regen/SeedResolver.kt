package dev.screch.regen

import java.security.SecureRandom

object SeedResolver {
    private val random = SecureRandom()

    fun random(): Long = random.nextLong()

    fun parse(seed: String): Long {
        val value = seed.trim { it <= ' ' }
        require(value.isNotEmpty()) { "The seed must not be blank" }
        return value.toLongOrNull() ?: value.hashCode().toLong()
    }
}
