package com.alibaba.android.arouter.compiler.processor

import com.alibaba.android.arouter.compiler.utils.Consts
import com.alibaba.android.arouter.compiler.utils.Consts.TYPE_WRAPPER
import com.alibaba.android.arouter.compiler.utils.LoggerWrapper
import com.alibaba.android.arouter.compiler.utils.findAnnotationWithType
import com.alibaba.android.arouter.compiler.utils.getKotlinPoetTTypeGeneric
import com.alibaba.android.arouter.compiler.utils.isPrimitive
import com.alibaba.android.arouter.compiler.utils.isSubclassOf
import com.alibaba.android.arouter.compiler.utils.quantifyNameToClassName
import com.alibaba.android.arouter.compiler.utils.routeType
import com.alibaba.android.arouter.compiler.utils.typeExchange
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.enums.RouteType
import com.alibaba.android.arouter.facade.enums.TypeKind
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Processor used to create autowired helper
 */
class AutowiredProcessorKt(
    private val codeGenerator: CodeGenerator, private val logger: LoggerWrapper
) : SymbolProcessor {
    private val parentAndChild =
        mutableMapOf<KSClassDeclaration, MutableList<KSPropertyDeclaration>>()

    private val ARouterClass = ClassName("com.alibaba.android.arouter", "ARouter")

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info(">>> AutowiredProcessor init. <<<")
        val symbols = resolver.getSymbolsWithAnnotation(Autowired::class.qualifiedName!!)
        val ret = symbols.filter { !it.validate() }.toList()

        runCatching {
            logger.info(">>> Found autowired field, start... <<<")
            categories(symbols)
            generateHelper()
        }.onFailure {
            logger.error(it.message.orEmpty())
        }
        return ret
    }

    private fun generateHelper() {
        val type_ISyringe = Consts.ISYRINGE.quantifyNameToClassName()
        val type_JsonService = Consts.JSON_SERVICE.quantifyNameToClassName()

        // Build input param name.
        val objectParamSpec = ParameterSpec.builder("target", ANY).build()

        if (parentAndChild.isNotEmpty()) {
            parentAndChild.forEach { (parent, childs) ->
                // Build method : 'inject'
                val injectMethodBuilder =
                    FunSpec.builder(Consts.METHOD_INJECT).addModifiers(KModifier.OVERRIDE)
                        .addModifiers(KModifier.PUBLIC).addParameter(objectParamSpec)

                val qualifiedName = parent.qualifiedName?.asString()
                val packageName = qualifiedName?.substring(0, qualifiedName.lastIndexOf("."))
                val fileName = "${parent.simpleName}${Consts.NAME_OF_AUTOWIRED}"

                logger.info(">>> Start process ${childs.size} field in ${parent.simpleName} ... <<<")

                val helper = TypeSpec.classBuilder(fileName).addKdoc(Consts.WARNING_TIPS)
                    .addSuperinterface(type_ISyringe).addModifiers(KModifier.PUBLIC)

                val jsonServiceField = PropertySpec.builder(
                    "serializationService", type_JsonService, KModifier.PRIVATE
                ).build()
                helper.addProperty(jsonServiceField)

                injectMethodBuilder.addStatement(
                    "serializationService = ARouter.getInstance().navigation(%T::class.java)",
                    ARouterClass,
                    type_JsonService
                )
                injectMethodBuilder.addStatement(
                    "%T substitute = (%T)target", parent.toClassName(), parent.simpleName
                )

                //Generate method body, start inject.
                childs.forEach { element ->
                    val fieldConfig = element.findAnnotationWithType<Autowired>()
                    val fieldName = element.simpleName.asString()
                    // It's provider
                    if (element.isSubclassOf(Consts.IPROVIDER)) {
                        val propertyType = element.getKotlinPoetTTypeGeneric()
                        // User has not set service path, then use byType.
                        // Getter
                        if (fieldConfig?.name.isNullOrEmpty()) {
                            injectMethodBuilder.addStatement(
                                "substitute.${fieldName} = %T.getInstance().navigation(%T.class)",
                                ARouterClass,
                                propertyType
                            )
                        } else {
                            // use byName
                            // Getter
                            injectMethodBuilder.addStatement(
                                "substitute.${fieldName} = (%T)%T.getInstance().build(%S).navigation()",
                                propertyType,
                                ARouterClass,
                                fieldConfig?.name.orEmpty()
                            )
                        }

                        // Validator
                        if (fieldConfig?.required == true) {
                            injectMethodBuilder.beginControlFlow("if (substitute.${fieldName} == null)")
                            injectMethodBuilder.addStatement(
                                "throw RuntimeException(\"The field '$fieldName' is null, in class '%L' !\")",
                                parent.simpleName
                            )
                            injectMethodBuilder.endControlFlow()
                        }
                    } else {
                        // It's normal intent value
                        val isActivity = when (parent.routeType) {
                            RouteType.ACTIVITY -> true
                            RouteType.FRAGMENT -> false
                            else -> throw IllegalAccessException("The field [$fieldName] need autowired from intent, its parent must be activity or fragment!")
                        }

                        val originalValue = "substitute.${fieldName}"
                        var statement =
                            "substitute.${fieldName} = substitute.${if (isActivity) "intent?.extras" else "arguments"}?."

                        statement = buildStatement(
                            originalValue,
                            statement,
                            TypeKind.values()[element.typeExchange()],
                            isActivity
                        )
                        if (statement.startsWith("serializationService.")) {   // Not mortals
                            // such as: val param = List<JailedBird> ==> %T ==> List<JailedBird>
                            val parameterClassName = element.getKotlinPoetTTypeGeneric()
                            injectMethodBuilder.beginControlFlow("if (null != serializationService)")
                            injectMethodBuilder.addStatement(
                                "substitute.$fieldName = $statement",
                                parameterClassName,
                                if (fieldConfig?.name.isNullOrEmpty()) fieldName else fieldConfig!!.name,
                                parameterClassName
                            )
                            injectMethodBuilder.nextControlFlow("else")
                            injectMethodBuilder.addStatement(
                                "Log.e(\"" + Consts.TAG + "\", \"You want automatic inject the field '" + fieldName + "' in class '%T' , then you should implement 'SerializationService' to support object auto inject!\")",
                                parent.toClassName()
                            )
                            injectMethodBuilder.endControlFlow()
                        } else if (statement.contains("getSerializable")) {
                            // such as: val param = List<JailedBird> ==> %T ==> List<JailedBird>
                            val parameterClassName = element.getKotlinPoetTTypeGeneric()
                            injectMethodBuilder.addStatement(
                                statement,
                                if (fieldConfig?.name.isNullOrEmpty()) fieldName else fieldConfig!!.name,
                                parameterClassName
                            )
                        } else if (statement.contains("getParcelable")) {
                            // such as: val param = List<JailedBird> ==> %T ==> List<JailedBird>
                            val parameterClassName = element.getKotlinPoetTTypeGeneric()
                            injectMethodBuilder.addStatement(
                                statement,
                                parameterClassName,
                                if (fieldConfig?.name.isNullOrEmpty()) fieldName else fieldConfig!!.name
                            )
                        } else {
                            injectMethodBuilder.addStatement(
                                statement,
                                if (fieldConfig?.name.isNullOrEmpty()) fieldName else fieldConfig!!.name
                            )
                        }

                        //Validator
                        if (fieldConfig?.required == true && !element.isPrimitive()) {// Primitive wont be check.
                            injectMethodBuilder.beginControlFlow("if (substitute.${fieldName} == null)")
                            injectMethodBuilder.addStatement(
                                "Log.e(\"${Consts.TAG}\",\"The field '$fieldName' is null, in class '%L' !\")",
                                parent.simpleName
                            )
                            injectMethodBuilder.endControlFlow()
                        }
                    }
                }

                helper.addFunction(injectMethodBuilder.build())

                // Generate autowired helper
                val file = codeGenerator.createNewFile(Dependencies(false), packageName!!, fileName)
                val fileSpec =
                    FileSpec.builder(packageName, fileName).addType(helper.build()).build()
                file.bufferedWriter().use {
                    fileSpec.writeTo(it)
                }

                logger.info(">>> ${parent.simpleName} has been processed, $fileName has been generated. <<<")
            }

            logger.info(">>> Autowired processor stop. <<<")
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    private fun buildStatement(
        originalValue: String, statement: String, typeKind: TypeKind, isActivity: Boolean
    ): String {
        when (typeKind) {
            TypeKind.BOOLEAN -> "${statement}getBoolean(%S,$originalValue)"
            TypeKind.BYTE -> "${statement}getByte(%S,$originalValue)"
            TypeKind.SHORT -> "${statement}getShort(%S,$originalValue)"
            TypeKind.INT -> "${statement}getInt(%S,$originalValue)"
            TypeKind.LONG -> "${statement}getLong(%S,$originalValue)"
            TypeKind.CHAR -> "${statement}getChar(%S,$originalValue)"
            TypeKind.FLOAT -> "${statement}getFloat(%S,$originalValue)"
            TypeKind.DOUBLE -> "${statement}getDouble(%S,$originalValue)"
            TypeKind.STRING -> "${statement}getString(%S,$originalValue)"
            TypeKind.SERIALIZABLE -> "${statement}getSerializable(%S) as? %T"
            TypeKind.PARCELABLE -> "${statement}getParcelable<%T>(%S)"
            TypeKind.OBJECT -> "serializationService?.parseObject<%T>(${if (isActivity) "intent?.extras" else "arguments"}?.getString(%S), (object : $TYPE_WRAPPER<%T>(){}).type)"
        }
        return statement
    }

    private fun categories(symbols: Sequence<KSAnnotated>) {
        val elements = symbols.filterIsInstance<KSPropertyDeclaration>().toList()
        if (elements.isNotEmpty()) {
            elements.forEach { element ->
                val ksClassDeclaration = element.parentDeclaration as KSClassDeclaration

                if (element.modifiers.contains(Modifier.PRIVATE)) {
                    throw IllegalArgumentException("Autowired field can't be private! please check field [${element.simpleName.asString()}] in class [${element.qualifiedName?.asString()}]")
                }

                if (parentAndChild.contains(ksClassDeclaration)) {
                    parentAndChild[ksClassDeclaration]?.add(element)
                } else {
                    parentAndChild[ksClassDeclaration] = mutableListOf(element)
                }

            }

            logger.info("categories finished.")
        }
    }

    override fun finish() {
        super.finish()
    }

    override fun onError() {
        super.onError()
    }


}