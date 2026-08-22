package com.masaibar.datastore.inspector.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.system.measureTimeMillis

class DataStoreInstrumentationSpec : DescribeSpec({
  describe("既知DataStore APIの計装") {
    context("全ての検証済みmethod routeがあるとき") {
      val routes = InvocationRoutes.routes
      val original =
        classWithCalls(
          routes.map { route ->
            MethodCall(
              opcode = route.opcode,
              owner = route.owner,
              name = route.originalName,
              descriptor = route.originalDescriptor,
              isInterface = route.opcode == Opcodes.INVOKEINTERFACE
            )
          }
        )

      it("各callを対応するstatic bridgeへ置換する") {
        val rewrittenCalls = calls(rewrite(original))

        routes.forEach { route ->
          rewrittenCalls.shouldContain(
            MethodCall(
              opcode = Opcodes.INVOKESTATIC,
              owner = route.bridgeOwner,
              name = route.bridgeName,
              descriptor = route.bridgeDescriptor,
              isInterface = false
            )
          )
        }
      }
    }

    context("FileStorageとOkioStorageの検証済みconstructorがあるとき") {
      val routes = ConstructorRoute.routes
      val original = classWithConstructors(routes)

      it("NEWとDUPを除去してfactory bridgeへ置換する") {
        val rewritten = rewrite(original)
        val rewrittenCalls = calls(rewritten)

        routes.forEach { route ->
          rewrittenCalls.shouldContain(
            MethodCall(
              opcode = Opcodes.INVOKESTATIC,
              owner = route.bridgeOwner,
              name = route.bridgeName,
              descriptor = route.bridgeDescriptor,
              isInterface = false
            )
          )
        }
        newTypes(rewritten) shouldBe emptyList()
      }

      it("default constructor markerをRuntime bridgeのObject ABIへ正規化する") {
        routes
          .filter { route -> route.bridgeName.endsWith("Default") }
          .forEach { route ->
            route.bridgeDescriptor.contains(
              "Lkotlin/jvm/internal/DefaultConstructorMarker;"
            ) shouldBe false
            route.bridgeDescriptor.contains("ILjava/lang/Object;") shouldBe true
          }
      }
    }

    context("同じclassを二度計装するとき") {
      val route = DelegateGenerationRoute.TYPED_DEFAULT
      val original =
        classWithCalls(
          listOf(
            MethodCall(
              opcode = Opcodes.INVOKESTATIC,
              owner = route.owner,
              name = route.originalName,
              descriptor = route.originalDescriptor,
              isInterface = false
            )
          )
        )

      it("class markerによりbridge callを重ねて追加しない") {
        val once = rewrite(original)
        val twice = rewrite(once)

        calls(twice) shouldBe calls(once)
        markerFields(twice) shouldHaveSize 1
      }
    }

    context("既知factory ownerとmethod名に未知descriptorがあるとき") {
      val original =
        classWithCalls(
          listOf(
            MethodCall(
              opcode = Opcodes.INVOKEVIRTUAL,
              owner = "androidx/datastore/core/DataStoreFactory",
              name = "create",
              descriptor = "()Landroidx/datastore/core/DataStore;",
              isInterface = false
            )
          )
        )

      it("silent skipせずcompatibility errorにする") {
        val error = shouldThrow<IllegalStateException> { rewrite(original) }

        error.message.orEmpty() shouldContain "未知のsignature"
      }
    }

    context("既知structured capture ownerとmethod名に未知descriptorがあるとき") {
      val unknownStructuredCalls =
        listOf(
          MethodCall(
            opcode = Opcodes.INVOKEVIRTUAL,
            owner = "kotlinx/serialization/cbor/Cbor",
            name = "encodeToByteArray",
            descriptor = "(Ljava/lang/Object;)[B",
            isInterface = false
          ),
          MethodCall(
            opcode = Opcodes.INVOKEVIRTUAL,
            owner = "kotlinx/serialization/json/Json",
            name = "decodeFromString",
            descriptor = "(Ljava/lang/String;)Ljava/lang/Object;",
            isInterface = false
          )
        )
      val unknownStructuredClasses =
        unknownStructuredCalls.map { call -> classWithCalls(listOf(call)) }

      it("version driftをsilent skipせず各callでcompatibility errorにする") {
        unknownStructuredClasses.forEach { original ->
          val error =
            shouldThrow<IllegalStateException> {
              rewrite(original)
            }

          error.message.orEmpty() shouldContain "未知のsignature"
        }
      }
    }
  }

  describe("計装対象class policy") {
    context("AndroidX・Kotlin・Inspector RuntimeまたはProtocol classのとき") {
      val excluded =
        listOf(
          "androidx.datastore.core.DataStoreFactory",
          "kotlin.collections.CollectionsKt",
          "kotlinx.coroutines.Job",
          "kotlinx.serialization.json.Json",
          "com.masaibar.datastore.inspector.runtime.core.DataStoreCreationBridge",
          "com.masaibar.datastore.inspector.protocol.RequestEnvelope"
        )

      it("ALL scopeでも計装対象から除外する") {
        excluded.forEach { className ->
          InstrumentationClassPolicy.isInstrumentable(className) shouldBe false
        }
      }
    }

    context("applicationとexternal AARのconsumer classのとき") {
      val included =
        listOf(
          "dev.example.app.CustomStores",
          "dev.example.external.ExternalStoreFactory"
        )

      it("計装対象にする") {
        included.forEach { className ->
          InstrumentationClassPolicy.isInstrumentable(className) shouldBe true
        }
      }
    }
  }

  describe("計装性能budget") {
    context("target class budgetより小さいsynthetic corpusのとき") {
      val route = DelegateGenerationRoute.TYPED_DEFAULT
      val corpus =
        List(InstrumentationBudget.REGRESSION_SAMPLE_CLASS_COUNT) { index ->
          classWithCalls(
            calls =
              listOf(
                MethodCall(
                  opcode = Opcodes.INVOKESTATIC,
                  owner = route.owner,
                  name = route.originalName,
                  descriptor = route.originalDescriptor,
                  isInterface = false
                )
              ),
            className = "sample/StoreDeclarations$index"
          )
        }

      it("固定時間budget内で全classを変換する") {
        val elapsed = measureTimeMillis { corpus.forEach(::rewrite) }

        elapsed shouldBeLessThan
          InstrumentationBudget.REGRESSION_SAMPLE_ELAPSED_MILLIS
      }
    }
  }
})

private fun rewrite(bytes: ByteArray): ByteArray {
  val writer = ClassWriter(0)
  ClassReader(bytes).accept(DataStoreCallClassVisitor(writer), 0)
  return writer.toByteArray()
}

private fun classWithCalls(
  calls: List<MethodCall>,
  className: String = "sample/StoreDeclarations"
): ByteArray {
  val writer = ClassWriter(0)
  writer.visit(
    Opcodes.V17,
    Opcodes.ACC_PUBLIC,
    className,
    null,
    "java/lang/Object",
    null
  )
  val method =
    writer.visitMethod(
      Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
      "declare",
      "()V",
      null,
      null
    )
  method.visitCode()
  calls.forEach { call ->
    method.visitMethodInsn(
      call.opcode,
      call.owner,
      call.name,
      call.descriptor,
      call.isInterface
    )
  }
  method.visitInsn(Opcodes.RETURN)
  method.visitMaxs(0, 0)
  method.visitEnd()
  writer.visitEnd()
  return writer.toByteArray()
}

private fun classWithConstructors(routes: List<ConstructorRoute>): ByteArray {
  val writer = ClassWriter(0)
  writer.visit(
    Opcodes.V17,
    Opcodes.ACC_PUBLIC,
    "sample/StorageDeclarations",
    null,
    "java/lang/Object",
    null
  )
  val method =
    writer.visitMethod(
      Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
      "declare",
      "()V",
      null,
      null
    )
  method.visitCode()
  routes.forEach { route ->
    method.visitTypeInsn(Opcodes.NEW, route.owner)
    method.visitInsn(Opcodes.DUP)
    method.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      route.owner,
      "<init>",
      route.originalDescriptor,
      false
    )
    method.visitInsn(Opcodes.POP)
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
      ): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9) {
          override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
          ) {
            result += MethodCall(opcode, owner, name, descriptor, isInterface)
          }
        }
    },
    0
  )
  return result
}

private fun newTypes(bytes: ByteArray): List<String> {
  val result = mutableListOf<String>()
  ClassReader(bytes).accept(
    object : ClassVisitor(Opcodes.ASM9) {
      override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
      ): MethodVisitor =
        object : MethodVisitor(Opcodes.ASM9) {
          override fun visitTypeInsn(opcode: Int, type: String) {
            if (opcode == Opcodes.NEW) result += type
          }
        }
    },
    0
  )
  return result
}

private fun markerFields(bytes: ByteArray): List<String> {
  val result = mutableListOf<String>()
  ClassReader(bytes).accept(
    object : ClassVisitor(Opcodes.ASM9) {
      override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
      ): FieldVisitor? {
        if (value == "custom-datastore-v1") result += name
        return null
      }
    },
    0
  )
  return result
}

private data class MethodCall(
  val opcode: Int,
  val owner: String,
  val name: String,
  val descriptor: String,
  val isInterface: Boolean
)
