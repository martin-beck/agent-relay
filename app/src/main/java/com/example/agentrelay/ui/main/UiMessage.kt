/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

internal sealed interface UiMessage {
    data class Localized(
        @param:StringRes val resourceId: Int,
        val formatArguments: List<Any> = emptyList(),
    ) : UiMessage

    data class Plural(
        @param:PluralsRes val resourceId: Int,
        val quantity: Int,
        val formatArguments: List<Any> = emptyList(),
    ) : UiMessage

    @JvmInline
    value class Verbatim(val value: String) : UiMessage
}

@Suppress("SpreadOperator") // Android string resources expose formatting only through varargs.
@Composable
internal fun UiMessage.resolve(): String = when (this) {
    is UiMessage.Localized -> stringResource(
        resourceId,
        *formatArguments.toTypedArray(),
    )
    is UiMessage.Plural -> pluralStringResource(
        resourceId,
        quantity,
        *formatArguments.toTypedArray(),
    )
    is UiMessage.Verbatim -> value
}
