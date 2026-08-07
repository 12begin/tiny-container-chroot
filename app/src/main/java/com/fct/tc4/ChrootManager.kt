// ChrootManager.kt -- This file is part of tiny_container.
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

package com.fct.tc4

import android.util.Log

/**
 * chroot 容器文件系统挂载管理。
 *
 * 负责在 chroot 启动前挂载必要的文件系统（proc/sys/dev/dev/pts/sdcard），
 * 以及在容器退出后卸载它们。所有操作通过 RootUtils.executeWithSu 以 root 权限执行。
 */
object ChrootManager {

    private const val TAG = "ChrootManager"

    /** 需要挂载的文件系统列表 */
    private data class MountEntry(
        val type: String,        // proc / sysfs / bind
        val source: String,      // 源路径
        val target: String,      // 容器内目标路径
        val fsType: String = "", // 文件系统类型（proc/sysfs 专用）
        val options: String = "" // 挂载选项
    )

    /**
     * 挂载所有必要的文件系统到容器的文件系统目录。
     * @param suPath su 二进制路径
     * @param containerDir 容器的根文件系统目录
     * @param extraBindMounts 额外的绑定挂载，格式 "源路径:目标路径"
     * @return true 表示全部挂载成功
     */
    fun mountAll(
        suPath: String,
        containerDir: String,
        extraBindMounts: List<String> = emptyList()
    ): Boolean {
        var allSuccess = true

        // 先清理所有旧的挂载点，避免反复启动退出导致挂载点堆积
        // umount -l 会立即断开，即使进程还在使用
        val oldMounts = getMountedList(suPath, containerDir)
        for (line in oldMounts) {
            val parts = line.split(" ")
            if (parts.size >= 2) {
                RootUtils.executeWithSu(suPath, "umount -l \"${parts[1]}\" 2>/dev/null")
            }
        }

        // 关键步骤：先 bind mount 容器目录自身，再 remount 为 exec,suid,dev
        // 和 linuxdeploy 的 mount_part root 逻辑一致：
        //   mount -o bind "${TARGET_PATH}" "${CHROOT_DIR}" &&
        //   mount -o remount,exec,suid,dev "${CHROOT_DIR}"
        // 只 remount 容器目录本身，不修改整个 /data 分区，避免 KernelSU 阻止
        try {
            RootUtils.executeWithSu(suPath, "mount -o bind \"$containerDir\" \"$containerDir\" 2>/dev/null")
            RootUtils.executeWithSu(suPath, "mount -o remount,exec,suid,dev \"$containerDir\" 2>/dev/null")
            Log.d(TAG, "容器目录已 remount 为 exec,suid,dev: $containerDir")
        } catch (e: Exception) {
            Log.w(TAG, "remount 容器目录失败: ${e.message}")
            allSuccess = false
        }

        val entries = buildMountEntries(containerDir, extraBindMounts)
        // 获取当前已挂载列表，跳过已挂载的点
        val mountedList = getMountedList(suPath, containerDir)

        for (entry in entries) {
            // 跳过已挂载的点
            if (mountedList.any { it.contains(entry.target) }) {
                Log.d(TAG, "跳过挂载（已挂载）: ${entry.target}")
                continue
            }
            val result = mount(suPath, entry)
            if (!result) {
                Log.w(TAG, "挂载失败: ${entry.type} ${entry.source} → ${entry.target}")
                allSuccess = false
            }
        }

        return allSuccess
    }

    /**
     * 卸载所有已挂载的文件系统（逆序卸载）。
     * 和 linuxdeploy 的 container_umount 逻辑一致：
     * 1. 从 /proc/mounts 获取所有挂载点
     * 2. 逆序卸载（先卸载最内层的挂载）
     * 3. 对每个挂载点尝试 3 次
     * 4. 最后卸载容器目录自身的 bind mount
     * @param suPath su 二进制路径
     * @param containerDir 容器的根文件系统目录
     */
    fun umountAll(suPath: String, containerDir: String) {
        // 不 kill 任何进程，直接用 lazy umount
        // 注意：不能用 lsof 搜索容器目录，会误杀原项目进程
        // umount -l 会立即断开，即使进程还在使用，进程退出后自动清理

        // 从 /proc/mounts 获取所有容器相关的挂载点
        val mountList = getMountedList(suPath, containerDir)
        if (mountList.isEmpty()) {
            Log.d(TAG, "没有已挂载的文件系统，跳过卸载")
            return
        }

        // 从挂载行中提取目标路径（第二个字段）
        // 排除 containerDir 自身（bind mount 自身），
        // 卸载它会导致容器目录不可访问
        val targets = mountList.mapNotNull { line ->
            val parts = line.split(" ")
            if (parts.size >= 2) parts[1] else null
        }.filter { it.startsWith(containerDir) && it != containerDir }

        // 逆序卸载（先卸载最内层的挂载）
        for (target in targets.reversed()) {
            // 用 umount -l（lazy unmount），立即断开挂载点
            // 多次尝试，确保卸载干净
            for (attempt in 1..5) {
                try {
                    RootUtils.executeWithSu(suPath, "umount -l \"$target\" 2>/dev/null")
                    RootUtils.executeWithSu(suPath, "umount \"$target\" 2>/dev/null")
                } catch (_: Exception) {}
                Thread.sleep(200)
            }
            Log.d(TAG, "已尝试卸载: $target")
        }
        // 最终确认：再次检查是否有残留挂载
        val remaining = getMountedList(suPath, containerDir)
        if (remaining.isNotEmpty()) {
            Log.w(TAG, "仍有残留挂载点: ${remaining.size} 个")
            // 用 su 强制清理 /proc/mounts 中容器相关的挂载
            try {
                RootUtils.executeWithSu(suPath,
                    "cat /proc/mounts | grep '$containerDir' | awk '{print \$2}' | sort -r | while read mp; do umount -l \"\$mp\" 2>/dev/null; done")
            } catch (_: Exception) {}
        }
    }

    /**
     * 检查容器文件系统是否已挂载。
     * @param containerDir 容器的根文件系统目录
     * @return true 表示 proc 已挂载（可作为挂载状态的标志）
     */
    fun isMounted(suPath: String, containerDir: String): Boolean {
        val result = RootUtils.executeWithSu(suPath, "mount | grep \"$containerDir/proc\"")
        return result?.contains("$containerDir/proc") == true
    }

    /**
     * 获取已挂载的文件系统列表（用于 UI 显示）。
     * @param containerDir 容器的根文件系统目录
     * @return 已挂载的文件系统描述列表
     */
    fun getMountedList(suPath: String, containerDir: String): List<String> {
        val result = RootUtils.executeWithSu(suPath, "mount | grep \"$containerDir\"")
        if (result.isNullOrBlank()) return emptyList()
        return result.lines().filter { it.contains(containerDir) }
    }

    // ===================== 内部方法 =====================

    private fun buildMountEntries(
        containerDir: String,
        extraBindMounts: List<String>
    ): List<MountEntry> {
        val entries = mutableListOf(
            MountEntry("proc", "proc", "$containerDir/proc", "proc"),
            MountEntry("sysfs", "sysfs", "$containerDir/sys", "sysfs"),
            MountEntry("bind", "/dev", "$containerDir/dev", "", "bind"),
            MountEntry("bind", "/dev/pts", "$containerDir/dev/pts", "", "bind"),
            // dev/shm 是很多应用（如 Python、Chrome）需要的共享内存
            MountEntry("tmpfs", "tmpfs", "$containerDir/dev/shm", "tmpfs", "mode=1777"),
            // 绑定 Android 系统分区，供容器内访问
            MountEntry("bind", "/system", "$containerDir/system", "", "bind"),
            MountEntry("bind", "/vendor", "$containerDir/vendor", "", "bind")
        )

        // 如果 /sdcard 存在，添加绑定挂载
        try {
            if (java.io.File("/sdcard").exists()) {
                entries.add(MountEntry("bind", "/sdcard", "$containerDir/sdcard", "", "bind"))
            }
        } catch (_: Exception) {
            // 忽略
        }

        // 添加用户自定义的绑定挂载
        for (extra in extraBindMounts) {
            val parts = extra.split(":")
            if (parts.size == 2) {
                entries.add(MountEntry("bind", parts[0], "$containerDir/${parts[1]}", "", "bind"))
            }
        }

        return entries
    }

    private fun mount(suPath: String, entry: MountEntry): Boolean {
        return try {
            val cmd = when (entry.type) {
                "proc" -> "mount -t proc proc \"${entry.target}\" 2>/dev/null"
                "sysfs" -> "mount -t sysfs sysfs \"${entry.target}\" 2>/dev/null"
                "tmpfs" -> {
                    // 确保目标目录存在
                    RootUtils.executeWithSu(suPath, "mkdir -p \"${entry.target}\"")
                    val opts = if (entry.options.isNotBlank()) "-o ${entry.options}" else ""
                    "mount -t tmpfs $opts tmpfs \"${entry.target}\" 2>/dev/null"
                }
                "bind" -> {
                    // 确保目标目录存在
                    RootUtils.executeWithSu(suPath, "mkdir -p \"${entry.target}\"")
                    "mount -o bind \"${entry.source}\" \"${entry.target}\" 2>/dev/null"
                }
                else -> return false
            }
            val result = RootUtils.executeWithSu(suPath, cmd)
            // mount 成功时无输出，失败时输出错误信息
            result == null
        } catch (e: Exception) {
            Log.e(TAG, "mount 异常: ${entry.type} ${entry.source}", e)
            false
        }
    }
}