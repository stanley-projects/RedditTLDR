package com.stanley.reddittldr.data

enum class SummaryLength(val storageKey: String) {
    SHORT("short"),
    MEDIUM("medium"),
    DETAILED("detailed");

    companion object {
        fun fromStorageKey(key: String?): SummaryLength =
            values().firstOrNull { it.storageKey == key } ?: MEDIUM
    }
}
