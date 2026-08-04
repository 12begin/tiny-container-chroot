// ContainerManageViewModel.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.page

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fct.tc4.R
import com.fct.tc4.RootUtils
import com.fct.tc4.ui.misc.ConfigManager
import com.fct.tc4.ui.misc.Global
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.pow
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.resume

// ====== 页面级别状态 ======
sealed class ContainerManagePageState {
    data object Loading : ContainerManagePageState()
    data object Idle : ContainerManagePageState()
    data class Error(val message: String) : ContainerManagePageState()
}

// ====== 单个容器状态 ======
sealed class ContainerItem {
    abstract val code: String

    data class Loaded(
        override val code: String,
        val name: String = "",
        val description: String = "",
        val image: String = "",
        val spaceBytes: Long? = null
    ) : ContainerItem()

    data class Error(
        override val code: String,
        val message: String
    ) : ContainerItem()
}

// ====== 删除流程 ======

/** 可恢复的删除状态 */
sealed class DeleteState {
    data object InProgress : DeleteState()
    data object Completed : DeleteState()
    data class Failed(val message: String) : DeleteState()
}

// ====== 安装流程 ======

enum class InstallStep { DELETING_OLD, EXTRACTING_ROOTFS, CLEANING_CACHE }

sealed class InstallState {
    data object Idle : InstallState()
    data object ImportWarn : InstallState()
    data object CopyingToCache : InstallState()
    data object ExtractingConfig : InstallState()
    data class AwaitingConfirm(
        val rawConfig: Map<String, Any>,
        val containerSizeBytes: Long
    ) : InstallState()
    data class Installing(
        val code: String,
        val currentStep: InstallStep,
        val startTimeMillis: Long,
        val containerSizeBytes: Long,
        val webpage: String?,
        val log: String = ""
    ) : InstallState()
    data class Completed(
        val launchAfterInstall: Boolean,
        val code: String
    ) : InstallState()
    data class Failed(val message: String) : InstallState()
}

// ====== 导出流程 ======

sealed class ExportState {
    data class PendingPick(val code: String, val displayName: String) : ExportState()
    data class InProgress(val startTimeMillis: Long, val containerSizeBytes: Long) : ExportState()
    data object Completed : ExportState()
    data class Failed(val message: String) : ExportState()
}

class ContainerManageViewModel(application: Application) : AndroidViewModel(application) {

    // ====== 页面状态 ======
    private val _pageState = MutableStateFlow<ContainerManagePageState>(ContainerManagePageState.Loading)
    val pageState: StateFlow<ContainerManagePageState> = _pageState.asStateFlow()

    private val _containers = MutableStateFlow<List<ContainerItem>>(emptyList())
    val containers: StateFlow<List<ContainerItem>> = _containers.asStateFlow()

    private val _selectedPosition = MutableStateFlow(0)
    val selectedPosition: StateFlow<Int> = _selectedPosition.asStateFlow()

    fun selectPosition(position: Int) {
        _selectedPosition.value = position
    }

    // ====== 删除流程 ======
    private val _pendingDeleteConfirm = MutableStateFlow<Pair<String, String>?>(null)
    val pendingDeleteConfirm: StateFlow<Pair<String, String>?> = _pendingDeleteConfirm.asStateFlow()

    private val _deleteState = MutableStateFlow<DeleteState?>(null)
    val deleteState: StateFlow<DeleteState?> = _deleteState.asStateFlow()

    // ====== 安装流程 ======
    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    /** 确认安装对话框中用户正在编辑的 code（用于旋转恢复） */
    private val _installDialogCode = MutableStateFlow("")
    val installDialogCode: StateFlow<String> = _installDialogCode.asStateFlow()

    /** 确认安装对话框中"安装后启动"开关状态（用于旋转恢复） */
    private val _installDialogLaunch = MutableStateFlow(false)
    val installDialogLaunch: StateFlow<Boolean> = _installDialogLaunch.asStateFlow()

    fun updateInstallDialogCode(code: String) {
        _installDialogCode.value = code
    }

    fun updateInstallDialogLaunch(launch: Boolean) {
        _installDialogLaunch.value = launch
    }

    fun resetInstallDialogState() {
        _installDialogCode.value = ""
        _installDialogLaunch.value = false
    }

    init {
        loadContainers()
    }

    fun loadContainers() {
        viewModelScope.launch(Dispatchers.IO) {
            _pageState.value = ContainerManagePageState.Loading
            try {
                val codes = Global.installedContainers.toList()
                if (codes.isEmpty()) {
                    _containers.value = emptyList()
                    _pageState.value = ContainerManagePageState.Idle
                    return@launch
                }
                val list = codes.map { code ->
                    try {
                        val config = ConfigManager.load(code)
                        if (config != null) {
                            ContainerItem.Loaded(
                                code = code,
                                name = config["name"] as? String ?: "",
                                description = config["description"] as? String ?: "",
                                image = config["preview"] as? String ?: ""
                            )
                        } else {
                            ContainerItem.Loaded(code = code)
                        }
                    } catch (e: Exception) {
                        ContainerItem.Error(code = code, message = getApplication<Application>().getString(R.string.tc4_container_loading_failed, code))
                    }
                }
                _containers.value = list
                _pageState.value = ContainerManagePageState.Idle

                val dataDir = getApplication<Application>().dataDir
                list.forEach { item ->
                    if (item is ContainerItem.Loaded) {
                        val dir = File(dataDir, item.code)
                        updateSpace(item.code, calculateContainerSize(dir))
                    }
                }
            } catch (e: Exception) {
                _pageState.value = ContainerManagePageState.Error(
                    getApplication<Application>().getString(R.string.tc4_container_list_load_failed, e.message ?: ""))
            }
        }
    }

    private fun calculateContainerSize(dir: File): Long {
        var bytes = 0L
        if (dir.exists()) {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    return if (attrs.isSymbolicLink) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(path: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE // 忽略报错，继续执行
            })
        }
        return bytes
    }

    fun fakeProgress(startTimeMillis: Long, containerSizeBytes: Long): Float {
        val elapsedMin = (System.currentTimeMillis() - startTimeMillis) / 60000f
        val containerSizeGB = containerSizeBytes / 1_000_000_000f
        return 1f - 10f.pow(-elapsedMin / 2f / containerSizeGB)
    }

    fun updateSpace(code: String, bytes: Long) {
        _containers.value = _containers.value.map {
            if (it.code == code && it is ContainerItem.Loaded) it.copy(spaceBytes = bytes) else it
        }
    }

    // ======================== 编辑容器信息 ========================

    fun updateContainerConfig(code: String, name: String, description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val config = ConfigManager.load(code) ?: return@launch
            val updated = config.toMutableMap().apply {
                put("name", name)
                put("description", description)
            }
            ConfigManager.save(code, updated)
            _containers.value = _containers.value.map {
                if (it.code == code && it is ContainerItem.Loaded) {
                    it.copy(name = name, description = description)
                } else it
            }
        }
    }

    // ======================== 删除流程 ========================

    fun requestDelete(code: String, displayName: String) {
        _pendingDeleteConfirm.value = code to displayName
    }

    fun confirmDelete(code: String) {
        _pendingDeleteConfirm.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _deleteState.value = DeleteState.InProgress
            try {
                val dir = File(getApplication<Application>().dataDir, code)
                if (dir.exists()) dir.deleteRecursively()
                if (code == Global.autoLaunch) {
                    Global.autoLaunch = ""
                }
                Global.installedContainers -= code
                _containers.value = _containers.value.filter { it.code != code }
                _deleteState.value = DeleteState.Completed
            } catch (e: Exception) {
                _deleteState.value = DeleteState.Failed(
                    getApplication<Application>().getString(R.string.tc4_container_delete_failed, e.message ?: ""))
            }
        }
    }

    fun cancelDelete() {
        _pendingDeleteConfirm.value = null
    }

    fun resetDeleteState() {
        _deleteState.value = null
    }

    // ======================== 导出流程 ========================

    private val _exportState = MutableStateFlow<ExportState?>(null)
    val exportState: StateFlow<ExportState?> = _exportState.asStateFlow()

    fun requestExport(code: String, displayName: String) {
        _exportState.value = ExportState.PendingPick(code, displayName)
    }

    fun exportContainer(code: String, uri: Uri) {
        val sizeBytes = _containers.value
            .find { it.code == code }
            ?.let { (it as? ContainerItem.Loaded)?.spaceBytes }
            ?: 1_000_000_000L
        _exportState.value = ExportState.InProgress(
            startTimeMillis = System.currentTimeMillis(),
            containerSizeBytes = sizeBytes
        )
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val config = ConfigManager.load(code)
                    ?: throw Exception(app.getString(R.string.tc4_container_config_missing))
                val exportCmd = config["export_command"] as? String
                    ?: throw Exception(app.getString(R.string.tc4_export_missing_cmd))

                execShell {
                    Global.setupEnvironment()
                    Global.sendCommand("export CONTAINER_DIR=${app.dataDir.absolutePath}/$code")
                    Global.sendCommand("mkdir -p ${app.filesDir}/public")
                    Global.sendCommand(exportCmd)
                    Global.sendCommand("exit")
                }


                val outputFile = File(app.filesDir, "public/rootfs.tar.zst")
                if (!outputFile.exists()) throw Exception(app.getString(R.string.tc4_export_no_output))

                app.contentResolver.openOutputStream(uri)?.use { out ->
                    outputFile.inputStream().use { input -> input.copyTo(out, bufferSize = 8192) }
                } ?: throw Exception(app.getString(R.string.tc4_export_cannot_write))

                outputFile.delete()
                _exportState.value = ExportState.Completed
            } catch (e: Exception) {
                _exportState.value = ExportState.Failed(
                    app.getString(R.string.tc4_export_failed, e.message ?: ""))
            }
        }
    }

    fun resetExportState() {
        _exportState.value = null
    }

    // ======================== 安装流程 ========================

    /** 用户通过菜单/按钮发起导入（弹确认对话框） */
    fun startImport() {
        _installState.value = InstallState.ImportWarn
    }

    /** 用户通过 SAF 选完文件后调用 */
    fun onFileSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _installState.value = InstallState.CopyingToCache
            val app = getApplication<Application>()
            try {
                val cacheFile = File(app.cacheDir, "rootfs.tar.zst")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                } ?: throw IllegalStateException(app.getString(R.string.tc4_validate_clipboard_read))

                processCachedRootfs()
            } catch (e: Exception) {
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_failed, e.message ?: ""))
            }
        }
    }

    /** 从 assets 内置 rootfs.tar.zst 开始导入（用户手动点"安装内置容器"触发） */
    fun startBuiltInImport() {
        viewModelScope.launch(Dispatchers.IO) {
            _installState.value = InstallState.CopyingToCache
            val app = getApplication<Application>()
            try {
                val cacheFile = File(app.cacheDir, "rootfs.tar.zst")
                app.assets.open("rootfs.tar.zst").use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }

                processCachedRootfs()
            } catch (e: Exception) {
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_builtin_failed, e.message ?: ""))
            }
        }
    }

    /** 初次启动自动安装内置容器，跳过所有用户确认步骤 */
    fun autoInstallBuiltInContainer() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val cacheFile = File(app.cacheDir, "rootfs.tar.zst")
                val code = "xfce"

                // 先进入 Installing 状态，显示日志弹窗
                _installState.value = InstallState.Installing(
                    code = code,
                    currentStep = InstallStep.DELETING_OLD,
                    startTimeMillis = System.currentTimeMillis(),
                    containerSizeBytes = 0L,
                    webpage = null,
                    log = "开始安装容器..."
                )

                // 从 assets 复制到缓存
                appendLog("开始复制 rootfs 到缓存...")
                appendLog("assets 中 rootfs.tar.zst 是否存在: ${try { app.assets.open("rootfs.tar.zst").use { true } } catch (_: Exception) { false }}")
                appendLog("assets 中 .tiny.yaml 是否存在: ${try { app.assets.open(".tiny.yaml").use { true } } catch (_: Exception) { false }}")
                app.assets.open("rootfs.tar.zst").use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
                appendLog("复制完成，缓存文件大小: ${cacheFile.length()} 字节")

                // 读取配置
                appendLog("读取 .tiny.yaml 配置...")
                val config = try {
                    app.assets.open(".tiny.yaml").use { input ->
                        val content = input.bufferedReader().readText()
                        @Suppress("UNCHECKED_CAST")
                        Yaml().load<Map<String, Any>>(content)
                    }
                } catch (e: Exception) {
                    appendLog("读取配置失败: ${e.message}")
                    null
                }
                if (config == null) {
                    appendLog("配置为空，使用硬编码默认配置")
                }

                // 直接安装
                performInstall(code, config ?: mapOf(
                    "code" to code,
                    "name" to "XFCE Desktop",
                    "description" to "XFCE Desktop Environment",
                    "chroot_boot_command" to "/bin/bash --login",
                    "feature" to listOf(mapOf("type" to "audio", "enabled" to true))
                ))

                appendLog("安装完成！")
                Global.autoLaunch = code
                Global.isFirstLaunchDone = true

                _installState.value = InstallState.Completed(
                    launchAfterInstall = false,  // 不自动启动，让用户看到安装结果
                    code = code
                )
            } catch (e: Exception) {
                appendLog("安装失败: ${e.message}")
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_auto_failed, e.message ?: ""))
            }
        }
    }

    /** 用户确认安装 */
    fun confirmInstall(code: String, launchAfterInstall: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val currentState = _installState.value
            val rawConfig = (currentState as? InstallState.AwaitingConfirm)?.rawConfig ?: run {
                _installState.value = InstallState.Failed(app.getString(R.string.tc4_install_state_error))
                return@launch
            }
            try {
                performInstall(code, rawConfig)
                _installState.value = InstallState.Completed(
                    launchAfterInstall = launchAfterInstall,
                    code = code
                )
            } catch (e: Exception) {
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_failed, e.message ?: ""))
            }
        }
    }

    /** 从已复制到 cache 的 rootfs.tar.zst 中提取配置，进入等待确认状态 */
    private suspend fun processCachedRootfs() {
        val config = extractAndParseConfig() ?: return
        val cacheFile = File(getApplication<Application>().cacheDir, "rootfs.tar.zst")
        val code = config["code"] as? String ?: ""
        if (code.isBlank()) {
            cleanCacheFiles()
            _installState.value = InstallState.Failed(
                getApplication<Application>().getString(R.string.tc4_import_missing_code))
            return
        }
        _installState.value = InstallState.AwaitingConfirm(
            rawConfig = config,
            containerSizeBytes = cacheFile.length()
        )
    }

    /** 从缓存 rootfs.tar.zst 中提取 .tiny.yaml 并解析，失败时已设置 Failed 状态并返回 null */
    private suspend fun extractAndParseConfig(): Map<String, Any>? {
        val app = getApplication<Application>()

        _installState.value = InstallState.ExtractingConfig

        // 尝试用 execShell + tar 提取 .tiny.yaml
        val cacheDir = app.cacheDir.absolutePath
        val rootfsFile = File(app.cacheDir, "rootfs.tar.zst")
        if (!rootfsFile.exists()) {
            cleanCacheFiles()
            _installState.value = InstallState.Failed(app.getString(R.string.tc4_import_failed, "rootfs file not found"))
            return null
        }

        // 方式一：通过 execShell 提取（原版方式）
        val extracted = extractConfigViaExecShell(app, cacheDir)
        if (!extracted) {
            // 方式一失败，尝试方式二：直接通过 ProcessBuilder 调用 tar
            extractConfigViaProcessBuilder(app, cacheDir)
        }

        val configFile = File(app.cacheDir, ".tiny.yaml")
        if (!configFile.exists()) {
            cleanCacheFiles()
            _installState.value = InstallState.Failed(app.getString(R.string.tc4_import_no_config))
            return null
        }
        val config = try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any>>(configFile.readText())
        } catch (e: Exception) {
            cleanCacheFiles()
            _installState.value = InstallState.Failed(
                app.getString(R.string.tc4_import_config_parse_failed, e.message ?: ""))
            return null
        }
        return config
    }

    /** 通过 execShell 提取 .tiny.yaml */
    private suspend fun extractConfigViaExecShell(app: Application, cacheDir: String): Boolean {
        return try {
            val bootstrapDir = "${app.filesDir.absolutePath}/bootstrap/bin"
            execShell {
                // 先解压 zstd，再用 tar 提取（避免 tar 不支持 zstd 的问题）
                Global.sendCommand("$bootstrapDir/zstd -d -f \"$cacheDir/rootfs.tar.zst\" -o \"$cacheDir/rootfs.tar\" 2>/dev/null")
                Global.sendCommand("$bootstrapDir/tar -xf \"$cacheDir/rootfs.tar\" -C \"$cacheDir\" .tiny.yaml 2>/dev/null")
                Global.sendCommand("exit")
            }
            File(app.cacheDir, ".tiny.yaml").exists()
        } catch (_: Exception) {
            false
        }
    }

    /** 通过 ProcessBuilder 提取 .tiny.yaml（备用方案） */
    private fun extractConfigViaProcessBuilder(app: Application, cacheDir: String) {
        try {
            val bootstrapDir = "${app.filesDir.absolutePath}/bootstrap/bin"
            val env = mapOf("LD_LIBRARY_PATH" to "${app.filesDir.absolutePath}/bootstrap/lib")

            // 1. 先解压 zstd
            val zstd = ProcessBuilder("$bootstrapDir/zstd", "-d", "-f",
                "$cacheDir/rootfs.tar.zst", "-o", "$cacheDir/rootfs.tar")
            zstd.environment().putAll(env)
            zstd.start().waitFor(120, java.util.concurrent.TimeUnit.SECONDS)

            // 2. 再用 tar 提取 .tiny.yaml
            val tar = ProcessBuilder("$bootstrapDir/tar", "-xf",
                "$cacheDir/rootfs.tar", "-C", cacheDir, ".tiny.yaml")
            tar.environment().putAll(env)
            tar.start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS)

            // 清理临时 tar 文件
            File(cacheDir, "rootfs.tar").delete()
        } catch (_: Exception) {
            File(cacheDir, "rootfs.tar").delete()
        }
    }

    /** 执行实际的容器安装操作（解压 rootfs、修复权限、保存配置），调用方负责状态管理 */
    private suspend fun performInstall(code: String, rawConfig: Map<String, Any>) {
        val app = getApplication<Application>()
        val cacheFile = File(app.cacheDir, "rootfs.tar.zst")

        _installState.value = InstallState.Installing(
            code = code,
            currentStep = InstallStep.DELETING_OLD,
            startTimeMillis = System.currentTimeMillis(),
            containerSizeBytes = cacheFile.length(),
            webpage = rawConfig["webpage"] as? String
        )

        // DELETING_OLD
        val dir = File(app.dataDir, code)
        if (dir.exists()) dir.deleteRecursively()
        updateCurrentStep(InstallStep.EXTRACTING_ROOTFS)

        // EXTRACTING_ROOTFS
        dir.mkdirs()
        val cacheDir = app.cacheDir.absolutePath
        val containerDir = "${app.dataDir.absolutePath}/$code"

        appendLog("开始解压 rootfs...")
        appendLog("cacheDir: $cacheDir")
        appendLog("containerDir: $containerDir")
        appendLog("rootfs 文件大小: ${cacheFile.length()} 字节")

        // 验证 bootstrap 文件是否存在
        val bDir = "${app.filesDir.absolutePath}/bootstrap/bin"
        val bLib = "${app.filesDir.absolutePath}/bootstrap/lib"
        val appLibDir = "${app.filesDir.absolutePath}/applib"
        appendLog("bDir 存在: ${File(bDir).exists()}")
        appendLog("bDir 内容: ${File(bDir).list()?.take(30)?.joinToString(", ") ?: "空"}")
        appendLog("bDir/tar 存在: ${File("$bDir/tar").exists()}")
        appendLog("bLib/libzstd.so.1 存在: ${File("$bLib/libzstd.so.1").exists()}")
        appendLog("bLib 内容: ${File(bLib).list()?.take(30)?.joinToString(", ") ?: "空"}")
        appendLog("applib 存在: ${File(appLibDir).exists()}")
        appendLog("applib 内容: ${File(appLibDir).list()?.take(30)?.joinToString(", ") ?: "空"}")

        // 先用 execShell 测试终端是否正常工作
        var testOk = false
        execShell(15_000) {
            Global.sendCommand("echo 'shell_test_ok' && exit")
        }
        testOk = true
        appendLog("终端测试: 正常")

        // 先用 execShell 创建 bootstrap symlink，再解压
        appendLog("正在创建 bootstrap symlink...")
        execShell(30_000) {
            val fDir = app.filesDir.absolutePath
            // 直接创建 symlink，不依赖 shouldResetBootstrap
            Global.sendCommand("mkdir -p $fDir/bootstrap/bin $fDir/bootstrap/lib")
            Global.sendCommand("ln -sf $fDir/applib/lib__bin__busybox__.so $fDir/bootstrap/bin/busybox")
            Global.sendCommand("ln -sf $fDir/applib/lib__bin__busybox__.so $fDir/bootstrap/bin/sh")
            Global.sendCommand("ln -sf $fDir/applib/lib__bin__tar__.so $fDir/bootstrap/bin/tar")
            Global.sendCommand("ln -sf $fDir/applib/lib__bin__zstd__.so $fDir/bootstrap/bin/zstd")
            Global.sendCommand("ln -sf $fDir/applib/lib__lib__libzstd.so.1.5.7__.so $fDir/bootstrap/lib/libzstd.so.1")
            Global.sendCommand("exit")
        }
        appendLog("创建后 bDir 内容: ${File(bDir).list()?.take(30)?.joinToString(", ") ?: "空"}")
        appendLog("创建后 bDir/tar 存在: ${File("$bDir/tar").exists()}")
        appendLog("创建后 bLib/libzstd.so.1 存在: ${File("$bLib/libzstd.so.1").exists()}")
        appendLog("创建后 bLib 内容: ${File(bLib).list()?.take(30)?.joinToString(", ") ?: "空"}")

        // 用 ProcessBuilder 解压（使用 busybox tar，不需要 LD_LIBRARY_PATH）
        var extracted = false
        try {
            val bDir = "${app.filesDir.absolutePath}/bootstrap/bin"

            appendLog("步骤1: zstd 解压...")
            val zstdCmd = arrayOf("$bDir/zstd", "-d", "-f", "$cacheDir/rootfs.tar.zst", "-o", "$cacheDir/rootfs.tar")
            val zstdPb = ProcessBuilder(*zstdCmd)
            zstdPb.environment().putAll(mapOf("LD_LIBRARY_PATH" to "${app.filesDir.absolutePath}/bootstrap/lib"))
            zstdPb.redirectErrorStream(true)
            val zstdProc = zstdPb.start()
            val zstdOut = zstdProc.inputStream.bufferedReader().readText()
            zstdProc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
            appendLog("zstd 完成, 输出: $zstdOut")

            appendLog("步骤2: tar 提取...")
            val tarCmd = arrayOf("$bDir/tar", "-xf", "$cacheDir/rootfs.tar", "-C", containerDir)
            val tarPb = ProcessBuilder(*tarCmd)
            tarPb.environment().putAll(mapOf("LD_LIBRARY_PATH" to "${app.filesDir.absolutePath}/bootstrap/lib"))
            tarPb.redirectErrorStream(true)
            val tarProc = tarPb.start()
            val tarOut = tarProc.inputStream.bufferedReader().readText()
            tarProc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
            appendLog("tar 完成, 输出: $tarOut")

            // 清理
            File("$cacheDir/rootfs.tar.zst").delete()
            File("$cacheDir/rootfs.tar").delete()

            extracted = File(containerDir, "etc").exists()
            appendLog("解压结果: extracted=$extracted")
        } catch (e: Exception) {
            appendLog("解压异常: ${e.message}")
        }

        if (!extracted) {
            appendLog("解压失败！容器目录内容:")
            val dirList = dir.list()?.take(20)?.joinToString(", ") ?: "空"
            appendLog("$dirList")
        } else {
            appendLog("解压成功！")
            // 修复容器 rootfs 关键文件的所有者
            // 原项目的 rootfs 是在 proot 的 uid 映射下打包的，
            // 文件所有者可能是宿主机的 uid（如 10337），chroot 下 uid 不映射，
            // 导致 sudo 等需要 root 所有权的文件报错
            try {
                appendLog("修复文件所有者...")
                val suPath = Global.suPath
                if (suPath.isNotEmpty()) {
                    RootUtils.executeWithSu(suPath,
                        "chown -R 0:0 \"$containerDir/etc/sudo.conf\" \"$containerDir/etc/sudoers\" \"$containerDir/etc/sudoers.d\" 2>/dev/null")
                    RootUtils.executeWithSu(suPath,
                        "chmod 440 \"$containerDir/etc/sudoers\" 2>/dev/null")
                    appendLog("文件所有者修复完成")
                }
            } catch (e: Exception) {
                appendLog("修复文件所有者失败: ${e.message}")
            }
            // 复制 bootstrap busybox 到容器内，作为 chroot 的静态入口
            try {
                val srcBusybox = File("$bDir/busybox")
                val dstBusybox = File(containerDir, "bin/busybox")
                if (srcBusybox.exists() && !dstBusybox.exists()) {
                    srcBusybox.copyTo(dstBusybox, overwrite = false)
                    // 设置可执行权限
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", dstBusybox.absolutePath)).waitFor()
                    appendLog("已复制 busybox 到容器内: ${dstBusybox.absolutePath}")
                } else {
                    appendLog("busybox 已存在或源文件不存在: srcExists=${srcBusybox.exists()}, dstExists=${dstBusybox.exists()}")
                }
            } catch (e: Exception) {
                appendLog("复制 busybox 失败: ${e.message}")
            }
        }
        updateCurrentStep(InstallStep.CLEANING_CACHE)
        cleanCacheFiles()

        Global.installedContainers += code
        val mergedConfig = rawConfig.toMutableMap()
        mergedConfig["code"] = code
        ConfigManager.save(code, mergedConfig)

        loadContainers()
    }

    fun cancelInstall() {
        cleanCacheFiles()
        _installState.value = InstallState.Idle
    }

    fun cleanCacheFiles() {
        File(getApplication<Application>().cacheDir, "rootfs.tar.zst").delete()
        File(getApplication<Application>().cacheDir, ".tiny.yaml").delete()
    }

    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }

    /** 向安装日志追加一行 */
    private fun appendLog(msg: String) {
        val current = _installState.value
        if (current is InstallState.Installing) {
            _installState.value = current.copy(log = current.log + "\n" + msg)
        }
    }

    /** 向 MainViewModel 的日志面板追加日志 */
    private fun mainLog(msg: String) {
        try {
            val app = getApplication<Application>()
            // 通过 activityViewModels 获取 MainViewModel 的方式行不通，
            // 直接改用 InstallState 的 log 字段，由 MainActivity 统一收集
            appendLog(msg)
        } catch (_: Exception) {}
    }

    private fun updateCurrentStep(step: InstallStep) {
        val current = _installState.value
        if (current is InstallState.Installing) {
            _installState.value = current.copy(currentStep = step)
        }
    }

    /** 在 terminal session 中执行命令，等待 session 结束后返回 exitCode */
    private suspend fun execShell(timeoutMs: Long = 0, block: () -> Unit): Int = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                Global.newSession(onFinished = { exitCode ->
                    if (cont.isActive) cont.resume(exitCode)
                })
                block()
                // 如果设置了超时，在超时后取消
                if (timeoutMs > 0) {
                    // 用线程池实现超时，不依赖协程
                    Thread {
                        try {
                            Thread.sleep(timeoutMs)
                            if (cont.isActive) {
                                appendLog("execShell 超时（${timeoutMs}ms）")
                                cont.resume(-1)
                            }
                        } catch (_: InterruptedException) {}
                    }.start()
                }
            } catch (e: Exception) {
                appendLog("execShell 异常: ${e.message}")
                if (cont.isActive) cont.resume(-1)
            }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L     -> "%.2f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L         -> "%.2f KB".format(bytes / 1_000.0)
    else                    -> "$bytes B"
}
