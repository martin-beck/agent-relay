/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

internal sealed interface PairingHandoffUiState {
    data class Review(val verified: VerifiedPairingAppLink) : PairingHandoffUiState

    data object Rejected : PairingHandoffUiState
}
