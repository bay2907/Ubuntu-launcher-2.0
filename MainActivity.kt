package com.example.ubuntulauncher

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/** Launcher bergaya Ubuntu (GNOME): top bar, dock kiri, dan "Show Applications". */
class MainActivity : Activity() {

    class App(val label: String, val pkg: String, val intent: Intent, val icon: Drawable)

    // Warna Ubuntu
    private val ORANGE = "#E95420"
    private val AUBERGINE = "#2C001E"

    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private var apps = listOf<App>()
    private var shown = listOf<App>()
    private val running = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("ubuntu_launcher", MODE_PRIVATE) }
    private val h = Handler(Looper.getMainLooper())

    private lateinit var dock: LinearLayout
    private lateinit var overlay: LinearLayout
    private lateinit var search: EditText
    private lateinit var clock: TextView
    private lateinit var adapter: BaseAdapter

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("EEE d MMM  HH:mm", Locale.getDefault()).format(Date())
            h.postDelayed(this, 15000)
        }
    }

    private val pkgRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadApps(); refreshDock(); filter(search.text.toString())
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun clr(s: String) = Color.parseColor(s)
    private fun rounded(c: String, r: Int) = GradientDrawable().apply {
        setColor(clr(c)); cornerRadius = dp(r).toFloat()
    }
    private fun tv(t: String, sp: Float) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
    }

    /** Ikon 9 titik "Show Applications" */
    inner class Dots(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(cv: Canvas) {
            val s = minOf(width, height) / 6f
            for (i in -1..1) for (j in -1..1)
                cv.drawCircle(width / 2f + i * s, height / 2f + j * s, s / 2.6f, p)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) // top bar kita menggantikan status bar
        window.navigationBarColor = clr(AUBERGINE)
        loadApps()

        // Wallpaper: gradasi ungu aubergine + cahaya oranye
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TR_BL,
                intArrayOf(clr("#77216F"), clr("#4A1038"), clr(AUBERGINE))
            )
        }
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(460).toFloat()
                setGradientCenter(0.15f, 1f)
                colors = intArrayOf(clr("#99E95420"), clr("#00E95420"))
            }
        }, M, M)

        // Top bar hitam: Activities | jam | ikon sistem
        val top = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        top.addView(tv("Activities", 14f).apply {
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { toggle() }
        }, FrameLayout.LayoutParams(W, M, Gravity.START))
        clock = tv("", 13f)
        top.addView(clock, FrameLayout.LayoutParams(W, M, Gravity.CENTER))
        top.addView(tv("📶  🔊  🔋", 12f).apply { setPadding(0, 0, dp(14), 0) },
            FrameLayout.LayoutParams(W, M, Gravity.END))

        // Dock kiri
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(clr("#99000000"))
            setPadding(0, dp(8), 0, dp(8))
        }

        // Grid aplikasi
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, g: ViewGroup?): View {
                val a = shown[p]
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                    addView(ImageView(this@MainActivity).apply { setImageDrawable(a.icon) },
                        LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(tv(a.label, 11f).apply {
                        gravity = Gravity.CENTER; maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(4) })
                }
            }
        }
        val grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(8)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(14), 0, dp(8))
            adapter = this@MainActivity.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, p, _ -> launch(shown[p]) }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, p, _ ->
                togglePin(shown[p].pkg); true   // tekan lama = pin / unpin di dock
            }
        }

        search = EditText(this).apply {
            hint = "Type to search…"
            setHintTextColor(clr("#99FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine()
            background = rounded("#33FFFFFF", 22)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filter(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                shown.firstOrNull()?.let { launch(it) }
                true
            }
        }

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(clr("#E62C001E"))
            setPadding(dp(12), dp(14), dp(12), 0)
            addView(search, LinearLayout.LayoutParams(M, W))
            addView(grid, LinearLayout.LayoutParams(M, 0, 1f))
        }
        val content = FrameLayout(this).apply { addView(overlay, M, M) }

        val row = LinearLayout(this).apply {
            addView(dock, LinearLayout.LayoutParams(dp(58), M))
            addView(content, LinearLayout.LayoutParams(0, M, 1f))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(M, dp(32)))
            addView(row, LinearLayout.LayoutParams(M, 0, 1f))
        }, M, M)

        setContentView(root)
        refreshDock()
        registerReceiver(pkgRx, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
    }

    private fun loadApps() {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { r ->
                val ai = r.activityInfo
                App(
                    r.loadLabel(packageManager).toString(), ai.packageName,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ai.packageName, ai.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    r.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase() }
        shown = apps
    }

    private fun filter(q: String) {
        shown = apps.filter { it.label.contains(q.trim(), true) }
        adapter.notifyDataSetChanged()
    }

    private fun pins(): MutableList<String> {
        prefs.getString("pins", null)?.let { s ->
            return s.split(",").filter { it.isNotEmpty() }.toMutableList()
        }
        // Default: telepon, browser, kamera, pengaturan
        return listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Settings.ACTION_SETTINGS)
        ).mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.filter { p -> p != "android" && apps.any { it.pkg == p } }
            .distinct()
            .ifEmpty { apps.take(4).map { it.pkg } }
            .toMutableList()
    }

    private fun togglePin(pkg: String) {
        val p = pins()
        if (!p.remove(pkg)) p.add(pkg)
        prefs.edit().putString("pins", p.joinToString(",")).apply()
        refreshDock()
    }

    private fun refreshDock() {
        dock.removeAllViews()
        for (pkg in pins()) {
            val a = apps.firstOrNull { it.pkg == pkg } ?: continue
            val item = FrameLayout(this)
            item.addView(ImageView(this).apply { setImageDrawable(a.icon) },
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            if (pkg in running) {   // titik oranye = aplikasi sedang berjalan
                item.addView(View(this).apply { background = rounded(ORANGE, 3) },
                    FrameLayout.LayoutParams(dp(4), dp(4), Gravity.CENTER_VERTICAL or Gravity.START)
                        .apply { leftMargin = dp(3) })
            }
            item.setOnClickListener { launch(a) }
            item.setOnLongClickListener { togglePin(pkg); true }
            dock.addView(item, LinearLayout.LayoutParams(dp(58), dp(50)))
        }
        dock.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        dock.addView(Dots(this).apply { setOnClickListener { toggle() } },
            LinearLayout.LayoutParams(dp(58), dp(50)))
    }

    private fun launch(a: App) {
        running += a.pkg
        hide()
        try { startActivity(a.intent) } catch (_: Exception) {}
        refreshDock()
    }

    private fun toggle() { if (overlay.visibility == View.VISIBLE) hide() else show() }

    private fun show() {
        search.setText("")
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(150).start()
    }

    private fun hide() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        overlay.animate().alpha(0f).setDuration(120)
            .withEndAction { overlay.visibility = View.GONE }.start()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (overlay.visibility == View.VISIBLE) hide()   // tombol Home menutup overview
    }

    override fun onBackPressed() { if (overlay.visibility == View.VISIBLE) hide() }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(pkgRx) }
}
