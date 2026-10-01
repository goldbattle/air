package com.aria.air

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplaneTicket
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.load(this)
        Land.load(this)
        Guides.init(this)
        Store.init(this)
        Store.onChange = { scope.launch { FlightWidget.refresh(this@App) } }
        Notify.channels(this)
        TrackWorker.schedule(this)
        Updater.schedule(this)
        scope.launch { Api.backfillWeather() }
    }

    companion object {
        /** Outlives screens, so lookups and imports finish when you navigate away. */
        val scope = MainScope()
    }
}

enum class Tab { Flights, Friends, Passport, Settings }

sealed class Screen {
    data class FlightDetail(val id: String) : Screen()
    data class Add(val friend: Boolean, val found: List<Found>? = null, val error: String? = null) : Screen()
    data class AirportInfo(val code: String) : Screen()
    data class AirlineInfo(val code: String) : Screen()
}

object Nav {
    var tab by mutableStateOf(Tab.Flights)
    val stack = mutableStateListOf<Screen>()
    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun open(tab: Tab) { this.tab = tab; stack.clear() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        C.dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !C.dark
        if (savedInstanceState == null) handle(intent)
        setContent { AppTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Notification taps open the flight; shares from other apps go to import. */
    private fun handle(intent: Intent) {
        intent.getStringExtra("flight")?.let { id ->
            val f = Store.get(id) ?: return
            Nav.open(if (f.mine) Tab.Flights else Tab.Friends)
            Nav.push(Screen.FlightDetail(id))
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        Nav.open(Tab.Flights)
        importInto(this, stream, text, friend = false)
    }

    @Composable
    private fun Root() {
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (Prefs["askedNotifications"].isEmpty() && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                Prefs["askedNotifications"] = "1"
                notificationPermission.launch(perm)
            }
        }
        BackHandler(Nav.stack.isNotEmpty() || Nav.tab != Tab.Flights) {
            if (Nav.stack.isNotEmpty()) Nav.pop() else Nav.tab = Tab.Flights
        }
        Column(Modifier.fillMaxSize().background(C.bg)) {
            AnimatedContent(
                Nav.tab, Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "tabs",
            ) { tab ->
                Stack(Nav.stack.toList(), depth = { it.size }) { stack ->
                    when (val s = stack.lastOrNull()) {
                        null -> when (tab) {
                            Tab.Flights -> FlightsScreen(friends = false)
                            Tab.Friends -> FlightsScreen(friends = true)
                            Tab.Passport -> PassportScreen()
                            Tab.Settings -> SettingsScreen()
                        }
                        is Screen.FlightDetail -> FlightScreen(s.id)
                        is Screen.Add -> AddScreen(s)
                        is Screen.AirportInfo -> AirportScreen(s.code)
                        is Screen.AirlineInfo -> AirlineScreen(s.code)
                    }
                }
            }
            if (!WindowInsets.isImeVisibleCompat()) TabBar()
        }
    }
}

@Composable
private fun WindowInsets.Companion.isImeVisibleCompat(): Boolean = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

@Composable
private fun TabBar() {
    Column(Modifier.fillMaxWidth().background(C.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(C.separator))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            TabItem(Tab.Flights, "Flights", Icons.Filled.AirplaneTicket)
            TabItem(Tab.Friends, "Friends", Icons.Filled.Group, dot = if (Store.flights.any { !it.mine && it.phase() == Phase.InFlight }) C.green else null)
            TabItem(Tab.Passport, "Passport", Icons.Filled.Public)
            TabItem(Tab.Settings, "Settings", Icons.Filled.Settings)
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: Tab, label: String, icon: ImageVector, dot: Color? = null) {
    val selected = Nav.tab == tab
    val tint = if (selected) C.blue else C.secondary
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { Nav.open(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
            if (dot != null) Box(Modifier.align(Alignment.TopEnd).padding(start = 18.dp).size(9.dp).background(dot, CircleShape))
        }
        Text(label, fontSize = 10.sp, color = tint)
    }
}
