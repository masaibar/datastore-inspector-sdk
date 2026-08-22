package com.masaibar.datastore.inspector.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class DelegateGenerationInstrumentationSpec : DescribeSpec() {
  init {
    describe("DelegateGenerationInstrumentation") {
      context("既存の契約を検証するとき") {
        it("4つの既知delegate生成経路だけをbridgeへ置換する") {
          val original = classWithCalls(DelegateGenerationRoute.entries.map(::originalCall))
          val rewritten = rewrite(original)
          val calls = calls(rewritten)

          DelegateGenerationRoute.entries.forEach { route ->
            (
              calls.any {
                it.owner == route.bridgeOwner &&
                  it.name == route.bridgeName &&
                  it.descriptor == route.bridgeDescriptor
              }
            ) shouldBe true
            (
              calls.none {
                it.owner == route.owner &&
                  it.name == route.originalName &&
                  it.descriptor == route.originalDescriptor
              }
            ) shouldBe true
          }
          val constants = constants(rewritten)
          (constants.contains("sample.StoreDeclarations#declare#0#preferences-delegate-default-v1")) shouldBe true
          (constants.contains("sample.StoreDeclarations")) shouldBe true
          (constants.contains("declare@0")) shouldBe true
        }

        it("既知ownerとnameの未知signatureはbuild errorにする") {
          val route = DelegateGenerationRoute.PREFERENCES_DEFAULT
          val original = classWithCalls(
            listOf(MethodCall(Opcodes.INVOKESTATIC, route.owner, route.originalName, "()V"))
          )

          val error = shouldThrow<IllegalStateException> { rewrite(original) }

          (error.message.orEmpty().contains("未知のsignature")) shouldBe true
        }

        it("KMP Factory経路と無関係な呼び出しは変更しない") {
          val factory = MethodCall(
            Opcodes.INVOKESTATIC,
            "androidx/datastore/preferences/core/PreferenceDataStoreFactory",
            "createWithPath\$default",
            "()Landroidx/datastore/core/DataStore;"
          )
          val original = classWithCalls(listOf(factory))

          (calls(rewrite(original))) shouldBe (listOf(factory))
        }
      }
    }
  }

  private fun rewrite(bytes: ByteArray): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(DataStoreCallClassVisitor(writer), 0)
    return writer.toByteArray()
  }

  private fun classWithCalls(calls: List<MethodCall>): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC,
      "sample/StoreDeclarations",
      null,
      "java/lang/Object",
      null
    )
    val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "declare", "()V", null, null)
    method.visitCode()
    calls.forEach { call ->
      method.visitMethodInsn(call.opcode, call.owner, call.name, call.descriptor, false)
    }
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(0, 0)
    method.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun calls(bytes: ByteArray): List<MethodCall> {
    val result = mutableListOf<MethodCall>()
    ClassReader(bytes).accept(
      object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          exceptions: Array<out String>?
        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
          override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
          ) {
            result += MethodCall(opcode, owner, name, descriptor)
          }
        }
      },
      0
    )
    return result
  }

  private fun constants(bytes: ByteArray): List<String> {
    val result = mutableListOf<String>()
    ClassReader(bytes).accept(
      object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          exceptions: Array<out String>?
        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
          override fun visitLdcInsn(value: Any) {
            if (value is String) result += value
          }
        }
      },
      0
    )
    return result
  }

  private fun originalCall(route: DelegateGenerationRoute) =
    MethodCall(Opcodes.INVOKESTATIC, route.owner, route.originalName, route.originalDescriptor)

  private data class MethodCall(
    val opcode: Int,
    val owner: String,
    val name: String,
    val descriptor: String
  )
}
