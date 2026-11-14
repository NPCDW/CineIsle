package app.marlboroadvance.mpvex.domain.postgres

import android.content.Context
import android.util.AtomicFile
import app.marlboroadvance.mpvex.utils.security.CryptoUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** An encrypted whole-config envelope; never pass plaintext to AtomicFile. */
class PostgresConfigStore internal constructor(
  private val file: File,
  private val crypto: PostgresConfigCrypto,
  private val atomicFile: AtomicFile = AtomicFile(file),
) : PostgresConfigStorage {
  constructor(context: Context) : this(
    File(context.applicationContext.noBackupFilesDir, "postgres-config.json"),
    KeystorePostgresConfigCrypto,
  )

  override val sourceChanges: StateFlow<PostgresSourceState> = revisions.asStateFlow()

  override suspend fun load(): PostgresConfig? = withContext(Dispatchers.IO) {
    fileLock.withLock {
      try {
        if (!file.exists() && !File(file.path + ".bak").exists()) return@withLock null
        val bytes = atomicFile.openRead().use { input ->
          val result = java.io.ByteArrayOutputStream()
          val buffer = ByteArray(8192)
          while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size() + count <= MAX_FILE_BYTES)
            result.write(buffer, 0, count)
          }
          result.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString()
        val envelope = json.decodeFromString<EncryptedConfig>(text)
        require(envelope.version == 1 && CryptoUtils.isEncrypted(envelope.payload))
        val plain = crypto.decrypt(envelope.payload)
        require(plain.isNotEmpty() && !CryptoUtils.isEncrypted(plain))
        PostgresConfigParser.validate(json.decodeFromString<PostgresConfig>(plain)).also { loaded ->
          // Cold start publishes the fingerprint after secure disk load, without an artificial save revision.
          if (revisions.value.sourceKey.isEmpty()) revisions.value = revisions.value.copy(sourceKey = loaded.sourceKey())
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // CryptoUtils returns empty on failure and accepts legacy plaintext: both are rejected here.
        throw PostgresException(PostgresFailure.LOAD)
      }
    }
  }

  override suspend fun save(config: PostgresConfig) {
    val validated = PostgresConfigParser.validate(config)
    withContext(Dispatchers.IO) {
      fileLock.withLock {
        var output: FileOutputStream? = null
        try {
          val plain = json.encodeToString(validated)
          val encrypted = crypto.encrypt(plain)
          require(CryptoUtils.isEncrypted(encrypted) && encrypted != plain)
          val bytes = json.encodeToString(EncryptedConfig(payload = encrypted)).toByteArray(Charsets.UTF_8)
          require(bytes.size <= MAX_FILE_BYTES)
          currentCoroutineContext().ensureActive()
          // Do all fallible encryption/serialization before opening the atomic transaction.
          output = atomicFile.startWrite()
          output.write(bytes)
          output.flush()
          // Android's finishWrite logs sync errors instead of throwing: explicitly sync first.
          output.fd.sync()
          currentCoroutineContext().ensureActive()
          atomicFile.finishWrite(output)
          // finishWrite also swallows rename failures. On current Android an uncommitted
          // .new remains; older Android leaves .bak. Never read/delete staging files here.
          require(file.isFile && !File(file.path + ".new").exists() && !File(file.path + ".bak").exists())
          output = null
          revisions.value = PostgresSourceState(revisions.value.revision + 1, validated.sourceKey())
        } catch (e: CancellationException) {
          output?.let { runCatching { atomicFile.failWrite(it) } }
          throw e
        } catch (_: Exception) {
          output?.let { runCatching { atomicFile.failWrite(it) } }
          throw PostgresException(PostgresFailure.STORAGE)
        }
      }
    }
  }

  @Serializable
  private data class EncryptedConfig(val version: Int = 1, val payload: String)

  private companion object {
    const val MAX_FILE_BYTES = 1024 * 1024
    // AtomicFile supplies no locking. Also serialize independently-created stores in this process.
    val fileLock = Mutex()
    val revisions = MutableStateFlow(PostgresSourceState())
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
  }
}

/** Test seam for real AES/GCM tests without pretending Robolectric has Android Keystore. */
internal interface PostgresConfigCrypto {
  fun encrypt(plain: String): String
  fun decrypt(encrypted: String): String
}

private object KeystorePostgresConfigCrypto : PostgresConfigCrypto {
  override fun encrypt(plain: String): String = CryptoUtils.encrypt(plain)
  override fun decrypt(encrypted: String): String = CryptoUtils.decrypt(encrypted)
}
