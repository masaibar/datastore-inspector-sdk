package com.masaibar.datastore.inspector.runtime.sharedpreferences

import android.content.SharedPreferences
import com.masaibar.datastore.inspector.protocol.BooleanValue
import com.masaibar.datastore.inspector.protocol.CanonicalUtf8
import com.masaibar.datastore.inspector.protocol.ClearPreferences
import com.masaibar.datastore.inspector.protocol.DeletePreference
import com.masaibar.datastore.inspector.protocol.DoubleValue
import com.masaibar.datastore.inspector.protocol.FloatValue
import com.masaibar.datastore.inspector.protocol.IntValue
import com.masaibar.datastore.inspector.protocol.MutatePreferences
import com.masaibar.datastore.inspector.protocol.PreferenceEntry
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.PutPreference
import com.masaibar.datastore.inspector.protocol.ReplacePreferences
import com.masaibar.datastore.inspector.protocol.StringSetValue
import com.masaibar.datastore.inspector.protocol.StringValue
import com.masaibar.datastore.inspector.runtime.core.AdapterWriteResult
import com.masaibar.datastore.inspector.runtime.core.DynamicStoreCatalog
import com.masaibar.datastore.inspector.runtime.core.PreferencesSnapshotLimits
import com.masaibar.datastore.inspector.runtime.core.StoreAdapterException
import com.masaibar.datastore.inspector.runtime.core.StoreCatalogException
import com.masaibar.datastore.inspector.runtime.core.StoreSnapshotUnsupportedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SharedPreferencesFileCatalogSpec : DescribeSpec() {
  init {
    describe("SharedPreferencesFileCatalog") {
      context("既存の契約を検証するとき") {
        it("xmlとbakを同じStoreにまとめ一時fileとsymlinkを無視する") { `xmlとbakを同じStoreにまとめ一時fileとsymlinkを無視する`() }
        it("Runtime起動後に永続化されたfileを次のscanで発見する") { `Runtime起動後に永続化されたfileを次のscanで発見する`() }
        it("logical nameの1から247 byteだけを許可する") { `logical nameの1から247 byteだけを許可する`() }
        it("Store 1024件を許可し1025件目で部分catalogを返さない") { `Store 1024件を許可し1025件目で部分catalogを返さない`() }
        it("backing fileが16MiBを超えるとopen前に拒否する") { `backing fileが16MiBを超えるとopen前に拒否する`() }
      }
    }
  }
  private fun `xmlとbakを同じStoreにまとめ一時fileとsymlinkを無視する`() =
    withStoreDirectory { directory ->
      File(directory, "alpha.xml").writeText("<map/>")
      File(directory, "alpha.xml.bak").writeText("<map/>")
      File(directory, "beta.xml.bak").writeText("<map/>")
      File(directory, "ignored.xml.tmp").writeText("<map/>")
      val outside = Files.createTempFile("shared-preferences-outside", ".xml")
      try {
        Files.createSymbolicLink(
          directory.toPath().resolve("linked.xml"),
          outside
        )

        val names = SharedPreferencesFileCatalog.scan(directory)

        (names) shouldBe (CanonicalUtf8.sorted(listOf("alpha", "beta")))
      } finally {
        Files.deleteIfExists(outside)
      }
    }

  private fun `Runtime起動後に永続化されたfileを次のscanで発見する`() =
    withStoreDirectory { directory ->
      (SharedPreferencesFileCatalog.scan(directory).isEmpty()) shouldBe true

      File(directory, "created_later.xml").writeText("<map/>")

      (SharedPreferencesFileCatalog.scan(directory)) shouldBe (listOf("created_later"))
    }

  private fun `logical nameの1から247 byteだけを許可する`() =
    withStoreDirectory { directory ->
      val maximum = "a".repeat(247)
      File(directory, "$maximum.xml.bak").writeText("<map/>")
      (SharedPreferencesFileCatalog.scan(directory)) shouldBe (listOf(maximum))

      File(directory, "${"b".repeat(248)}.xml").writeText("<map/>")
      val tooLong =
        shouldThrow<StoreCatalogException> {
          SharedPreferencesFileCatalog.scan(directory)
        }
      (tooLong.code) shouldBe (ProtocolErrorCode.STORE_NAME_UNSUPPORTED)
    }

  private fun `Store 1024件を許可し1025件目で部分catalogを返さない`() =
    withStoreDirectory { directory ->
      repeat(DynamicStoreCatalog.MAX_STORES) { index ->
        File(directory, "store-$index.xml").writeText("<map/>")
      }
      (SharedPreferencesFileCatalog.scan(directory).size) shouldBe (DynamicStoreCatalog.MAX_STORES)

      File(directory, "store-over-limit.xml").writeText("<map/>")
      val error =
        shouldThrow<StoreCatalogException> {
          SharedPreferencesFileCatalog.scan(directory)
        }
      (error.code) shouldBe (ProtocolErrorCode.STORE_CATALOG_LIMIT)
    }

  private fun `backing fileが16MiBを超えるとopen前に拒否する`() =
    withStoreDirectory { directory ->
      val file = File(directory, "oversized.xml")
      RandomAccessFile(file, "rw").use { output ->
        output.setLength(PreferencesSnapshotLimits.MAX_BACKING_FILE_BYTES + 1)
      }
      val backing = SharedPreferencesBackingFiles(directory, "oversized")

      val error =
        shouldThrow<StoreAdapterException> {
          backing.validate()
        }

      (error.code) shouldBe (ProtocolErrorCode.PREFERENCES_SNAPSHOT_LIMIT)
    }
}

class SharedPreferencesStoreAdapterSpec : DescribeSpec() {
  init {
    describe("SharedPreferencesStoreAdapter") {
      context("既存の契約を検証するとき") {
        it("framework標準XMLの6型だけを構造検証し値はSharedPreferences APIから読む") { `framework標準XMLの6型だけを構造検証し値はSharedPreferences APIから読む`() }
        it("破損DTD重複key未知tagをframeworkで空mapに見えてもopen前に拒否する") { `破損DTD重複key未知tagをframeworkで空mapに見えてもopen前に拒否する`() }
        it("bakがあれば破損mainよりbakをauthoritative fileとして検証する") { `bakがあれば破損mainよりbakをauthoritative fileとして検証する`() }
        it("snapshot後にXMLが破損したらmutation直前に再検証してEditorを作らない") { `snapshot後にXMLが破損したらmutation直前に再検証してEditorを作らない`() }
        it("標準6型と空白keyをdefensive copyしcanonical順でsnapshotにする") { `標準6型と空白keyをdefensive copyしcanonical順でsnapshotにする`() }
        it("map順とSet順が変わってもfingerprintは安定し型が変われば変化する") { `map順とSet順が変わってもfingerprintは安定し型が変われば変化する`() }
        it("未知型 null 非String Set要素と不正surrogateを部分表示せず拒否する") { `未知型 null 非String Set要素と不正surrogateを部分表示せず拒否する`() }
        it("65535 byte超Stringをsnapshotでき論理上限超過はstructured errorにする") { `65535 byte超Stringをsnapshotでき論理上限超過はstructured errorにする`() }
        it("fingerprint一致時だけadd edit delete clearをcommit一回ずつ実行する") { `fingerprint一致時だけadd edit delete clearをcommit一回ずつ実行する`() }
        it("全置換はclearと全entryを一個のEditorへ積みcommit一回で反映する") { `全置換はclearと全entryを一個のEditorへ積みcommit一回で反映する`() }
        it("SharedPreferences非対応型を含む全置換はEditor作成前に拒否する") { `SharedPreferences非対応型を含む全置換はEditor作成前に拒否する`() }
        it("fingerprint不一致と型不一致ではEditorを作らない") { `fingerprint不一致と型不一致ではEditorを作らない`() }
        it("commit falseと例外は結果不明で自動再送しない") { `commit falseと例外は結果不明で自動再送しない`() }
        it("commit成功後の再読だけ失敗したらsnapshot unavailableにする") { `commit成功後の再読だけ失敗したらsnapshot unavailableにする`() }
        it("commit前のreadがinterruptを無視してもBUSYでloopを戻し終了まではstage laneを保持する") { `commit前のreadがinterruptを無視してもBUSYでloopを戻し終了まではstage laneを保持する`() }
        it("commit後のreadがtimeoutしてもapplied snapshot unavailableでloopを戻す") { `commit後のreadがtimeoutしてもapplied snapshot unavailableでloopを戻す`() }
        it("commitが期限内に戻らなくても結果不明でloopを戻し終了までは後続をBUSYにする") { `commitが期限内に戻らなくても結果不明でloopを戻し終了までは後続をBUSYにする`() }
        it("block中はglobal laneへqueueせずBUSYを返す") { `block中はglobal laneへqueueせずBUSYを返す`() }
        it("暗号化marker片方でもsnapshotとmutation直前にStore全体を拒否する") { `暗号化marker片方でもsnapshotとmutation直前にStore全体を拒否する`() }
      }
    }
  }
  private fun `framework標準XMLの6型だけを構造検証し値はSharedPreferences APIから読む`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText(
        """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                    <string name="string">value &amp; more</string>
                    <int name="int" value="-1" />
                    <long name="long" value="9223372036854775807" />
                    <float name="float" value="-Infinity" />
                    <boolean name="boolean" value="true" />
                    <set name="set">
                        <string>alpha</string>
                        <string> padded </string>
                    </set>
                </map>
        """.trimIndent()
      )
      val fake =
        FakeSharedPreferences(
          linkedMapOf(
            "string" to "framework-value",
            "int" to -1,
            "long" to Long.MAX_VALUE,
            "float" to Float.NEGATIVE_INFINITY,
            "boolean" to true,
            "set" to linkedSetOf("alpha", " padded ")
          )
        )

      val snapshot = adapter(directory, fake).snapshot()

      val rows = (snapshot.payload as PreferencesTree).root.children
      ((rows.single { it.name == "string" }.value).shouldBeInstanceOf<StringValue>().value) shouldBe ("framework-value")
      (rows.size) shouldBe (6)
    }
  }

  private fun `破損DTD重複key未知tagをframeworkで空mapに見えてもopen前に拒否する`() = runTest {
    val invalidXml =
      listOf(
        "<map><string name=\"survivor\">unterminated",
        """
                <!DOCTYPE map [<!ENTITY recovered "value">]>
                <map><string name="key">&recovered;</string></map>
        """.trimIndent(),
        "<map><string name=\"duplicate\">a</string><int name=\"duplicate\" value=\"1\"/></map>",
        "<map><double name=\"unknown\" value=\"1.0\"/></map>",
        "<map><boolean name=\"invalid\" value=\"not-boolean\"/></map>"
      )
    invalidXml.forEachIndexed { index, xml ->
      withStoreDirectory { directory ->
        storeFile(directory).writeText(xml)
        val fake = FakeSharedPreferences(emptyMap<String, Any>())

        val error =
          shouldThrow<StoreAdapterException> {
            adapter(directory, fake).snapshot()
          }

        withClue("invalid XML case $index") { (error.code) shouldBe (ProtocolErrorCode.STORE_UNSUPPORTED) }
        (fake.readCount) shouldBe (0)
        (fake.editCount) shouldBe (0)
        (fake.commitCount) shouldBe (0)
      }
    }
  }

  private fun `bakがあれば破損mainよりbakをauthoritative fileとして検証する`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText(
        "<map><string name=\"broken\">unterminated"
      )
      File(directory, "$STORE_NAME.xml.bak").writeText(
        "<map><string name=\"safe\">backup</string></map>"
      )
      val fake = FakeSharedPreferences(linkedMapOf("safe" to "framework-backup"))

      val snapshot = adapter(directory, fake).snapshot()

      (((snapshot.payload as PreferencesTree).root.children.single().value).shouldBeInstanceOf<StringValue>().value) shouldBe ("framework-backup")
    }
  }

  private fun `snapshot後にXMLが破損したらmutation直前に再検証してEditorを作らない`() = runTest {
    withStoreDirectory { directory ->
      val file = storeFile(directory)
      file.writeText("<map><int name=\"count\" value=\"1\"/></map>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
      val adapter = adapter(directory, fake)
      val before = adapter.snapshot()
      file.writeText("<map><int name=\"count\" value=\"1\"")

      val error =
        shouldThrow<StoreAdapterException> {
          adapter.write(
            before.fingerprint,
            MutatePreferences(PutPreference("count", IntValue(2)))
          )
        }

      (error.code) shouldBe (ProtocolErrorCode.STORE_UNSUPPORTED)
      (error.operationStarted) shouldBe (false)
      (fake.editCount) shouldBe (0)
      (fake.commitCount) shouldBe (0)
    }
  }

  private fun `標準6型と空白keyをdefensive copyしcanonical順でsnapshotにする`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val mutableSet = linkedSetOf("😀", "alpha", "\u0080")
      val fake =
        FakeSharedPreferences(
          linkedMapOf(
            "long" to Long.MAX_VALUE,
            "" to "empty-key",
            "float" to Float.fromBits(0x80000000.toInt()),
            "boolean" to true,
            "set" to mutableSet,
            "int" to Int.MIN_VALUE,
            "   " to "blank-key"
          )
        )
      val adapter = adapter(directory, fake)

      val snapshot = adapter.snapshot()
      mutableSet += "changed-after-read"
      val root = (snapshot.payload as PreferencesTree).root

      (root.children.map { it.name }) shouldBe (CanonicalUtf8.sorted(fake.stringKeys()))
      (root.children.single { it.name == "" }.value).shouldBeInstanceOf<StringValue>()
      (
        (root.children.single { it.name == "float" }.value).shouldBeInstanceOf<FloatValue>()
          .rawBitsHex
      ) shouldBe ("80000000")
      (
        (root.children.single { it.name == "set" }.value).shouldBeInstanceOf<StringSetValue>()
          .values
      ) shouldBe (CanonicalUtf8.sorted(listOf("😀", "alpha", "\u0080")))
      (
        (root.children.single { it.name == "set" }.value).shouldBeInstanceOf<StringSetValue>()
          .values
          .contains("changed-after-read")
      ) shouldBe false
    }
  }

  private fun `map順とSet順が変わってもfingerprintは安定し型が変われば変化する`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val first =
        adapter(
          directory,
          FakeSharedPreferences(
            linkedMapOf(
              "set" to linkedSetOf("z", "a"),
              "value" to 1
            )
          )
        ).snapshot()
      val reordered =
        adapter(
          directory,
          FakeSharedPreferences(
            linkedMapOf(
              "value" to 1,
              "set" to linkedSetOf("a", "z")
            )
          )
        ).snapshot()
      val changedType =
        adapter(
          directory,
          FakeSharedPreferences(
            linkedMapOf(
              "value" to 1L,
              "set" to linkedSetOf("a", "z")
            )
          )
        ).snapshot()

      (reordered.fingerprint) shouldBe (first.fingerprint)
      (changedType.fingerprint) shouldNotBe (first.fingerprint)
    }
  }

  private fun `未知型 null 非String Set要素と不正surrogateを部分表示せず拒否する`() = runTest {
    val invalidValues =
      listOf(
        linkedMapOf<Any?, Any?>("unknown" to 1.0),
        linkedMapOf<Any?, Any?>("null" to null),
        linkedMapOf<Any?, Any?>("set" to linkedSetOf("valid", 1)),
        linkedMapOf<Any?, Any?>(null to "value"),
        linkedMapOf<Any?, Any?>("\ud800" to "value"),
        linkedMapOf<Any?, Any?>("value" to "\ud800")
      )
    invalidValues.forEachIndexed { index, values ->
      withStoreDirectory { directory ->
        storeFile(directory).writeText("<map/>")
        val error =
          shouldThrow<StoreAdapterException> {
            adapter(directory, FakeSharedPreferences(values)).snapshot()
          }
        withClue("invalid case $index") { (error.code) shouldBe (ProtocolErrorCode.STORE_UNSUPPORTED) }
      }
    }
  }

  private fun `65535 byte超Stringをsnapshotでき論理上限超過はstructured errorにする`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val supported = "x".repeat(70_000)
      val supportedSnapshot =
        adapter(
          directory,
          FakeSharedPreferences(linkedMapOf("large" to supported))
        ).snapshot()
      (((supportedSnapshot.payload as PreferencesTree).root.children.single().value).shouldBeInstanceOf<StringValue>().value) shouldBe (supported)

      val error =
        shouldThrow<StoreAdapterException> {
          adapter(
            directory,
            FakeSharedPreferences(
              linkedMapOf(
                "too-large" to
                  "x".repeat(
                    PreferencesSnapshotLimits.MAX_STRING_UTF8_BYTES + 1
                  )
              )
            )
          ).snapshot()
        }
      (error.code) shouldBe (ProtocolErrorCode.PREFERENCES_SNAPSHOT_LIMIT)
    }
  }

  private fun `fingerprint一致時だけadd edit delete clearをcommit一回ずつ実行する`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1, "remove" to "yes"))
      val adapter = adapter(directory, fake)

      var snapshot = adapter.snapshot()
      (
        adapter.write(
          snapshot.fingerprint,
          MutatePreferences(PutPreference("added", BooleanValue(true)))
        )
      ).shouldBeInstanceOf<AdapterWriteResult.Applied>()
      snapshot = adapter.snapshot()
      (
        adapter.write(
          snapshot.fingerprint,
          MutatePreferences(PutPreference("count", IntValue(2)))
        )
      ).shouldBeInstanceOf<AdapterWriteResult.Applied>()
      snapshot = adapter.snapshot()
      (
        adapter.write(
          snapshot.fingerprint,
          MutatePreferences(DeletePreference("remove"))
        )
      ).shouldBeInstanceOf<AdapterWriteResult.Applied>()
      snapshot = adapter.snapshot()
      (adapter.write(snapshot.fingerprint, ClearPreferences)).shouldBeInstanceOf<AdapterWriteResult.Applied>()

      (fake.commitCount) shouldBe (4)
      (fake.editCount) shouldBe (4)
      (fake.values.isEmpty()) shouldBe true
    }
  }

  private fun `全置換はclearと全entryを一個のEditorへ積みcommit一回で反映する`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1, "remove" to "old"))
      val adapter = adapter(directory, fake)
      val before = adapter.snapshot()

      val result =
        adapter.write(
          before.fingerprint,
          ReplacePreferences(
            listOf(
              PreferenceEntry("count", StringValue("retyped")),
              PreferenceEntry("enabled", BooleanValue(true))
            )
          )
        )

      (result).shouldBeInstanceOf<AdapterWriteResult.Applied>()
      (fake.editCount) shouldBe (1)
      (fake.commitCount) shouldBe (1)
      (fake.values) shouldBe (linkedMapOf<Any?, Any?>("count" to "retyped", "enabled" to true))
    }
  }

  private fun `SharedPreferences非対応型を含む全置換はEditor作成前に拒否する`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
      val adapter = adapter(directory, fake)
      val before = adapter.snapshot()

      val error =
        shouldThrow<StoreAdapterException> {
          adapter.write(
            before.fingerprint,
            ReplacePreferences(
              listOf(
                PreferenceEntry(
                  "double",
                  DoubleValue("3ff0000000000000")
                )
              )
            )
          )
        }

      (error.code) shouldBe (ProtocolErrorCode.TYPE_MISMATCH)
      (fake.editCount) shouldBe (0)
      (fake.commitCount) shouldBe (0)
      (fake.values) shouldBe (linkedMapOf<Any?, Any?>("count" to 1))
    }
  }

  private fun `fingerprint不一致と型不一致ではEditorを作らない`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
      val adapter = adapter(directory, fake)

      (
        adapter.write(
          "stale",
          MutatePreferences(PutPreference("count", IntValue(2)))
        )
      ).shouldBeInstanceOf<AdapterWriteResult.Conflict>()
      val snapshot = adapter.snapshot()
      val typeError =
        shouldThrow<StoreAdapterException> {
          adapter.write(
            snapshot.fingerprint,
            MutatePreferences(PutPreference("count", StringValue("two")))
          )
        }

      (typeError.code) shouldBe (ProtocolErrorCode.TYPE_MISMATCH)
      (fake.editCount) shouldBe (0)
      (fake.commitCount) shouldBe (0)
    }
  }

  private fun `commit falseと例外は結果不明で自動再送しない`() = runTest {
    listOf(CommitBehavior.FALSE, CommitBehavior.THROW).forEach { behavior ->
      withStoreDirectory { directory ->
        storeFile(directory).writeText("<map/>")
        val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
        fake.commitBehavior = behavior
        val adapter = adapter(directory, fake)
        val before = adapter.snapshot()

        val result =
          (
            adapter.write(
              before.fingerprint,
              MutatePreferences(PutPreference("count", IntValue(2)))
            )
          ).shouldBeInstanceOf<AdapterWriteResult.OutcomeUnknown>()

        (fake.commitCount) shouldBe (1)
        (result.currentSnapshot).shouldNotBeNull()
        (fake.values["count"]) shouldBe (2)
      }
    }
  }

  private fun `commit成功後の再読だけ失敗したらsnapshot unavailableにする`() = runTest {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
      fake.failReadsAfterCommit = true
      val adapter = adapter(directory, fake)
      val before = adapter.snapshot()

      val result =
        adapter.write(
          before.fingerprint,
          MutatePreferences(PutPreference("count", IntValue(2)))
        )

      (result).shouldBeInstanceOf<AdapterWriteResult.AppliedSnapshotUnavailable>()
      (fake.commitCount) shouldBe (1)
      (fake.values["count"]) shouldBe (2)
    }
  }

  private fun `commit前のreadがinterruptを無視してもBUSYでloopを戻し終了まではstage laneを保持する`() = runBlocking {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
      val adapter = adapter(directory, fake, stageTimeoutMillis = 200)
      val before = adapter.snapshot()
      val blockedRead = CountDownLatch(1)
      fake.readBlock = blockedRead
      fake.ignoreReadInterrupts = true

      try {
        val timedOut =
          shouldThrow<StoreAdapterException> {
            adapter.write(
              before.fingerprint,
              MutatePreferences(PutPreference("count", IntValue(2)))
            )
          }
        val whileWorkerIsBlocked =
          shouldThrow<StoreAdapterException> {
            adapter.write(
              before.fingerprint,
              MutatePreferences(PutPreference("count", IntValue(2)))
            )
          }

        (timedOut.code) shouldBe (ProtocolErrorCode.BUSY)
        (timedOut.retryable) shouldBe true
        (timedOut.operationStarted) shouldBe (false)
        (whileWorkerIsBlocked.code) shouldBe (ProtocolErrorCode.BUSY)
        (whileWorkerIsBlocked.operationStarted) shouldBe (false)
        (fake.editCount) shouldBe (0)
        (fake.commitCount) shouldBe (0)
      } finally {
        blockedRead.countDown()
        fake.ignoreReadInterrupts = false
        fake.readBlock = null
      }

      val result =
        withTimeout(5_000) {
          while (true) {
            try {
              return@withTimeout adapter.write(
                before.fingerprint,
                MutatePreferences(PutPreference("count", IntValue(2)))
              )
            } catch (error: StoreAdapterException) {
              if (error.code != ProtocolErrorCode.BUSY) throw error
              delay(10)
            }
          }
          error("unreachable")
        }
      (result).shouldBeInstanceOf<AdapterWriteResult.Applied>()
    }
  }

  private fun `commit後のreadがtimeoutしてもapplied snapshot unavailableでloopを戻す`() = runBlocking {
    withStoreDirectory { directory ->
      storeFile(directory).writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
      val adapter = adapter(directory, fake, stageTimeoutMillis = 500)
      val before = adapter.snapshot()
      val blockedRead = CountDownLatch(1)
      fake.readBlockAfterCommit = blockedRead

      val result =
        try {
          adapter.write(
            before.fingerprint,
            MutatePreferences(PutPreference("count", IntValue(2)))
          )
        } finally {
          blockedRead.countDown()
          fake.readBlockAfterCommit = null
        }

      (result).shouldBeInstanceOf<AdapterWriteResult.AppliedSnapshotUnavailable>()
      (fake.commitCount) shouldBe (1)
      (fake.values["count"]) shouldBe (2)
    }
  }

  private fun `commitが期限内に戻らなくても結果不明でloopを戻し終了までは後続をBUSYにする`() =
    runBlocking {
      withStoreDirectory { firstDirectory ->
        withStoreDirectory { secondDirectory ->
          storeFile(firstDirectory).writeText("<map/>")
          storeFile(secondDirectory).writeText("<map/>")
          val blockedCommit = CountDownLatch(1)
          val firstFake = FakeSharedPreferences(linkedMapOf("count" to 1)).apply {
            commitBlock = blockedCommit
          }
          val secondFake = FakeSharedPreferences(linkedMapOf("count" to 1))
          val firstAdapter =
            adapter(
              firstDirectory,
              firstFake,
              commitWaitTimeoutMillis = 200
            )
          val secondAdapter = adapter(secondDirectory, secondFake)
          val firstBefore = firstAdapter.snapshot()
          val secondBefore = secondAdapter.snapshot()

          try {
            val unknown =
              (
                firstAdapter.write(
                  firstBefore.fingerprint,
                  MutatePreferences(PutPreference("count", IntValue(2)))
                )
              ).shouldBeInstanceOf<AdapterWriteResult.OutcomeUnknown>()
            val busy =
              shouldThrow<StoreAdapterException> {
                secondAdapter.write(
                  secondBefore.fingerprint,
                  MutatePreferences(PutPreference("count", IntValue(2)))
                )
              }

            (unknown.currentSnapshot) shouldBe (null)
            (busy.code) shouldBe (ProtocolErrorCode.BUSY)
            (busy.operationStarted) shouldBe (false)
            (secondFake.editCount) shouldBe (0)
          } finally {
            blockedCommit.countDown()
          }

          val secondResult =
            withTimeout(5_000) {
              while (true) {
                try {
                  return@withTimeout secondAdapter.write(
                    secondBefore.fingerprint,
                    MutatePreferences(PutPreference("count", IntValue(2)))
                  )
                } catch (error: StoreAdapterException) {
                  if (error.code != ProtocolErrorCode.BUSY) throw error
                  delay(10)
                }
              }
              error("unreachable")
            }
          (secondResult).shouldBeInstanceOf<AdapterWriteResult.Applied>()
        }
      }
    }

  private fun `block中はglobal laneへqueueせずBUSYを返す`() = runBlocking {
    withStoreDirectory { firstDirectory ->
      withStoreDirectory { secondDirectory ->
        storeFile(firstDirectory).writeText("<map/>")
        storeFile(secondDirectory).writeText("<map/>")
        val firstFake = FakeSharedPreferences(linkedMapOf("count" to 1))
        val secondFake = FakeSharedPreferences(linkedMapOf("count" to 1))
        firstFake.commitBlock = CountDownLatch(1)
        val firstAdapter = adapter(firstDirectory, firstFake)
        val secondAdapter = adapter(secondDirectory, secondFake)
        val firstBefore = firstAdapter.snapshot()
        val secondBefore = secondAdapter.snapshot()

        val blockedWrite =
          async(Dispatchers.Default) {
            firstAdapter.write(
              firstBefore.fingerprint,
              MutatePreferences(PutPreference("count", IntValue(2)))
            )
          }
        (firstFake.commitStarted.await(5, TimeUnit.SECONDS)) shouldBe true
        val busy =
          shouldThrow<StoreAdapterException> {
            secondAdapter.write(
              secondBefore.fingerprint,
              MutatePreferences(PutPreference("count", IntValue(2)))
            )
          }
        firstFake.commitBlock?.countDown()
        (blockedWrite.await()).shouldBeInstanceOf<AdapterWriteResult.Applied>()

        (busy.code) shouldBe (ProtocolErrorCode.BUSY)
        (busy.retryable) shouldBe true
        (busy.operationStarted) shouldBe (false)
        (secondFake.editCount) shouldBe (0)
      }
    }
  }

  private fun `暗号化marker片方でもsnapshotとmutation直前にStore全体を拒否する`() = runTest {
    val markers =
      listOf(
        "__androidx_security_crypto_encrypted_prefs_key_keyset__",
        "__androidx_security_crypto_encrypted_prefs_value_keyset__"
      )
    markers.forEach { marker ->
      withStoreDirectory { directory ->
        storeFile(directory).writeText("<map><string name=\"$marker\">value</string></map>")
        shouldThrow<StoreSnapshotUnsupportedException> {
          adapter(
            directory,
            FakeSharedPreferences(linkedMapOf("visible" to "value"))
          ).snapshot()
        }
      }
    }

    withStoreDirectory { directory ->
      val file = storeFile(directory)
      file.writeText("<map/>")
      val fake = FakeSharedPreferences(linkedMapOf("count" to 1))
      val adapter = adapter(directory, fake)
      val before = adapter.snapshot()
      file.writeText(
        "<map><string name=\"${markers.first()}\">value</string></map>"
      )

      shouldThrow<StoreSnapshotUnsupportedException> {
        adapter.write(
          before.fingerprint,
          MutatePreferences(PutPreference("count", IntValue(2)))
        )
      }
      (fake.editCount) shouldBe (0)
      (fake.commitCount) shouldBe (0)
    }
  }

  private fun adapter(
    directory: File,
    fake: FakeSharedPreferences,
    stageTimeoutMillis: Long = 5_000,
    commitWaitTimeoutMillis: Long = 15_000
  ): SharedPreferencesStoreAdapter =
    SharedPreferencesStoreAdapter(
      SharedPreferencesBackingFiles(directory, STORE_NAME),
      preferences = { fake.instance },
      stageTimeoutMillis = stageTimeoutMillis,
      commitWaitTimeoutMillis = commitWaitTimeoutMillis
    )
}

private enum class CommitBehavior {
  TRUE,
  FALSE,
  THROW
}

private class FakeSharedPreferences(
  initialValues: Map<*, *>
) {
  val values = LinkedHashMap<Any?, Any?>().apply { putAll(initialValues) }
  var readCount: Int = 0
  var editCount: Int = 0
  var commitCount: Int = 0
  var commitBehavior: CommitBehavior = CommitBehavior.TRUE
  var failReadsAfterCommit: Boolean = false
  var readBlock: CountDownLatch? = null
  var readBlockAfterCommit: CountDownLatch? = null
  var ignoreReadInterrupts: Boolean = false
  var commitBlock: CountDownLatch? = null
  val commitStarted = CountDownLatch(1)

  val instance: SharedPreferences =
    proxy(SharedPreferences::class.java) { proxy, method, _ ->
      when (method.name) {
        "getAll" -> {
          readCount++
          readBlock?.let(::awaitReadBlock)
          if (commitCount > 0) readBlockAfterCommit?.let(::awaitReadBlock)
          if (failReadsAfterCommit && commitCount > 0) {
            error("fault-injected getAll failure")
          }
          LinkedHashMap(values)
        }
        "edit" -> {
          editCount++
          editor()
        }
        "contains" -> false
        "getString" -> null
        "getStringSet" -> null
        "getInt" -> 0
        "getLong" -> 0L
        "getFloat" -> 0f
        "getBoolean" -> false
        "registerOnSharedPreferenceChangeListener",
        "unregisterOnSharedPreferenceChangeListener"
        -> Unit
        "equals" -> proxy === proxy
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "FakeSharedPreferences"
        else -> error("Unexpected SharedPreferences call: ${method.name}")
      }
    }

  fun stringKeys(): Set<String> = values.keys.map { it as String }.toSet()

  private fun awaitReadBlock(block: CountDownLatch) {
    while (true) {
      try {
        block.await()
        return
      } catch (error: InterruptedException) {
        if (!ignoreReadInterrupts) throw error
      }
    }
  }

  private fun editor(): SharedPreferences.Editor {
    val changes = LinkedHashMap<String, Any?>()
    val removals = linkedSetOf<String>()
    var clear = false
    lateinit var editorProxy: SharedPreferences.Editor
    editorProxy =
      proxy(SharedPreferences.Editor::class.java) { proxy, method, arguments ->
        val args = arguments.orEmpty()
        when (method.name) {
          "putString",
          "putInt",
          "putLong",
          "putFloat",
          "putBoolean"
          -> {
            changes[args[0] as String] = args[1]
            removals -= args[0] as String
            proxy
          }
          "putStringSet" -> {
            @Suppress("UNCHECKED_CAST")
            changes[args[0] as String] =
              LinkedHashSet(args[1] as Set<String>)
            removals -= args[0] as String
            proxy
          }
          "remove" -> {
            removals += args[0] as String
            changes -= args[0] as String
            proxy
          }
          "clear" -> {
            clear = true
            proxy
          }
          "commit" -> {
            applyChanges(clear, removals, changes)
            commitCount++
            commitStarted.countDown()
            commitBlock?.await()
            when (commitBehavior) {
              CommitBehavior.TRUE -> true
              CommitBehavior.FALSE -> false
              CommitBehavior.THROW -> error("fault-injected commit failure")
            }
          }
          "apply" -> {
            applyChanges(clear, removals, changes)
            Unit
          }
          "equals" -> proxy === editorProxy
          "hashCode" -> System.identityHashCode(proxy)
          "toString" -> "FakeSharedPreferences.Editor"
          else -> error("Unexpected Editor call: ${method.name}")
        }
      }
    return editorProxy
  }

  private fun applyChanges(
    clear: Boolean,
    removals: Set<String>,
    changes: Map<String, Any?>
  ) {
    if (clear) values.clear()
    removals.forEach(values::remove)
    changes.forEach { (key, value) ->
      values[key] =
        if (value is Set<*>) {
          LinkedHashSet(value)
        } else {
          value
        }
    }
  }
}

private fun <T> proxy(
  type: Class<T>,
  handler: (Any, Method, Array<out Any?>?) -> Any?
): T {
  val instance =
    Proxy.newProxyInstance(
      type.classLoader,
      arrayOf(type),
      InvocationHandler(handler)
    )
  return requireNotNull(type.cast(instance))
}

private const val STORE_NAME = "preferences"

private fun storeFile(directory: File): File = File(directory, "$STORE_NAME.xml")

private inline fun withStoreDirectory(block: (File) -> Unit) {
  val directory = Files.createTempDirectory("shared-preferences-runtime-test").toFile()
  try {
    block(directory)
  } finally {
    directory.deleteRecursively()
  }
}
