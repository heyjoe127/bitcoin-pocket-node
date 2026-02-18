package com.pocketnode.mempool

data class ProjectedBlockInfo(
    val index: Int,
    val transactionCount: Int,
    val totalWeight: Int,
    val totalFees: Double,
    val minFeeRate: Double,
    val maxFeeRate: Double,
    val medianFeeRate: Double,
    val feeRateBands: List<FeeRateBand>
)

data class FeeRateBand(
    val feeRateRange: ClosedFloatingPointRange<Double>,
    val proportion: Float
)

data class LatestBlockInfo(
    val height: Int,
    val hash: String,
    val time: Long,
    val txCount: Int,
    val size: Int,
    val weight: Int
)
