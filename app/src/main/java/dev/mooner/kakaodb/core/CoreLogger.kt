package dev.mooner.kakaodb.core

/**
 * Logging for the core package. The core runs in JVM unit tests too, where StarLight's logger doesn't exist, so the
 * plugin hands in an adapter instead of the core using LoggerFactory directly.
 */
interface CoreLogger {

    fun debug(message: () -> String)

    fun info(message: () -> String)

    fun warn(error: Throwable? = null, message: () -> String)

    fun error(error: Throwable? = null, message: () -> String)

    object None : CoreLogger {
        override fun debug(message: () -> String) {}
        override fun info(message: () -> String) {}
        override fun warn(error: Throwable?, message: () -> String) {}
        override fun error(error: Throwable?, message: () -> String) {}
    }
}
