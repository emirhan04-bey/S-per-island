package com.example.dynamicisland

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

private val GREEN = Color.parseColor("#1DB954")
private val BLUE = Color.parseColor("#4A9EFF")
private val GRAY = Color.parseColor("#AAAAAA")

// ============ Bildirim modeli ve paylaşılan köprü ============

data class IslandNotif(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val intent: PendingIntent?
)

object NotifBus {
    val items = mutableListOf<IslandNotif>()
    var listener: ((IslandNotif?) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun post(n: IslandNotif) {
        main.post {
            items.removeAll { it.key == n.key }
            items.add(0, n)
            while (items.size > 10) items.removeAt(items.lastIndex)
            listener?.invoke(n)
        }
    }

    fun remove(key: String) {
        main.post {
            items.removeAll { it.key == key }
            listener?.invoke(null)
        }
    }
}

// ============ Bildirim dinleyici ============

class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        NotifBus.post(
            IslandNotif(
                key = sbn.key,
                pkg = sbn.packageName,
                app = appName(sbn.packageName),
                title = title,
                text = text,
                intent = sbn.notification.contentIntent
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifBus.remove(sbn.key)
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        var instance: IslandNotificationListener? = null
    }
}

// ============ Ada overlay servisi ============

class DynamicIslandService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var dotBg: GradientDrawable
    private lateinit var compactBox: LinearLayout
    private lateinit var compactTv: TextView
    private lateinit var expandedBox: LinearLayout
    private lateinit var notifBox: LinearLayout
    private lateinit var musicBox: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var artistTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var current: MediaController? = null
    private var expanded = false
    private var musicVisible = false
    private var toast: IslandNotif? = null
    private var lastForeground: String? = null
    private var usageCursor = System.currentTimeMillis() - 60_000

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (expanded && musicVisible) updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val autoCollapse = Runnable { setExpanded(false) }

    private val toastOff = Runnable {
        toast = null
        render()
    }

    private val notifListener: (IslandNotif?) -> Unit = { n ->
        if (n != null && n.pkg != packageName) {
            toast = n
            handler.removeCallbacks(toastOff)
            handler.postDelayed(toastOff, 4000)
        }
        renderNotifs()
        render()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildIsland()
        root.visibility = View.GONE
        wm.addView(root, params)

        NotifBus.listener = notifListener
        handler.post(poll)
        handler.post(tick)
    }

    override fun onDestroy() {
        NotifBus.listener = null
        handler.removeCallbacksAndMessages(null)
        if (::root.isInitialized && root.isAttachedToWindow) {
            wm.removeView(root)
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(size: Float) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        maxLines = 1
    }

    private fun ctrl(symbol: String, action: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        setTextColor(Color.WHITE)
        textSize = 20f
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener {
            action()
            scheduleCollapse()
        }
    }

    private fun buildIsland() {
        val bg = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(28).toFloat()
        }

        dotBg = GradientDrawable().apply {
            setColor(BLUE)
            cornerRadius = dp(4).toFloat()
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
            background = dotBg
        }

        compactTv = label(13f).apply {
            maxWidth = dp(260)
            ellipsize = TextUtils.TruncateAt.END
        }

        compactBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot)
            addView(compactTv)
        }

        titleTv = label(14f).apply {
            maxWidth = dp(260)
            ellipsize = TextUtils.TruncateAt.END
        }
        artistTv = label(12f).apply {
            setTextColor(GRAY)
            maxWidth = dp(260)
            ellipsize = TextUtils.TruncateAt.END
        }
        timeTv = label(11f).apply { setTextColor(GRAY) }

        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {
                    handler.removeCallbacks(autoCollapse)
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    current?.transportControls?.seekTo(sb.progress.toLong())
                    scheduleCollapse()
                }
            })
        }

        playBtn = ctrl("▶") { togglePlay() }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ctrl("⏮") { current?.transportControls?.skipToPrevious() })
            addView(ctrl("⏪5") { seek(-5000) })
            addView(playBtn)
            addView(ctrl("5⏩") { seek(5000) })
            addView(ctrl("⏭") { current?.transportControls?.skipToNext() })
        }

        musicBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(titleTv)
            addView(artistTv)
            addView(seekBar)
            addView(timeTv)
            addView(controls)
        }

        notifBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        expandedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(notifBox)
            addView(musicBox)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            minimumWidth = dp(200)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(compactBox)
            addView(expandedBox)
            setOnClickListener { setExpanded(!expanded) }
        }

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }
    }

    // ============ Bildirim satırları ============

    private fun renderNotifs() {
        notifBox.removeAllViews()
        NotifBus.items.take(4).forEach { notifBox.addView(notifRow(it)) }
        notifBox.visibility = if (NotifBus.items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun notifRow(n: IslandNotif): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))

        val textCol = LinearLayout(this@DynamicIslandService).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DynamicIslandService).apply {
                text = n.app
                setTextColor(BLUE)
                textSize = 11f
                maxLines = 1
            })
            addView(TextView(this@DynamicIslandService).apply {
                text = if (n.text.isNotBlank()) "${n.title}: ${n.text}" else n.title
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 2
                maxWidth = dp(240)
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        addView(textCol)

        addView(TextView(this@DynamicIslandService).apply {
            text = "✕"
            setTextColor(GRAY)
            textSize = 16f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener {
                IslandNotificationListener.instance?.cancelNotification(n.key)
                NotifBus.remove(n.key)
            }
        })

        setOnClickListener { openNotif(n) }
    }

    private fun openNotif(n: IslandNotif) {
        try {
            n.intent?.send()
        } catch (e: PendingIntent.CanceledException) {
        }
        setExpanded(false)
    }

    // ============ Görünürlük ve genişleme ============

    private fun render() {
        val t = toast
        when {
            t != null -> {
                dotBg.setColor(BLUE)
                compactTv.text = "${t.app}: ${t.title.ifBlank { t.text }}"
            }
            musicVisible -> {
                dotBg.setColor(GREEN)
                compactTv.text = titleTv.text
            }
            else -> {
                dotBg.setColor(BLUE)
                compactTv.text = "${NotifBus.items.size} bildirim"
            }
        }

        musicBox.visibility = if (musicVisible) View.VISIBLE else View.GONE

        val hasNotifs = NotifBus.items.isNotEmpty()
        if (t != null || musicVisible || hasNotifs) showRoot() else hideRoot()
    }

    private fun showRoot() {
        if (root.visibility == View.VISIBLE) return
        root.visibility = View.VISIBLE
        root.translationY = -dp(60).toFloat()
        root.animate().translationY(0f).setDuration(350).start()
    }

    private fun hideRoot() {
        if (root.visibility != View.VISIBLE) return
        root.visibility = View.GONE
        setExpanded(false)
    }

    private fun setExpanded(value: Boolean) {
        handler.removeCallbacks(autoCollapse)
        if (expanded == value) return
        expanded = value
        TransitionManager.beginDelayedTransition(root, AutoTransition())
        compactBox.visibility = if (value) View.GONE else View.VISIBLE
        expandedBox.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            renderNotifs()
            scheduleCollapse()
        }
    }

    private fun scheduleCollapse() {
        handler.removeCallbacks(autoCollapse)
        if (expanded) handler.postDelayed(autoCollapse, 5000)
    }

    // ============ Ana döngü ============

    private fun refresh() {
        updateForeground()

        val session = pickSession()
        current = session

        val state = session?.playbackState?.state
        val active = session != null &&
                (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED)

        musicVisible = active && session?.packageName != lastForeground

        if (musicVisible && session != null) {
            val m = session.metadata
            titleTv.text = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Müzik"
            artistTv.text = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            playBtn.text = if (state == PlaybackState.STATE_PLAYING) "⏸" else "▶"
        }

        render()
    }

    private fun pickSession(): MediaController? {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = try {
            msm.getActiveSessions(ComponentName(this, IslandNotificationListener::class.java))
        } catch (e: SecurityException) {
            return null
        }
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
    }

    @Suppress("DEPRECATION")
    private fun updateForeground() {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(usageCursor, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = e.packageName
            }
        }
        usageCursor = now
    }

    // ============ Müzik kontrolleri ============

    private fun togglePlay() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    private fun seek(deltaMs: Long) {
        val c = current ?: return
        val st = c.playbackState ?: return
        val dur = duration(c)
        var target = (currentPosition(st) + deltaMs).coerceAtLeast(0L)
        if (dur > 0) target = target.coerceAtMost(dur)
        c.transportControls.seekTo(target)
    }

    private fun updateProgress() {
        val c = current ?: return
        val st = c.playbackState ?: return
        val dur = duration(c)
        val pos = currentPosition(st)
        if (!seekBar.isPressed) {
            seekBar.max = dur.coerceAtLeast(1L).toInt()
            seekBar.progress = pos.toInt()
        }
        timeTv.text = "${fmt(pos)} / ${fmt(dur)}"
    }

    private fun currentPosition(st: PlaybackState): Long {
        val elapsed = if (st.state == PlaybackState.STATE_PLAYING)
            ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        else 0L
        return st.position + elapsed
    }

    private fun duration(c: MediaController): Long =
        c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ============ Foreground servis bildirimi ============

    private fun startAsForeground() {
        val channelId = "island_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Dynamic Island", NotificationManager.IMPORTANCE_MIN)
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Dynamic Island aktif")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }
}

// ============ Ayar ekranı ============

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        }

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        root.addView(btn("1. Diğer uygulamaların üzerinde göster") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        root.addView(btn("2. Bildirim erişimi (tüm bildirimler + müzik)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        root.addView(btn("3. Kullanım erişimi (uygulama algılama)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        root.addView(btn("4. Adayı başlat") {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, DynamicIslandService::class.java))
            } else {
                Toast.makeText(this, "Önce 1. izni ver", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(root)
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }
}
