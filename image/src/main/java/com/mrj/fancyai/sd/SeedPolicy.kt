package com.mrj.fancyai.sd

import kotlin.random.Random

object SeedPolicy {

    private const val SEED_SPACE = 4_294_967_296L

    fun roll(random: Random = Random.Default): Long = random.nextLong(0L, SEED_SPACE)
}
