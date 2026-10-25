// Portado a Kotlin desde Free My TV (github.com/simbasjungles/free-my-tv, app/src/io/freemytv/Adb.java) vía OmarchyTV
// (gitlab.com/piermark/omarchytv, LocalAdb.java). MIT License, Copyright (c) 2026 Free My TV contributors,
// Copyright (c) 2026 Gathin, Copyright (c) 2026 Marco Porro. Texto de la licencia en NOTICE.
package cl.deco.shelf.service

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Cliente ADB contra el mismo equipo (127.0.0.1:5555, abierto con la depuración por red activa). La primera vez la TV
 * pregunta "¿Permitir la depuración?" y se acepta con "Permitir siempre". Solo lo usa [SelfSetup], con comandos fijos
 * escritos en el código (nunca texto que venga de afuera). La llave RSA vive en el almacenamiento privado y no se registra.
 * Protocolo: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 */
class LocalAdb private constructor(private val socket: Socket) : Closeable {

    private val input = DataInputStream(socket.getInputStream())
    private val output: OutputStream = socket.getOutputStream()
    private var nextLocalId = 1

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** Ejecuta un comando en la shell del equipo y devuelve su salida (stdout y stderr juntos). */
    fun shell(command: String): String {
        val local = nextLocalId++
        send(OPEN, local, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
        var remote = 0
        val result = ByteArrayOutputStream()
        while (true) {
            val m = read()
            if (m.arg1 != local) continue
            when (m.command) {
                OKAY -> remote = m.arg0
                WRTE -> { result.write(m.data); send(OKAY, local, m.arg0, null) }
                CLSE -> {
                    if (remote == 0 && m.arg0 == 0) throw IOException("ADB rechazó el comando")
                    send(CLSE, local, m.arg0, null)
                    return result.toString("UTF-8")
                }
            }
        }
    }

    override fun close() { try { socket.close() } catch (_: IOException) {} }

    private fun read(): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int; val arg0 = b.int; val arg1 = b.int; val length = b.int
        b.int // checksum (adbd moderno no lo valida)
        val magic = b.int
        if (command xor -1 != magic) throw IOException("mensaje ADB inválido")
        if (length < 0 || length > 1024 * 1024) throw IOException("mensaje ADB demasiado grande")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        val length = data?.size ?: 0
        var sum = 0
        data?.forEach { sum += it.toInt() and 0xff }
        val b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command xor -1)
        if (data != null) b.put(data)
        output.write(b.array())
        output.flush()
    }

    companion object {
        private const val CNXN = 0x4e584e43; private const val AUTH = 0x48545541; private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f; private const val CLSE = 0x45534c43; private const val WRTE = 0x45545257
        private const val STLS = 0x534c5453
        private const val VERSION = 0x01000001; private const val MAX_DATA = 256 * 1024
        private const val AUTH_TOKEN = 1; private const val AUTH_SIGNATURE = 2; private const val AUTH_RSAPUBLICKEY = 3
        /** Inicio del bloque PKCS#1 de una firma SHA-1 (adbd trata el token como si fuera el digest). */
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /**
         * Conecta y autentica. Si el equipo aún no conoce la llave, avisa con [onApproval] (la TV muestra el diálogo) y
         * espera hasta [approvalMs] a que se acepte.
         */
        fun connect(ctx: Context, approvalMs: Int, onApproval: (() -> Unit)? = null): LocalAdb {
            val keys = keys(ctx)
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
            } catch (e: IOException) {
                s.close()
                throw IOException("la depuración por red está desactivada (puerto 5555 cerrado)")
            }
            s.soTimeout = 10_000
            val adb = LocalAdb(s)
            try {
                adb.send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII))
                var signed = false
                var offeredKey = false
                while (true) {
                    val m = try { adb.read() } catch (e: SocketTimeoutException) {
                        throw IOException(if (offeredKey) "no se aceptó el permiso de depuración en la TV" else "el ADB del equipo no respondió")
                    }
                    if (m.command == CNXN) { s.soTimeout = 20_000; return adb }
                    if (m.command == STLS) throw IOException("el equipo pide TLS (depuración inalámbrica); usa la depuración por red clásica")
                    if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw IOException("respuesta inesperada de ADB")
                    if (!signed) {
                        adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.private, m.data))
                        signed = true
                    } else if (!offeredKey) {
                        // Llave desconocida: se envía la pública y la TV muestra "¿Permitir la depuración?"
                        adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.public))
                        offeredKey = true
                        onApproval?.invoke()
                        s.soTimeout = approvalMs
                    } else {
                        throw IOException("la TV rechazó la llave de Lienzo")
                    }
                }
            } catch (e: IOException) {
                adb.close()
                throw e
            }
        }

        @Synchronized
        private fun keys(ctx: Context): KeyPair {
            val dir = ctx.filesDir
            val privateFile = File(dir, "adbkey"); val publicFile = File(dir, "adbkey.pub")
            try {
                val factory = KeyFactory.getInstance("RSA")
                if (privateFile.exists() && publicFile.exists()) {
                    try {
                        return KeyPair(factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                            factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())))
                    } catch (_: Exception) {
                        // Archivo dañado (corte de luz a mitad de escritura): llave nueva; la TV vuelve a preguntar
                    }
                }
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                writeFile(privateFile, pair.private.encoded)
                writeFile(publicFile, pair.public.encoded)
                return pair
            } catch (e: GeneralSecurityException) {
                throw IOException("llave ADB: ${e.message}")
            }
        }

        private fun sign(key: PrivateKey, token: ByteArray): ByteArray = try {
            val block = ByteArray(256)
            block[1] = 1
            val separator = block.size - SHA1_PREFIX.size - token.size - 1
            for (i in 2 until separator) block[i] = 0xff.toByte()
            System.arraycopy(SHA1_PREFIX, 0, block, separator + 1, SHA1_PREFIX.size)
            System.arraycopy(token, 0, block, separator + 1 + SHA1_PREFIX.size, token.size)
            Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(block)
        } catch (e: GeneralSecurityException) {
            throw IOException("firma ADB: ${e.message}")
        }

        /** Llave pública en el formato de adbd (struct RSAPublicKey en base64 más un nombre). */
        private fun publicKeyBlob(key: PublicKey): ByteArray {
            val rsa = key as RSAPublicKey
            val n = rsa.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
            val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
            val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(64)
            b.putInt(n0inv.toInt())
            b.put(littleEndian(n, 256))
            b.put(littleEndian(rr, 256))
            b.putInt(rsa.publicExponent.toInt())
            return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " lienzo@tv\u0000").toByteArray(Charsets.US_ASCII)
        }

        private fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val big = value.toByteArray()
            val little = ByteArray(size)
            var i = 0
            while (i < size && i < big.size) { little[i] = big[big.size - 1 - i]; i++ }
            return little
        }

        /** Todo o nada: escribe al lado, sincroniza a disco y recién entonces renombra. */
        private fun writeFile(f: File, data: ByteArray) {
            val tmp = File(f.path + ".tmp")
            java.io.FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
            if (!tmp.renameTo(f)) throw IOException("no se pudo guardar ${f.name}")
        }
    }
}
