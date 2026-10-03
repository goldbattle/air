package com.aria.air

// Navigation: the tabs, the screens pushed over them, and the back stack.

import com.aria.air.data.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*

enum class Tab { Flights, Friends, Passport, Settings }

sealed class Screen {
    data class FlightDetail(val id: String) : Screen()
    data class Add(val friend: Boolean, val found: List<Found>? = null, val error: String? = null) : Screen()
    data class AirportInfo(val code: String) : Screen()
    data class AirlineInfo(val code: String) : Screen()
    data object MailAccounts : Screen()
    data object MailFound : Screen()
}

object Nav {
    var tab by mutableStateOf(Tab.Flights)
    val stack = mutableStateListOf<Screen>()
    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    /** Swaps the top screen, so Back skips it (moving between legs of a connection). */
    fun replace(s: Screen) { if (stack.isEmpty()) push(s) else stack[stack.lastIndex] = s }
    fun open(tab: Tab) { this.tab = tab; stack.clear() }
}
