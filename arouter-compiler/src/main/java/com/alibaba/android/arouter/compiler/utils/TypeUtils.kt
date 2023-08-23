package com.alibaba.android.arouter.compiler.utils

import com.alibaba.android.arouter.facade.enums.RouteType
import com.alibaba.android.arouter.facade.enums.TypeKind
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

/**
 * AutoWire Inject Field Type check and convert
 * */
internal fun KSPropertyDeclaration.typeExchange(): Int {
    val type = this.type.resolve()
    @Suppress("KotlinConstantConditions") return when (type.declaration.qualifiedName?.asString()) {
        Consts.KBYTE -> TypeKind.BYTE.ordinal
        Consts.KSHORT -> TypeKind.SHORT.ordinal
        Consts.KINTEGER -> TypeKind.INT.ordinal
        Consts.KLONG -> TypeKind.LONG.ordinal
        Consts.KFLOAT -> TypeKind.FLOAT.ordinal
        Consts.KDOUBEL -> TypeKind.DOUBLE.ordinal
        Consts.KBOOLEAN -> TypeKind.BOOLEAN.ordinal
        Consts.KCHAR -> TypeKind.CHAR.ordinal
        Consts.KSTRING -> TypeKind.STRING.ordinal
        else -> {
            when (this.isSubclassOf(listOf(Consts.PARCELABLE, Consts.SERIALIZABLE))) {
                0 -> TypeKind.PARCELABLE.ordinal
                1 -> TypeKind.SERIALIZABLE.ordinal
                else -> TypeKind.OBJECT.ordinal
            }
        }
    }
}

private val ROUTE_TYPE_LIST = listOf(
    Consts.ACTIVITY,// 0
    Consts.ACTIVITY_ANDROIDX, // 1
    Consts.FRAGMENT, // 2
    Consts.FRAGMENT_V4, // 3
    Consts.SERVICE, // 5
    Consts.IPROVIDER // 6
)

internal val KSClassDeclaration.routeType: RouteType
    get() = when (isSubclassOf(ROUTE_TYPE_LIST)) {
        0, 1 -> RouteType.ACTIVITY
        2, 3 -> RouteType.FRAGMENT
        5 -> RouteType.SERVICE
        6 -> RouteType.PROVIDER
        else -> RouteType.UNKNOWN
    }

internal fun KSPropertyDeclaration.isPrimitive(): Boolean {
    val type = this.type.resolve()
    @Suppress("KotlinConstantConditions") return when (type.declaration.qualifiedName?.asString()) {
        Consts.KBYTE, Consts.KSHORT, Consts.KINTEGER, Consts.KLONG, Consts.KFLOAT, Consts.KDOUBEL, Consts.KBOOLEAN, Consts.KCHAR, Consts.KSTRING -> true

        else -> false
    }
}

/**
 *  Find module name from ksp arguments, please add this config
 *  " ksp { arg("AROUTER_MODULE_NAME", project.getName()) } "
 *  in your module's build.gradle
 * */
@Suppress("SpellCheckingInspection")
internal fun Map<String, String>.findModuleName(logger: KSPLogger): String {
    val name = this[Consts.KEY_MODULE_NAME]
    return if (!name.isNullOrEmpty()) {
        val moduleName = name.replace("[^0-9a-zA-Z_]+".toRegex(), "")
        logger.info("The user has configuration the module name, it was [$moduleName]")
        moduleName
    } else {
        logger.error(Consts.NO_MODULE_NAME_TIPS_KSP)
        throw RuntimeException("ARouter::Compiler >>> No module name, for more information, look at gradle log.")
    }
}
