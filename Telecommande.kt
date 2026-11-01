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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

// ===================== Couleurs (sombre + rose comme la page web) =====================
val BG = Color(0xFF111111)
val Pink = Color(0xFFFF2BD6)
val PinkDark = Color(0xFFB3199A)

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

    fun press(host: String, port: Int, code: Int) {
        if (queued.get() > 3) return
        queued.incrementAndGet()
        scope.launch {
            try {
                var d = conn ?: open(host, port)
                try {
                    d.shell("input keyevent $code")
                } catch (e: Exception) {
                    d = open(host, port)
                    d.shell("input keyevent $code")
                }
                connected = true
                status = "Connecté"
                detail = ""
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

    private fun describe(e: Exception): String =
        (e.javaClass.simpleName + " : " + (e.message ?: "")).take(160)
}

// ===================== Activité =====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Adb.prepare(this)
        val prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
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
                1 -> WebTab(host, pin)
                else -> ConnectTab(ip, port, pin, { ip = it }, { port = it }, { pin = it })
            }
        }
        BottomBar(tab) { tab = it }
    }
}

// ===================== Onglet 1 : télécommande =====================
@Composable
fun RemoteTab(host: String, port: Int) {
    val send: (Int) -> Unit = { Adb.press(host, port, it) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusChip(host, port)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.size(88.dp))
            KeyButton("▲", Keys.UP, send, repeat = true)
            Spacer(Modifier.size(88.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyButton("◀", Keys.LEFT, send, repeat = true)
            KeyButton("OK", Keys.OK, send)
            KeyButton("▶", Keys.RIGHT, send, repeat = true)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.size(88.dp))
            KeyButton("▼", Keys.DOWN, send, repeat = true)
            Spacer(Modifier.size(88.dp))
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyButton("⮌ Retour", Keys.BACK, send, Modifier.weight(1f).height(68.dp))
            KeyButton("⌂ Home", Keys.HOME, send, Modifier.weight(1f).height(68.dp))
            KeyButton("☰ Menu", Keys.MENU, send, Modifier.weight(1f).height(68.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyButton("🔉  −", Keys.VOL_DOWN, send, Modifier.weight(1f).height(68.dp), repeat = true)
            KeyButton("🔇 Muet", Keys.MUTE, send, Modifier.weight(1f).height(68.dp))
            KeyButton("🔊  +", Keys.VOL_UP, send, Modifier.weight(1f).height(68.dp), repeat = true)
        }
    }
}

@Composable
fun KeyButton(
    label: String, code: Int, send: (Int) -> Unit,
    modifier: Modifier = Modifier.size(88.dp), repeat: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sendNow by rememberUpdatedState(send)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) PinkDark else Pink)
            .pointerInput(code) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
            .background(Color(0xFF1E1E1E))
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

// ===================== Onglet 2 : page web de réglages =====================
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
            Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(10.dp),
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

// ===================== Onglet 3 : connexion =====================
@Composable
fun ConnectTab(
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
            cursorBrush = SolidColor(Pink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E1E))
                .border(1.dp, Pink.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
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
            .background(Pink)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf("🎮" to "Télécommande", "⚙" to "Réglages TV", "🔧" to "Connexion")
    Row(Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(vertical = 6.dp)) {
        items.forEachIndexed { i, item ->
            Column(
                Modifier.weight(1f).clickable { onTab(i) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(item.first, fontSize = 22.sp)
                Text(
                    item.second,
                    color = if (tab == i) Pink else Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
