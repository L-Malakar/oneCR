package com.lmalakar.onecr.privilege.embeddedadb

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

/**
 * TLS session plumbing shared by the pairing and connect paths.
 *
 * Android adb wireless debugging is TLS 1.3-only (tls_connection.cpp pins
 * min=max=TLS1_3_VERSION). The peer certificate is intentionally not verified
 * (adb's SetCertVerifyCallback accepts anything) — authentication happens at
 * the protocol layer via the SPAKE2 pairing and the public-key exchange.
 *
 * Conscrypt is bundled because the pairing flow needs `SSL_export_keying_material`
 * (RFC 5705), which the platform TLS API does not expose — but Conscrypt does
 * (Conscrypt.exportKeyingMaterial), and Android's TLS stack IS Conscrypt.
 */
internal object TlsSupport {

    /**
     * TLS exporter label used by adb: "adb-label" as a C string literal, so the
     * byte length INCLUDES the NUL terminator (sizeof in tls_connection.cpp).
     */
    const val EXPORTER_LABEL = "adb-label\u0000"
    const val EXPORTED_KEY_LENGTH = 64

    private val conscryptProvider: java.security.Provider? by lazy {
        runCatching { Conscrypt.newProvider() }
            .onFailure { android.util.Log.w("TlsSupport", "Conscrypt unavailable: ${it.message}") }
            .getOrNull()
    }

    /** True when the bundled Conscrypt (and therefore the TLS exporter) is usable. */
    val hasExporter: Boolean get() = conscryptProvider != null

    /**
     * Creates a connected, handshaken TLS 1.3 client socket presenting [identity]'s
     * certificate, trusting any server certificate.
     */
    fun connect(identity: AdbIdentity, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        val ssl = context(identity).socketFactory.createSocket(
            raw, host, port, true,
        ) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return ssl
    }

    private fun context(identity: AdbIdentity): SSLContext {
        val provider = conscryptProvider
        val ctx = if (provider != null) {
            SSLContext.getInstance("TLSv1.3", provider)
        } else {
            SSLContext.getInstance("TLSv1.3")
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identityKeyStore(identity), CharArray(0))

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        ctx.init(kmf.keyManagers, trustAll, SecureRandom())
        return ctx
    }

    private fun identityKeyStore(identity: AdbIdentity): KeyStore {
        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(
            ByteArrayInputStream(identity.selfSignedCertificate()),
        ) as X509Certificate

        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }
        ks.setKeyEntry("adbkey", identity.privateKey, CharArray(0), arrayOf(cert))
        return ks
    }

    /**
     * RFC 5705 exporter — must be called after the handshake. Returns null when
     * the bundled Conscrypt could not produce the material.
     */
    fun exportKeyingMaterial(socket: SSLSocket): ByteArray? = runCatching {
        val provider = conscryptProvider ?: return@runCatching null
        Security.addProvider(provider)
        Conscrypt.exportKeyingMaterial(socket, EXPORTER_LABEL, null, EXPORTED_KEY_LENGTH)
    }.getOrNull()

    /** Registers the bundled Conscrypt provider (idempotent, best effort). */
    fun installProvider() {
        conscryptProvider?.let { runCatching { Security.insertProviderAt(it, 1) } }
    }
}
