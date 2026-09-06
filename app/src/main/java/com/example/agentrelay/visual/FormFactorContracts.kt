package com.example.agentrelay.visual

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class FormFactorPosture { FLAT, BOOK, TABLETOP, UNKNOWN }

@Serializable
enum class FormFactorWindowMode { FULLSCREEN, SPLIT_SCREEN, FREEFORM }

@Serializable
data class HingeOcclusion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) { "Hinge bounds must be positive" }
    }

    fun intersects(x: Int, y: Int, width: Int, height: Int): Boolean =
        x < right && x + width > left && y < bottom && y + height > top
}

@Serializable
data class FormFactorFixture(
    val id: String,
    val widthDp: Int,
    val heightDp: Int,
    val posture: FormFactorPosture,
    val windowMode: FormFactorWindowMode,
    val rotationDegrees: Int,
    val fontScale: Float = 1f,
    val hinge: HingeOcclusion? = null,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{2,63}"))) { "Fixture id must be stable" }
        require(widthDp >= 240 && heightDp >= 240) { "Fixture bounds are too small" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Rotation must be a right angle" }
        require(fontScale in 1f..2f) { "Font scale must be between 1 and 2" }
        require(hinge == null || (hinge.right <= widthDp && hinge.bottom <= heightDp)) {
            "Hinge must fit inside fixture bounds"
        }
        require(posture == FormFactorPosture.BOOK || posture == FormFactorPosture.TABLETOP || hinge == null) {
            "Only folded postures may declare a hinge"
        }
    }

    val isFolded: Boolean get() = posture == FormFactorPosture.BOOK || posture == FormFactorPosture.TABLETOP

    fun paneWidthDp(): Int = if (hinge == null) widthDp else minOf(hinge.left, widthDp - hinge.right)

    fun acceptsContentBounds(x: Int, y: Int, width: Int, height: Int): Boolean =
        x >= 0 && y >= 0 && x + width <= widthDp && y + height <= heightDp &&
            (hinge == null || !hinge.intersects(x, y, width, height))
}

@Serializable
data class FormFactorCatalog(val schemaVersion: Int, val fixtures: List<FormFactorFixture>) {
    init {
        require(schemaVersion == 1) { "Unsupported form-factor fixture schema" }
        require(fixtures.isNotEmpty()) { "At least one fixture is required" }
        require(fixtures.map(FormFactorFixture::id).toSet().size == fixtures.size) { "Fixture ids must be unique" }
    }

    fun fixture(id: String): FormFactorFixture = fixtures.first { it.id == id }
    fun canonicalJson(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        val current = FormFactorCatalog(
            schemaVersion = 1,
            fixtures = listOf(
                FormFactorFixture("phone-portrait", 360, 800, FormFactorPosture.FLAT, FormFactorWindowMode.FULLSCREEN, 0),
                FormFactorFixture("tablet-landscape", 1280, 800, FormFactorPosture.FLAT, FormFactorWindowMode.FULLSCREEN, 90),
                FormFactorFixture(
                    "foldable-book",
                    1344,
                    800,
                    FormFactorPosture.BOOK,
                    FormFactorWindowMode.FULLSCREEN,
                    90,
                    hinge = HingeOcclusion(664, 0, 680, 800),
                ),
                FormFactorFixture(
                    "foldable-tabletop",
                    800,
                    1344,
                    FormFactorPosture.TABLETOP,
                    FormFactorWindowMode.SPLIT_SCREEN,
                    0,
                    hinge = HingeOcclusion(0, 664, 800, 680),
                ),
            ),
        )
    }
}
