package com.example.speakassist

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.data.SettingsPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * API 配置页：用户填写自己的智谱 AutoGLM 与百度语音 API 凭据。
 *
 * 为什么单独开一页，而不是塞进设置：
 * - 凭据属于"一次性初始化"类信息，设置页是运行时偏好（开关/模式），语义不同
 * - 侧栏单独入口降低发现成本，新用户打开 app 就能看到
 *
 * 导出/导入 JSON 的动机：DataStore 在卸载/清除 app 数据时会丢，导出到 SAF
 * 选择的位置（如 Download）后可跨安装恢复。
 */
class ApiConfigActivity : AppCompatActivity() {

    private lateinit var etZhipu: TextInputEditText
    private lateinit var etBaiduApiKey: TextInputEditText
    private lateinit var etBaiduSecretKey: TextInputEditText

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        // 用户取消时 uri 为 null，静默忽略
        if (uri != null) handleExport(uri)
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_config)

        setupBackToolbar(findViewById(R.id.toolbar), getString(R.string.nav_api_config))

        etZhipu = findViewById(R.id.etZhipuApiKey)
        etBaiduApiKey = findViewById(R.id.etBaiduApiKey)
        etBaiduSecretKey = findViewById(R.id.etBaiduSecretKey)

        lifecycleScope.launch { loadFieldsFromStore() }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.api_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.api_config_export_warn_title)
                    .setMessage(R.string.api_config_export_warn_message)
                    .setPositiveButton(R.string.api_config_export_warn_continue) { _, _ ->
                        exportLauncher.launch("speakassist_api.json")
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            R.id.action_import -> {
                // 宽 MIME：部分文件管理器不把 .json 识别成 application/json
                importLauncher.launch(arrayOf("application/json", "*/*"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun save() {
        val zhipu = etZhipu.text?.toString().orEmpty().trim()
        val baiduKey = etBaiduApiKey.text?.toString().orEmpty().trim()
        val baiduSecret = etBaiduSecretKey.text?.toString().orEmpty().trim()

        // 百度的两个 key 成对：要填都填，要空都空，防止半配置导致识别静默失败
        if (baiduKey.isBlank() != baiduSecret.isBlank()) {
            Toast.makeText(this, R.string.api_config_baidu_incomplete, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            SettingsPrefs.setZhipuApiKey(this@ApiConfigActivity, zhipu)
            SettingsPrefs.setBaiduCredentials(this@ApiConfigActivity, baiduKey, baiduSecret)
            Toast.makeText(this@ApiConfigActivity, R.string.api_config_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun loadFieldsFromStore() {
        etZhipu.setText(SettingsPrefs.zhipuApiKey(this).first())
        etBaiduApiKey.setText(SettingsPrefs.baiduApiKey(this).first())
        etBaiduSecretKey.setText(SettingsPrefs.baiduSecretKey(this).first())
    }

    private fun handleExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                ApiConfigBackup.exportToUri(this@ApiConfigActivity, uri)
                toast(R.string.api_config_exported)
            } catch (e: Exception) {
                toast(getString(R.string.api_config_io_error, e.message.orEmpty()))
            }
        }
    }

    private fun handleImport(uri: Uri) {
        lifecycleScope.launch {
            try {
                ApiConfigBackup.importFromUri(this@ApiConfigActivity, uri)
                // 写入 DataStore 成功后，重新从 store 读回到 EditText，
                // 保持"界面 = 存储"的单一事实来源
                loadFieldsFromStore()
                toast(R.string.api_config_imported)
            } catch (e: ApiConfigBackup.BackupFormatException) {
                toast(R.string.api_config_import_format_error)
            } catch (e: ApiConfigBackup.BackupEmptyException) {
                toast(R.string.api_config_import_empty_error)
            } catch (e: ApiConfigBackup.BackupVersionException) {
                toast(R.string.api_config_import_version_error)
            } catch (e: Exception) {
                toast(getString(R.string.api_config_io_error, e.message.orEmpty()))
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
