package com.masaibar.datastore.inspector.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class CustomDocumentValidationSpec :
  DescribeSpec({
    describe("validate") {
      context("nested object・collection・Unicode・null・数値境界を含むstrict JSONのとき") {
        val documents =
          listOf(
            """{}""",
            """[]""",
            """{"plain":"日本語","emoji":"😀","escaped":"\uD83D\uDE00","nested":[null,true,false,-0,1.234e-10]}""",
            """{"minimum":-9223372036854775808,"maximum":18446744073709551615,"precise":0.123456789012345678901234567890}"""
          )

        it("数値を浮動小数点へ変換せず受理する") {
          documents.map { document ->
            runCatching {
              CustomDocumentValidation.validate(CustomDocumentFormat.JSON, document)
            }.isSuccess
          } shouldBe List(documents.size) { true }
        }
      }

      context("同じobject keyをliteralとUnicode escapeで重複させたとき") {
        val documents =
          listOf(
            """{"same":1,"same":2}""",
            """{"a":1,"\u0061":2}"""
          )

        it("どちらもduplicate keyとして拒否する") {
          documents.map { document ->
            shouldThrow<CustomDocumentValidationException> {
              CustomDocumentValidation.validate(CustomDocumentFormat.JSON, document)
            }.failure
          } shouldBe
            listOf(
              CustomDocumentValidationFailure.DUPLICATE_JSON_KEY,
              CustomDocumentValidationFailure.DUPLICATE_JSON_KEY
            )
        }
      }

      context("trailing garbage・trailing comma・非有限数を含むとき") {
        val invalidDocuments =
          listOf(
            """{"value":1} trailing""" to CustomDocumentValidationFailure.INVALID_JSON,
            """{"value":1,}""" to CustomDocumentValidationFailure.INVALID_JSON,
            """[1,]""" to CustomDocumentValidationFailure.INVALID_JSON,
            "NaN" to CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER,
            "Infinity" to CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER,
            "+Infinity" to CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER,
            "-Infinity" to CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER,
            ".5" to CustomDocumentValidationFailure.INVALID_JSON,
            "1x" to CustomDocumentValidationFailure.INVALID_JSON,
            "1e2x" to CustomDocumentValidationFailure.INVALID_JSON,
            "\ufeff{\"value\":1}" to CustomDocumentValidationFailure.INVALID_JSON,
            "1\u0661" to CustomDocumentValidationFailure.INVALID_JSON
          )

        it("strict JSON違反を分類して拒否する") {
          invalidDocuments.map { (document, expectedFailure) ->
            val actual =
              shouldThrow<CustomDocumentValidationException> {
                CustomDocumentValidation.validate(CustomDocumentFormat.JSON, document)
              }.failure
            actual == expectedFailure
          } shouldBe List(invalidDocuments.size) { true }
        }
      }

      context("JSON nesting depthが上限以下と上限超過のとき") {
        val valid =
          "[".repeat(CustomDocumentLimits.MAX_JSON_DEPTH) +
            "0" +
            "]".repeat(CustomDocumentLimits.MAX_JSON_DEPTH)
        val tooDeep =
          "[".repeat(CustomDocumentLimits.MAX_JSON_DEPTH + 1) +
            "0" +
            "]".repeat(CustomDocumentLimits.MAX_JSON_DEPTH + 1)

        it("上限値だけを受理する") {
          runCatching {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, valid)
          }.isSuccess shouldBe true
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, tooDeep)
          }.failure shouldBe CustomDocumentValidationFailure.JSON_DEPTH_LIMIT
        }
      }

      context("JSON collection件数が上限を超えるとき") {
        val atLimit =
          List(CustomDocumentLimits.MAX_JSON_COLLECTION_ENTRIES) { "0" }
            .joinToString(prefix = "[", postfix = "]")
        val overLimit =
          List(CustomDocumentLimits.MAX_JSON_COLLECTION_ENTRIES + 1) { "0" }
            .joinToString(prefix = "[", postfix = "]")

        it("上限値だけを受理する") {
          runCatching {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, atLimit)
          }.isSuccess shouldBe true
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, overLimit)
          }.failure shouldBe CustomDocumentValidationFailure.JSON_COLLECTION_LIMIT
        }
      }

      context("JSON node総数が上限を超えるとき") {
        val child = List(10) { "0" }.joinToString(prefix = "[", postfix = "]")
        val belowLimit =
          List(9_090) { child }
            .joinToString(prefix = "[", postfix = "]")
        val overLimit =
          List(CustomDocumentLimits.MAX_JSON_COLLECTION_ENTRIES) { child }
            .joinToString(prefix = "[", postfix = "]")

        it("上限近傍まで受理し超過を拒否する") {
          runCatching {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, belowLimit)
          }.isSuccess shouldBe true
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, overLimit)
          }.failure shouldBe CustomDocumentValidationFailure.JSON_NODE_LIMIT
        }
      }

      context("JSON Stringとnumber tokenが個別上限を超えるとき") {
        val stringAtLimit =
          "\"" + "a".repeat(CustomDocumentLimits.MAX_JSON_STRING_UTF8_BYTES) + "\""
        val stringOverLimit =
          "\"" + "a".repeat(CustomDocumentLimits.MAX_JSON_STRING_UTF8_BYTES + 1) + "\""
        val numberAtLimit =
          "1" + "0".repeat(CustomDocumentLimits.MAX_JSON_NUMBER_CHARACTERS - 1)
        val numberOverLimit =
          "1" + "0".repeat(CustomDocumentLimits.MAX_JSON_NUMBER_CHARACTERS)

        it("上限値だけを受理する") {
          runCatching {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, stringAtLimit)
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, numberAtLimit)
          }.isSuccess shouldBe true
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, stringOverLimit)
          }.failure shouldBe CustomDocumentValidationFailure.JSON_STRING_LIMIT
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.JSON, numberOverLimit)
          }.failure shouldBe CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE
        }
      }

      context("document byte上限を超えるstrict UTF-8 textのとき") {
        val atLimit = "a".repeat(CustomDocumentLimits.MAX_DOCUMENT_UTF8_BYTES)
        val overLimit = atLimit + "a"

        it("1 MiBだけを受理する") {
          runCatching {
            CustomDocumentValidation.validate(CustomDocumentFormat.TEXT, atLimit)
          }.isSuccess shouldBe true
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.TEXT, overLimit)
          }.failure shouldBe CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE
        }
      }

      context("Stringにunpaired surrogateが含まれるとき") {
        val document = charArrayOf(0xd800.toChar()).concatToString()

        it("UTF-8へ置換せず拒否する") {
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.TEXT, document)
          }.failure shouldBe CustomDocumentValidationFailure.MALFORMED_UTF16
        }
      }

      context("textが表示可能文字と許可改行だけを含むとき") {
        val document = "plain\t日本語\r\nemoji 😀"

        it("strict UTF-8 textとして受理する") {
          runCatching {
            CustomDocumentValidation.validate(CustomDocumentFormat.TEXT, document)
          }.isSuccess shouldBe true
        }
      }

      context("textがNUL・DEL・C1 controlを含むとき") {
        val documents = listOf("before\u0000after", "before\u007fafter", "before\u0085after")

        it("unsafe textとして拒否する") {
          documents.map { document ->
            shouldThrow<CustomDocumentValidationException> {
              CustomDocumentValidation.validate(CustomDocumentFormat.TEXT, document)
            }.failure
          } shouldBe List(documents.size) { CustomDocumentValidationFailure.TEXT_UNSAFE_CONTROL }
        }
      }

      context("未知のdocument formatのとき") {
        val document = "opaque"

        it("編集用validatorではfail closedする") {
          shouldThrow<CustomDocumentValidationException> {
            CustomDocumentValidation.validate(CustomDocumentFormat.UNKNOWN, document)
          }.failure shouldBe CustomDocumentValidationFailure.UNSUPPORTED_FORMAT
        }
      }
    }

    describe("detectFormat") {
      context("valid JSONとplain textを解決するとき") {
        val documents =
          listOf(
            """{"value":1}""" to CustomDocumentFormat.JSON,
            "plain document" to CustomDocumentFormat.TEXT,
            "hello {not-json-syntax}" to CustomDocumentFormat.TEXT
          )

        it("strict JSONをtextより先に選ぶ") {
          documents.map { (document, _) ->
            CustomDocumentValidation.detectFormat(document)
          } shouldBe documents.map { (_, expected) -> expected }
        }
      }

      context("JSONらしいdocumentがstrict JSON違反のとき") {
        val documents =
          listOf(
            """{"a":1,"a":2}""" to
              CustomDocumentValidationFailure.DUPLICATE_JSON_KEY,
            """[1] trailing""" to CustomDocumentValidationFailure.INVALID_JSON,
            "NaN" to CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER,
            "\ufeff{\"value\":1}" to CustomDocumentValidationFailure.INVALID_JSON,
            ".5" to CustomDocumentValidationFailure.INVALID_JSON,
            "+Infinity" to CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER
          )

        it("textへ格下げしない") {
          documents.map { (document, _) ->
            shouldThrow<CustomDocumentValidationException> {
              CustomDocumentValidation.detectFormat(document)
            }.failure
          } shouldBe documents.map { (_, expected) -> expected }
        }
      }
    }

    describe("CustomDocumentValidationException") {
      context("secretに見えるdocumentのvalidationが失敗するとき") {
        val secret = "do-not-leak"
        val document = """{"secret":"$secret",}"""

        it("messageとcauseへdocumentやparser詳細を保持しない") {
          val error =
            shouldThrow<CustomDocumentValidationException> {
              CustomDocumentValidation.validate(CustomDocumentFormat.JSON, document)
            }

          error.message?.contains(secret) shouldBe false
          error.cause shouldBe null
        }
      }
    }
  })
