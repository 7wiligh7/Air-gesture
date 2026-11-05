/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 游戏守护：检测到游戏在前台时，AirGesture 主动停用（含关闭无障碍服务），
 * 避免用户在竞技游戏里误用注入手势而触发反作弊/封号。
 *
 * 不是隐藏服务，而是真的退出。
 */
object GameGuard {

    private const val TAG = "GameGuard"

    /** 明确拉黑的竞技游戏（其余靠 ApplicationInfo.category == CATEGORY_GAME 兜底） */
    private val BLOCKED = setOf(
        "com.tencent.tmgp.cod",              // 使命召唤手游 国服
        "com.activision.callofduty.shooter", // 使命召唤手游 国际服
        "com.garena.game.codm"               // 使命召唤手游 Garena
    )

    fun isGame(context: Context, pkg: String): Boolean {
        if (pkg in BLOCKED) {
            Log.d(TAG, "blocked game pkg=$pkg")
            return true
        }
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            val game = ai.category == ApplicationInfo.CATEGORY_GAME
            if (game) Log.d(TAG, "category game pkg=$pkg")
            game
        } catch (_: Exception) {
            false
        }
    }
}