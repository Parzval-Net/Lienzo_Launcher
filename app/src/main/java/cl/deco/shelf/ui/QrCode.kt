package cl.deco.shelf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs

/**
 * Generador compacto de códigos QR (ISO/IEC 18004, versiones 1 a 6, modo Byte, ECC L) en Kotlin puro.
 * Cero dependencias externas. Suficiente para URLs LAN con token (hasta 134 bytes UTF-8).
 */
object QrCode {

    class Matrix(val size: Int, val modules: BooleanArray) {
        fun get(x: Int, y: Int): Boolean = modules[y * size + x]
    }

    private class Spec(
        val version: Int,
        val totalBytes: Int,
        val dataBytes: Int,
        val ecPerBlock: Int,
        val blocks: Int,
        val alignCoords: IntArray,
        val remainderBits: Int,
    )

    private val SPECS = listOf(
        Spec(1, 26, 19, 7, 1, intArrayOf(), 0),
        Spec(2, 44, 34, 10, 1, intArrayOf(6, 18), 7),
        Spec(3, 70, 55, 15, 1, intArrayOf(6, 22), 7),
        Spec(4, 100, 80, 20, 1, intArrayOf(6, 26), 7),
        Spec(5, 134, 108, 26, 1, intArrayOf(6, 30), 7),
        Spec(6, 172, 136, 18, 2, intArrayOf(6, 34), 7),
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until 512) GF_EXP[i] = GF_EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else GF_EXP[GF_LOG[a] + GF_LOG[b]]

    private fun rsGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val root = GF_EXP[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], root)
            }
            poly = next
        }
        return poly
    }

    private fun rsCompute(data: ByteArray, ecLen: Int): ByteArray {
        val gen = rsGenerator(ecLen)
        val res = IntArray(data.size + ecLen)
        for (i in data.indices) res[i] = data[i].toInt() and 0xff
        for (i in data.indices) {
            val factor = res[i]
            if (factor != 0) {
                for (j in gen.indices) res[i + j] = res[i + j] xor gfMul(gen[j], factor)
            }
        }
        return ByteArray(ecLen) { idx -> res[data.size + idx].toByte() }
    }

    fun encode(text: String): Matrix {
        val payload = text.toByteArray(Charsets.UTF_8)
        val spec = SPECS.firstOrNull { payload.size <= it.dataBytes - 2 }
            ?: throw IllegalArgumentException("QR demasiado largo (${payload.size} bytes, máx 134)")
        val codewords = encodeData(payload, spec)
        val stream = interleaveBlocks(codewords, spec)

        val size = 17 + 4 * spec.version
        val mod = BooleanArray(size * size)
        val func = BooleanArray(size * size)

        drawFinder(mod, func, size, 0, 0)
        drawFinder(mod, func, size, size - 7, 0)
        drawFinder(mod, func, size, 0, size - 7)
        drawTiming(mod, func, size)
        drawAlignments(mod, func, size, spec.alignCoords)
        // Módulo oscuro fijo en (8, 4*v + 9)
        setFunc(mod, func, size, 8, 4 * spec.version + 9, true)
        reserveFormat(func, size)
        placeData(mod, func, size, stream, spec.remainderBits)

        var bestPenalty = Int.MAX_VALUE
        var bestGrid: BooleanArray? = null
        for (mask in 0 until 8) {
            val candidate = mod.copyOf()
            applyMask(candidate, func, size, mask)
            drawFormat(candidate, size, mask)
            val p = penalty(candidate, size)
            if (p < bestPenalty) {
                bestPenalty = p
                bestGrid = candidate
            }
        }
        return Matrix(size, bestGrid!!)
    }

    fun toBitmap(matrix: Matrix, sizePx: Int): Bitmap {
        val quiet = 4
        val totalMods = matrix.size + quiet * 2
        val scale = (sizePx / totalMods).coerceAtLeast(1)
        val dim = totalMods * scale
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK }
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.get(x, y)) continue
                val l = ((x + quiet) * scale).toFloat()
                val t = ((y + quiet) * scale).toFloat()
                canvas.drawRect(l, t, l + scale, t + scale, paint)
            }
        }
        return bmp
    }

    private fun encodeData(payload: ByteArray, spec: Spec): ByteArray {
        val bits = ArrayList<Int>(spec.dataBytes * 8)
        fun push(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value shr i) and 1
        }
        push(0b0100, 4) // modo Byte
        push(payload.size, 8) // longitudes 1..134 caben en 8 bits para versiones 1..9
        for (b in payload) push(b.toInt() and 0xff, 8)
        val capBits = spec.dataBytes * 8
        val term = minOf(4, capBits - bits.size)
        repeat(term) { bits += 0 }
        while (bits.size % 8 != 0) bits += 0
        val out = ByteArray(spec.dataBytes)
        var byteIdx = 0
        while (byteIdx * 8 < bits.size) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bits[byteIdx * 8 + b]
            out[byteIdx++] = v.toByte()
        }
        var pad = 0xEC
        while (byteIdx < spec.dataBytes) {
            out[byteIdx++] = pad.toByte()
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        return out
    }

    private fun interleaveBlocks(data: ByteArray, spec: Spec): ByteArray {
        val blockSize = spec.dataBytes / spec.blocks
        val dataBlocks = Array(spec.blocks) { i -> data.copyOfRange(i * blockSize, (i + 1) * blockSize) }
        val ecBlocks = Array(spec.blocks) { i -> rsCompute(dataBlocks[i], spec.ecPerBlock) }
        val out = ByteArray(spec.totalBytes)
        var idx = 0
        for (j in 0 until blockSize) for (b in 0 until spec.blocks) out[idx++] = dataBlocks[b][j]
        for (j in 0 until spec.ecPerBlock) for (b in 0 until spec.blocks) out[idx++] = ecBlocks[b][j]
        return out
    }

    private fun setFunc(mod: BooleanArray, func: BooleanArray, size: Int, x: Int, y: Int, v: Boolean) {
        val i = y * size + x
        mod[i] = v
        func[i] = true
    }

    private fun drawFinder(mod: BooleanArray, func: BooleanArray, size: Int, ox: Int, oy: Int) {
        for (dy in -1..7) for (dx in -1..7) {
            val x = ox + dx; val y = oy + dy
            if (x !in 0 until size || y !in 0 until size) continue
            val dark = (dx in 0..6 && dy in 0..6) && (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
            setFunc(mod, func, size, x, y, dark)
        }
    }

    private fun drawTiming(mod: BooleanArray, func: BooleanArray, size: Int) {
        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            if (!func[6 * size + i]) setFunc(mod, func, size, i, 6, dark)
            if (!func[i * size + 6]) setFunc(mod, func, size, 6, i, dark)
        }
    }

    private fun drawAlignments(mod: BooleanArray, func: BooleanArray, size: Int, coords: IntArray) {
        if (coords.isEmpty()) return
        for (cy in coords) for (cx in coords) {
            if (func[cy * size + cx]) continue // solapa con finder pattern
            for (dy in -2..2) for (dx in -2..2) {
                val dark = maxOf(abs(dx), abs(dy)) != 1
                setFunc(mod, func, size, cx + dx, cy + dy, dark)
            }
        }
    }

    private fun reserveFormat(func: BooleanArray, size: Int) {
        for (i in 0..8) {
            if (i != 6) {
                func[8 * size + i] = true
                func[i * size + 8] = true
            }
        }
        for (i in size - 8 until size) func[8 * size + i] = true
        for (i in size - 7 until size) func[i * size + 8] = true
    }

    private fun placeData(mod: BooleanArray, func: BooleanArray, size: Int, data: ByteArray, remainderBits: Int) {
        var bitIdx = 0
        val totalBits = data.size * 8
        var up = true
        var x = size - 1
        while (x > 0) {
            if (x == 6) x--
            for (step in 0 until size) {
                val y = if (up) size - 1 - step else step
                for (dx in 0..1) {
                    val cx = x - dx
                    val idx = y * size + cx
                    if (func[idx]) continue
                    val bit = if (bitIdx < totalBits) ((data[bitIdx shr 3].toInt() ushr (7 - (bitIdx and 7))) and 1) == 1 else false
                    if (bitIdx < totalBits + remainderBits) bitIdx++
                    mod[idx] = bit
                }
            }
            up = !up
            x -= 2
        }
    }

    private fun applyMask(mod: BooleanArray, func: BooleanArray, size: Int, mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val idx = y * size + x
            if (func[idx]) continue
            val flip = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> ((y / 2) + (x / 3)) % 2 == 0
                5 -> (x * y) % 2 + (x * y) % 3 == 0
                6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
            }
            if (flip) mod[idx] = !mod[idx]
        }
    }

    private fun drawFormat(mod: BooleanArray, size: Int, mask: Int) {
        val data = (0b01 shl 3) or mask // ECC L = 01
        var rem = data shl 10
        for (i in 4 downTo 0) if ((rem shr (i + 10)) and 1 != 0) rem = rem xor (0x537 shl i)
        val bits = ((data shl 10) or rem) xor 0x5412

        for (i in 0..5) mod[i * size + 8] = ((bits shr i) and 1) == 1
        mod[7 * size + 8] = ((bits shr 6) and 1) == 1
        mod[8 * size + 8] = ((bits shr 7) and 1) == 1
        mod[8 * size + 7] = ((bits shr 8) and 1) == 1
        for (i in 9..14) mod[8 * size + (14 - i)] = ((bits shr i) and 1) == 1

        for (i in 0..7) mod[8 * size + (size - 1 - i)] = ((bits shr i) and 1) == 1
        for (i in 8..14) mod[(size - 15 + i) * size + 8] = ((bits shr i) and 1) == 1
    }

    private fun penalty(mod: BooleanArray, size: Int): Int {
        var score = 0
        // N1: filas y columnas con >= 5 módulos iguales seguidos
        for (y in 0 until size) {
            var run = 1
            for (x in 1 until size) {
                if (mod[y * size + x] == mod[y * size + x - 1]) run++
                else { if (run >= 5) score += 3 + (run - 5); run = 1 }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        for (x in 0 until size) {
            var run = 1
            for (y in 1 until size) {
                if (mod[y * size + x] == mod[(y - 1) * size + x]) run++
                else { if (run >= 5) score += 3 + (run - 5); run = 1 }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        // N2: bloques 2x2 iguales
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = mod[y * size + x]
            if (mod[y * size + x + 1] == c && mod[(y + 1) * size + x] == c && mod[(y + 1) * size + x + 1] == c) score += 3
        }
        // N3: patrón 1:1:3:1:1
        fun hasFinderPattern(line: (Int) -> Boolean): Int {
            var hits = 0
            for (i in 0..size - 11) {
                val p1 = line(i) && !line(i + 1) && line(i + 2) && line(i + 3) && line(i + 4) && !line(i + 5) && line(i + 6) &&
                    !line(i + 7) && !line(i + 8) && !line(i + 9) && !line(i + 10)
                val p2 = !line(i) && !line(i + 1) && !line(i + 2) && !line(i + 3) &&
                    line(i + 4) && !line(i + 5) && line(i + 6) && line(i + 7) && line(i + 8) && !line(i + 9) && line(i + 10)
                if (p1 || p2) hits += 40
            }
            return hits
        }
        for (y in 0 until size) score += hasFinderPattern { x -> mod[y * size + x] }
        for (x in 0 until size) score += hasFinderPattern { y -> mod[y * size + x] }
        // N4: proporción de oscuros respecto al 50%
        val dark = mod.count { it }
        val pct = (dark * 100) / mod.size
        score += (abs(pct - 50) / 5) * 10
        return score
    }
}
