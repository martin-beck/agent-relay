/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

/** The root destination stays available when Android asks the shell to navigate back. */
internal fun canPopAppShell(backStackSize: Int): Boolean = backStackSize > 1
