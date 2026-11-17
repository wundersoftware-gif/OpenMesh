package com.openmesh.transport

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * A node identity: a private key and a self-signed X.509 certificate stored in
 * a PKCS#12 keystore. The certificate fingerprint is the value users compare
 * out of band before a client trusts a gateway.
 *
 * P2 uses a software key stored in app-private storage. Moving the key into the
 * Android Keystore is tracked as a P2 hardening item; no custom cryptography is
 * used here: the certificate is signed with Bouncy Castle and the transport is
 * JSSE TLS 1.3.
 */
class TlsIdentity(
    val keyStore: KeyStore,
    val alias: String,
    val password: CharArray,
    val certificate: X509Certificate,
) {
    val fingerprint: String
        get() = Fingerprint.of(certificate)

    fun save(file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            keyStore.store(output, password)
        }
    }

    companion object {
        private const val ALIAS = "openmesh-node"
        private const val DEFAULT_PASSWORD = "openmesh-pkcs12"

        fun generate(commonName: String): TlsIdentity {
            val provider = BouncyCastleProvider()
            val random = SecureRandom()
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), random)
            val keyPair = generator.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 60 * 60 * 1000)
            val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000)
            val serial = BigInteger(160, random).abs().add(BigInteger.ONE)
            val subject = X500Name("CN=$commonName")

            val builder = JcaX509v3CertificateBuilder(
                subject,
                serial,
                notBefore,
                notAfter,
                subject,
                keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature),
            )
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(
                    arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth),
                ),
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(provider)
                .build(keyPair.private)
            val certificate = JcaX509CertificateConverter()
                .setProvider(provider)
                .getCertificate(builder.build(signer))
            certificate.checkValidity()

            val password = DEFAULT_PASSWORD.toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, password)
            keyStore.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))
            return TlsIdentity(keyStore, ALIAS, password, certificate)
        }

        fun load(file: File, password: CharArray): TlsIdentity {
            val keyStore = KeyStore.getInstance("PKCS12")
            FileInputStream(file).use { input ->
                keyStore.load(input, password)
            }
            val alias = keyStore.aliases().toList().firstOrNull()
                ?: throw IllegalStateException("keystore contains no identities")
            val certificate = keyStore.getCertificate(alias) as? X509Certificate
                ?: throw IllegalStateException("keystore entry $alias has no X.509 certificate")
            return TlsIdentity(keyStore, alias, password, certificate)
        }
    }
}
