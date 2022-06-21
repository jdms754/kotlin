/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.ir.isInlineClassType
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.codegen.state.InfoForMangling
import org.jetbrains.kotlin.codegen.state.collectFunctionSignatureForManglingSuffix
import org.jetbrains.kotlin.codegen.state.md5base64
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name

/**
 * Replace inline classes by their underlying types.
 */
fun IrType.unboxInlineClass(context: JvmBackendContext) = InlineClassAbi.unboxType(this, context) ?: this

object InlineClassAbi {
    /**
     * An origin for IrFunctionReferences which prevents inline class mangling. This only exists because of
     * inconsistencies between `RuntimeTypeMapper` and `KotlinTypeMapper`. The `RuntimeTypeMapper` does not
     * perform inline class mangling and so in the absence of jvm signatures in the metadata we need to avoid
     * inline class mangling as well in the function references used as arguments to the signature string intrinsic.
     */
    object UNMANGLED_FUNCTION_REFERENCE : IrStatementOriginImpl("UNMANGLED_FUNCTION_REFERENCE")

    /**
     * Unwraps inline class types to their underlying representation.
     * Returns null if the type cannot be unboxed.
     */
    fun unboxType(type: IrType, context: JvmBackendContext): IrType? {
        if (!type.isInlineClassType()) return null

        val unsubstitutedUnderlyingType = with(context.typeSystem) {
            type.getUnsubstitutedUnderlyingType() as? IrType
        } ?: return null

        val substitutedUnderlyingType = with(context.typeSystem) {
            type.getSubstitutedUnderlyingType() as? IrType
        } ?: return null

        if (!type.isNullable()) {
            return if (unsubstitutedUnderlyingType.isNullable() && substitutedUnderlyingType.isInlineClassType()) {
                unsubstitutedUnderlyingType
            } else {
                unboxType(substitutedUnderlyingType, context) ?: substitutedUnderlyingType
            }
        }

        if (substitutedUnderlyingType.isNullable() ||
            substitutedUnderlyingType.isPrimitiveType()
        ) return null

        return substitutedUnderlyingType.makeNullable()
    }

    /**
     * Returns a mangled name for a function taking inline class arguments
     * to avoid clashes between overloaded methods.
     */
    fun mangledNameFor(irFunction: IrFunction, mangleReturnTypes: Boolean, useOldMangleRules: Boolean): Name {
        if (irFunction is IrConstructor) {
            // Note that we might drop this convention and use standard mangling for constructors too, see KT-37186.
            assert(irFunction.constructedClass.isSingleFieldValueClass) {
                "Should not mangle names of non-inline class constructors: ${irFunction.render()}"
            }
            return Name.identifier("constructor-impl")
        }

        val suffix = hashSuffix(irFunction, mangleReturnTypes, useOldMangleRules)
        if (suffix == null && ((irFunction.parent as? IrClass)?.isSingleFieldValueClass != true || irFunction.origin == IrDeclarationOrigin.IR_BUILTINS_STUB)) {
            return irFunction.name
        }

        val base = when {
            irFunction.isGetter ->
                JvmAbi.getterName(irFunction.propertyName.asString())
            irFunction.isSetter ->
                JvmAbi.setterName(irFunction.propertyName.asString())
            irFunction.name.isSpecial ->
                error("Unhandled special name in mangledNameFor: ${irFunction.name}")
            else ->
                irFunction.name.asString()
        }

        return Name.identifier("$base-${suffix ?: "impl"}")
    }

    fun hashSuffix(irFunction: IrFunction, mangleReturnTypes: Boolean, useOldMangleRules: Boolean): String? =
        hashSuffix(
            useOldMangleRules,
            irFunction.fullValueParameterList.map { it.type },
            irFunction.returnType.takeIf { mangleReturnTypes && irFunction.hasMangledReturnType },
            irFunction.isSuspend
        )

    fun hashSuffix(
        useOldMangleRules: Boolean,
        valueParameters: List<IrType>,
        returnType: IrType?,
        addContinuation: Boolean = false
    ): String? =
        collectFunctionSignatureForManglingSuffix(
            useOldMangleRules,
            valueParameters.any { it.requiresMangling },
            // The JVM backend computes mangled names after creating suspend function views, but before default argument
            // stub insertion. It would be nice if this part of the continuation lowering happened earlier in the pipeline.
            // TODO: Move suspend function view creation before JvmInlineClassLowering.
            if (addContinuation)
                valueParameters.map { it.asInfoForMangling() } +
                        InfoForMangling(FqNameUnsafe("kotlin.coroutines.Continuation"), isInline = false, isNullable = false)
            else
                valueParameters.map { it.asInfoForMangling() },
            returnType?.asInfoForMangling()
        )?.let(::md5base64)

    private fun IrType.asInfoForMangling(): InfoForMangling =
        InfoForMangling(
            erasedUpperBound.fqNameWhenAvailable!!.toUnsafe(),
            isInline = isInlineClassType(),
            isNullable = isNullable()
        )

    private val IrFunction.propertyName: Name
        get() = (this as IrSimpleFunction).correspondingPropertySymbol!!.owner.name
}

val IrType.requiresMangling: Boolean
    get() {
        val irClass = erasedUpperBound
        return irClass.isSingleFieldValueClass && irClass.fqNameWhenAvailable != StandardNames.RESULT_FQ_NAME
    }

val IrFunction.fullValueParameterList: List<IrValueParameter>
    get() = listOfNotNull(extensionReceiverParameter) + valueParameters

val IrFunction.hasMangledParameters: Boolean
    get() = dispatchReceiverParameter != null && parentAsClass.isSingleFieldValueClass ||
            fullValueParameterList.any { it.type.requiresMangling } ||
            (this is IrConstructor && constructedClass.isSingleFieldValueClass)

val IrFunction.hasMangledReturnType: Boolean
    get() = returnType.isInlineClassType() && parentClassOrNull?.isFileClass != true

val IrClass.inlineClassFieldName: Name
    get() = (inlineClassRepresentation ?: error("Not an inline class: ${render()}")).underlyingPropertyName

val IrFunction.isInlineClassFieldGetter: Boolean
    get() = (parent as? IrClass)?.isSingleFieldValueClass == true && this is IrSimpleFunction && extensionReceiverParameter == null &&
            correspondingPropertySymbol?.let { it.owner.getter == this && it.owner.name == parentAsClass.inlineClassFieldName } == true

val IrFunction.isMultiFieldValueClassFieldGetter: Boolean
    get() = (parent as? IrClass)?.isMultiFieldValueClass == true && this is IrSimpleFunction && extensionReceiverParameter == null &&
            correspondingPropertySymbol?.let {
                val multiFieldValueClassRepresentation = parentAsClass.multiFieldValueClassRepresentation
                    ?: error("Multi-field value class must have multiFieldValueClassRepresentation: ${parentAsClass.render()}")
                it.owner.getter == this && multiFieldValueClassRepresentation.containsPropertyWithName(it.owner.name)
            } == true

fun JvmBackendContext.coerceInlineClass(value: IrExpression, from: IrType, to: IrType, skipCast: Boolean = false): IrExpression =
    IrCallImpl.fromSymbolOwner(value.startOffset, value.endOffset, to, ir.symbols.unsafeCoerceIntrinsic).apply {
        val fromUnderlyingType = from.erasedUpperBound.inlineClassRepresentation?.underlyingType
        val toUnderlyingType = to.erasedUpperBound.inlineClassRepresentation?.underlyingType

        when {
            fromUnderlyingType?.isTypeParameter() == true && !skipCast -> {
                putTypeArgument(0, from)
                putTypeArgument(1, fromUnderlyingType)
            }

            toUnderlyingType?.isTypeParameter() == true && !skipCast -> {
                putTypeArgument(0, toUnderlyingType)
                putTypeArgument(1, to)
            }

            else -> {
                putTypeArgument(0, from)
                putTypeArgument(1, to)
            }
        }
        putValueArgument(0, value)
    }