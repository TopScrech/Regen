package dev.screch.regen

interface RegenServer {
    fun regenQueue(seed: Long): Boolean
    fun regenRunPending()
    fun regenIsRebuilding(): Boolean
}
