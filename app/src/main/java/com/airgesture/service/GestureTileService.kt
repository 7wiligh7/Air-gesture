/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airgesture.MainActivity
import com.airgesture.R

/**
 * 快捷设置磁贴：下拉状态栏点一下就能彻底停用 AirGesture（关闭无障碍）。
 * 比通知 action 更可靠，HyperOS 也不会折叠。
 */
class GestureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val active = AirGestureService.isGestureMode
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "AirGesture"
            icon = Icon.createWithResource(this@GestureTileService, R.drawable.ic_hand)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (active) "点击彻底关闭" else "点击打开 App"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val svc = AirGestureService.instance
        if (AirGestureService.isGestureMode && svc != null) {
            // 手势开着：一键彻底关闭（停手势 + 关无障碍 + 结束进程）
            svc.disableForGame()
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                updateTile()
            }
        } else {
            // 没开：打开 App，而不是系统设置
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 禁止 TileService 用 Intent 启动 Activity，必须用 PendingIntent
                val pending = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                startActivityAndCollapse(intent)
            } else {
                startActivity(intent)
            }
        }
    }
}