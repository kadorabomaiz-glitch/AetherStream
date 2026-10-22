package com.aether.stream

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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

    private var playerEngine: AndroidMedia3PlayerEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        var fatalError by mutableStateOf<String?>(null)

        try {
            val prefs = getSharedPreferences("aether_device_prefs", Context.MODE_PRIVATE)

            // 1. Verzeichnis für Room SQLite auf Fire OS sicherstellen
            val dbFile = applicationContext.getDatabasePath("aether_stream.db")
            dbFile.parentFile?.mkdirs()

            // 2. Room mit BundledSQLiteDriver und Migrations-Toleranz instanziieren
            val database = Room.databaseBuilder(
                context = applicationContext,
                klass = AetherDatabase::class.java,
                name = dbFile.absolutePath
            )
                .setDriver(BundledSQLiteDriver())
                .fallbackToDestructiveMigration(true)
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
            val engine = AndroidMedia3PlayerEngine(applicationContext)
            playerEngine = engine

            setContent {
                if (fatalError != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF07090E))
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SelectionContainer {
                            Text(
                                text = "AETHER START-DIAGNOSE:\n\n$fatalError",
                                color = Color(0xFF00F0FF),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        }
                    }
                } else {
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
                        playerEngine = engine,
                        navigationViewModel = navViewModel,
                        gatekeeperViewModel = gatekeeperViewModel,
                        dashboardViewModel = dashboardViewModel
                    )
                }
            }
        } catch (t: Throwable) {
            fatalError = t.stackTraceToString()
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF07090E))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SelectionContainer {
                        Text(
                            text = "AETHER SYSTEMFEHLER BEIM START:\n\n$fatalError",
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        playerEngine?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerEngine?.release()
    }
}
