package com.aria.air

// Navigation: the tabs, the screens pushed over them, and the back stack.

import com.aria.air.data.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*

/** The four tabs along the bottom. */
enum class Tab { Flights, Friends, Passport, Settings }

/** Pages pushed over a tab. Each carries what it needs to draw itself, so the back stack is just a list of these. */
sealed class Screen {
    /** One flight's page. */
    data class FlightDetail(val id: String) : Screen()

    /** Add a flight ([friend] when started from the Friends tab), or review an import. */
    data class Add(val friend: Boolean, val found: List<Found>? = null, val error: String? = null) : Screen()

    /** An airport's page, with its guide. */
    data class AirportInfo(val code: String) : Screen()

    /** An airline's page, with its guide. */
    data class AirlineInfo(val code: String) : Screen()

    /** Linked email accounts. */
    data object MailAccounts : Screen()

    /** Flights found in email, to review. */
    data object MailFound : Screen()
}

/** Where the app is: the selected tab and the screens pushed over it, as Compose state so the UI follows. */
object Nav {
    /** The selected tab. */
    var tab by mutableStateOf(Tab.Flights)

    /** Screens pushed over the tab, the top one showing. */
    val stack = mutableStateListOf<Screen>()

    /** Opens [s] on top of the current screen. */
    fun push(s: Screen) { stack += s }

    /** Goes back one screen; does nothing at the root of a tab. */
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    /** Swaps the top screen, so Back skips it (moving between legs of a connection). */
    fun replace(s: Screen) { if (stack.isEmpty()) push(s) else stack[stack.lastIndex] = s }

    /** Switches to [tab] and drops the pushed screens, like tapping the tab bar. */
    fun open(tab: Tab) { this.tab = tab; stack.clear() }
}
