package com.ovaphlow.crate.log

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Structured JSON logger for the Crate platform.
 *
 * On first use, this auto-configures a daily-rotating JSON Lines file appender
 * at `$LOG_DIR/app.jsonl` (default: `logs/app.jsonl`).  If the Logback context
 * already has an appender named `JSON_FILE` (e.g. from `logback.xml`), the
 * programmatic setup is skipped.
 *
 * Usage:
 * ```kotlin
 * private val log = Log.getLogger("com.example.MyService")
 * log.info("Processing {}", id)
 * ```
 */
object Log {

    @Volatile
    private var initialized = false

    /** Obtain an SLF4J [Logger] by logger name. */
    fun getLogger(name: String): Logger {
        init()
        return LoggerFactory.getLogger(name)
    }

    /** Obtain an SLF4J [Logger] by class. */
    fun getLogger(clazz: Class<*>): Logger {
        init()
        return LoggerFactory.getLogger(clazz)
    }

    // ------------------------------------------------------------------
    // Internal: one-shot programmatic Logback configuration
    // ------------------------------------------------------------------

    private fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val root = ctx.getLogger(Logger.ROOT_LOGGER_NAME)

            if (root.getAppender(APPENDER_NAME) == null) {
                installJsonFileAppender(ctx, root)
            }
            initialized = true
        }
    }

    private fun installJsonFileAppender(ctx: LoggerContext, root: ch.qos.logback.classic.Logger) {
        val logDir = System.getProperty(LOG_DIR_PROP) ?: DEFAULT_LOG_DIR

        val appender = RollingFileAppender<ILoggingEvent>()
        appender.name = APPENDER_NAME
        appender.context = ctx
        appender.file = "$logDir/app.jsonl"

        val encoder = LogstashEncoder()
        encoder.context = ctx
        encoder.start()

        val rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>()
        rollingPolicy.context = ctx
        rollingPolicy.fileNamePattern = "$logDir/app.%d{yyyy-MM-dd}.jsonl"
        rollingPolicy.maxHistory = 30
        rollingPolicy.setParent(appender)
        rollingPolicy.start()

        appender.encoder = encoder
        appender.rollingPolicy = rollingPolicy
        appender.start()

        root.addAppender(appender)
    }

    private const val APPENDER_NAME = "JSON_FILE"
    private const val LOG_DIR_PROP = "LOG_DIR"
    private const val DEFAULT_LOG_DIR = "logs"
}
