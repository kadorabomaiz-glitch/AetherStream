package com.aether.stream

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aether.stream.core.navigation.AetherNavigationViewModel
import com.aether.stream.data.local.AetherDatabase
import com.aether.stream.data.repository.XtreamSyncRepository
import com.aether.stream.feature.dashboard.DashboardAndFilterViewModel
import com.aether.stream.feature.gatekeeper.GatekeeperViewModel
import com.aether.stream.feature.player.IosAvPlayerEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.serialization.json.Json
import platform.Foundation.NSHomeDirectory

fun MainViewController() = ComposeUIViewController {
    val database = remember {
        val dbFilePath = NSHomeDirectory() + "/Documents/aether_stream.db"
        Room.databaseBuilder<AetherDatabase>(name = dbFilePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
    val dao = remember { database.aetherDao() }

    val httpClient = remember {
        HttpClient(Darwin) {
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
    }

    val syncRepo = remember { XtreamSyncRepository(dao, httpClient) }
    val playerEngine = remember { IosAvPlayerEngine() }
    val navVm = remember { AetherNavigationViewModel() }
    val gatekeeperVm = remember { GatekeeperViewModel() }
    val dashboardVm = remember { DashboardAndFilterViewModel(dao, syncRepo) }

    AetherApp(
        dao = dao,
        playerEngine = playerEngine,
        navigationViewModel = navVm,
        gatekeeperViewModel = gatekeeperVm,
        dashboardViewModel = dashboardVm
    )
}