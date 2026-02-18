package com.pocketnode.mempool

import org.json.JSONObject

/**
 * Represents a mempool transaction entry from getrawmempool RPC call
 */
data class MempoolEntry(
    val vsize: Int = 0,
    val weight: Int = 0,
    val fee: Double = 0.0,
    val modifiedfee: Double = 0.0,
    val time: Long = 0,
    val height: Int = 0,
    val descendantcount: Int = 0,
    val descendantsize: Int = 0,
    val descendantfees: Double = 0.0,
    val ancestorcount: Int = 0,
    val ancestorsize: Int = 0,
    val ancestorfees: Double = 0.0,
    val wtxid: String = "",
    val fees: MempoolFees? = null,
    val depends: List<String> = emptyList(),
    val spentby: List<String> = emptyList(),
    val unbroadcast: Boolean = false
) {
    val effectiveFee: Double get() = fees?.base ?: fee
    val effectiveAncestorFees: Double get() = fees?.ancestor ?: ancestorfees
    val effectiveDescendantFees: Double get() = fees?.descendant ?: descendantfees

    companion object {
        fun fromJson(json: JSONObject): MempoolEntry {
            val feesObj = json.optJSONObject("fees")
            val dependsArray = json.optJSONArray("depends")
            val spentbyArray = json.optJSONArray("spentby")

            return MempoolEntry(
                vsize = json.optInt("vsize", 0),
                weight = json.optInt("weight", 0),
                fee = json.optDouble("fee", 0.0),
                modifiedfee = json.optDouble("modifiedfee", 0.0),
                time = json.optLong("time", 0),
                height = json.optInt("height", 0),
                descendantcount = json.optInt("descendantcount", 0),
                descendantsize = json.optInt("descendantsize", 0),
                descendantfees = json.optDouble("descendantfees", 0.0),
                ancestorcount = json.optInt("ancestorcount", 0),
                ancestorsize = json.optInt("ancestorsize", 0),
                ancestorfees = json.optDouble("ancestorfees", 0.0),
                wtxid = json.optString("wtxid", ""),
                fees = feesObj?.let { MempoolFees.fromJson(it) },
                depends = buildList {
                    if (dependsArray != null) {
                        for (i in 0 until dependsArray.length()) {
                            add(dependsArray.getString(i))
                        }
                    }
                },
                spentby = buildList {
                    if (spentbyArray != null) {
                        for (i in 0 until spentbyArray.length()) {
                            add(spentbyArray.getString(i))
                        }
                    }
                },
                unbroadcast = json.optBoolean("unbroadcast", false)
            )
        }
    }
}

data class MempoolFees(
    val base: Double = 0.0,
    val modified: Double = 0.0,
    val ancestor: Double = 0.0,
    val descendant: Double = 0.0
) {
    companion object {
        fun fromJson(json: JSONObject): MempoolFees {
            return MempoolFees(
                base = json.optDouble("base", 0.0),
                modified = json.optDouble("modified", 0.0),
                ancestor = json.optDouble("ancestor", 0.0),
                descendant = json.optDouble("descendant", 0.0)
            )
        }
    }
}
