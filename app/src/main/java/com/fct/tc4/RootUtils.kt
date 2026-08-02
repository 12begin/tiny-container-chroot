// RootUtils.kt -- This file is part of tiny_container.
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

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root 权限检测工具。
 *
 * 检测设备是否已 root、su 二进制路径、chroot 命令是否可用。
 * 所有检测通过执行实际命令完成，不走静态特征匹配（避免假阳性）。
 */
object RootUtils {

    /**
     * Root 检测结果
     * @param rootAvailable 是否拥有 root 权限
     * @param suPath su 二进制路径，null 表示未找到
     * @param chrootAvailable chroot 命令是否可用
     * @param errorMessage 错误信息，null 表示检测正常
     */
    data class RootStatus(
        val rootAvailable: Boolean,
        val suPath: String?,
        val chrootAvailable: Boolean,
        val errorMessage: String? = null
    )

    /** 常见的 su 二进制路径，按优先级排序 */
    private val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/system/bin/su",
        "/su/bin/su"
    )

    /**
     * 执行 root 检测，返回完整状态。
     */
    fun checkRootStatus(): RootStatus {
        val suPath = findSuBinary()
        if (suPath == null) {
            return RootStatus(
                rootAvailable = false,
                suPath = null,
                chrootAvailable = false,
                errorMessage = "未找到 su 二进制文件。请确认设备已 root 并授予了 root 权限。"
            )
        }

        // 验证 su 是否真的能工作
        val uid = executeWithSu(suPath, "id -u")
        val isRoot = uid?.trim() == "0"

        if (!isRoot) {
            return RootStatus(
                rootAvailable = false,
                suPath = suPath,
                chrootAvailable = false,
                errorMessage = "su 命令存在，但未能获取 root 权限。请检查 root 管理器是否授予了权限。"
            )
        }

        // 检测 chroot 是否可用
        val chrootCheck = executeWithSu(suPath, "command -v chroot")
        val chrootAvailable = chrootCheck?.trim()?.isNotEmpty() == true

        return RootStatus(
            rootAvailable = true,
            suPath = suPath,
            chrootAvailable = chrootAvailable
        )
    }

    /**
     * 在常见路径中查找 su 二进制。
     * 由于 File.exists() 在隔离环境下可能受限，优先尝试直接执行。
     */
    private fun findSuBinary(): String? {
        for (path in suPaths) {
            try {
                if (File(path).exists()) {
                    return path
                }
            } catch (_: SecurityException) {
                // 无权限访问，跳过
            }
        }

        // 如果文件检测失败，尝试用 which/command -v 查找
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v su 2>/dev/null"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val result = reader.readLine()
            proc.waitFor()
            if (result?.isNotEmpty() == true) result else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过 su 执行命令，返回命令输出。
     * @param suPath su 二进制路径
     * @param command 要执行的命令
     * @return 命令的标准输出，null 表示执行失败
     */
    fun executeWithSu(suPath: String, command: String): String? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(suPath, "-c", command))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val output = reader.readText()
            val errorReader = BufferedReader(InputStreamReader(proc.errorStream))
            val error = errorReader.readText()
            proc.waitFor()

            if (proc.exitValue() != 0 && output.isBlank()) {
                // 命令执行失败，返回错误信息供调试
                error.ifBlank { null }
            } else {
                output.ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过 su 执行命令，返回命令输出（使用已保存的 su 路径）。
     */
    fun executeWithSu(command: String): String? {
        val suPath = findSuBinary() ?: return null
        return executeWithSu(suPath, command)
    }
}