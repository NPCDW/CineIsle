package app.marlboroadvance.mpvex.domain.postgres

import java.lang.reflect.Proxy
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLSession
import org.junit.Assert.*
import org.junit.Test

class AndroidPostgresHostnameVerifierTest {
  @Test fun `DNS name checks exact names and single-label wildcards only`() {
    assertTrue(AndroidPostgresHostnameVerifier.matchesDns("DB.example.org", "db.example.org"))
    assertTrue(AndroidPostgresHostnameVerifier.matchesDns("db.example.org", "*.example.org"))
    assertFalse(AndroidPostgresHostnameVerifier.matchesDns("a.db.example.org", "*.example.org"))
    assertFalse(AndroidPostgresHostnameVerifier.matchesDns("example.org", "*.example.org"))
    assertFalse(AndroidPostgresHostnameVerifier.matchesDns("db.example.org.evil", "*.example.org"))
    assertFalse(AndroidPostgresHostnameVerifier.matchesDns("evil.com", "*.com"))
    assertFalse(AndroidPostgresHostnameVerifier.matchesDns("db.example.org", "d*.example.org"))
  }

  @Test fun `verifies certificate SAN and refuses wrong host or CN-only certificate`() {
    val verifier = AndroidPostgresHostnameVerifier()
    assertTrue(verifier.verify("db.example.org", session(listOf(listOf(2, "db.example.org")))))
    assertFalse(verifier.verify("evil.example.org", session(listOf(listOf(2, "db.example.org")))))
    assertFalse(verifier.verify("db.example.org", session(emptyList())))
    assertTrue(verifier.verify("[2001:db8::1]", session(listOf(listOf(7, "2001:db8:0:0:0:0:0:1")))))
    assertFalse(verifier.verify("2001:db8::2", session(listOf(listOf(7, "2001:db8::1")))))
    assertTrue(verifier.verify("127.0.0.1", session(listOf(listOf(7, "127.0.0.1")))))
    assertFalse(verifier.verify("127.0.0.1", session(listOf(listOf(2, "127.0.0.1")))))
  }

  private fun session(sans: Collection<List<*>>): SSLSession = Proxy.newProxyInstance(
    SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java),
  ) { _, method, _ ->
    if (method.name == "getPeerCertificates") arrayOf<Certificate>(SanCertificate(sans)) else null
  } as SSLSession

  private class SanCertificate(private val sans: Collection<List<*>>) : X509Certificate() {
    override fun getSubjectAlternativeNames(): Collection<List<*>> = sans
    override fun checkValidity() = Unit
    override fun checkValidity(date: Date?) = Unit
    override fun getVersion() = 3
    override fun getSerialNumber(): BigInteger = BigInteger.ONE
    override fun getIssuerDN(): Principal = Principal { "CN=issuer" }
    override fun getSubjectDN(): Principal = Principal { "CN=db.example.org" }
    override fun getNotBefore() = Date(0)
    override fun getNotAfter() = Date(Long.MAX_VALUE)
    override fun getTBSCertificate() = byteArrayOf()
    override fun getSignature() = byteArrayOf()
    override fun getSigAlgName() = "SHA256withRSA"
    override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
    override fun getSigAlgParams(): ByteArray? = null
    override fun getIssuerUniqueID(): BooleanArray? = null
    override fun getSubjectUniqueID(): BooleanArray? = null
    override fun getKeyUsage(): BooleanArray? = null
    override fun getBasicConstraints() = -1
    override fun getEncoded() = byteArrayOf()
    override fun verify(key: PublicKey?) = Unit
    override fun verify(key: PublicKey?, sigProvider: String?) = Unit
    override fun toString() = "SAN test certificate"
    override fun getPublicKey(): PublicKey? = null
    override fun getCriticalExtensionOIDs(): Set<String>? = null
    override fun getNonCriticalExtensionOIDs(): Set<String>? = null
    override fun getExtensionValue(oid: String?): ByteArray? = null
    override fun hasUnsupportedCriticalExtension() = false
  }
}
