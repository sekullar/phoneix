package app.phoneix

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.concurrent.thread

/** Hangi uygulamaların bildirimi bilgisayara geçsin: tıklayarak seç. */
class AppsActivity : Activity() {
    private class Item(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var store: Store
    private val selected = HashSet<String>()
    private var items = listOf<Item>()
    private lateinit var adapter: BaseAdapter

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Ui.init(this)
        store = Store(this)
        selected.addAll(store.apps)
        val frame = android.widget.FrameLayout(this)
        frame.addView(AuroraView(this), android.widget.FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(48), dp(16), 0) }
        frame.addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(TextView(this).apply { text = "Bildirimleri geçecek uygulamalar"; textSize = 20f; setTextColor(Ui.INK); paint.isFakeBoldText = true })
        root.addView(TextView(this).apply { text = "Seçmediklerin bilgisayara hiç gönderilmez."; setTextColor(Ui.DIM); setPadding(0, dp(4), 0, dp(8)) })
        val list = ListView(this).apply { divider = null; clipToPadding = false; setPadding(0, 0, 0, dp(16)) }
        root.addView(Ui.card(this).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); addView(list, LinearLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(16) })
        setContentView(frame); Ui.window(this)

        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val it = items[i]
                val row = (v as? LinearLayout) ?: LinearLayout(this@AppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                    addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(TextView(context).apply { textSize = 16f; setTextColor(Ui.INK); setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(CheckBox(context).apply { isClickable = false; isFocusable = false; buttonTintList = android.content.res.ColorStateList.valueOf(Ui.A1) })
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(it.icon)
                (row.getChildAt(1) as TextView).text = it.label
                (row.getChildAt(2) as CheckBox).isChecked = it.pkg in selected
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            val pkg = items[i].pkg
            if (!selected.remove(pkg)) selected.add(pkg)
            store.setApps(selected)
            adapter.notifyDataSetChanged()
        }
        thread {
            val pm = packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(launch, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { Item(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .sortedWith(compareBy<Item>({ it.pkg !in selected }, { it.label.lowercase() }))
            runOnUiThread { items = found; adapter.notifyDataSetChanged() }
        }
    }
}
