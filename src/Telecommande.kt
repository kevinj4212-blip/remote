package com.exemple.telecommande

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// ===================== Style : sombre + couleur au choix =====================
val BG = Color(0xFF0E0E12)
val CardBg = Color(0xFF1A1A22)

object Ui {
    val palette = listOf(
        "Rose" to Color(0xFFFF2BD6),
        "Cyan" to Color(0xFF00E5FF),
        "Vert" to Color(0xFF00E676),
        "Orange" to Color(0xFFFF9100),
        "Violet" to Color(0xFFB388FF),
        "Rouge" to Color(0xFFFF1744),
        "Or" to Color(0xFFFFC400)
    )
    var accentIndex by mutableStateOf(0)
    var haptics by mutableStateOf(true)
    val accent: Color get() = palette[accentIndex].second
    val dark: Color
        get() {
            val c = accent
            return Color(c.red * 0.65f, c.green * 0.65f, c.blue * 0.65f)
        }
}

// ===================== Codes des touches Android =====================
object Keys {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val BACK = 4
    const val HOME = 3
    const val MENU = 82
    const val VOL_UP = 24
    const val VOL_DOWN = 25
    const val MUTE = 164
    const val PLAY_PAUSE = 85
    const val REWIND = 89
    const val FORWARD = 90
    const val SOURCE = 178
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
}

// ===================== Connexion ADB par Wi-Fi =====================
object Adb {
    @Volatile private var conn: Dadb? = null
    private val queued = AtomicInteger(0)
    private val exec = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(exec.asCoroutineDispatcher() + SupervisorJob())

    var status by mutableStateOf("Non connecté")
    var detail by mutableStateOf("")
    var connected by mutableStateOf(false)
    var apps by mutableStateOf(emptyList<String>())

    // la bibliothèque range sa clé ADB dans « user.home »
    fun prepare(ctx: Context) {
        val home = File(ctx.filesDir, "adbhome")
        File(home, ".android").mkdirs()
        System.setProperty("user.home", home.absolutePath)
    }

    private fun open(host: String, port: Int): Dadb {
        close()
        val d = Dadb.create(host, port)
        conn = d
        return d
    }

    fun close() {
        try { conn?.close() } catch (e: Exception) { }
        conn = null
        connected = false
    }

    fun connect(host: String, port: Int) {
        scope.launch {
            status = "Connexion… (accepte sur la télé si elle le demande)"
            detail = ""
            try {
                val d = open(host, port)
                val r = d.shell("echo ok")
                if (r.exitCode == 0) {
                    connected = true
                    status = "Connecté"
                } else {
                    status = "Non connecté"
                    detail = "Réponse inattendue de la télé"
                    close()
                }
            } catch (e: Exception) {
                status = "Non connecté"
                detail = describe(e)
                close()
            }
        }
    }

    private fun run(host: String, port: Int, cmd: String, onDone: ((String) -> Unit)? = null) {
        if (queued.get() > 3) return
        queued.incrementAndGet()
        scope.launch {
            try {
                var d = conn ?: open(host, port)
                val out = try {
                    d.shell(cmd).output
                } catch (e: Exception) {
                    d = open(host, port)
                    d.shell(cmd).output
                }
                connected = true
                status = "Connecté"
                detail = ""
                onDone?.invoke(out)
            } catch (e: Exception) {
                connected = false
                status = "Non connecté"
                detail = describe(e)
                close()
            } finally {
                queued.decrementAndGet()
            }
        }
    }

    fun press(host: String, port: Int, code: Int) = run(host, port, "input keyevent $code")

    fun shell(host: String, port: Int, cmd: String) = run(host, port, cmd)

    fun text(host: String, port: Int, s: String) {
        val clean = s.filter { (it.isLetterOrDigit() && it.code < 128) || it in " .,_@:/+=-" }.replace(" ", "%s")
        if (clean.isNotEmpty()) run(host, port, "input text '$clean'")
    }

    fun launchApp(host: String, port: Int, pkg: String) =
        run(host, port, "monkey -p $pkg -c android.intent.category.LAUNCHER 1")

    fun listApps(host: String, port: Int) {
        run(host, port, "pm list packages -3") { out ->
            apps = out.lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.sorted()
        }
    }

    private fun describe(e: Exception): String =
        (e.javaClass.simpleName + " : " + (e.message ?: "")).take(160)
}

val KNOWN_APPS = mapOf(
    "com.google.android.youtube.tv" to "YouTube",
    "com.netflix.ninja" to "Netflix",
    "com.amazon.amazonvideo.livingroom" to "Prime Video",
    "com.android.chrome" to "Chrome",
    "org.videolan.vlc" to "VLC",
    "com.termux" to "Termux",
    "com.exemple.ps5launcher" to "Mon accueil",
    "com.google.android.apps.tv.launcherx" to "Google TV",
    "com.spotify.tv.android" to "Spotify",
    "com.disney.disneyplus" to "Disney+"
)

fun prettyName(pkg: String): String {
    KNOWN_APPS[pkg]?.let { return it }
    val skip = setOf("com", "org", "net", "io", "android", "google", "app", "apps", "www", "tv")
    val parts = pkg.split('.').filter { it !in skip }.takeLast(2)
    val name = if (parts.isEmpty()) pkg else parts.joinToString(" ")
    return name.replaceFirstChar { it.uppercase() }
}

// ===================== Activité =====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Adb.prepare(this)
        val prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
        Ui.accentIndex = prefs.getInt("accent", 0).coerceIn(0, Ui.palette.lastIndex)
        Ui.haptics = prefs.getBoolean("haptics", true)
        setContent { RemoteApp(prefs) }
    }
}

@Composable
fun RemoteApp(prefs: SharedPreferences) {
    var tab by remember { mutableStateOf(0) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "10.59.220.72") ?: "10.59.220.72") }
    var port by remember { mutableStateOf(prefs.getString("port", "5555") ?: "5555") }
    var pin by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    val host = ip.trim()
    val prt = port.trim().toIntOrNull() ?: 5555

    LaunchedEffect(ip, port, pin) {
        prefs.edit().putString("ip", ip).putString("port", port).putString("pin", pin).apply()
    }
    LaunchedEffect(Unit) { Adb.connect(host, prt) }

    Column(Modifier.fillMaxSize().background(BG)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> RemoteTab(host, prt)
                1 -> AppsTab(host, prt)
                2 -> WebTab(host, pin)
                else -> ConnectTab(prefs, ip, port, pin, { ip = it }, { port = it }, { pin = it })
            }
        }
        BottomBar(tab) { tab = it }
    }
}

// ===================== Onglet 1 : télécommande =====================
@Composable
fun RemoteTab(host: String, port: Int) {
    var mode by remember { mutableStateOf(0) }
    val send: (Int) -> Unit = { Adb.press(host, port, it) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusChip(host, port)
        Spacer(Modifier.height(14.dp))
        ModeSwitch(mode) { mode = it }
        Spacer(Modifier.height(18.dp))
        when (mode) {
            0 -> ButtonsMode(send)
            1 -> {
                TouchPad(send)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyButton("↩ Retour", Keys.BACK, send, Modifier.weight(1f).height(60.dp))
                    KeyButton("⌂ Home", Keys.HOME, send, Modifier.weight(1f).height(60.dp))
                    KeyButton("🔇", Keys.MUTE, send, Modifier.weight(1f).height(60.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyButton("🔉  −", Keys.VOL_DOWN, send, Modifier.weight(1f).height(60.dp), repeat = true)
                    KeyButton("🔊  +", Keys.VOL_UP, send, Modifier.weight(1f).height(60.dp), repeat = true)
                }
            }
            else -> KeyboardMode(host, port, send)
        }
    }
}

@Composable
fun ModeSwitch(mode: Int, onMode: (Int) -> Unit) {
    val items = listOf("🎮 Boutons", "👆 Pavé", "⌨ Clavier")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { i, label ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (mode == i) Ui.accent else Color.Transparent)
                    .clickable { onMode(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = Color.White, fontSize = 14.sp, fontWeight = if (mode == i) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun ButtonsMode(send: (Int) -> Unit) {
    Box(
        Modifier.size(290.dp).clip(CircleShape).background(CardBg).border(2.dp, Ui.accent.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        KeyButton("▲", Keys.UP, send, Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(100.dp, 84.dp), repeat = true, flat = true)
        KeyButton("▼", Keys.DOWN, send, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(100.dp, 84.dp), repeat = true, flat = true)
        KeyButton("◀", Keys.LEFT, send, Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(84.dp, 100.dp), repeat = true, flat = true)
        KeyButton("▶", Keys.RIGHT, send, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(84.dp, 100.dp), repeat = true, flat = true)
        KeyButton("OK", Keys.OK, send, Modifier.size(108.dp), round = true)
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyButton("↩ Retour", Keys.BACK, send, Modifier.weight(1f).height(64.dp))
        KeyButton("⌂ Home", Keys.HOME, send, Modifier.weight(1f).height(64.dp))
        KeyButton("☰ Menu", Keys.MENU, send, Modifier.weight(1f).height(64.dp))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyButton("🔉  −", Keys.VOL_DOWN, send, Modifier.weight(1f).height(64.dp), repeat = true)
        KeyButton("🔇 Muet", Keys.MUTE, send, Modifier.weight(1f).height(64.dp))
        KeyButton("🔊  +", Keys.VOL_UP, send, Modifier.weight(1f).height(64.dp), repeat = true)
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyButton("⏪", Keys.REWIND, send, Modifier.weight(1f).height(64.dp))
        KeyButton("⏯", Keys.PLAY_PAUSE, send, Modifier.weight(1f).height(64.dp))
        KeyButton("⏩", Keys.FORWARD, send, Modifier.weight(1f).height(64.dp))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyButton("📺 Source", Keys.SOURCE, send, Modifier.weight(1f).height(64.dp))
        KeyButton("⏻ Veille", Keys.POWER, send, Modifier.weight(1f).height(64.dp))
    }
}

@Composable
fun TouchPad(send: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val sendNow by rememberUpdatedState(send)
    var ax by remember { mutableStateOf(0f) }
    var ay by remember { mutableStateOf(0f) }
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clip(shape)
            .background(CardBg)
            .border(2.dp, Ui.accent.copy(alpha = 0.6f), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        sendNow(Keys.OK)
                        if (Ui.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onLongPress = {
                        sendNow(Keys.BACK)
                        if (Ui.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { ax = 0f; ay = 0f }) { change, drag ->
                    change.consume()
                    ax += drag.x
                    ay += drag.y
                    val step = 60f
                    if (abs(ax) > step || abs(ay) > step) {
                        val key = if (abs(ax) > abs(ay)) {
                            if (ax > 0) Keys.RIGHT else Keys.LEFT
                        } else {
                            if (ay > 0) Keys.DOWN else Keys.UP
                        }
                        sendNow(key)
                        if (Ui.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        ax = 0f
                        ay = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🖐", fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text("Glisse pour naviguer", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Touche = OK  •  Appui long = Retour", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

@Composable
fun KeyboardMode(host: String, port: Int, send: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledField("Texte à écrire sur la télé", text, KeyboardType.Text) { text = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyButton("⌫ Effacer", Keys.DEL, send, Modifier.weight(1f).height(60.dp), repeat = true)
            KeyButton("⏎ Entrée", Keys.ENTER, send, Modifier.weight(1f).height(60.dp))
        }
        PinkButton("📤 Envoyer le texte", Modifier.fillMaxWidth()) { Adb.text(host, port, text) }
        Text(
            "Place d'abord le curseur dans un champ de texte sur la télé (recherche YouTube, par exemple). " +
                "Les lettres accentuées ne passent pas : c'est une limite d'Android.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp
        )
    }
}

@Composable
fun KeyButton(
    label: String, code: Int, send: (Int) -> Unit,
    modifier: Modifier = Modifier.size(88.dp), repeat: Boolean = false,
    flat: Boolean = false, round: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sendNow by rememberUpdatedState(send)
    var pressed by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (pressed) 0.92f else 1f, tween(80), label = "key")
    val shape = if (round) CircleShape else RoundedCornerShape(20.dp)
    val bg = if (flat) {
        if (pressed) Ui.accent.copy(alpha = 0.3f) else Color.Transparent
    } else {
        if (pressed) Ui.dark else Ui.accent
    }
    Box(
        modifier
            .scale(sc)
            .clip(shape)
            .background(bg)
            .pointerInput(code) {
                detectTapGestures(onPress = {
                    pressed = true
                    if (Ui.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sendNow(code)
                    val job = if (repeat) {
                        scope.launch {
                            delay(450)
                            while (true) { sendNow(code); delay(160) }
                        }
                    } else null
                    tryAwaitRelease()
                    job?.cancel()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (flat) Ui.accent else Color.White,
            fontSize = if (flat) 30.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun StatusChip(host: String, port: Int) {
    val color = when {
        Adb.connected -> Color(0xFF4CAF50)
        Adb.status.startsWith("Connexion") -> Color(0xFFFFB300)
        else -> Color(0xFFFF5252)
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { Adb.connect(host, port) }
            .background(CardBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(8.dp))
            Text(Adb.status + "  •  " + host, color = Color.White, fontSize = 14.sp)
        }
        if (Adb.detail.isNotEmpty()) {
            Text(Adb.detail, color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

// ===================== Onglet 2 : applis de la télé =====================
@Composable
fun AppsTab(host: String, port: Int) {
    LaunchedEffect(host) { Adb.listApps(host, port) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Raccourcis", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PinkButton("🏠 Mon accueil", Modifier.weight(1f)) {
                Adb.shell(host, port, "am start -n com.exemple.ps5launcher/.MainActivity")
            }
            PinkButton("🛠️ Paramètres", Modifier.weight(1f)) {
                Adb.shell(host, port, "am start -a android.settings.SETTINGS")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PinkButton("📶 Wi-Fi", Modifier.weight(1f)) {
                Adb.shell(host, port, "am start -a android.settings.WIFI_SETTINGS")
            }
            PinkButton("🔄 Actualiser", Modifier.weight(1f)) { Adb.listApps(host, port) }
        }
        Spacer(Modifier.height(6.dp))
        Text("Applis installées (" + Adb.apps.size + ")", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (Adb.apps.isEmpty()) {
            Text("Aucune appli trouvée. Vérifie que la télé est connectée (onglet Télécommande).", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        Adb.apps.forEach { pkg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .clickable { Adb.launchApp(host, port, pkg) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Ui.accent), contentAlignment = Alignment.Center) {
                    Text(prettyName(pkg).take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(prettyName(pkg), color = Color.White, fontSize = 17.sp)
                    Text(pkg, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                }
                Text("▶", color = Ui.accent, fontSize = 18.sp)
            }
        }
    }
}

// ===================== Onglet 3 : page web de réglages =====================
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebTab(host: String, pin: String) {
    val url = "http://$host:8080"
    var pending by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        pending?.onReceiveValue(uris.toTypedArray())
        pending = null
    }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var tried by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(CardBg).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(url, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            PinkButton("↻ Recharger") {
                tried = false
                error = ""
                webRef?.loadUrl(url)
            }
        }
        if (error.isNotEmpty()) {
            Text(error, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF111111.toInt())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            val digits = pin.filter { c -> c.isDigit() }
                            if (digits.isNotEmpty() && !tried) {
                                tried = true
                                view.evaluateJavascript(
                                    "(function(){var i=document.querySelector('input[name=pin]');" +
                                        "if(i){i.value='" + digits + "';i.form.submit();}})()",
                                    null
                                )
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                            if (request.isForMainFrame) {
                                error = "Impossible de joindre la télé. Vérifie qu'elle est allumée et sur le même Wi-Fi."
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                        ): Boolean {
                            pending?.onReceiveValue(null)
                            pending = callback
                            picker.launch("image/*")
                            return true
                        }
                    }
                    loadUrl(url)
                    webRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ===================== Onglet 4 : connexion et personnalisation =====================
@Composable
fun ConnectTab(
    prefs: SharedPreferences,
    ip: String, port: String, pin: String,
    onIp: (String) -> Unit, onPort: (String) -> Unit, onPin: (String) -> Unit
) {
    val prt = port.trim().toIntOrNull() ?: 5555
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Connexion à la télé", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        LabeledField("Adresse IP de la télé", ip, KeyboardType.Uri, onIp)
        LabeledField("Port ADB", port, KeyboardType.Number, onPort)
        LabeledField("Code PIN de la page web (facultatif : connexion automatique)", pin, KeyboardType.NumberPassword, onPin)
        PinkButton("🔌 Se connecter / tester", Modifier.fillMaxWidth()) { Adb.connect(ip.trim(), prt) }
        StatusChip(ip.trim(), prt)

        Spacer(Modifier.height(6.dp))
        Text("Personnalisation", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Couleur de l'appli", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ui.palette.forEachIndexed { i, entry ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(entry.second)
                        .border(if (Ui.accentIndex == i) 3.dp else 0.dp, Color.White, CircleShape)
                        .clickable {
                            Ui.accentIndex = i
                            prefs.edit().putInt("accent", i).apply()
                        }
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .clickable {
                    Ui.haptics = !Ui.haptics
                    prefs.edit().putBoolean("haptics", Ui.haptics).apply()
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Vibration au toucher", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(if (Ui.haptics) "Oui" else "Non", color = Ui.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Text(
            "Sur la télé : Paramètres > Préférences de l'appareil > Options pour les développeurs > " +
                "activer le débogage USB / ADB. À la première connexion, la télé demande d'autoriser " +
                "cet appareil : choisis « Toujours autoriser ».\n\n" +
                "Le téléphone et la télé doivent être sur le même Wi-Fi. Si l'adresse IP de la télé change, " +
                "modifie-la ici.",
            color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp
        )
    }
}

@Composable
fun LabeledField(label: String, value: String, keyboard: KeyboardType, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            cursorBrush = SolidColor(Ui.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, Ui.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        )
    }
}

// ===================== Éléments communs =====================
@Composable
fun PinkButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Ui.accent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf("🎮" to "Télécommande", "📱" to "Applis", "🛠️" to "Réglages TV", "🔧" to "Connexion")
    Row(Modifier.fillMaxWidth().background(CardBg).padding(vertical = 6.dp)) {
        items.forEachIndexed { i, item ->
            Column(
                Modifier.weight(1f).clickable { onTab(i) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(item.first, fontSize = 22.sp)
                Text(
                    item.second,
                    color = if (tab == i) Ui.accent else Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
