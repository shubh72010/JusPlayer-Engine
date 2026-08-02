package org.jusplayer.engine.utils

object IdGenerator {
    fun generate(): String = java.util.UUID.randomUUID().toString()
}