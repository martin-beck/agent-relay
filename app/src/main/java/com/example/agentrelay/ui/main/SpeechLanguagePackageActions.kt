/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

internal data class SpeechLanguagePackageActions(
    val install: (String) -> Unit,
    val cancel: (String) -> Unit,
    val remove: (String) -> Unit,
    val setAllowMetered: (Boolean) -> Unit,
)
