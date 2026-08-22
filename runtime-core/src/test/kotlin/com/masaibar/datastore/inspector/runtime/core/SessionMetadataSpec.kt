package com.masaibar.datastore.inspector.runtime.core

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionMetadataSpec :
  DescribeSpec({
    describe("supportsInspectorRuntime") {
      context("API 27とAPI 28のとき") {
        it("API 27を拒否しAPI 28をRuntime起動対象にする") {
          supportsInspectorRuntime(27) shouldBe false
          supportsInspectorRuntime(28) shouldBe true
        }
      }
    }

    describe("encodeSessionMetadata") {
      context("process名がescape対象文字を含むとき") {
        val session = RuntimeSession("session", "socket", "token")
        val process = "sample\"process\\name\nsecondary"

        it("正しいsession JSONを生成する") {
          val json = Json.parseToJsonElement(encodeSessionMetadata(session, 123, process)).jsonObject

          json.getValue("version").jsonPrimitive.int shouldBe 1
          json.getValue("sessionId").jsonPrimitive.content shouldBe "session"
          json.getValue("socket").jsonPrimitive.content shouldBe "socket"
          json.getValue("token").jsonPrimitive.content shouldBe "token"
          json.getValue("pid").jsonPrimitive.int shouldBe 123
          json.getValue("process").jsonPrimitive.content shouldBe process
        }
      }
    }
  })
