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

        // 关键步骤：重新挂载 /data 分区为 dev,suid,exec
        // Android 的 /data 分区默认以 nosuid,noexec 挂载，
        // 导致 chroot 后的二进制文件无法执行（表现为 "No such file or directory"）
        //
        // 注意：必须保留原有的挂载选项（如 lazytime, seclabel 等），
        // 只替换 nosuid→suid, noexec→exec, nodev→dev，否则会 EINVAL 失败
        try {
            val dataMountOpts = RootUtils.executeWithSu(suPath,
                "grep ' /data ' /proc/mounts | head -1 | sed 's/.* -o //;s/ .*//'")
            if (dataMountOpts != null) {
                val newOpts = dataMountOpts
                    .replace("nosuid", "suid")
                    .replace("noexec", "exec")
                    .replace("nodev", "dev")
                RootUtils.executeWithSu(suPath, "mount -o remount,$newOpts /data 2>/dev/null")
                Log.d(TAG, "/data 已重新挂载: $newOpts")
            } else {
                // fallback: 直接尝试
                RootUtils.executeWithSu(suPath, "mount -o remount,dev,suid,exec /data 2>/dev/null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "重新挂载 /data 失败: ${e.message}")
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
     * 先检查是否已挂载，只卸载确实挂载了的点，避免对系统造成影响。
     * @param suPath su 二进制路径
     * @param containerDir 容器的根文件系统目录
     */
    fun umountAll(suPath: String, containerDir: String) {
        // 逆序卸载：先卸载最内层的挂载
        val mountPoints = listOf(
            "$containerDir/sdcard",
            "$containerDir/dev/pts",
            "$containerDir/dev",
            "$containerDir/sys",
            "$containerDir/proc"
        )

        // 先获取当前已挂载的列表，只处理确实挂载了的点
        val mountedList = getMountedList(suPath, containerDir)
        if (mountedList.isEmpty()) {
            Log.d(TAG, "没有已挂载的文件系统，跳过卸载")
            return
        }

        for (mp in mountPoints) {
            // 检查这个挂载点是否在已挂载列表中
            val isActuallyMounted = mountedList.any { it.contains(mp) }
            if (!isActuallyMounted) {
                Log.d(TAG, "跳过卸载（未挂载）: $mp")
                continue
            }
            try {
                // 使用 lazy umount，立即断开挂载，后台清理
                RootUtils.executeWithSu(suPath, "umount -l \"$mp\" 2>/dev/null")
                Log.d(TAG, "已卸载: $mp")
            } catch (e: Exception) {
                Log.w(TAG, "卸载 $mp 失败: ${e.message}，跳过继续")
            }
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
            MountEntry("tmpfs", "tmpfs", "$containerDir/dev/shm", "tmpfs", "mode=1777")
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