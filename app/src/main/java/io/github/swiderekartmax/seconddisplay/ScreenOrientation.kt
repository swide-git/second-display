package io.github.swiderekartmax.seconddisplay

enum class ScreenOrientation(val id: String, val label: String) {
    LANDSCAPE("landscape", "Landscape"),
    PORTRAIT("portrait", "Portrait"),
    AUTO("auto", "Auto");

    companion object {
        fun fromId(id: String?): ScreenOrientation =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LANDSCAPE
    }
}
