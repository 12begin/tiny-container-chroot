// ChrootManageActivity.kt -- This file is part of tiny_container.
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

import android.app.Application
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewModelScope
import com.fct.tc4.databinding.Tc4ActivityChrootManageBinding
import com.fct.tc4.ui.misc.Global
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chroot 容器管理界面。
 *
 * 显示挂载状态、提供挂载/卸载/修复权限/共享目录管理功能。
 */
class ChrootManageActivity : AppCompatActivity() {

    private lateinit var binding: Tc4ActivityChrootManageBinding
    private var containerCode: String = ""
    private var containerDir: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = Tc4ActivityChrootManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tc4_chroot_title)

        containerCode = intent.getStringExtra("code") ?: ""
        containerDir = "${(application as Application).dataDir.absolutePath}/$containerCode"

        // 挂载/卸载按钮
        binding.btnMount.setOnClickListener { mountAll() }
        binding.btnUnmount.setOnClickListener { unmountAll() }

        // 修复权限
        binding.btnRepair.setOnClickListener { repairPermissions() }

        // 刷新状态
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun refreshStatus() {
        val suPath = Global.suPath
        if (suPath.isEmpty() || !Global.rootAvailable) {
            binding.statusText.text = getString(R.string.tc4_chroot_root_unavailable)
            binding.btnMount.isEnabled = false
            binding.btnUnmount.isEnabled = false
            return
        }

        binding.statusText.text = getString(R.string.tc4_chroot_root_status,
            getString(R.string.tc4_chroot_root_available))

        CoroutineScope(Dispatchers.IO).launch {
            val isMounted = ChrootManager.isMounted(suPath, containerDir)
            val mountedList = ChrootManager.getMountedList(suPath, containerDir)

            withContext(Dispatchers.Main) {
                binding.mountStatusDetail.text = if (isMounted) {
                    getString(R.string.tc4_chroot_mounted) + " (" + mountedList.size + " mount points)"
                } else {
                    getString(R.string.tc4_chroot_not_mounted)
                }
                binding.btnMount.isEnabled = !isMounted
                binding.btnUnmount.isEnabled = isMounted
            }
        }
    }

    private fun mountAll() {
        binding.btnMount.isEnabled = false
        val suPath = Global.suPath
        CoroutineScope(Dispatchers.IO).launch {
            val success = ChrootManager.mountAll(suPath, containerDir)
            withContext(Dispatchers.Main) {
                if (success) {
                    Snackbar.make(binding.root, R.string.tc4_chroot_mount_success, Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, R.string.tc4_chroot_mount_failed, Snackbar.LENGTH_SHORT).show()
                }
                refreshStatus()
            }
        }
    }

    private fun unmountAll() {
        binding.btnUnmount.isEnabled = false
        val suPath = Global.suPath
        CoroutineScope(Dispatchers.IO).launch {
            ChrootManager.umountAll(suPath, containerDir)
            withContext(Dispatchers.Main) {
                Snackbar.make(binding.root, R.string.tc4_chroot_unmount_success, Snackbar.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    private fun repairPermissions() {
        val suPath = Global.suPath
        CoroutineScope(Dispatchers.IO).launch {
            // 修复容器内常见文件权限
            RootUtils.executeWithSu(suPath, "chown -R 0:0 \"$containerDir/etc\" 2>/dev/null")
            RootUtils.executeWithSu(suPath, "chmod 755 \"$containerDir\" 2>/dev/null")
            RootUtils.executeWithSu(suPath, "chmod 755 \"$containerDir/etc\" 2>/dev/null")
            RootUtils.executeWithSu(suPath, "chmod 644 \"$containerDir/etc/passwd\" \"$containerDir/etc/group\" \"$containerDir/etc/shadow\" 2>/dev/null")
            withContext(Dispatchers.Main) {
                Snackbar.make(binding.root, R.string.tc4_chroot_repair_done, Snackbar.LENGTH_SHORT).show()
            }
        }
    }
}