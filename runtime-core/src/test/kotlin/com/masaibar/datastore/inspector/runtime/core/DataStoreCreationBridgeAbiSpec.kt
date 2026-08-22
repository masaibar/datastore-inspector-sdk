package com.masaibar.datastore.inspector.runtime.core

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Method

class DataStoreCreationBridgeAbiSpec :
  DescribeSpec({
    describe("Storage constructor ASM bridge ABI") {
      context("Kotlin default constructor routeを置換するとき") {
        it("FileStorageとOkioStorageのmarkerをObjectとして公開する") {
          val descriptors =
            DataStoreCreationBridge::class.java.declaredMethods
              .filter { method ->
                method.name == "fileStorageDefault" ||
                  method.name == "okioStorageDefault"
              }
              .associate { method -> method.name to method.jvmDescriptor() }

          descriptors shouldBe
            mapOf(
              "fileStorageDefault" to
                "(Landroidx/datastore/core/Serializer;" +
                "Lkotlin/jvm/functions/Function1;" +
                "Lkotlin/jvm/functions/Function0;ILjava/lang/Object;)" +
                "Landroidx/datastore/core/FileStorage;",
              "okioStorageDefault" to
                "(Lokio/FileSystem;" +
                "Landroidx/datastore/core/okio/OkioSerializer;" +
                "Lkotlin/jvm/functions/Function2;" +
                "Lkotlin/jvm/functions/Function0;ILjava/lang/Object;)" +
                "Landroidx/datastore/core/okio/OkioStorage;"
            )
        }
      }
    }
  })

private fun Method.jvmDescriptor(): String =
  parameterTypes.joinToString(prefix = "(", postfix = ")", separator = "") { type ->
    type.jvmDescriptor()
  } + returnType.jvmDescriptor()

private fun Class<*>.jvmDescriptor(): String =
  when {
    isPrimitive ->
      when (this) {
        java.lang.Void.TYPE -> "V"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Character.TYPE -> "C"
        java.lang.Short.TYPE -> "S"
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        else -> error("未対応primitive typeです。")
      }
    isArray -> name.replace('.', '/')
    else -> "L${name.replace('.', '/')};"
  }
