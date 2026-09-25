/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.settings

import android.app.Application
import com.example.agentrelay.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLocaleTest {
    private val application: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun selectedLanguageIsConsumedByTheComposeResourceContext() {
        val localized = appLocaleContext(application, "de")

        assertEquals(Locale.GERMAN, localized.resources.configuration.locales[0])
        assertEquals("Neue Sitzung starten", localized.getString(R.string.session_creator_title))
    }

    @Test
    fun systemAndUnknownLanguageSafelyUseTheDeviceContext() {
        assertNull(selectedAppLocale(SYSTEM_LANGUAGE_TAG))
        assertNull(selectedAppLocale("not-supported"))

        val systemContext = appLocaleContext(application, SYSTEM_LANGUAGE_TAG)
        assertEquals(application.resources.configuration.locales, systemContext.resources.configuration.locales)
    }

    @Test
    fun supportedLanguageTagsHaveValidLocales() {
        supportedAppLanguages.filter { it.tag != SYSTEM_LANGUAGE_TAG }.forEach { language ->
            assertNotEquals(Locale.ROOT, selectedAppLocale(language.tag))
        }
    }
}
