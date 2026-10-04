package com.mrj.fancyai.sd.hd

object TileGrid {
    fun cover(size: Int, tile: Int, overlap: Int): List<Int> {
        require(tile in 1..size || size == tile) { "tile must be <= size" }
        if (size <= tile) return listOf(0)
        val step = (tile - overlap).coerceAtLeast(1)
        val origins = ArrayList<Int>()
        var o = 0
        while (true) {
            if (o + tile >= size) { origins.add(size - tile); break }
            origins.add(o)
            o += step
        }
        return origins.distinct()
    }
}
