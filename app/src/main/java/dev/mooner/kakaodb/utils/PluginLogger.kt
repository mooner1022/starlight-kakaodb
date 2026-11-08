package dev.mooner.kakaodb.utils

import dev.mooner.kakaodb.core.CoreLogger
import dev.mooner.starlight.plugincore.logger.LoggerFactory
import dev.mooner.starlight.plugincore.logger.TLogger

val LOG: TLogger = LoggerFactory.logger("KakaoDB")

/** Hands the core's logs to StarLight's logger. Debug logs go out only when [verbose] says so. */
class PluginLogger(private val verbose: () -> Boolean) : CoreLogger {

    override fun debug(message: () -> String) {
        if (verbose())
            LOG.debug(message)
    }

    override fun info(message: () -> String) =
        LOG.info(message)

    override fun warn(error: Throwable?, message: () -> String) =
        LOG.warn { withCause(message(), error) }

    override fun error(error: Throwable?, message: () -> String) =
        if (error == null) LOG.error(message) else LOG.error(error) { message() }

    private fun withCause(message: String, error: Throwable?): String =
        if (error == null || error.toString() in message) message else "$message ($error)"
}
