package org.jusplayer.engine.utils

internal inline fun <T> validateNotNull(value: T?, message: () -> String): T {
    require(value != null) { message() }
    return value
}

internal inline fun validateNotEmpty(value: String, message: () -> String) {
    require(value.isNotBlank()) { message() }
}