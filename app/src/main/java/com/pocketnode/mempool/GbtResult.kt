package com.pocketnode.mempool

data class GbtResult(
    val blocks: Array<IntArray> = emptyArray(),
    val blockWeights: IntArray = intArrayOf(),
    val clusters: Array<IntArray> = emptyArray(),
    val rates: Array<DoubleArray> = emptyArray(),
    val overflow: IntArray = intArrayOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GbtResult
        if (!blocks.contentDeepEquals(other.blocks)) return false
        if (!blockWeights.contentEquals(other.blockWeights)) return false
        if (!clusters.contentDeepEquals(other.clusters)) return false
        if (!rates.contentDeepEquals(other.rates)) return false
        if (!overflow.contentEquals(other.overflow)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = blocks.contentDeepHashCode()
        result = 31 * result + blockWeights.contentHashCode()
        result = 31 * result + clusters.contentDeepHashCode()
        result = 31 * result + rates.contentDeepHashCode()
        result = 31 * result + overflow.contentHashCode()
        return result
    }
}
