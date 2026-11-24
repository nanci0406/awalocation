package com.nanci.awalocation.android.coro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class CoroutineController {
    private val pausedFlow = MutableStateFlow(true)
    val isPaused: Boolean get() = pausedFlow.value

    suspend fun controlledCoroutine() {
        pausedFlow.first { !it }
    }

    fun pause() {
        pausedFlow.value = true
    }

    fun resume() {
        pausedFlow.value = false
    }
}