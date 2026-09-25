/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Returns the selected locale, or null when the device/System locale should be used. */
internal fun selectedAppLocale(languageTag: String): Locale? =
    supportedAppLanguages
        .firstOrNull { it.tag == languageTag }
        ?.tag
        ?.takeUnless { it == SYSTEM_LANGUAGE_TAG }
        ?.let(Locale::forLanguageTag)
        ?.takeIf { it != Locale.ROOT }

/** Creates the resource context consumed by Compose for the persisted app language. */
internal fun appLocaleContext(baseContext: Context, languageTag: String): Context {
    val configuration = Configuration(baseContext.resources.configuration)
    selectedAppLocale(languageTag)?.let(configuration::setLocale)
        ?: configuration.setLocales(baseContext.resources.configuration.locales)
    return baseContext.createConfigurationContext(configuration)
}
