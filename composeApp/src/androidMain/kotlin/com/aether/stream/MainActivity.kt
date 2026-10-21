package com.aether.stream

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.aether.stream.core.navigation.AetherNavigationViewModel
import com.aether.stream.core.navigation.AetherScreen
import com.aether.stream.data.local.AetherDatabase
import com.aether.stream.data.repository.XtreamSyncRepository
import com.aether.stream.feature.dashboard.DashboardAndFilterViewModel
import com.aether.stream.feature.gatekeeper.GatekeeperViewModel
import com.aether.stream.feature.player.AndroidMedia3PlayerEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private lateinit var playerEngine: AndroidMedia3PlayerEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("aether_device_prefs", Context.MODE_PRIVATE)

        // 1. Ordner auf Fire OS vor dem allerersten Start anlegen (verhindert Blackscreen-Crash!)
        val dbFile = applicationContext.getDatabasePath("aether_stream.db")
        dbFile.parentFile?.mkdirs()

        // 2. Nativer Android-Treiber (100% stabil auf Fire TV Cube)
        val database = Room.databaseBuilder<AetherDatabase>(
            context = applicationContext,
            name = "aether_stream.db"
        )
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        val dao = database.aetherDao()

        val httpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        coerceInputValues = true
                    }
                )
            }
        }

        val syncRepository = XtreamSyncRepository(dao, httpClient)
        playerEngine = AndroidMedia3PlayerEngine(applicationContext)

        setContent {
            val navViewModel = viewModel { AetherNavigationViewModel() }
            val gatekeeperViewModel = viewModel { GatekeeperViewModel() }
            val dashboardViewModel = viewModel { DashboardAndFilterViewModel(dao, syncRepository) }
            val navState by navViewModel.uiState.collectAsState()

            LaunchedEffect(Unit) {
                val alreadyUnlocked = prefs.getBoolean("is_device_unlocked", false)
                val existingPlaylist = dao.getActivePlaylistOnce()
                if (alreadyUnlocked) {
                    navViewModel.initializeStartupState(
                        isUnlocked = true,
                        hasPlaylist = existingPlaylist != null,
                        newGroupsCount = 0
                    )
                }
            }

            LaunchedEffect(navState.currentScreen) {
                if (navState.currentScreen !is AetherScreen.GatekeeperLock) {
                    prefs.edit().putBoolean("is_device_unlocked", true).apply()
                }
            }

            BackHandler(
                enabled = navState.screenHistory.isNotEmpty() ||
                    navState.isAdminModalVisible ||
                    navState.isFilterDropDownVisible
            ) {
                navViewModel.navigateBack()
            }

            AetherApp(
                dao = dao,
                playerEngine = playerEngine,
                navigationViewModel = navViewModel,
                gatekeeperViewModel = gatekeeperViewModel,
                dashboardViewModel = dashboardViewModel
            )
        }
    }

    override fun onStop() {
        super.onStop()
        playerEngine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerEngine.release()
    }
}
