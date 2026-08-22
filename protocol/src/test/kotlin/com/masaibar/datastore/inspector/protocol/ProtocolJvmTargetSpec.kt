package com.masaibar.datastore.inspector.protocol

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ProtocolJvmTargetSpec :
  DescribeSpec({
    describe("Protocol classfile target") {
      context("ProtocolVersion classをbuildしたとき") {
        it("JVM 11のclassfileを生成する") {
          val classBytes =
            checkNotNull(
              ProtocolVersion::class.java.getResourceAsStream(
                "/com/masaibar/datastore/inspector/protocol/" +
                  "ProtocolVersion.class"
              )
            ) {
              "ProtocolVersion.classを読み込めません。"
            }.use { it.readBytes() }

          val classfileMajor =
            ((classBytes[6].toInt() and 0xff) shl 8) or
              (classBytes[7].toInt() and 0xff)

          classfileMajor shouldBe 55
        }
      }
    }
  })
