package dev.screch.regen

import net.minecraft.server.level.ServerPlayer

interface RegenPlayerList {
    fun regenDetachPlayers(): List<ServerPlayer>
    fun regenRestorePlayers(previous: List<ServerPlayer>)
}
