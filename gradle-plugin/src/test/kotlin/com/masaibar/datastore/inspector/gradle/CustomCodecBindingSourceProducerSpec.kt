package com.masaibar.datastore.inspector.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class CustomCodecBindingSourceProducerSpec : DescribeSpec({
  describe("produce") {
    context("serializer/value/codecの一意なbindingがあるとき") {
      val root = Files.createTempDirectory("custom-codec-source")
      val javaOutput = root.resolve("java").toFile()
      val resourcesOutput = root.resolve("resources").toFile()
      val mapping = "dev.example.Serializer=dev.example.Value=dev.example.ValueCodec"

      it("型検査されるdebug provider sourceとServiceLoader entryを生成する") {
        CustomCodecBindingSourceProducer.produce(
          mappings = listOf(mapping),
          javaOutputDirectory = javaOutput,
          resourcesOutputDirectory = resourcesOutput
        )

        val source =
          javaOutput.resolve(
            CustomCodecBindingSourceProducer.PROVIDER_CLASS
              .replace('.', '/') + ".java"
          ).readText()
        source shouldContain "dev.example.Serializer.class"
        source shouldContain "dev.example.Value.class"
        source shouldContain "new dev.example.ValueCodec()"
        resourcesOutput.resolve(
          "META-INF/services/" +
            CustomCodecBindingSourceProducer.SERVICE_INTERFACE
        ).readText() shouldBe
          "${CustomCodecBindingSourceProducer.PROVIDER_CLASS}\n"
      }
    }

    context("同じserializer/valueへ複数codecがあるとき") {
      val root = Files.createTempDirectory("custom-codec-duplicate")
      val javaOutput = root.resolve("java").toFile()
      val resourcesOutput = root.resolve("resources").toFile()
      val mappings =
        listOf(
          "dev.example.Serializer=dev.example.Value=dev.example.FirstCodec",
          "dev.example.Serializer=dev.example.Value=dev.example.SecondCodec"
        )

      it("曖昧なproviderを生成せず失敗する") {
        val error =
          shouldThrow<IllegalArgumentException> {
            CustomCodecBindingSourceProducer.produce(
              mappings = mappings,
              javaOutputDirectory = javaOutput,
              resourcesOutputDirectory = resourcesOutput
            )
          }

        error.message.orEmpty() shouldContain "複数codec binding"
      }
    }
  }
})
