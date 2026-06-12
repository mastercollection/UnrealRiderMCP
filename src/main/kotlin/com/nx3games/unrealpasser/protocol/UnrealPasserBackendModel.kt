package com.nx3games.unrealpasser.protocol

import com.jetbrains.rd.framework.FrameworkMarshallers
import com.jetbrains.rd.framework.IRdCall
import com.jetbrains.rd.framework.ISerializers
import com.jetbrains.rd.framework.base.ISerializersOwner
import com.jetbrains.rd.framework.base.RdExtBase
import com.jetbrains.rd.framework.impl.RdCall
import com.jetbrains.rd.ide.model.Solution

class UnrealPasserBackendModel(
    private val _execute: RdCall<String, String> = RdCall(
        FrameworkMarshallers.String,
        FrameworkMarshallers.String,
    ),
) : RdExtBase() {
    val execute: IRdCall<String, String>
        get() = _execute

    override val serializersOwner: ISerializersOwner
        get() = Companion

    override val serializationHash: Long
        get() = SERIALIZATION_HASH

    init {
        bindableChildren.add("execute" to _execute)
    }

    companion object : ISerializersOwner {
        const val EXTENSION_NAME: String = "UnrealPasserBackendModel"
        const val SERIALIZATION_HASH: Long = 0x5EA2_0000_0000_0001L

        override fun register(serializers: ISerializers) {
            registerSerializersCore(serializers)
        }

        override fun registerSerializersCore(serializers: ISerializers) {
        }
    }
}

fun Solution.getUnrealPasserBackendModel(): UnrealPasserBackendModel =
    getOrCreateExtension(UnrealPasserBackendModel.EXTENSION_NAME) { UnrealPasserBackendModel() }
