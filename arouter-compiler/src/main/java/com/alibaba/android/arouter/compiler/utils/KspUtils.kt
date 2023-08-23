package com.alibaba.android.arouter.compiler.utils

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.tags.TypeAliasTag

@OptIn(KspExperimental::class)
@Suppress("unused")
internal fun KSClassDeclaration.isKotlinClass(): Boolean {
    return origin == Origin.KOTLIN || origin == Origin.KOTLIN_LIB || isAnnotationPresent(Metadata::class)
}

@OptIn(KspExperimental::class)
internal inline fun <reified T : Annotation> KSAnnotated.findAnnotationWithType(): T? {
    return getAnnotationsByType(T::class).firstOrNull()
}

/**
 * Judge whether a class [KSClassDeclaration] is a subclass of another class [superClassName]
 * https://www.raywenderlich.com/33148161-write-a-symbol-processor-with-kotlin-symbol-processing
 * */
internal fun KSClassDeclaration.isSubclassOf(superClassName: String): Boolean {
    val superClasses = superTypes.toMutableList()
    while (superClasses.isNotEmpty()) {
        val current: KSTypeReference = superClasses.first()
        val declaration: KSDeclaration = current.resolve().declaration
        when {
            declaration is KSClassDeclaration && declaration.qualifiedName?.asString() == superClassName -> {
                return true
            }

            declaration is KSClassDeclaration -> {
                superClasses.removeAt(0)
                superClasses.addAll(0, declaration.superTypes.toList())
            }

            else -> {
                superClasses.removeAt(0)
            }
        }
    }
    return false
}

/**
 * 判断当前类是否继承自给定的某个父类或者父接口
 * @param superClassNames 给定的父类或父接口的全限定名列表
 * @return 若继承自某个给定的父类或父接口，则返回其在superClassNames列表中的索引（从0开始）；否则返回-1
 */
internal fun KSClassDeclaration.isSubclassOf(superClassNames: List<String>): Int {
    val superClasses = superTypes.toMutableList()// 获取当前类的所有父类型，并将其转化为可变列表
    while (superClasses.isNotEmpty()) {
        val current: KSTypeReference = superClasses.first()// 取出第一个父类型
        val declaration: KSDeclaration = current.resolve().declaration// 解析该父类型，获取其对应的声明对象
        when {
            // 如果该父类型对应的声明是一个类声明，并且其全限定名在superClassNames列表中，则说明当前类继承自该父类，直接返回其索引
            declaration is KSClassDeclaration && (superClassNames.indexOf(declaration.qualifiedName?.asString())) != -1 -> {
                return superClassNames.indexOf(declaration.qualifiedName?.asString())
            }

            // 如果该父类型对应的声明是一个类声明，但其全限定名不在superClassNames列表中，则将其父类型加入待检查列表中
            declaration is KSClassDeclaration -> {
                superClasses.removeAt(0)
                superClasses.addAll(0, declaration.superTypes.toList())
            }

            // 如果该父类型对应的声明既不是类声明，也不是接口声明，则直接将其移出待检查列表
            else -> {
                superClasses.removeAt(0)
            }
        }
    }
    return -1
}

internal fun KSPropertyDeclaration.isSubclassOf(superClassName: String): Boolean {
    val propertyType = type.resolve().declaration
    return if (propertyType is KSClassDeclaration) {
        propertyType.isSubclassOf(superClassName)
    } else {
        false
    }
}

internal fun KSPropertyDeclaration.isSubclassOf(superClassNames: List<String>): Int {
    val propertyType = type.resolve().declaration
    return if (propertyType is KSClassDeclaration) {
        propertyType.isSubclassOf(superClassNames)
    } else {
        -1
    }
}

internal fun String.quantifyNameToClassName(): ClassName {
    val index = lastIndexOf(".")
    return ClassName(substring(0, index), substring(index + 1, length))
}

/**
 *  such: val map = Map<String, String> ==> Map<String, String> (used for kotlinpoet for %T)
 *  考虑类型别名
 * */
internal fun KSPropertyDeclaration.getKotlinPoetTTypeGeneric(): TypeName {
    val classTypeParams = this.typeParameters.toTypeParameterResolver()
    val typeName = this.type.toTypeName(classTypeParams)
    // Fix: typealias-handling
    // https://square.github.io/kotlinpoet/interop-ksp/#typealias-handling
    // Alias class -> such as: var a = arrayList<String>() -> ArrayList<String>
    typeName.tags[TypeAliasTag::class]?.let {
        val typeAliasTag = (it as? TypeAliasTag)?.abbreviatedType
        if (typeAliasTag != null) {
            return typeAliasTag
        }
    }
    return typeName
}

/**
 *  such: val map = Map<String, String> ==> Map (used for kotlinpoet for %T)
 *  不考虑类型别名
 * */
@Suppress("unused")
internal fun KSPropertyDeclaration.getKotlinPoetTType(): TypeName {
    return this.type.resolve().toTypeName()
}