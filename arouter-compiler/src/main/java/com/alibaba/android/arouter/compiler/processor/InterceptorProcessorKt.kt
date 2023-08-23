package com.alibaba.android.arouter.compiler.processor

import com.alibaba.android.arouter.compiler.utils.Consts
import com.alibaba.android.arouter.compiler.utils.LoggerWrapper
import com.alibaba.android.arouter.compiler.utils.findAnnotationWithType
import com.alibaba.android.arouter.compiler.utils.findModuleName
import com.alibaba.android.arouter.compiler.utils.isSubclassOf
import com.alibaba.android.arouter.compiler.utils.quantifyNameToClassName
import com.alibaba.android.arouter.facade.annotation.Interceptor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import java.util.TreeMap

/**
 * Process the annotation of #{@link Interceptor}
 */
class InterceptorProcessorKt(
    private val codeGenerator: CodeGenerator,
    private val logger: LoggerWrapper,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val interceptors = TreeMap<Int, KSClassDeclaration>()
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info(">>> InterceptorProcessor init. <<<")

        val symbols = resolver.getSymbolsWithAnnotation(Interceptor::class.qualifiedName!!)
        val ret = symbols.filter { !it.validate() }.toList()

        runCatching {
            parseInterceptors(symbols)
        }.onFailure {
            logger.error(it.message.orEmpty())
        }
        return ret
    }

    private fun parseInterceptors(symbols: Sequence<KSAnnotated>) {
        val elements = symbols.filterIsInstance<KSClassDeclaration>().toList()
        if (elements.isNotEmpty()) {
            logger.info(">>> Found interceptors, size is ${elements.size} <<<")

            // Verify and cache, sort incidentally.
            elements.forEach { element ->
                if (verify(element)) {// Check the interceptor meta
                    // Check the interceptor meta
                    logger.info("A interceptor verify over, its ${element.qualifiedName}")
                    val interceptor = element.findAnnotationWithType<Interceptor>()!!

                    interceptors[interceptor.priority]?.let { lastInterceptor ->
                        throw IllegalArgumentException("More than one interceptors use same priority [${interceptor.priority}] : ${lastInterceptor.simpleName} & ${element.simpleName}")
                    }
                    interceptors[interceptor.priority] = element
                } else {
                    logger.error("A interceptor verify failed, its ${element.qualifiedName}")
                }
            }

            val type_IInterceptor = Consts.IINTERCEPTOR.quantifyNameToClassName()
            val type_IInterceptorGroup = Consts.IINTERCEPTOR_GROUP.quantifyNameToClassName()

            /**
             *  Build input type, format as :
             *  ```Map<Integer, Class<? extends IInterceptor>>```
             */
            val inputMapTypeOfTollgate = MAP.parameterizedBy(
                INT,
                Class::class.asClassName()
                    .parameterizedBy(WildcardTypeName.producerOf(type_IInterceptor))
            )

            // Build input param name.
            val tollgateParamSpec =
                ParameterSpec.builder("interceptors", inputMapTypeOfTollgate).build()

            // Build method : 'loadInto'
            val loadIntoMethodOfTollgateBuilder =
                FunSpec.builder(Consts.METHOD_LOAD_INTO).addAnnotation(Override::class)
                    .addModifiers(KModifier.PUBLIC).addParameter(tollgateParamSpec)

            // Generate
            if (interceptors.isNotEmpty()) {
                interceptors.forEach { entry ->
                    val priority: Int = entry.key
                    val interceptor: KSClassDeclaration = entry.value

                    loadIntoMethodOfTollgateBuilder.addStatement(
                        "interceptors.put(%L, %T::class.java)", priority, interceptor
                    )
                }
            }

            // Write to disk(Write file even interceptors is empty.
            val moduleName = options.findModuleName(logger)
            val interceptorClassName = Consts.NAME_OF_INTERCEPTOR + Consts.SEPARATOR + moduleName
            val file = codeGenerator.createNewFile(
                Dependencies(false), Consts.PACKAGE_OF_GENERATE_FILE, interceptorClassName
            )
            val fileSpec =
                FileSpec.builder(Consts.PACKAGE_OF_GENERATE_FILE, interceptorClassName).addType(
                    TypeSpec.classBuilder(
                        ClassName(Consts.PACKAGE_OF_GENERATE_FILE, interceptorClassName)
                    ).addModifiers(KModifier.PUBLIC).addKdoc(Consts.WARNING_TIPS)
                        .addFunction(loadIntoMethodOfTollgateBuilder.build())
                        .addSuperinterface(type_IInterceptorGroup).build()
                ).build()
            file.bufferedWriter().use {
                fileSpec.writeTo(it)
            }
            logger.info(">>> Interceptor group write over. <<<")
        }
    }

    /**
     * Verify inteceptor meta
     *
     * @param element Interceptor taw type
     * @return verify result
     */
    private fun verify(element: KSClassDeclaration): Boolean {
        val interceptor = element.findAnnotationWithType<Interceptor>()
        return null != interceptor && element.isSubclassOf(Consts.IINTERCEPTOR)
    }

}