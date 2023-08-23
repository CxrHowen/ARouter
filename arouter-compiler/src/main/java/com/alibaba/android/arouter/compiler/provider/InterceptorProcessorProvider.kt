package com.alibaba.android.arouter.compiler.provider

import com.alibaba.android.arouter.compiler.processor.InterceptorProcessorKt
import com.alibaba.android.arouter.compiler.utils.LoggerWrapper
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class InterceptorProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return InterceptorProcessorKt(
            environment.codeGenerator, LoggerWrapper(environment.logger), environment.options
        )
    }
}