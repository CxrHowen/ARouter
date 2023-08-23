package com.alibaba.android.arouter.compiler.utils

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

class LoggerWrapper(private val logger: KSPLogger) : KSPLogger {

    override fun info(message: String, symbol: KSNode?) {
        if (message.isNotEmpty()) {
            logger.info("${Consts.PREFIX_OF_LOGGER}${message}", symbol)
        }
    }

    override fun error(message: String, symbol: KSNode?) {
        if (message.isNotEmpty()) {
            logger.error("${Consts.PREFIX_OF_LOGGER}${message}", symbol)
        }
    }

    override fun warn(message: String, symbol: KSNode?) {
        if (message.isNotEmpty()) {
            logger.warn("${Consts.PREFIX_OF_LOGGER}${message}", symbol)
        }
    }

    override fun exception(e: Throwable) {
        logger.exception(e)
    }


    override fun logging(message: String, symbol: KSNode?) {
        if (message.isNotEmpty()) {
            logger.warn("${Consts.PREFIX_OF_LOGGER}${message}", symbol)
        }
    }

}