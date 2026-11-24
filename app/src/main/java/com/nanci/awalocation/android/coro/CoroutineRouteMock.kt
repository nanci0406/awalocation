package com.nanci.awalocation.android.coro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class CoroutineRouteMock {
    private val pausedFlow = MutableStateFlow(true)
    val isPaused: Boolean get() = pausedFlow.value

    suspend fun routeMockCoroutine() {
        pausedFlow.first { !it }
    }

    fun pause() {
        pausedFlow.value = true
    }

    fun resume() {
        pausedFlow.value = false
    }
}