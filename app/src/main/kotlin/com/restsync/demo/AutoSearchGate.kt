package com.restsync.demo

/** Consume a user's search request once both independent startup tasks are ready. */
internal class AutoSearchGate(private var pending: Boolean) {
    fun consumeIfReady(connected: Boolean, catalogReady: Boolean): Boolean {
        if (!pending || !connected || !catalogReady) return false
        pending = false
        return true
    }
}
