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
import dev.blooapp.web.SessionIsolator
import dev.webapps.model.UrlNormalizer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Менеджер: список окон и добавление новых.
 *
 * UI пока минимальный — сетка 4×N, группы и тема идут отдельными этапами.
 * Задача этого экрана сейчас: дать возможность создать несколько окон одного
 * сайта и убедиться, что их сессии независимы.
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
            onLongPress = { app -> showItemMenu(app) },
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
                is WebAppRepository.AddResult.Added -> announceAdded(result)

                is WebAppRepository.AddResult.NeedsConfirmation -> confirmSecondInstance(result)

                is WebAppRepository.AddResult.Rejected ->
                    Toast.makeText(
                        this@ManagerActivity,
                        getString(R.string.rejected, describe(result.reason)),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    /**
     * Сайт уже есть — но это не отказ. Второе окно того же сайта с отдельной
     * сессией и есть главная функция приложения, поэтому спрашиваем, а не
     * запрещаем.
     */
    private fun confirmSecondInstance(r: WebAppRepository.AddResult.NeedsConfirmation) {
        val count = r.existing.size
        val names = r.existing.joinToString("\n") { "  • ${it.displayName}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.another_window_title)
            .setMessage(getString(R.string.another_window_message, count, names))
            .setPositiveButton(R.string.action_create_window) { _, _ ->
                lifecycleScope.launch {
                    val parsed = UrlNormalizer.normalize(r.normalizedUrl)
                    if (parsed is UrlNormalizer.Result.Valid) {
                        val added = repo.addInstance(parsed.url, r.originKey)
                        announceAdded(added)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun announceAdded(r: WebAppRepository.AddResult.Added) {
        val text = if (r.instanceNumber > 1) {
            getString(R.string.added_instance, r.app.displayName, r.instanceNumber)
        } else {
            getString(R.string.added, r.app.displayName)
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
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

    private fun showItemMenu(app: WebApp) {
        val items = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_clear_session),
            getString(R.string.action_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(app.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(app)
                    1 -> confirmClearSession(app)
                    2 -> confirmDelete(app)
                }
            }
            .show()
    }

    private fun showRenameDialog(app: WebApp) {
        val input = EditText(this).apply {
            setText(app.instanceLabel ?: "")
            hint = getString(R.string.rename_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                lifecycleScope.launch { repo.rename(app.id, input.text.toString()) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Очистка сессии окна — то, чего нет в аналоге: там висит открытый вопрос
     * «как удалить cookies одного сайта», и автор ответить не смог.
     */
    private fun confirmClearSession(app: WebApp) {
        if (!app.hasOwnSession) {
            Toast.makeText(this, R.string.session_shared_notice, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_clear_session)
            .setMessage(getString(R.string.clear_session_message, app.displayName))
            .setPositiveButton(R.string.action_clear) { _, _ ->
                val ok = SessionIsolator.wipe(app.profileName!!, DiagBootstrap::emit)
                Toast.makeText(
                    this,
                    if (ok) R.string.session_cleared else R.string.session_clear_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(app: WebApp) {
        AlertDialog.Builder(this)
            .setTitle(app.displayName)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    // Сначала стираем данные профиля, потом запись: иначе
                    // осиротевший профиль остался бы на диске навсегда.
                    app.profileName?.let { SessionIsolator.wipe(it, DiagBootstrap::emit) }
                    repo.delete(app)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Экран диагностики. Показывает факты, от которых зависит доступность
     * изоляции сессий, — их бессмысленно угадывать заранее, потому что
     * MULTI_PROFILE требует и поддержки в WebView APK, и включённого
     * многопроцессного режима.
     */
    private fun showDiagnostics() {
        val env = DiagBootstrap.environmentEvent(this)
        val profiles = SessionIsolator.knownProfiles()
        val text = buildString {
            env.fields.forEach { (k, v) -> append("$k = $v\n") }
            append("\nизоляция: ")
            append(
                when (SessionIsolator.availability()) {
                    SessionIsolator.Unavailable.NONE -> "доступна"
                    SessionIsolator.Unavailable.WEBVIEW_TOO_OLD -> "нет — старый WebView"
                    SessionIsolator.Unavailable.MULTIPROCESS_DISABLED ->
                        "нет — выключен многопроцессный режим WebView"
                }
            )
            append("\nпрофилей в WebView: ${profiles.size}")
            if (profiles.isNotEmpty()) {
                append("\n")
                profiles.take(12).forEach { append("  • $it\n") }
            }
        }
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
            holder.title.text = app.displayName
            // Показываем и адрес, и состояние сессии: без этого невозможно
            // понять, изолировано ли окно, а это главный вопрос пользователя.
            holder.url.text = if (app.hasOwnSession) {
                "${app.baseUrl}  •  сессия #${app.instanceIndex}"
            } else {
                "${app.baseUrl}  •  общая сессия"
            }
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
