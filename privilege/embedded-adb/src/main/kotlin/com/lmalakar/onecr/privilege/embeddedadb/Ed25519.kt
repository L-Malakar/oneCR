package com.lmalakar.onecr.privilege.embeddedadb

import java.math.BigInteger

/**
 * Minimal edwards25519 point arithmetic (extended coordinates) — exactly what the
 * BoringSSL SPAKE2 variant used by Android's adb wireless-debugging pairing needs.
 *
 * Field p = 2^255 - 19; group order l = 2^252 + 27742317777372353535851937790883648493;
 * a = -1; complete unified addition (RFC 8032 §5.1.4).
 *
 * BigInteger field arithmetic is far slower than the production 64-bit-limb code but
 * a pairing does a handful of scalar multiplications, so wall-clock cost is negligible.
 */
internal object Ed25519 {

    val p: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    val l: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    private val d: BigInteger =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(p)).mod(p)

    /** Base point B (standard ed25519 generator). */
    val basePoint: Point = run {
        val x = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
        val y = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
        Point(x, y, BigInteger.ONE, x.multiply(y))
    }

    private val sqrtM1: BigInteger = BigInteger.TWO
        .modPow(p.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), p)

    /** Extended-coordinate point (X : Y : Z : T = XY/Z). */
    data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        companion object {
            /** Identity (0 : 1 : 1 : 0). */
            val IDENTITY = Point(
                BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO,
            )
        }
    }

    // ------------------------------------------------------------------
    // Field helpers
    // ------------------------------------------------------------------

    private fun add(a: BigInteger, b: BigInteger): BigInteger = a.add(b).mod(p)
    private fun sub(a: BigInteger, b: BigInteger): BigInteger = a.subtract(b).mod(p)
    private fun mul(a: BigInteger, b: BigInteger): BigInteger = a.multiply(b).mod(p)
    private fun sq(a: BigInteger): BigInteger = a.multiply(a).mod(p)
    private fun inv(a: BigInteger): BigInteger = a.modInverse(p)

    // ------------------------------------------------------------------
    // Point operations (RFC 8032 §5.1.4)
    // ------------------------------------------------------------------

    fun add(a: Point, b: Point): Point {
        val aa = mul(sub(a.y, a.x), sub(b.y, b.x))
        val bb = mul(add(a.y, a.x), add(b.y, b.x))
        val cc = mul(mul(a.t, b.t), d.multiply(BigInteger.TWO).mod(p))
        val dd = mul(a.z, b.z).multiply(BigInteger.TWO).mod(p)
        val e = sub(bb, aa)
        val f = sub(dd, cc)
        val g = add(dd, cc)
        val h = add(bb, aa)
        return Point(mul(e, f), mul(g, h), mul(f, g), mul(e, h))
    }

    fun double(a: Point): Point {
        val aa = sq(a.x) // A
        val bb = sq(a.y) // B
        val cc = sq(a.z).multiply(BigInteger.TWO).mod(p) // C
        val dd = aa.negate().mod(p) // D = a*A with a = -1
        val e = sq(add(a.x, a.y)).subtract(aa).subtract(bb).mod(p) // E = (X1+Y1)^2 - A - B
        val g = add(dd, bb) // G = D + B = B - A
        val f = sub(g, cc) // F = G - C
        val h = sub(dd, bb) // H = D - B = -(A + B)
        return Point(mul(e, f), mul(g, h), mul(f, g), mul(e, h))
    }

    fun scalarMult(k: BigInteger, a: Point): Point {
        var result = Point.IDENTITY
        var cur = a
        val scalar = k.mod(BigInteger.TWO.pow(256))
        var i = 0
        while (i < 256) {
            if (scalar.testBit(i)) result = add(result, cur)
            cur = double(cur)
            i++
        }
        return result
    }

    // ------------------------------------------------------------------
    // Encode / decode (compressed 32-byte little-endian, RFC 8032 §5.1.2/3)
    // ------------------------------------------------------------------

    fun encode(pt: Point): ByteArray {
        val x = pt.x.multiply(pt.z.modInverse(p)).mod(p)
        val y = pt.y.multiply(pt.z.modInverse(p)).mod(p)
        val out = ByteArray(32)
        val yBytes = toLittleEndian(y)
        System.arraycopy(yBytes, 0, out, 0, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Decodes a compressed point; returns null if invalid / not on the curve. */
    fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val yBytes = bytes.copyOf()
        val xIsOdd = (yBytes[31].toInt() and 0x80) != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = fromLittleEndian(yBytes)
        if (y >= p) return null

        // x^2 = (y^2 - 1) / (d*y^2 + 1)
        val y2 = sq(y)
        val numerator = sub(y2, BigInteger.ONE)
        val denominator = add(mul(d, y2), BigInteger.ONE)
        if (denominator.signum() == 0) return null
        val x2 = mul(numerator, inv(denominator))

        // candidate = x2^((p+3)/8); fix with sqrt(-1) when needed
        var x = x2.modPow(p.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), p)
        if (!sq(x).equalsConstantTime(x2)) {
            x = mul(x, sqrtM1)
            if (!sq(x).equalsConstantTime(x2)) return null
        }
        if (x.testBit(0) != xIsOdd) x = p.subtract(x)
        val pt = Point(x, y, BigInteger.ONE, mul(x, y))
        if (!isOnCurve(pt)) return null
        return pt
    }

    fun isOnCurve(pt: Point): Boolean {
        // Homogenized curve equation: (Y²-X²)·Z² == Z⁴ + d·X²Y²
        val x2 = sq(pt.x)
        val y2 = sq(pt.y)
        val z2 = sq(pt.z)
        val zz = mul(z2, z2)
        val left = mul(sub(y2, x2), z2)
        val right = add(zz, mul(d, sq(mul(pt.x, pt.y))))
        return left == right
    }

    /** Projective identity test: x = 0 and y = z (so x/z = 0, y/z = 1). */
    fun isIdentity(pt: Point): Boolean =
        pt.x.signum() == 0 && pt.y == pt.z

    /** A point has small order iff multiplying by the cofactor 8 yields identity. */
    fun isLowOrder(pt: Point): Boolean = isIdentity(scalarMult(BigInteger.valueOf(8), pt))

    /** Reduce a 64-byte (or shorter) scalar mod the group order l. */
    fun reduceModL(bytes: ByteArray): BigInteger = fromLittleEndian(bytes).mod(l)

    // ------------------------------------------------------------------
    // Byte helpers
    // ------------------------------------------------------------------

    fun toLittleEndian(v: BigInteger): ByteArray {
        val big = v.toByteArray() // big-endian, may have leading zero
        val le = ByteArray(32)
        var src = big.size - 1
        var dst = 0
        while (dst < 32 && src >= 0) {
            le[dst++] = big[src--]
        }
        return le
    }

    fun fromLittleEndian(bytes: ByteArray): BigInteger {
        var result = BigInteger.ZERO
        for (i in bytes.indices.reversed()) {
            result = result.shiftLeft(8).or(BigInteger.valueOf(bytes[i].toLong() and 0xFF))
        }
        return result
    }

    /** Constant-time-ish equality for BigIntegers (avoids early-exit leaks). */
    private fun BigInteger.equalsConstantTime(other: BigInteger): Boolean {
        val a = toByteArray()
        val b = other.toByteArray()
        var diff = if (a.size != b.size) 1 else 0
        for (i in a.indices) {
            if (i < b.size) diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
