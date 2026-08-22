package com.masaibar.datastore.inspector.sample

import com.google.protobuf.ByteString
import com.masaibar.datastore.inspector.sample.proto.UserSettings
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain

class UserSettingsSummarySpec :
  DescribeSpec({
    describe("UserSettings.summary") {
      context("全Proto fieldに値があるとき") {
        val summary =
          UserSettings.newBuilder()
            .setNickname("sample")
            .setScreenName("settings")
            .setRawValue(ByteString.copyFrom(byteArrayOf(0, 1, 2, -1)))
            .setUint32Value(UInt.MAX_VALUE.toInt())
            .setUint64Value(ULong.MAX_VALUE.toLong())
            .setSint32Value(Int.MIN_VALUE)
            .setSint64Value(Long.MIN_VALUE)
            .setFixed32Value(UInt.MAX_VALUE.toInt())
            .setFixed64Value(ULong.MAX_VALUE.toLong())
            .setSfixed32Value(Int.MAX_VALUE)
            .setSfixed64Value(Long.MAX_VALUE)
            .addAllUnsignedIds(listOf(0L, Long.MIN_VALUE, ULong.MAX_VALUE.toLong()))
            .putUnsignedCounters(UInt.MAX_VALUE.toInt(), ULong.MAX_VALUE.toLong())
            .build()
            .summary()

        it("全fieldを欠落なく含める") {
          listOf(
            "user_name:",
            "launch_count:",
            "last_login_epoch_millis:",
            "premium:",
            "rating:",
            "balance:",
            "uint32_value:",
            "uint64_value:",
            "sint32_value:",
            "sint64_value:",
            "fixed32_value:",
            "fixed64_value:",
            "sfixed32_value:",
            "sfixed64_value:",
            "theme:",
            "profile.present:",
            "profile.display_name:",
            "profile.age:",
            "enabled_features:",
            "feature_flags:",
            "nickname.present:",
            "nickname:",
            "destination.case:",
            "destination.screen_name:",
            "destination.content_id:",
            "raw_value:",
            "notifications.present:",
            "notifications.enabled:",
            "notifications.channels:",
            "unsigned_ids:",
            "notification_profiles:",
            "unsigned_counters:",
            "notification_routes:"
          ).forEach { field -> summary shouldContain field }

          summary shouldContain "nickname.present: true"
          summary shouldContain "destination.screen_name: settings"
          summary shouldContain "destination.content_id: <inactive>"
          summary shouldContain "raw_value: 0x000102ff"
          summary shouldContain "uint32_value: 4294967295"
          summary shouldContain "uint64_value: 18446744073709551615"
          summary shouldContain "sint32_value: -2147483648"
          summary shouldContain "sint64_value: -9223372036854775808"
          summary shouldContain "fixed32_value: 4294967295"
          summary shouldContain "fixed64_value: 18446744073709551615"
          summary shouldContain "sfixed32_value: 2147483647"
          summary shouldContain "sfixed64_value: 9223372036854775807"
          summary shouldContain "unsigned_ids: [0, 9223372036854775808, 18446744073709551615]"
          summary shouldContain "unsigned_counters: {4294967295=18446744073709551615}"
        }
      }
    }
  })
