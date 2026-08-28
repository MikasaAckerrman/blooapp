package dev.blooapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.blooapp.BlooApp
import dev.blooapp.R
import dev.blooapp.data.WebApp
import dev.blooapp.data.WebAppRepository
import dev.blooapp.diag.DiagBootstrap
import dev.webapps.model.UrlNormalizer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Менеджер: список веб-приложений и добавление нового.
 *
 * На этом этапе UI намеренно минимальный — задача этапа 1 в том, чтобы
 * вертикальный срез «ввёл адрес → открылось окно» работал и был проверяем на
 * устройстве. Compose и группы придут на этапах 4–5 по плану.
 */
class ManagerActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: Adapter

    private val repo: WebAppRepository get() = (application as BlooApp).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_manager)

        val root = findViewById<View>(R.id.manager_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        list = findViewById(R.id.web_app_list)
        empty = findViewById(R.id.empty_hint)
        adapter = Adapter(
            onOpen = { app -> startActivity(WebAppHostActivity.intentFor(this, app.id)) },
            onLongPress = { app -> confirmDelete(app) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.add_button).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.diag_button).setOnClickListener { showDiagnostics() }

        if ((application as BlooApp).storageBroken) {
            AlertDialog.Builder(this)
                .setTitle(R.string.storage_broken_title)
                .setMessage(R.string.storage_broken_message)
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }

        lifecycleScope.launch {
            repo.observeAll().collectLatest { apps ->
                adapter.submit(apps)
                empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_url_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_title)
            .setView(input)
            .setPositiveButton(R.string.action_add) { _, _ -> add(input.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun add(raw: String) {
        lifecycleScope.launch {
            when (val result = repo.add(raw)) {
                is WebAppRepository.AddResult.Added ->
                    Toast.makeText(
                        this@ManagerActivity,
                        getString(R.string.added, result.app.title),
                        Toast.LENGTH_SHORT,
                    ).show()

                is WebAppRepository.AddResult.Duplicate ->
                    Toast.makeText(
                        this@ManagerActivity,
                        getString(R.string.duplicate, result.existing.title),
                        Toast.LENGTH_LONG,
                    ).show()

                is WebAppRepository.AddResult.Rejected ->
                    Toast.makeText(
                        this@ManagerActivity,
                        getString(R.string.rejected, describe(result.reason)),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    private fun describe(reason: UrlNormalizer.Reason): String = getString(
        when (reason) {
            UrlNormalizer.Reason.EMPTY -> R.string.reason_empty
            UrlNormalizer.Reason.UNSUPPORTED_SCHEME -> R.string.reason_scheme
            UrlNormalizer.Reason.NO_HOST -> R.string.reason_no_host
            UrlNormalizer.Reason.BAD_PORT -> R.string.reason_port
            UrlNormalizer.Reason.BAD_HOST_CHARS -> R.string.reason_host_chars
        }
    )

    private fun confirmDelete(app: WebApp) {
        AlertDialog.Builder(this)
            .setTitle(app.title)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { repo.delete(app) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Экран диагностики. Показывает ровно те три факта, от которых зависит
     * доступность изоляции сессий, — их бессмысленно угадывать заранее,
     * потому что MULTI_PROFILE требует и поддержки в WebView APK, и
     * включённого многопроцессного режима.
     */
    private fun showDiagnostics() {
        val env = DiagBootstrap.environmentEvent(this)
        val text = env.fields.entries.joinToString("\n") { (k, v) -> "$k = $v" }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setMessage(text)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private class Adapter(
        private val onOpen: (WebApp) -> Unit,
        private val onLongPress: (WebApp) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        private var items: List<WebApp> = emptyList()

        fun submit(list: List<WebApp>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_web_app, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.title.text = app.title
            holder.url.text = app.baseUrl
            holder.itemView.setOnClickListener { onOpen(app) }
            holder.itemView.setOnLongClickListener { onLongPress(app); true }
        }

        override fun getItemCount() = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.item_title)
            val url: TextView = view.findViewById(R.id.item_url)
        }
    }
}
