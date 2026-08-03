// RootDetectActivity.kt -- This file is part of tiny_container.
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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fct.tc4.databinding.Tc4ActivityRootDetectBinding
import com.fct.tc4.ui.misc.Global
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动时的 Root 权限检测界面。
 *
 * 进入页面自动开始检测，用户也可手动点击"检测"按钮。
 * 检测成功 → 标记 rootCheckDone，跳转到 MainActivity。
 * 检测失败 → 显示错误信息，提供原项目链接。
 */
class RootDetectActivity : AppCompatActivity() {

    private lateinit var binding: Tc4ActivityRootDetectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = Tc4ActivityRootDetectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 如果已经完成 root 检测，直接跳转到主界面
        if (Global.rootCheckDone) {
            startActivity(Intent(this, com.fct.tc4.ui.main.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootDetect) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.detectButton.setOnClickListener {
            startDetection()
        }

        binding.originalLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Cateners/tiny_container"))
            startActivity(intent)
        }

        // 进入页面自动开始检测
        startDetection()
    }

    private fun startDetection() {
        setDetectingState(true)

        CoroutineScope(Dispatchers.IO).launch {
            val status = RootUtils.checkRootStatus()

            withContext(Dispatchers.Main) {
                setDetectingState(false)

                if (status.rootAvailable) {
                    onRootDetected(status)
                } else {
                    onRootFailed(status)
                }
            }
        }
    }

    private fun setDetectingState(isDetecting: Boolean) {
        binding.progressBar.visibility = if (isDetecting) View.VISIBLE else View.GONE
        binding.detectButton.isEnabled = !isDetecting
        binding.errorText.visibility = View.GONE
        binding.originalLink.visibility = View.GONE
        binding.detectButton.text = if (isDetecting) {
            getString(R.string.tc4_root_detect_checking)
        } else {
            getString(R.string.tc4_root_detect_btn)
        }
        binding.statusText.text = if (isDetecting) {
            getString(R.string.tc4_root_detect_checking)
        } else {
            getString(R.string.tc4_root_detect_ready)
        }
    }

    private fun onRootDetected(status: RootUtils.RootStatus) {
        // 保存 root 信息
        Global.rootAvailable = true
        Global.rootCheckDone = true
        Global.suPath = status.suPath ?: ""

        binding.statusText.text = getString(R.string.tc4_root_detect_success)
        binding.progressBar.visibility = View.GONE

        // 跳转到主界面
        val intent = Intent(this, com.fct.tc4.ui.main.MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun onRootFailed(status: RootUtils.RootStatus) {
        Global.rootAvailable = false
        Global.rootCheckDone = false

        binding.statusText.text = getString(R.string.tc4_root_detect_failed)
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = status.errorMessage ?: getString(R.string.tc4_root_detect_unknown_error)
        binding.originalLink.visibility = View.VISIBLE
        binding.detectButton.text = getString(R.string.tc4_root_detect_retry_btn)
    }
}