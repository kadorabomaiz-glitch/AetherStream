package com.aether.stream

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aether.stream.core.navigation.AetherNavigationViewModel
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

        val dbFile = applicationContext.getDatabasePath("aether_stream.db")
        val database = Room.databaseBuilder<AetherDatabase>(
            context = applicationContext,
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
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