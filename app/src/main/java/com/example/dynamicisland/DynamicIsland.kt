package com.example.dynamicisland

import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.text.format.DateFormat
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

// ============ Renkler ve ölçüler (tahmini, ekran görüntüsüyle ince ayar yapılabilir) ============

private val MUSIC_GREEN = Color.parseColor("#1DB954")
private val NOTIF_BLUE = Color.parseColor("#4A9EFF")
private val ALARM_ORANGE = Color.parseColor("#FF9F0A")
private val SUBTLE = Color.parseColor("#9AA0A6")
private val GLASS_TOP = Color.parseColor("#E61A1A1E")
private val GLASS_BOTTOM = Color.parseColor("#D2000000")
private val GLASS_RIM = Color.parseColor("#33FFFFFF")

private const val SHOW_IDLE_CLOCK = true
private const val ALARM_WINDOW_MS = 12L * 60 * 60 * 1000
private const val AUTO_COLLAPSE_MS = 6000L
private const val TOAST_MS = 4000L

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

// ============ Bildirim modeli ve köprü ============

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

    private enum class Mode { HIDDEN, CLOCK, ALARM, MUSIC, NOTIF }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var compactRow: LinearLayout
    private lateinit var expandedBox: LinearLayout
    private lateinit var notifBox: LinearLayout
    private lateinit var musicBox: LinearLayout
    private lateinit var compactTv: TextView
    private lateinit var appIcon: ImageView
    private lateinit var titleTv: TextView
    private lateinit var artistTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playBtn: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.HIDDEN
    private var current: MediaController? = null
    private var expanded = false
    private var musicVisible = false
    private var musicPkg: String? = null
    private var musicArt: Bitmap? = null
    private var toast: IslandNotif? = null
    private var iconKey: String? = null
    private var lastForeground: String? = null
    private var hiding = false
    private var usageCursor = System.currentTimeMillis() - 60_000

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                setExpanded(!expanded)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = if (e1 == null) 0f else e2.y - e1.y
                return when {
                    dy < -dp(24) && expanded -> { setExpanded(false); true }
                    dy > dp(24) && !expanded -> { setExpanded(true); true }
                    else -> false
                }
            }
        })
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (expanded && mode == Mode.MUSIC) updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val autoCollapse = Runnable { setExpanded(false) }

    private val toastOff = Runnable {
        toast = null
        refresh()
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
        buildUi()
        root.visibility = View.GONE
        wm.addView(root, params)

        NotifBus.listener = { n -> onNotifEvent(n) }
        handler.post(poll)
        handler.post(tick)
    }

    override fun onDestroy() {
        NotifBus.listener = null
        handler.removeCallbacksAndMessages(null)
        if (::root.isInitialized) {
            root.animate().cancel()
            if (root.isAttachedToWindow) wm.removeView(root)
        }
        super.onDestroy()
    }

    // ============ Arayüz kurulumu ============

    private fun ctrl(icon: Int, desc: String, onTap: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        contentDescription = desc
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener {
            onTap()
            poke()
        }
    }

    private fun seekBtn(icon: Int, desc: String, delta: Long): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ctrl(icon, desc) { seek(delta) })
            addView(TextView(this@DynamicIslandService).apply {
                text = "5"
                setTextColor(SUBTLE)
                textSize = 9f
                gravity = Gravity.CENTER
            })
        }

    private fun glassBackground(): LayerDrawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(GLASS_TOP, GLASS_BOTTOM)
        ).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), GLASS_RIM)
        }
        // Üst kenarda ince bir parlama, cam hissi için
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#22FFFFFF"), Color.TRANSPARENT)
        ).apply {
            cornerRadii = floatArrayOf(
                dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(),
                0f, 0f, 0f, 0f
            )
        }
        return LayerDrawable(arrayOf(base, sheen))
    }

    private fun buildUi() {
        appIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        compactTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            maxWidth = dp(240)
            ellipsize = TextUtils.TruncateAt.END
        }

        compactRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIcon)
            addView(compactTv)
        }

        titleTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            maxWidth = dp(260)
            ellipsize = TextUtils.TruncateAt.END
        }

        artistTv = TextView(this).apply {
            setTextColor(SUBTLE)
            textSize = 12f
            maxLines = 1
            maxWidth = dp(260)
            ellipsize = TextUtils.TruncateAt.END
        }

        timeTv = TextView(this).apply {
            setTextColor(SUBTLE)
            textSize = 11f
        }

        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT)
            progressTintList = ColorStateList.valueOf(MUSIC_GREEN)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {
                    handler.removeCallbacks(autoCollapse)
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    current?.transportControls?.seekTo(sb.progress.toLong())
                    poke()
                }
            })
        }

        playBtn = ctrl(android.R.drawable.ic_media_play, "Oynat/Duraklat") { togglePlay() }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ctrl(android.R.drawable.ic_media_previous, "Önceki") {
                current?.transportControls?.skipToPrevious()
            })
            addView(seekBtn(android.R.drawable.ic_media_rew, "5 saniye geri", -5000))
            addView(playBtn)
            addView(seekBtn(android.R.drawable.ic_media_ff, "5 saniye ileri", 5000))
            addView(ctrl(android.R.drawable.ic_media_next, "Sonraki") {
                current?.transportControls?.skipToNext()
            })
        }

        musicBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
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
            addView(musicBox)
            addView(notifBox)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBackground()
            minimumWidth = dp(150)
            setPadding(dp(14), dp(8), dp(14), dp(10))
            addView(compactRow)
            addView(expandedBox)
            setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
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

    // ============ Mod seçimi ============

    private fun nextAlarmText(): String? {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val info = am.nextAlarmClock ?: return null
        val left = info.triggerTime - System.currentTimeMillis()
        if (left < 0 || left > ALARM_WINDOW_MS) return null
        return DateFormat.format("HH:mm", info.triggerTime).toString()
    }

    private fun pickMode(alarm: String?): Mode = when {
        toast != null -> Mode.NOTIF
        musicVisible -> Mode.MUSIC
        NotifBus.items.isNotEmpty() -> Mode.NOTIF
        alarm != null -> Mode.ALARM
        SHOW_IDLE_CLOCK -> Mode.CLOCK
        else -> Mode.HIDDEN
    }

    private fun applyMode(newMode: Mode, alarm: String?) {
        val changed = newMode != mode
        if (changed) {
            // Ada şeklini yumuşakça değiştirir (morf)
            TransitionManager.beginDelayedTransition(
                root,
                ChangeBounds().setDuration(320).setInterpolator(OvershootInterpolator(0.7f))
            )
        }
        mode = newMode

        when (newMode) {
            Mode.NOTIF -> {
                val t = toast
                if (t != null) {
                    compactTv.text = "${t.app}: ${t.title.ifBlank { t.text }}"
                    setIcon(t.pkg, NOTIF_BLUE, null)
                } else {
                    compactTv.text = "${NotifBus.items.size} bildirim"
                    setIcon(null, NOTIF_BLUE, null)
                }
            }
            Mode.MUSIC -> {
                compactTv.text = titleTv.text
                setIcon(musicPkg, MUSIC_GREEN, musicArt)
            }
            Mode.ALARM -> {
                compactTv.text = "Alarm $alarm"
                setIcon(null, ALARM_ORANGE, null)
            }
            Mode.CLOCK -> {
                compactTv.text = DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
                setIcon(null, SUBTLE, null)
            }
            Mode.HIDDEN -> {}
        }

        musicBox.visibility = if (newMode == Mode.MUSIC) View.VISIBLE else View.GONE

        if (newMode == Mode.HIDDEN) hideRoot() else showRoot()
    }

    private fun setIcon(pkg: String?, accent: Int, art: Bitmap?) {
        val key = "$pkg|$accent|${art?.hashCode()}"
        if (key == iconKey) return
        iconKey = key

        if (art != null) {
            appIcon.background = null
            appIcon.setImageBitmap(art)
            return
        }

        val drawable = pkg?.let { p ->
            try {
                packageManager.getApplicationIcon(p)
            } catch (e: Exception) {
                null
            }
        }

        if (drawable != null) {
            appIcon.background = null
            appIcon.setImageDrawable(drawable)
        } else {
            appIcon.setImageDrawable(null)
            appIcon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
    }

    // ============ Görünürlük ve yay animasyonu ============

    private fun showRoot() {
        hiding = false
        if (root.visibility == View.VISIBLE && root.alpha == 1f && root.scaleX == 1f) return
        if (root.visibility != View.VISIBLE) {
            root.alpha = 0f
            root.scaleX = 0.9f
            root.scaleY = 0.9f
            root.translationY = -dp(20).toFloat()
            root.visibility = View.VISIBLE
        }
        root.animate().cancel()
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator(0.9f))
            .start()
    }

    private fun hideRoot() {
        if (hiding || root.visibility != View.VISIBLE) return
        hiding = true
        setExpanded(false)
        root.animate().cancel()
        root.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .translationY(-dp(20).toFloat())
            .setDuration(240)
            .withEndAction {
                root.visibility = View.GONE
                hiding = false
            }
            .start()
    }

    private fun setExpanded(value: Boolean) {
        handler.removeCallbacks(autoCollapse)
        if (expanded == value) return
        if (value && mode != Mode.MUSIC && NotifBus.items.isEmpty()) return

        expanded = value
        TransitionManager.beginDelayedTransition(
            root,
            ChangeBounds().setDuration(340).setInterpolator(OvershootInterpolator(0.6f))
        )
        expandedBox.visibility = if (value) View.VISIBLE else View.GONE
        compactRow.visibility = if (value && mode == Mode.MUSIC) View.GONE else View.VISIBLE
        if (value) {
            renderNotifs()
            poke()
        }
    }

    // Açık adada hareket olunca otomatik kapanma süresini yeniler
    private fun poke() {
        handler.removeCallbacks(autoCollapse)
        if (expanded) handler.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
    }

    // ============ Bildirimler ============

    private fun onNotifEvent(n: IslandNotif?) {
        if (n != null && n.pkg != packageName) {
            toast = n
            handler.removeCallbacks(toastOff)
            handler.postDelayed(toastOff, TOAST_MS)
        }
        renderNotifs()
        refresh()
    }

    private fun renderNotifs() {
        notifBox.removeAllViews()
        NotifBus.items.take(4).forEach { notifBox.addView(notifRow(it)) }
        notifBox.visibility = if (NotifBus.items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun notifRow(n: IslandNotif): View {
        val appTv = TextView(this).apply {
            text = n.app
            setTextColor(NOTIF_BLUE)
            textSize = 11f
            maxLines = 1
        }

        val bodyTv = TextView(this).apply {
            text = if (n.text.isNotBlank()) "${n.title}: ${n.text}" else n.title
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            maxWidth = dp(240)
            ellipsize = TextUtils.TruncateAt.END
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(appTv)
            addView(bodyTv)
        }

        val closeTv = TextView(this).apply {
            text = "✕"
            setTextColor(SUBTLE)
            textSize = 15f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener {
                IslandNotificationListener.instance?.cancelNotification(n.key)
                NotifBus.remove(n.key)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeTv)
            setOnClickListener { openNotif(n) }
        }
    }

    private fun openNotif(n: IslandNotif) {
        runCatching { n.intent?.send() }
        setExpanded(false)
    }

    // ============ Ana döngü ============

    private fun refresh() {
        updateForeground()

        val session = pickSession()
        current = session
        if (session != null) musicPkg = session.packageName

        val state = session?.playbackState?.state
        val active = session != null &&
                (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED)

        // Müzik uygulaması ön plandaysa müzik adası gizlenir
        musicVisible = active && session?.packageName != lastForeground

        if (musicVisible && session != null) {
            val m = session.metadata
            titleTv.text = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Müzik"
            artistTv.text = m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            musicArt = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            playBtn.setImageResource(
                if (state == PlaybackState.STATE_PLAYING) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        } else {
            musicArt = null
        }

        val alarm = nextAlarmText()
        val newMode = pickMode(alarm)
        applyMode(newMode, alarm)

        if (mode == Mode.CLOCK || mode == Mode.ALARM) {
            if (expanded) setExpanded(false)
        }
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

    // Android konumu yalnızca son güncelleme anında verir, aradaki süreyi hesaplıyoruz
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
            .setContentTitle("Dynamic Island çalışıyor")
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

    private lateinit var statusTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        }

        val pad = dp(24)

        statusTv = TextView(this)
        statusTv.textSize = 15f
        statusTv.setPadding(0, dp(16), 0, dp(16))

        val titleTv = TextView(this)
        titleTv.text = "Dynamic Island"
        titleTv.textSize = 26f

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.addView(titleTv)
        root.addView(statusTv)
        root.addView(button("Üstte göster izni ver") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("Bildirim erişimi ver") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Kullanım erişimi ver") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(button("Adayı başlat") { startIsland() })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun startIsland() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, DynamicIslandService::class.java))
        } else {
            Toast.makeText(this, "Önce üstte göster iznini ver", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val listener = isListenerEnabled()
        val usage = hasUsageAccess()
        statusTv.text = "Üstte göster: ${mark(overlay)}\n" +
                "Bildirim erişimi: ${mark(listener)}\n" +
                "Kullanım erişimi: ${mark(usage)}"
    }

    private fun mark(ok: Boolean) = if (ok) "✓ Açık" else "✗ Kapalı"

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }
}
