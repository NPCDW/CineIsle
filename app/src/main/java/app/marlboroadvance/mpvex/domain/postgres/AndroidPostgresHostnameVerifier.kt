package app.marlboroadvance.mpvex.domain.postgres

import java.net.InetAddress
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

/** SAN-only verification: pgjdbc's CN fallback needs javax.naming, unavailable on Android. */
class AndroidPostgresHostnameVerifier : HostnameVerifier {
  override fun verify(hostname: String, session: SSLSession): Boolean = try {
    val cert = session.peerCertificates.firstOrNull() as? X509Certificate
    val host = hostname.removePrefix("[").removeSuffix("]").lowercase(java.util.Locale.ROOT)
    val ip = numericAddress(host)
    cert?.subjectAlternativeNames?.any { entry ->
      if (entry.size != 2) false else when (entry[0]) {
        7 -> ip != null && when (val san = entry[1]) {
          is String -> numericAddress(san)?.contentEquals(ip) == true
          is ByteArray -> san.contentEquals(ip)
          else -> false
        }
        2 -> ip == null && (entry[1] as? String)?.let { matchesDns(host, it) } == true
        else -> false
      }
    } == true
  } catch (_: Exception) {
    false
  }

  internal companion object {
    fun matchesDns(host: String, pattern: String): Boolean {
      val name = host.lowercase(java.util.Locale.ROOT).removeSuffix(".")
      val san = pattern.lowercase(java.util.Locale.ROOT).removeSuffix(".")
      if ('*' !in san) return name == san
      if (!san.startsWith("*.") || san.count { it == '*' } != 1) return false
      val suffix = san.substring(2)
      // No public-suffix-like *.com or partial/multi-label wildcard matching.
      return '.' in suffix && name.substringAfter('.', "") == suffix && name.substringBefore('.').isNotEmpty()
    }

    private fun numericAddress(host: String): ByteArray? {
      if (':' in host) {
        if (host.any { it !in "0123456789abcdefABCDEF:." }) return null
        return runCatching { InetAddress.getByName(host).address }.getOrNull()
      }
      val parts = host.split('.')
      if (parts.size != 4 || parts.any { it.isEmpty() || it.any { ch -> ch !in '0'..'9' } }) return null
      val numbers = parts.map { it.toIntOrNull() ?: return null }
      if (numbers.any { it !in 0..255 }) return null
      return numbers.map { it.toByte() }.toByteArray()
    }
  }
}
