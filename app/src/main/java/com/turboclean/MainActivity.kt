package com.turboclean

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress

private val TurboBackground = Color(0xFF070B14)
private val TurboSurface = Color(0xFF111827)
private val TurboSurfaceAlt = Color(0xFF172033)
private val ElectricBlue = Color(0xFF168BFF)
private val NeonGreen = Color(0xFF58FF9A)
private val TextPrimary = Color(0xFFF4F8FF)
private val TextSecondary = Color(0xFF9AA9C2)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = ViewModelProvider(this)[TurboCleanViewModel::class.java]

        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                TurboCleanScreen(
                    state = state,
                    onClean = viewModel::scanGarbage,
                    onNetwork = viewModel::diagnoseNetwork,
                    onPermissions = ::openStorageAccessSettings
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val viewModel = ViewModelProvider(this)[TurboCleanViewModel::class.java]
        viewModel.refreshPermissionStatus()
    }

    private fun openStorageAccessSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
        } else {
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        }

        startActivity(intent)
    }
}

data class GarbageItem(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val category: String
)

data class TurboCleanUiState(
    val performance: Int = 82,
    val garbageText: String = "Toca para analizar",
    val networkText: String = "Sin diagnóstico",
    val permissionText: String = "Configurar accesos",
    val isScanning: Boolean = false,
    val garbageItems: List<GarbageItem> = emptyList()
)

class TurboCleanViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(TurboCleanUiState())
    val uiState: StateFlow<TurboCleanUiState> = _uiState.asStateFlow()

    init {
        refreshPermissionStatus()
    }

    fun refreshPermissionStatus() {
        val storageGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

        _uiState.value = _uiState.value.copy(
            permissionText = if (storageGranted) {
                "Acceso a archivos activo"
            } else {
                "Autoriza acceso a archivos"
            }
        )
    }

    fun scanGarbage() {
        viewModelScope.launch {
            val needsAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

            if (needsAllFilesAccess && !Environment.isExternalStorageManager()) {
                _uiState.value = _uiState.value.copy(
                    garbageText = "Autoriza acceso a archivos primero"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isScanning = true,
                garbageText = "Analizando almacenamiento…"
            )

            val result = StorageCleaner.scanGarbage()

            _uiState.value = _uiState.value.copy(
                isScanning = false,
                garbageItems = result,
                garbageText = when {
                    result.isEmpty() -> "No se detectó basura segura"
                    else -> "${result.size} elementos detectados"
                }
            )
        }
    }

    fun diagnoseNetwork() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                networkText = "Midiendo latencia…"
            )

            val result = NetworkDiagnostics.diagnose(context)

            _uiState.value = _uiState.value.copy(
                networkText = result.recommendation,
                performance = result.performance
            )
        }
    }
}

object StorageCleaner {

    suspend fun scanGarbage(): List<GarbageItem> = withContext(Dispatchers.IO) {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory()
        )
            .filter { it.exists() && it.canRead() }
            .distinctBy { it.absolutePath }

        val ignoredPaths = listOf(
            "/Android/data",
            "/Android/obb",
            "/Android/media"
        )

        val results = mutableListOf<GarbageItem>()

        roots.forEach { root ->
            runCatching {
                root.walkTopDown()
                    .onEnter { folder ->
                        ignoredPaths.none { ignoredPath ->
                            folder.absolutePath.contains(ignoredPath)
                        }
                    }
                    .maxDepth(8)
                    .forEach { file ->
                        when {
                            file.isFile &&
                                file.extension.lowercase() in setOf("tmp", "log") -> {
                                results += GarbageItem(
                                    name = file.name,
                                    path = file.absolutePath,
                                    sizeBytes = file.length(),
                                    category = "Archivo temporal"
                                )
                            }

                            file.isDirectory &&
                                file.listFiles()?.isEmpty() == true -> {
                                results += GarbageItem(
                                    name = file.name.ifBlank { "Carpeta vacía" },
                                    path = file.absolutePath,
                                    sizeBytes = 0L,
                                    category = "Carpeta vacía"
                                )
                            }
                        }
                    }
            }
        }

        results
            .distinctBy { it.path }
            .sortedByDescending { it.sizeBytes }
    }

    suspend fun deleteSelected(items: List<GarbageItem>): List<GarbageItem> =
        withContext(Dispatchers.IO) {
            items.filter { item ->
                val file = File(item.path)
                file.exists() && file.delete()
            }
        }
}

data class NetworkDiagnostic(
    val latencyMs: Long?,
    val recommendation: String,
    val performance: Int
)

object NetworkDiagnostics {

    suspend fun diagnose(context: Context): NetworkDiagnostic =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

            val network = manager.activeNetwork
                ?: return@withContext NetworkDiagnostic(
                    latencyMs = null,
                    recommendation = "Sin conexión. Revisa Wi‑Fi o datos móviles.",
                    performance = 35
                )

            val capabilities = manager.getNetworkCapabilities(network)
                ?: return@withContext NetworkDiagnostic(
                    latencyMs = null,
                    recommendation = "Conexión no disponible.",
                    performance = 35
                )

            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return@withContext NetworkDiagnostic(
                    latencyMs = null,
                    recommendation = "La red no tiene acceso validado a Internet.",
                    performance = 45
                )
            }

            val startTime = System.nanoTime()

            val latency = runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress("1.1.1.1", 443), 3_000)
                }

                (System.nanoTime() - startTime) / 1_000_000
            }.getOrNull()

            when {
                latency == null -> NetworkDiagnostic(
                    latencyMs = null,
                    recommendation = "No se pudo medir. Prueba otra red o revisa DNS.",
                    performance = 55
                )

                latency < 70 -> NetworkDiagnostic(
                    latencyMs = latency,
                    recommendation = "Red estable: $latency ms. Ideal para juegos.",
                    performance = 92
                )

                latency < 150 -> NetworkDiagnostic(
                    latencyMs = latency,
                    recommendation = "Latencia media: $latency ms. Usa Wi‑Fi de 5 GHz si está disponible.",
                    performance = 75
                )

                else -> NetworkDiagnostic(
                    latencyMs = latency,
                    recommendation = "Latencia alta: $latency ms. Acércate al router o limita datos en segundo plano.",
                    performance = 58
                )
            }
        }
}

data class OptimizationCardModel(
    val title: String,
    val subtitle: String,
    val value: String,
    val accent: Color,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun TurboCleanScreen(
    state: TurboCleanUiState,
    onClean: () -> Unit,
    onNetwork: () -> Unit,
    onPermissions: () -> Unit
) {
    val cards = listOf(
        OptimizationCardModel(
            title = "Limpiador de Basura",
            subtitle = "Archivos .tmp y carpetas vacías",
            value = state.garbageText,
            accent = NeonGreen,
            icon = Icons.Rounded.AutoDelete,
            onClick = onClean
        ),
        OptimizationCardModel(
            title = "Liberador de RAM",
            subtitle = "Android administra la memoria",
            value = "Consulta procesos y uso",
            accent = ElectricBlue,
            icon = Icons.Rounded.Memory,
            onClick = {}
        ),
        OptimizationCardModel(
            title = "Optimizar Red",
            subtitle = "Latencia y calidad de conexión",
            value = state.networkText,
            accent = NeonGreen,
            icon = Icons.Rounded.NetworkCheck,
            onClick = onNetwork
        ),
        OptimizationCardModel(
            title = "Gestión de Permisos",
            subtitle = "Acceso a archivos y uso de apps",
            value = state.permissionText,
            accent = ElectricBlue,
            icon = Icons.Rounded.Security,
            onClick = onPermissions
        )
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TurboBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TurboClean",
                color = TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Optimización segura del dispositivo",
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )

            PerformanceIndicator(
                performance = state.performance,
                isScanning = state.isScanning,
                onClick = onClean,
                modifier = Modifier.padding(vertical = 30.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(cards, key = { it.title }) { card ->
                    OptimizationCard(card)
                }
            }
        }
    }
}

@Composable
fun PerformanceIndicator(
    performance: Int,
    isScanning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "performancePulse")

    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "performanceScale"
    )

    Card(
        modifier = modifier
            .size(218.dp)
            .scale(scale)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(109.dp),
        colors = CardDefaults.cardColors(containerColor = TurboSurface)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = performance.coerceIn(0, 100).toFloat() / 100f,
                modifier = Modifier.size(154.dp),
                color = NeonGreen,
                trackColor = TurboSurfaceAlt,
                strokeWidth = 12.dp
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$performance%",
                    color = TextPrimary,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (isScanning) "ANALIZANDO…" else "TOCA PARA ANALIZAR",
                    color = ElectricBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun OptimizationCard(model: OptimizationCardModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = model.onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = TurboSurface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = model.icon,
                contentDescription = model.title,
                tint = model.accent,
                modifier = Modifier.size(30.dp)
            )

            Text(
                text = model.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )

            Text(
                text = model.subtitle,
                color = TextSecondary,
                fontSize = 12.sp
            )

            Text(
                text = model.value,
                color = model.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF070B14)
@Composable
fun TurboCleanPreview() {
    MaterialTheme {
        TurboCleanScreen(
            state = TurboCleanUiState(
                garbageText = "1,24 GB detectados",
                networkText = "Red estable: 42 ms"
            ),
            onClean = {},
            onNetwork = {},
            onPermissions = {}
        )
    }
}
