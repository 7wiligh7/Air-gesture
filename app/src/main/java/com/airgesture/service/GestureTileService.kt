/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.service

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (active) "点击停用无障碍" else "无手势"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val svc = AirGestureService.instance
        if (svc != null) {
            svc.disableForGame()
        } else {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                startActivityAndCollapse(intent)
            } else {
                startActivity(intent)
            }
        }
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }
}