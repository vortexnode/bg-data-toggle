package app.bgdata.toggle

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

data class AppItem(val label: String, val pkg: String, val uid: Int, val system: Boolean)

object Sh {
    // Shizuku.newProcess is private in newer API versions, so call it by reflection.
    fun run(cmd: String): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        return code to (out + err).trim()
    }
}

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    return pm.getInstalledApplications(0)
        .map {
            AppItem(
                label = it.loadLabel(pm).toString(),
                pkg = it.packageName,
                uid = it.uid,
                system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .sortedBy { it.label.lowercase() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Root() }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { Content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Content() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var blacklist by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var shizuku by remember { mutableStateOf(0) } // 0 not running, 1 no permission, 2 ready
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    fun refresh() {
        thread {
            try {
                val (code, out) = Sh.run("cmd netpolicy list restrict-background-blacklist")
                if (code == 0) {
                    blacklist = Regex("\\d+").findAll(out).map { it.value.toInt() }.toSet()
                    error = ""
                } else {
                    error = out
                }
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
        }
    }

    fun check() {
        shizuku = when {
            !Shizuku.pingBinder() -> 0
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> 1
            else -> 2
        }
        if (shizuku == 2) refresh()
    }

    fun toggle(uid: Int, allow: Boolean) {
        blacklist = if (allow) blacklist - uid else blacklist + uid
        thread {
            try {
                val sub = if (allow) "remove" else "add"
                val (code, out) = Sh.run("cmd netpolicy $sub restrict-background-blacklist $uid")
                if (code != 0) error = out
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        thread { apps = loadApps(ctx) }
        check()
    }

    DisposableEffect(Unit) {
        val onBinder = Shizuku.OnBinderReceivedListener { check() }
        val onPerm = Shizuku.OnRequestPermissionResultListener { _, _ -> check() }
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addRequestPermissionResultListener(onPerm)
        onDispose {
            Shizuku.removeBinderReceivedListener(onBinder)
            Shizuku.removeRequestPermissionResultListener(onPerm)
        }
    }

    val filtered = apps.filter {
        (showSystem || !it.system) &&
            (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Мобильные фоновые данные") }) }) { pad ->
        Column(Modifier.padding(pad)) {
            if (shizuku == 0) {
                Banner("Shizuku не запущен. Запусти его и вернись сюда.", "Проверить") { check() }
            } else if (shizuku == 1) {
                Banner("Нужен доступ к Shizuku.", "Дать доступ") { Shizuku.requestPermission(1) }
            }
            if (error.isNotEmpty()) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Поиск") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Показывать системные", Modifier.weight(1f))
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
            }
            LazyColumn {
                items(filtered, key = { it.pkg }) { app ->
                    val allowed = app.uid !in blacklist
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.pkg)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                app.pkg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allowed,
                            onCheckedChange = { toggle(app.uid, it) },
                            enabled = shizuku == 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Banner(text: String, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Spacer(Modifier.size(40.dp))
    }
}
