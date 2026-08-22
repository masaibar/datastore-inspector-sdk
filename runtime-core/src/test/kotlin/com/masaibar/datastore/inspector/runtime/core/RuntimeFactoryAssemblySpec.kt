package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.UnsupportedReason
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger

class RuntimeFactoryAssemblySpec :
  DescribeSpec({
    describe("assembleRuntimeFactories") {
      context("application providerが予約済みCustom factory IDを名乗るとき") {
        val external = CountingFactory(CUSTOM_STORE_ADAPTER_PROVIDER_ID)
        val builtIn =
          CountingFactory(
            providerId = CUSTOM_STORE_ADAPTER_PROVIDER_ID,
            customResolution =
              AdapterResolution.Unsupported(
                UnsupportedReason("BUILT_IN", "built-in", false)
              )
          )
        val factories = assembleRuntimeFactories(listOf(external), builtIn)
        val registry = DataStoreRegistry { "custom-store" }
        val declaration = declaration(StoreKind.CUSTOM)

        it("外部factoryへCustom instanceを渡さず予約ID競合をErrorへfail closedする") {
          val entry = registry.resolve(Any(), declaration, factories)

          entry.state.shouldBeInstanceOf<RegistryState.Error>()
          external.calls.get() shouldBe 0
          builtIn.calls.get() shouldBe 0
        }
      }

      context("別IDのapplication factoryがCustom kindを横取りしようとするとき") {
        val external =
          CountingFactory(
            providerId = "application-custom-claim",
            customResolution =
              AdapterResolution.Unsupported(
                UnsupportedReason("EXTERNAL", "external", false)
              )
          )
        val builtIn =
          CountingFactory(
            providerId = CUSTOM_STORE_ADAPTER_PROVIDER_ID,
            customResolution =
              AdapterResolution.Unsupported(
                UnsupportedReason("BUILT_IN", "built-in", false)
              )
          )
        val factories = assembleRuntimeFactories(listOf(external), builtIn)
        val registry = DataStoreRegistry { "custom-store" }
        val declaration = declaration(StoreKind.CUSTOM)

        it("built-in Custom factoryを先に適用する") {
          val entry = registry.resolve(Any(), declaration, factories)

          entry.state
            .shouldBeInstanceOf<RegistryState.Unsupported>()
            .reason.code shouldBe
            "BUILT_IN"
          builtIn.calls.get() shouldBe 1
          external.calls.get() shouldBe 0
        }
      }

      context("通常のoptional provider IDが重複するとき") {
        val first = CountingFactory("duplicate-optional")
        val second = CountingFactory("duplicate-optional")
        val builtIn = CountingFactory(CUSTOM_STORE_ADAPTER_PROVIDER_ID)
        val factories = assembleRuntimeFactories(listOf(first, second), builtIn)
        val registry = DataStoreRegistry { "unknown-store" }
        val declaration = declaration(StoreKind.UNKNOWN)

        it("distinctByで隠さず既存Registry方針どおりErrorへfail closedする") {
          val entry = registry.resolve(Any(), declaration, factories)

          entry.state.shouldBeInstanceOf<RegistryState.Error>()
          first.calls.get() shouldBe 0
          second.calls.get() shouldBe 0
          builtIn.calls.get() shouldBe 0
        }
      }
    }
  })

private class CountingFactory(
  override val providerId: String,
  private val customResolution: AdapterResolution = AdapterResolution.NotApplicable
) : StoreAdapterFactory {
  val calls = AtomicInteger()

  override fun create(candidate: StoreCandidate): AdapterResolution {
    calls.incrementAndGet()
    return if (candidate.declaration.kindHint == StoreKind.CUSTOM) {
      customResolution
    } else {
      AdapterResolution.NotApplicable
    }
  }
}

private fun declaration(kind: StoreKind): StoreDeclaration =
  StoreDeclaration(
    declarationId = "declaration-${kind.name}",
    name = "store",
    fileName = null,
    kindHint = kind,
    owner = "fixture.Owner",
    property = "store"
  )
