package com.alibaba.android.arouter.compiler.processor

import com.alibaba.android.arouter.compiler.entity.RouteDoc
import com.alibaba.android.arouter.compiler.entity.RouteMetaKsp
import com.alibaba.android.arouter.compiler.utils.Consts
import com.alibaba.android.arouter.compiler.utils.LoggerWrapper
import com.alibaba.android.arouter.compiler.utils.findAnnotationWithType
import com.alibaba.android.arouter.compiler.utils.isSubclassOf
import com.alibaba.android.arouter.compiler.utils.quantifyNameToClassName
import com.alibaba.android.arouter.compiler.utils.routeType
import com.alibaba.android.arouter.compiler.utils.typeExchange
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.enums.RouteType
import com.alibaba.android.arouter.facade.model.RouteMeta
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import java.util.TreeSet

/**
 * A processor used for find route.
 */
class RouteProcessorKt(
    private val codeGenerator: CodeGenerator,
    private val logger: LoggerWrapper,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val groupMap = hashMapOf<String, TreeSet<RouteMeta>>()// ModuleName and routeMeta.

    private val rootMap =
        hashMapOf<String, String>()// Map of root metas, used for generate class file in order.

    private val generateDoc = Consts.VALUE_ENABLE == options[Consts.KEY_GENERATE_DOC_NAME]
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info(">>> RouteProcessor init. <<<")
        val symbols = resolver.getSymbolsWithAnnotation(Route::class.qualifiedName!!)
        val ret = symbols.filter { !it.validate() }.toList()

        runCatching {
            logger.info(">>> Found routes, start... <<<")
            parseRoutes(symbols)
        }.onFailure {
            logger.error(it.message.orEmpty())
        }
        return ret
    }

    private fun parseRoutes(symbols: Sequence<KSAnnotated>) {
        val routeElements = symbols.filterIsInstance<KSClassDeclaration>().toList()
        if (routeElements.isNotEmpty()) {

            // prepare the type an so on.
            logger.info(">>> Found routes, size is ${routeElements.size} <<<")

            rootMap.clear()

            // Interface of ARouter
            val type_IRouteGroup = Consts.IROUTE_GROUP.quantifyNameToClassName()
            val type_IProviderGroup = Consts.IPROVIDER_GROUP.quantifyNameToClassName()
            val routeMetaCn = RouteMeta::class.asClassName()
            val routeTypeCn = RouteType::class.asClassName()

            /*
               Build input type, format as :

               ```Map<String, Class<? extends IRouteGroup>>```
             */
            val inputMapTypeOfRoot = MAP.parameterizedBy(
                STRING,
                Class::class.asClassName()
                    .parameterizedBy(WildcardTypeName.producerOf(type_IRouteGroup))
            )

            /*

            ```Map<String, RouteMeta>```
           */
            val inputMapTypeOfGroup = MAP.parameterizedBy(STRING, routeMetaCn)

            /*
             Build input param name.
            */
            val rootParamSpec = ParameterSpec.builder("routes", inputMapTypeOfRoot).build()
            val groupParamSpec = ParameterSpec.builder("atlas", inputMapTypeOfGroup).build()
            val providerParamSpec = ParameterSpec.builder("providers", inputMapTypeOfGroup).build()

            /*
             Build method : 'loadInto'
            */
            val loadIntoMethodOfRootBuilder =
                FunSpec.builder(Consts.METHOD_LOAD_INTO).addAnnotation(Override::class)
                    .addModifiers(KModifier.PUBLIC).addParameter(rootParamSpec)

            //  Follow a sequence, find out metas of group first, generate java file, then statistics them as root.
            routeElements.forEach { element ->
                val route = element.findAnnotationWithType<Route>()!!
                val routeMeta: RouteMeta

                // Activity or Fragment
                if (element.isSubclassOf(Consts.ACTIVITY_ANDROIDX) || element.isSubclassOf(Consts.FRAGMENT) || element.isSubclassOf(
                        Consts.FRAGMENT_V4
                    )
                ) {
                    // Get all fields annotation by @Autowired
                    val paramsType = hashMapOf<String, Int>()
                    val injectConfig = hashMapOf<String, Autowired>()
                    injectParamCollector(element, paramsType, injectConfig)

                    if (element.isSubclassOf(Consts.ACTIVITY_ANDROIDX)) {
                        // Activity
                        logger.info(">>> Found activity route: ${element.qualifiedName?.asString()} <<<")
                    } else {
                        // Fragment
                        logger.info(">>> Found fragment route: ${element.qualifiedName?.asString()} <<<")
                    }
                    routeMeta = RouteMetaKsp.build(route, element, element.routeType, paramsType)
                    routeMeta.injectConfig = injectConfig
                } else if (element.isSubclassOf(Consts.IPROVIDER)) {
                    // IProvider
                    logger.info(">>> Found provider route: ${element.qualifiedName?.asString()} <<<")
                    routeMeta = RouteMetaKsp.build(route, element, element.routeType, null)
                } else if (element.isSubclassOf(Consts.SERVICE)) {
                    // Service
                    logger.info(">>> Found service route: ${element.qualifiedName?.asString()} <<<")
                    routeMeta = RouteMetaKsp.build(route, element, element.routeType, null)
                } else {
                    throw RuntimeException(
                        "The @Route is marked on unsupported class, look at [${element.qualifiedName?.asString()}]."
                    )
                }

                categories(routeMeta)
            }

            val loadIntoMethodOfProviderBuilder =
                FunSpec.builder(Consts.METHOD_LOAD_INTO).addAnnotation(Override::class)
                    .addModifiers(KModifier.PUBLIC).addParameter(providerParamSpec)

            val docSource = hashMapOf<String, List<RouteDoc>>()

            // Start generate java source, structure is divided into upper and lower levels, used for demand initialization.
            groupMap.forEach { (groupName, groupData) ->

                val loadIntoMethodOfGroupBuilder =
                    FunSpec.builder(Consts.METHOD_LOAD_INTO).addAnnotation(Override::class)
                        .addModifiers(KModifier.PUBLIC).addParameter(groupParamSpec)

                val routeDocList = mutableListOf<RouteDoc>()

                //Build group method body
                groupData.forEach { routeMeta ->
                    val routeDoc = extractDocInfo(routeMeta)

                    val className = (routeMeta as? RouteMetaKsp)?.kspRawTypeInner?.toClassName()

                    when (routeMeta.type) {
                        RouteType.PROVIDER -> {// Need cache provider's super class
                            (routeMeta as? RouteMetaKsp)?.kspRawTypeInner
                        }

                        else -> {

                        }
                    }
                }
            }
        }
    }

    /**
     * Extra doc info from route meta
     *
     * @param routeMeta meta
     * @return doc
     */
    private fun extractDocInfo(routeMeta: RouteMeta): RouteDoc {
        val routeDoc = RouteDoc()
        routeDoc.group = routeMeta.group
        routeDoc.path = routeMeta.path
        routeDoc.description = routeMeta.name
        routeDoc.type = routeMeta.type.name.lowercase()
        routeDoc.mark = routeMeta.extra

        return routeDoc
    }

    /**
     * Sort metas in group.
     *
     * @param routeMeta metas.
     */
    private fun categories(routeMeta: RouteMeta?) {
        if (routeVerify(routeMeta)) {
            logger.info(">>> Start categories, group = ${routeMeta?.group}, path = ${routeMeta?.path} <<<")
            val routeMetas = groupMap[routeMeta!!.group]
            if (routeMetas.isNullOrEmpty()) {
                val routeMetaSet = sortedSetOf<RouteMeta>({ r1, r2 ->
                    try {
                        r1!!.path.compareTo(r2!!.path)
                    } catch (npe: NullPointerException) {
                        logger.error(npe.message.toString())
                        0
                    }
                })
                routeMetaSet.add(routeMeta)
                groupMap[routeMeta.group!!] = routeMetaSet
            } else {
                routeMetas.add(routeMeta)
            }
        } else {
            logger.warn(">>> Route meta verify error, group is ${routeMeta?.group} <<<")
        }
    }

    /**
     * Verify the route meta
     *
     * @param meta raw meta
     */
    private fun routeVerify(meta: RouteMeta?): Boolean {
        val path = meta?.path

        if (meta == null || path.isNullOrEmpty() || !path.startsWith("/")) {// The path must be start with '/' and not empty!
            return false
        }

        if (meta.group.isNullOrEmpty()) {// Use default group(the first word in path)
            try {
                val defaultGroup = path.substring(1, path.indexOf("/", 1))
                if (defaultGroup.isEmpty()) return false

                meta.group = defaultGroup
                return true
            } catch (e: Exception) {
                logger.error("Failed to extract default group! ${e.message}")
                return false
            }
        }
        return true
    }

    private fun injectParamCollector(
        element: KSClassDeclaration,
        paramsType: HashMap<String, Int>,
        injectConfig: HashMap<String, Autowired>
    ) {
        element.getAllProperties().forEach { field ->
            val paramConfig = field.findAnnotationWithType<Autowired>()
            if (paramConfig != null && !field.isSubclassOf(Consts.IPROVIDER)) {
                val injectName =
                    if (paramConfig.name.isNotEmpty()) field.simpleName.asString() else paramConfig.name
                paramsType[injectName] = field.typeExchange()
                injectConfig[injectName] = paramConfig
            }
        }

        // if has parent?
//        val parent = element.superTypes.firstOrNull()?.declaration as? KSClassDeclaration
    }
}