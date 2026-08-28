package com.masaibar.datastore.inspector.runtime.sharedpreferences

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import com.masaibar.datastore.inspector.protocol.CanonicalUtf8
import com.masaibar.datastore.inspector.protocol.PreferenceValueTypeIds
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import com.masaibar.datastore.inspector.runtime.core.CatalogStoreCandidate
import com.masaibar.datastore.inspector.runtime.core.DynamicStoreCatalog
import com.masaibar.datastore.inspector.runtime.core.InternalDataStoreInspectorApi
import com.masaibar.datastore.inspector.runtime.core.PreferencesSnapshotLimits
import com.masaibar.datastore.inspector.runtime.core.StoreAdapterException
import com.masaibar.datastore.inspector.runtime.core.StoreCatalogException
import com.masaibar.datastore.inspector.runtime.core.StoreCatalogProvider
import com.masaibar.datastore.inspector.runtime.core.StoreSemanticIdentity
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.FilterInputStream
import java.io.FilterReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.parsers.SAXParserFactory

@InternalDataStoreInspectorApi
public class SharedPreferencesStoreCatalogProvider() : StoreCatalogProvider {
  override val providerId: String = "shared-preferences-v1"
  override val requiredCapabilities: Set<String> =
    setOf(ProtocolCapabilities.SHARED_PREFERENCES_INSPECT)

  private var sharedPreferencesDirectory: File? = null
  private var opener: ((String) -> SharedPreferences)? = null

  internal constructor(
    directory: File,
    opener: (String) -> SharedPreferences
  ) : this() {
    sharedPreferencesDirectory = directory
    this.opener = opener
  }

  override fun initialize(context: Context): StoreCatalogProvider {
    val applicationContext = context.applicationContext
    sharedPreferencesDirectory =
      File(applicationContext.applicationInfo.dataDir, SHARED_PREFERENCES_DIRECTORY)
    opener = { logicalName ->
      applicationContext.getSharedPreferences(logicalName, Context.MODE_PRIVATE)
    }
    return this
  }

  override fun scan(processName: String): List<CatalogStoreCandidate> {
    val directory =
      sharedPreferencesDirectory
        ?: throw StoreCatalogException(ProtocolErrorCode.STORE_ERROR)
    val open =
      opener
        ?: throw StoreCatalogException(ProtocolErrorCode.STORE_ERROR)
    return SharedPreferencesFileCatalog.scan(directory).map { logicalName ->
      CatalogStoreCandidate(
        identity =
          StoreSemanticIdentity(
            backend = StoreBackend.SHARED_PREFERENCES,
            storageScope = StorageScope.CREDENTIAL_PROTECTED,
            processName = processName,
            logicalName = logicalName
          ),
        name = logicalName,
        fileName = "$logicalName.xml",
        kind = StoreKind.PREFERENCES,
        semantics = SHARED_PREFERENCES_SEMANTICS,
        capabilities = SHARED_PREFERENCES_CAPABILITIES,
        incarnationToken = "shared-preferences-file",
        openAdapter = {
          SharedPreferencesStoreAdapter(
            backingFiles = SharedPreferencesBackingFiles(directory, logicalName),
            preferences = { open(logicalName) }
          )
        }
      )
    }
  }

  private companion object {
    const val SHARED_PREFERENCES_DIRECTORY: String = "shared_prefs"
  }
}

internal val SHARED_PREFERENCES_SEMANTICS =
  StoreSemantics(
    backend = StoreBackend.SHARED_PREFERENCES,
    storageScope = StorageScope.CREDENTIAL_PROTECTED,
    supportedValueTypes = PreferenceValueTypeIds.SHARED_PREFERENCES,
    writeConsistency = WriteConsistency.BEST_EFFORT_NON_ATOMIC
  )

internal val SHARED_PREFERENCES_CAPABILITIES =
  setOf(
    StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
    StoreCapability(ProtocolCapabilities.PREFERENCES_WRITE),
    StoreCapability(ProtocolCapabilities.PREFERENCES_REPLACE),
    StoreCapability(ProtocolCapabilities.STORE_RESET),
    StoreCapability(ProtocolCapabilities.STORE_CHANGES)
  )

@TargetApi(26)
internal object SharedPreferencesFileCatalog {
  fun scan(directory: File): List<String> {
    val directoryPath = directory.toPath().toAbsolutePath().normalize()
    if (!Files.exists(directoryPath, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    if (
      Files.isSymbolicLink(directoryPath) ||
      !Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS)
    ) {
      throw StoreCatalogException(ProtocolErrorCode.STORE_ERROR)
    }
    val logicalNames = linkedSetOf<String>()
    try {
      Files.newDirectoryStream(directoryPath).use { stream ->
        for (path in stream) {
          val normalized = path.toAbsolutePath().normalize()
          if (normalized.parent != directoryPath) continue
          val attributes =
            Files.readAttributes(
              normalized,
              BasicFileAttributes::class.java,
              LinkOption.NOFOLLOW_LINKS
            )
          if (!attributes.isRegularFile || Files.isSymbolicLink(normalized)) continue
          val fileName = normalized.fileName.toString()
          val logicalName =
            when {
              fileName.endsWith(XML_BACKUP_SUFFIX) ->
                fileName.removeSuffix(XML_BACKUP_SUFFIX)
              fileName.endsWith(XML_SUFFIX) ->
                fileName.removeSuffix(XML_SUFFIX)
              else -> continue
            }
          validateLogicalName(logicalName)
          logicalNames += logicalName
          if (logicalNames.size > DynamicStoreCatalog.MAX_STORES) {
            throw StoreCatalogException(ProtocolErrorCode.STORE_CATALOG_LIMIT)
          }
        }
      }
    } catch (error: StoreCatalogException) {
      throw error
    } catch (error: Throwable) {
      throw StoreCatalogException(
        ProtocolErrorCode.STORE_ERROR,
        retryable = true,
        cause = error
      )
    }
    return CanonicalUtf8.sorted(logicalNames)
  }

  private fun validateLogicalName(logicalName: String) {
    val size =
      if (CanonicalUtf8.isWellFormed(logicalName)) {
        logicalName.encodeToByteArray().size
      } else {
        -1
      }
    if (
      size !in MIN_LOGICAL_NAME_BYTES..MAX_LOGICAL_NAME_BYTES ||
      logicalName == "." ||
      logicalName == ".." ||
      '/' in logicalName ||
      '\\' in logicalName
    ) {
      throw StoreCatalogException(ProtocolErrorCode.STORE_NAME_UNSUPPORTED)
    }
  }

  private const val XML_SUFFIX: String = ".xml"
  private const val XML_BACKUP_SUFFIX: String = ".xml.bak"
  private const val MIN_LOGICAL_NAME_BYTES: Int = 1
  private const val MAX_LOGICAL_NAME_BYTES: Int = 247
}

@TargetApi(26)
internal class SharedPreferencesBackingFiles(
  directory: File,
  private val logicalName: String
) {
  private val directory: Path = directory.toPath().toAbsolutePath().normalize()

  fun validate(): List<Path> {
    if (
      !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
      Files.isSymbolicLink(directory)
    ) {
      unavailable()
    }
    val paths =
      listOf(
        directory.resolve("$logicalName.xml"),
        directory.resolve("$logicalName.xml.bak")
      ).filter { path ->
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.parent != directory) unavailable()
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
          false
        } else {
          val attributes =
            try {
              Files.readAttributes(
                normalized,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
              )
            } catch (error: Throwable) {
              throw StoreAdapterException(
                ProtocolErrorCode.STORE_ERROR,
                cause = error
              )
            }
          if (
            !attributes.isRegularFile ||
            Files.isSymbolicLink(normalized)
          ) {
            unavailable()
          }
          if (attributes.size() > PreferencesSnapshotLimits.MAX_BACKING_FILE_BYTES) {
            throw StoreAdapterException(
              ProtocolErrorCode.PREFERENCES_SNAPSHOT_LIMIT
            )
          }
          true
        }
      }
    if (paths.isEmpty()) unavailable()
    return paths
  }

  fun containsEncryptedMarker(): Boolean =
    listOf(authoritativeFile()).any { path ->
      ENCRYPTED_MARKERS.any { marker -> contains(path, marker.encodeToByteArray()) }
    }

  fun validateStandardXml() {
    SharedPreferencesXmlValidator.validate(authoritativeFile())
  }

  private fun authoritativeFile(): Path {
    val paths = validate()
    val backup = directory.resolve("$logicalName.xml.bak")
    return paths.firstOrNull { it == backup }
      ?: paths.firstOrNull { it == directory.resolve("$logicalName.xml") }
      ?: unavailable()
  }

  private fun contains(path: Path, marker: ByteArray): Boolean {
    try {
      Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8 * 1024)
        var retained = ByteArray(0)
        var total = 0L
        while (true) {
          val read = input.read(buffer)
          if (read < 0) return false
          total += read
          if (total > PreferencesSnapshotLimits.MAX_BACKING_FILE_BYTES) {
            throw StoreAdapterException(
              ProtocolErrorCode.PREFERENCES_SNAPSHOT_LIMIT
            )
          }
          val candidate = retained + buffer.copyOf(read)
          if (candidate.contains(marker)) return true
          val retainCount = minOf(marker.size - 1, candidate.size)
          retained =
            candidate.copyOfRange(
              candidate.size - retainCount,
              candidate.size
            )
        }
      }
    } catch (error: StoreAdapterException) {
      throw error
    } catch (error: Exception) {
      throw StoreAdapterException(
        ProtocolErrorCode.STORE_ERROR,
        retryable = true,
        cause = error
      )
    }
  }

  private fun ByteArray.contains(expected: ByteArray): Boolean {
    if (expected.isEmpty()) return true
    if (size < expected.size) return false
    return (0..size - expected.size).any { offset ->
      expected.indices.all { index -> this[offset + index] == expected[index] }
    }
  }

  private fun unavailable(): Nothing =
    throw StoreAdapterException(ProtocolErrorCode.STORE_NOT_FOUND)

  private companion object {
    val ENCRYPTED_MARKERS =
      listOf(
        "__androidx_security_crypto_encrypted_prefs_key_keyset__",
        "__androidx_security_crypto_encrypted_prefs_value_keyset__"
      )
  }
}

/**
 * SharedPreferencesImplはXML parse失敗を空mapとして公開するため、framework APIだけでは
 * 「正当な空Store」と「破損して空に見えるStore」を区別できない。値は引き続きframework APIを
 * 正本とし、ここではframeworkが生成する標準6型の構造だけをboundedかつfail-closedに検証する。
 */
@TargetApi(26)
private object SharedPreferencesXmlValidator {
  fun validate(path: Path) {
    val parser =
      try {
        SAXParserFactory.newInstance().apply {
          isNamespaceAware = false
          isValidating = false
        }.newSAXParser().xmlReader
      } catch (error: Exception) {
        throw StoreAdapterException(
          ProtocolErrorCode.STORE_ERROR,
          operationStarted = false,
          cause = error
        )
      }
    parser.contentHandler = StandardPreferencesHandler()
    parser.entityResolver = { _, _ -> throw InvalidPreferencesXmlException() }
    try {
      Files.newInputStream(path).use { input ->
        val bounded =
          BoundedInputStream(
            input,
            PreferencesSnapshotLimits.MAX_BACKING_FILE_BYTES
          )
        val decoder =
          StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader =
          DeclarationRejectingReader(
            InputStreamReader(bounded, decoder)
          )
        parser.parse(InputSource(reader))
      }
    } catch (error: Throwable) {
      when {
        error.hasCause<PreferencesXmlLimitException>() ->
          throw StoreAdapterException(
            ProtocolErrorCode.PREFERENCES_SNAPSHOT_LIMIT,
            operationStarted = false,
            cause = error
          )
        error is SAXException ||
          error.hasCause<InvalidPreferencesXmlException>() ||
          error.hasCause<InvalidPreferencesXmlIOException>() ||
          error.hasCause<java.nio.charset.CharacterCodingException>() ->
          throw StoreAdapterException(
            ProtocolErrorCode.STORE_UNSUPPORTED,
            operationStarted = false,
            cause = error
          )
        error is IOException ->
          throw StoreAdapterException(
            ProtocolErrorCode.STORE_ERROR,
            retryable = true,
            operationStarted = false,
            cause = error
          )
        error is StoreAdapterException -> throw error
        else ->
          throw StoreAdapterException(
            ProtocolErrorCode.STORE_ERROR,
            operationStarted = false,
            cause = error
          )
      }
    }
  }

  private class StandardPreferencesHandler : DefaultHandler() {
    private val elements = mutableListOf<Element>()
    private val keys = linkedSetOf<String>()
    private var rootSeen = false
    private var rootClosed = false
    private var entryCount = 0
    private var totalSetElements = 0

    override fun startElement(
      uri: String,
      localName: String,
      qName: String,
      attributes: Attributes
    ) {
      val name = qName.ifEmpty { localName }
      if (uri.isNotEmpty() || rootClosed) invalid()
      if (elements.isEmpty()) {
        if (rootSeen || name != MAP || attributes.length != 0) invalid()
        rootSeen = true
        elements += Element.Map
        return
      }
      when (val parent = elements.last()) {
        Element.Map -> startMapEntry(name, attributes)
        is Element.Set -> {
          if (name != STRING) invalid()
          requireAttributes(attributes, emptySet())
          elements += Element.StringValue(inSet = true)
        }
        is Element.Scalar,
        is Element.StringValue
        -> invalid()
      }
    }

    override fun characters(
      chars: CharArray,
      start: Int,
      length: Int
    ) {
      val current = elements.lastOrNull()
      if (current is Element.StringValue) {
        current.text.append(chars, start, length)
      } else if ((start until start + length).any { !chars[it].isWhitespace() }) {
        invalid()
      }
    }

    override fun endElement(
      uri: String,
      localName: String,
      qName: String
    ) {
      val name = qName.ifEmpty { localName }
      if (uri.isNotEmpty() || elements.isEmpty()) invalid()
      val element = elements.removeAt(elements.lastIndex)
      if (element.tag != name) invalid()
      when (element) {
        Element.Map -> rootClosed = true
        is Element.StringValue -> {
          val value = element.text.toString()
          if (
            value.encodeToByteArray().size >
            PreferencesSnapshotLimits.MAX_STRING_UTF8_BYTES
          ) {
            limit()
          }
          if (element.inSet) {
            val set = elements.lastOrNull() as? Element.Set ?: invalid()
            if (!set.values.add(value)) invalid()
            totalSetElements++
            if (
              set.values.size > PreferencesSnapshotLimits.MAX_SET_ELEMENTS ||
              totalSetElements > PreferencesSnapshotLimits.MAX_TOTAL_SET_ELEMENTS
            ) {
              limit()
            }
          }
        }
        is Element.Scalar,
        is Element.Set
        -> Unit
      }
    }

    override fun endDocument() {
      if (!rootSeen || !rootClosed || elements.isNotEmpty()) invalid()
    }

    private fun startMapEntry(
      tag: String,
      attributes: Attributes
    ) {
      entryCount++
      if (entryCount > PreferencesSnapshotLimits.MAX_ENTRIES) limit()
      val values =
        when (tag) {
          STRING, SET -> requireAttributes(attributes, setOf(NAME))
          INT, LONG, FLOAT, BOOLEAN ->
            requireAttributes(attributes, setOf(NAME, VALUE))
          else -> invalid()
        }
      val key = values.getValue(NAME)
      if (!keys.add(key)) invalid()
      when (tag) {
        STRING -> elements += Element.StringValue(inSet = false)
        SET -> elements += Element.Set()
        INT -> {
          values.getValue(VALUE).toIntOrNull() ?: invalid()
          elements += Element.Scalar(INT)
        }
        LONG -> {
          values.getValue(VALUE).toLongOrNull() ?: invalid()
          elements += Element.Scalar(LONG)
        }
        FLOAT -> {
          values.getValue(VALUE).toFloatOrNull() ?: invalid()
          elements += Element.Scalar(FLOAT)
        }
        BOOLEAN -> {
          if (values.getValue(VALUE) !in setOf("true", "false")) invalid()
          elements += Element.Scalar(BOOLEAN)
        }
      }
    }

    private fun requireAttributes(
      attributes: Attributes,
      expected: Set<String>
    ): Map<String, String> {
      if (attributes.length != expected.size) invalid()
      val values = LinkedHashMap<String, String>(attributes.length)
      repeat(attributes.length) { index ->
        if (attributes.getURI(index).isNotEmpty()) invalid()
        val name = attributes.getQName(index).ifEmpty {
          attributes.getLocalName(index)
        }
        if (name !in expected || values.put(name, attributes.getValue(index)) != null) {
          invalid()
        }
      }
      if (values.keys != expected) invalid()
      return values
    }
  }

  private sealed interface Element {
    val tag: String

    data object Map : Element {
      override val tag: String = MAP
    }

    data class Scalar(
      override val tag: String
    ) : Element

    data class StringValue(
      val inSet: Boolean,
      val text: StringBuilder = StringBuilder()
    ) : Element {
      override val tag: String = STRING
    }

    data class Set(
      val values: MutableSet<String> = linkedSetOf()
    ) : Element {
      override val tag: String = SET
    }
  }

  private class BoundedInputStream(
    input: InputStream,
    private val limit: Long
  ) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
      val value = super.read()
      if (value >= 0) addConsumed(1)
      return value
    }

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int
    ): Int {
      val read = super.read(buffer, offset, length)
      if (read > 0) addConsumed(read.toLong())
      return read
    }

    private fun addConsumed(count: Long) {
      consumed += count
      if (consumed > limit) throw PreferencesXmlLimitException()
    }
  }

  // Android frameworkのserializerはDTD・entity宣言・comment・CDATAを生成しない。
  // "<!" をreader段階で拒否し、parser実装ごとの外部entity feature差に依存しない。
  private class DeclarationRejectingReader(
    reader: Reader
  ) : FilterReader(reader) {
    private var previous: Char? = null

    override fun read(): Int {
      val value = super.read()
      if (value >= 0) inspect(value.toChar())
      return value
    }

    override fun read(
      buffer: CharArray,
      offset: Int,
      length: Int
    ): Int {
      val read = super.read(buffer, offset, length)
      if (read > 0) {
        (offset until offset + read).forEach { index -> inspect(buffer[index]) }
      }
      return read
    }

    private fun inspect(value: Char) {
      if (previous == '<' && value == '!') throw InvalidPreferencesXmlIOException()
      previous = value
    }
  }

  private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
      if (current is T) return true
      current = current.cause
    }
    return false
  }

  private fun invalid(): Nothing = throw InvalidPreferencesXmlException()

  private fun limit(): Nothing = throw PreferencesXmlLimitException()

  private class InvalidPreferencesXmlException : SAXException()
  private class InvalidPreferencesXmlIOException : IOException()
  private class PreferencesXmlLimitException : IOException()

  private const val MAP = "map"
  private const val STRING = "string"
  private const val INT = "int"
  private const val LONG = "long"
  private const val FLOAT = "float"
  private const val BOOLEAN = "boolean"
  private const val SET = "set"
  private const val NAME = "name"
  private const val VALUE = "value"
}
