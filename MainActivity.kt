package com.turboclean

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress

private val Background = Color(0xFF070B14)
private val Surface = Color(0xFF111827)
private val SurfaceAlt = Color(0xFF172033)
private val Blue = Color(0xFF168BFF)
private val Green = Color(0xFF58FF9A)
private val Primary = Color(0xFFF4F8FF)
private val Secondary = Color(0xFF9AA9C2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[TurboCleanViewModel::class.java]
        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                TurboCleanScreen(
                    state = state,
                    onScan = model::scanGarbage,
                    onDelete = model::deleteSelected,
                    onNetwork = model::diagnoseNetwork,
                    onStoragePermission = ::openAllFilesSettings,
                    onUsagePermission = ::openUsageSettings,
                    onStorageGuide = ::openStorageSettings
                )
            }
        }
    }

    private fun openAllFilesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } else Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        startActivity(intent)
    }
    private fun openUsageSettings() = startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    private fun openStorageSettings() = startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
}

data class GarbageItem(val name: String, val path: String, val bytes: Long, val category: String)
data class TurboState(
    val performance: Int = 82,
    val garbage: String = "Toca para analizar",
    val network: String = "Sin diagnóstico",
    val scanning: Boolean = false,
    val items: List<GarbageItem> = emptyList()
)

class TurboCleanViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val _state = MutableStateFlow(TurboState())
    val state: StateFlow<TurboState> = _state.asStateFlow()

    fun scanGarbage() = viewModelScope.launch {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            _state.value = _state.value.copy(garbage = "Concede acceso a archivos para analizar")
            return@launch
        }
        _state.value = _state.value.copy(scanning = true, garbage = "Analizando archivos permitidos…")
        val found = StorageCleaner.scan()
        _state.value = _state.value.copy(scanning = false, items = found,
            garbage = if (found.isEmpty()) "No se detectó basura segura" else "${found.size} elementos para revisar")
    }

    fun deleteSelected() = viewModelScope.launch {
        val deleted = StorageCleaner.delete(_state.value.items)
        _state.value = _state.value.copy(items = emptyList(), garbage = "${deleted.size} elementos eliminados")
    }

    fun diagnoseNetwork() = viewModelScope.launch {
        _state.value = _state.value.copy(network = "Midiendo latencia…")
        val result = NetworkDiagnostics.run(context)
        _state.value = _state.value.copy(network = result.message, performance = result.performance)
    }
}

/** Only scans shared locations where the user has granted access. */
object StorageCleaner {
    suspend fun scan(): List<GarbageItem> = withContext(Dispatchers.IO) {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory()
        ).filter { it.exists() && it.canRead() }.distinctBy { it.canonicalPath }
        val blocked = listOf("/Android/data", "/Android/obb")
        val found = mutableListOf<GarbageItem>()
        roots.forEach { root -> runCatching {
            root.walkTopDown().maxDepth(7).onEnter { dir -> blocked.none { dir.path.contains(it) } }.forEach { file ->
                when {
                    file.isFile && file.extension.lowercase() in setOf("tmp", "log") ->
                        found += GarbageItem(file.name, file.path, file.length(), "Archivo temporal")
                    file.isDirectory && file.listFiles()?.isEmpty() == true ->
                        found += GarbageItem(file.name.ifBlank { "Carpeta vacía" }, file.path, 0, "Carpeta vacía")
                }
            }
        } }
        found.distinctBy { it.path }.sortedByDescending { it.bytes }
    }
    suspend fun delete(items: List<GarbageItem>): List<GarbageItem> = withContext(Dispatchers.IO) {
        items.filter { File(it.path).let { file -> file.exists() && file.delete() } }
    }
}

private data class NetworkResult(val message: String, val performance: Int)
private object NetworkDiagnostics {
    suspend fun run(context: Context): NetworkResult = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return@withContext NetworkResult("Sin conexión. Revisa Wi‑Fi o datos móviles.", 35)
        val caps = manager.getNetworkCapabilities(network) ?: return@withContext NetworkResult("Red no disponible.", 35)
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            return@withContext NetworkResult("La red no tiene Internet validado.", 45)
        val start = System.nanoTime()
        val latency = runCatching {
            network.socketFactory.createSocket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 3000) }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
        when {
            latency == null -> NetworkResult("No se pudo medir; prueba otra red o DNS.", 55)
            latency < 70 -> NetworkResult("Red estable: ${latency} ms. Buena para juegos.", 92)
            latency < 150 -> NetworkResult("${latency} ms: usa Wi‑Fi 5 GHz si está disponible.", 75)
            else -> NetworkResult("${latency} ms: acércate al router y limita datos en segundo plano.", 58)
        }
    }
}

private data class CardInfo(val title: String, val subtitle: String, val value: String, val icon: ImageVector, val accent: Color, val action: () -> Unit)

@Composable private fun TurboCleanScreen(state: TurboState, onScan: () -> Unit, onDelete: () -> Unit, onNetwork: () -> Unit, onStoragePermission: () -> Unit, onUsagePermission: () -> Unit, onStorageGuide: () -> Unit) {
    val cards = listOf(
        CardInfo("Limpiador de Basura", "Archivos temporales y carpetas vacías", state.garbage, Icons.Rounded.AutoDelete, Green, onScan),
        CardInfo("Liberador de RAM", "Android administra la memoria", "Revisa el uso de apps", Icons.Rounded.Memory, Blue, onUsagePermission),
        CardInfo("Optimizar Red", "Latencia y conexión", state.network, Icons.Rounded.NetworkCheck, Green, onNetwork),
        CardInfo("Gestión de Permisos", "Archivos y estadísticas de uso", "Configurar accesos", Icons.Rounded.Security, Blue, onStoragePermission)
    )
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TurboClean", color = Primary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Optimización segura del dispositivo", color = Secondary, modifier = Modifier.padding(top = 4.dp))
            PerformanceIndicator(state.performance, state.scanning, onScan, Modifier.padding(vertical = 22.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(cards) { OptimizationCard(it) }
                if (state.items.isNotEmpty()) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Resultados: revisa antes de borrar", color = Primary, fontWeight = FontWeight.Bold)
                            state.items.take(12).forEach { Text("• ${it.category}: ${it.name}", color = Secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) }
                            Text("Eliminar elementos mostrados", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp).clickable(onClick = onDelete))
                        }
                    }
                }
                item { Text("Abrir almacenamiento del sistema", color = Blue, modifier = Modifier.padding(4.dp).clickable(onClick = onStorageGuide)) }
            }
        }
    }
}

@Composable private fun PerformanceIndicator(performance: Int, scanning: Boolean, action: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(1f, 1.035f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "scale")
    Card(modifier = modifier.size(210.dp).scale(scale).clickable(onClick = action), shape = RoundedCornerShape(105.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator({ performance.coerceIn(0, 100) / 100f }, Modifier.size(150.dp), Green, SurfaceAlt, 12.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$performance%", color = Primary, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text(if (scanning) "ANALIZANDO…" else "TOCA PARA ANALIZAR", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun OptimizationCard(info: CardInfo) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = info.action), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(info.icon, info.title, tint = info.accent, modifier = Modifier.size(30.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(info.title, color = Primary, fontWeight = FontWeight.Bold)
                Text(info.subtitle, color = Secondary, fontSize = 12.sp)
                Text(info.value, color = info.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF070B14)
@Composable private fun TurboCleanPreview() = MaterialTheme {
    TurboCleanScreen(TurboState(garbage = "4 elementos para revisar", network = "Red estable: 42 ms"), {}, {}, {}, {}, {}, {})
}
