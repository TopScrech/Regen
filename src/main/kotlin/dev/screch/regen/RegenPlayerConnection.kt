package dev.screch.regen

import net.minecraft.server.level.ServerPlayer

interface RegenPlayerConnection {
    fun regenReplacePlayer(replacement: ServerPlayer)
}
