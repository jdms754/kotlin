/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.extensions

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.container.StorageComponentContainer
import org.jetbrains.kotlin.container.useInstance
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.js.translate.extensions.JsSyntheticTranslateExtension
import org.jetbrains.kotlin.library.metadata.KlibMetadataSerializerProtocol
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.serialization.DescriptorSerializerPlugin
import org.jetbrains.kotlin.serialization.js.JsSerializerProtocol
import org.jetbrains.kotlinx.serialization.compiler.diagnostic.SerializationPluginDeclarationChecker

class SerializationComponentRegistrar : CompilerPluginRegistrar() {
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        Companion.registerExtensions(this)
    }

    override val supportsK2: Boolean
        get() = false

    companion object {
        fun registerExtensions(extensionStorage: ExtensionStorage) = with(extensionStorage) {
            // This method is never called in the IDE, therefore this extension is not available there.
            // Since IDE does not perform any serialization of descriptors, metadata written to the 'serializationDescriptorSerializer'
            // is never deleted, effectively causing memory leaks.
            // So we create SerializationDescriptorSerializerPlugin only outside of IDE.
            val serializationDescriptorSerializer = SerializationDescriptorSerializerPlugin()
            DescriptorSerializerPlugin.registerExtension(serializationDescriptorSerializer)
            registerProtoExtensions()

            SyntheticResolveExtension.registerExtension(SerializationResolveExtension(serializationDescriptorSerializer))

            ExpressionCodegenExtension.registerExtension(SerializationCodegenExtension(serializationDescriptorSerializer))
            JsSyntheticTranslateExtension.registerExtension(SerializationJsExtension(serializationDescriptorSerializer))
            IrGenerationExtension.registerExtension(SerializationLoweringExtension(serializationDescriptorSerializer))

            StorageComponentContainerContributor.registerExtension(SerializationPluginComponentContainerContributor())
        }

        private fun registerProtoExtensions() {
            SerializationPluginMetadataExtensions.registerAllExtensions(JvmProtoBufUtil.EXTENSION_REGISTRY)
            SerializationPluginMetadataExtensions.registerAllExtensions(JsSerializerProtocol.extensionRegistry)
            SerializationPluginMetadataExtensions.registerAllExtensions(KlibMetadataSerializerProtocol.extensionRegistry)
        }
    }
}

class SerializationPluginComponentContainerContributor : StorageComponentContainerContributor {
    override fun registerModuleComponents(
        container: StorageComponentContainer,
        platform: TargetPlatform,
        moduleDescriptor: ModuleDescriptor
    ) {
        container.useInstance(SerializationPluginDeclarationChecker())
    }
}
