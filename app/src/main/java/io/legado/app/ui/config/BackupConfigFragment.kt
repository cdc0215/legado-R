package io.legado.app.ui.config

import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.progressDialog
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toEditable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null
    private var restoreJob: Job? = null

    private companion object {
        const val PROGRESS_MAX = 100
        const val BYTES_PER_MB = 1024L * 1024L
    }

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString())
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText("恢复中…")
            waitDialog.setCancelButton {
                confirmStopTask {
                    restoreJob?.cancel()
                }
            }
            waitDialog.show()
            Coroutine.async {
                restoreJob = coroutineContext[Job]
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
                waitDialog.hideCancelButton()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_backup)
        findPreference<EditTextPreference>(PreferKey.webDavPassword)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDir)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDir?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDeviceName)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDeviceName?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<Preference>(PreferKey.syncThemePackages)?.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !hasWebDavAccount()) {
                    toastOnUi("请先填写 WebDAV 账号和密码")
                    false
                } else {
                    true
                }
            }
        }
        upPreferenceSummary(PreferKey.webDavUrl, getPrefString(PreferKey.webDavUrl))
        upPreferenceSummary(PreferKey.webDavAccount, getPrefString(PreferKey.webDavAccount))
        upPreferenceSummary(PreferKey.webDavPassword, getPrefString(PreferKey.webDavPassword))
        upPreferenceSummary(PreferKey.webDavDir, AppConfig.webDavDir)
        upPreferenceSummary(PreferKey.webDavDeviceName, AppConfig.webDavDeviceName)
        upPreferenceSummary(PreferKey.backupPath, getPrefString(PreferKey.backupPath))
        findPreference<io.legado.app.lib.prefs.Preference>("web_dav_restore")
            ?.onLongClick {
                restoreFromLocal()
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        if (!LocalConfig.backupHelpVersionIsLast) {
            showHelp("webDavHelp")
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyUiMenuStyle(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> listView.post {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                viewModel.upWebDavConfig()
            }

            PreferKey.webDavDeviceName -> upPreferenceSummary(key, getPrefString(key))
        }
    }

    private fun hasWebDavAccount(): Boolean {
        return !getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !getPrefString(PreferKey.webDavPassword).isNullOrBlank()
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.webDavUrl ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_url_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavAccount ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_account_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavPassword ->
                if (value.isNullOrEmpty()) {
                    preference.summary = getString(R.string.web_dav_pw_s)
                } else {
                    preference.summary = "*".repeat(value.length)
                }

            PreferKey.webDavDir -> preference.summary = when (value) {
                null -> "legado"
                else -> value
            }

            else -> {
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(value)
                    // Set the summary to reflect the new value.
                    preference.summary = if (index >= 0) preference.entries[index] else null
                } else {
                    preference.summary = value
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> selectBackupPath.launch()
            PreferKey.restoreIgnore -> backupIgnore()
            "web_dav_backup" -> backup()
            "web_dav_restore" -> restore()
            "import_old" -> restoreOld.launch()
        }
        return super.onPreferenceTreeClick(preference)
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }


    fun backup() {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath)
            }
        }
    }

    private fun backup(backupPath: String) {
        backupJob?.cancel()
        val progressDialog = progressDialog(
            title = getString(R.string.backup),
            message = "备份中..."
        ) {
            isIndeterminate = true
            max = PROGRESS_MAX
            setCancelConfirmButton {
                backupJob?.cancel()
            }
        }
        var lastProgress = -1
        var lastMegabyte = -1L
        val onUploadProgress: (Long, Long) -> Unit = progress@{ finished, total ->
            val progress = if (total > 0) {
                (finished * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX)
            } else {
                -1
            }
            val megabyte = finished / BYTES_PER_MB
            if (progress >= 0) {
                if (progress == lastProgress && finished < total) return@progress
                lastProgress = progress
            } else {
                if (megabyte == lastMegabyte) return@progress
                lastMegabyte = megabyte
            }
            lifecycleScope.launch(Main) {
                progressDialog.updateTransferProgress("上传中", finished, total, progress)
            }
        }
        backupJob = lifecycleScope.launch {
            try {
                Backup.backupLocked(requireContext(), backupPath, onUploadProgress)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    private fun backupUsePermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setCancelButton {
            confirmStopTask {
                restoreJob?.cancel()
            }
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错WebDavError\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            alert {
                setTitle(R.string.restore)
                setMessage("WebDavError\n${it.localizedMessage}\n将从本地备份恢复。")
                okButton {
                    restoreFromLocal()
                }
                cancelButton()
            }
        }.onFinally {
            waitDialog.dismiss()
            waitDialog.hideCancelButton()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) { AppWebDav.getBackupNames() }
        if (AppWebDav.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.selector(
                    title = context.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        listView.post {
                            restoreWebDav(names[index])
                        }
                    }
                }
            }
        } else {
            throw NoStackTraceException("Web dav no back up file")
        }
    }

    private fun restoreWebDav(name: String) {
        restoreJob?.cancel()
        val progressDialog = progressDialog(
            title = getString(R.string.restore),
            message = "恢复中…"
        ) {
            isIndeterminate = true
            max = PROGRESS_MAX
            setCancelConfirmButton {
                restoreJob?.cancel()
            }
        }
        var lastProgress = -1
        var lastMegabyte = -1L
        val onDownloadProgress: (Long, Long) -> Unit = progress@{ finished, total ->
            val progress = if (total > 0) {
                (finished * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX)
            } else {
                -1
            }
            val megabyte = finished / BYTES_PER_MB
            if (progress >= 0) {
                if (progress == lastProgress && finished < total) return@progress
                lastProgress = progress
            } else {
                if (megabyte == lastMegabyte) return@progress
                lastMegabyte = megabyte
            }
            lifecycleScope.launch(Main) {
                progressDialog.updateTransferProgress("下载中", finished, total, progress)
            }
        }
        restoreJob = lifecycleScope.launch {
            try {
                AppWebDav.restoreWebDav(
                    name = name,
                    onProgress = onDownloadProgress,
                    onDownloadFinish = {
                        lifecycleScope.launch(Main) {
                            progressDialog.showIndeterminateMessage("恢复中...")
                        }
                    }
                )
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("WebDav恢复出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi("WebDav恢复出错\n${e.localizedMessage}")
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    private fun ProgressDialog.updateTransferProgress(
        action: String,
        finished: Long,
        total: Long,
        progress: Int
    ) {
        if (total > 0 && progress >= 0) {
            isIndeterminate = false
            max = PROGRESS_MAX
            this.progress = progress
            setMessage("$action $progress%\n${formatBytes(finished)} / ${formatBytes(total)}")
        } else {
            showIndeterminateMessage("$action\n${formatBytes(finished)}")
        }
    }

    private fun ProgressDialog.showIndeterminateMessage(message: String) {
        isIndeterminate = true
        setMessage(message)
    }

    private fun ProgressDialog.setCancelConfirmButton(onConfirm: () -> Unit) {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setButton(DialogInterface.BUTTON_NEGATIVE, getString(android.R.string.cancel)) { _, _ -> }
        setOnShowListener {
            getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
                confirmStopTask(onConfirm)
            }
        }
    }

    private fun confirmStopTask(onConfirm: () -> Unit) {
        if (context == null) {
            return
        }
        alert {
            setTitle(R.string.stop)
            setMessage("确认停止？")
            okButton {
                onConfirm()
            }
            cancelButton()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < BYTES_PER_MB) {
            return "${bytes / 1024} KB"
        }
        return String.format("%.1f MB", bytes.toDouble() / BYTES_PER_MB)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        waitDialog.dismiss()
    }

}
