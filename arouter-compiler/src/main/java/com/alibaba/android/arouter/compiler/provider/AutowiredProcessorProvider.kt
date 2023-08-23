package com.alibaba.android.arouter.compiler.provider

import com.alibaba.android.arouter.compiler.processor.AutowiredProcessorKt
import com.alibaba.android.arouter.compiler.utils.LoggerWrapper
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
class AutowiredProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AutowiredProcessorKt(environment.codeGenerator, LoggerWrapper(environment.logger))
    }
}