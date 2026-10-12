package dev.screch.regen.mixin

import it.unimi.dsi.fastutil.objects.Object2IntMap
import net.minecraft.stats.Stat
import net.minecraft.stats.StatsCounter
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(StatsCounter::class)
interface StatsCounterAccessor {
    @Accessor("stats")
    fun regenGetStats(): Object2IntMap<Stat<*>>
}
