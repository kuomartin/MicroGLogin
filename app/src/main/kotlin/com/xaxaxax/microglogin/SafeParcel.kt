package com.xaxaxax.microglogin

import android.net.Uri
import android.os.Parcel

/**
 * GMS 的 SafeParcel 格式，照 microG 的 `SafeParcelWriter`／`SafeParcelReader`。模組沒有 GMS 的 class，
 * 要自己組出替代品讀得懂的請求，並讀回它的結果。
 *
 * 每個欄位以一個 int 開頭：低 16 位是欄位編號，高 16 位是長度；長度為 `0xFFFF` 時，下一個 int 才是長度。
 * 物件本身是編號 [OBJECT_MAGIC] 的欄位，內容是它的各個欄位。
 */
internal object SafeParcel {

    private const val OBJECT_MAGIC = 0x4F45
    private const val EXTENDED = 0xFFFF

    /** 寫出一個 SafeParcel 物件；[body] 在物件標頭與結尾之間寫欄位。 */
    fun marshall(body: Writer.() -> Unit): ByteArray {
        val parcel = Parcel.obtain()
        try {
            Writer(parcel).obj(OBJECT_MAGIC, body)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    class Writer(private val parcel: Parcel) {
        fun int(id: Int, value: Int) {
            header(id, 4)
            parcel.writeInt(value)
        }

        fun boolean(id: Int, value: Boolean) = int(id, if (value) 1 else 0)

        fun string(id: Int, value: String?) {
            if (value == null) return
            obj(id) { parcel.writeString(value) }
        }

        /** `List<SafeParcelable>`：個數，每個元素前一個 int 標示長度（0 表示 null）。 */
        fun objects(id: Int, items: List<Writer.() -> Unit>) = obj(id) {
            parcel.writeInt(items.size)
            for (item in items) {
                val sizePosition = parcel.dataPosition()
                parcel.writeInt(1)
                val start = parcel.dataPosition()
                obj(OBJECT_MAGIC, item)
                val end = parcel.dataPosition()
                parcel.setDataPosition(sizePosition)
                parcel.writeInt(end - start)
                parcel.setDataPosition(end)
            }
        }

        internal fun obj(id: Int, body: Writer.() -> Unit) {
            header(id, EXTENDED)
            val start = parcel.dataPosition()
            body()
            val end = parcel.dataPosition()
            parcel.setDataPosition(start - 4)
            parcel.writeInt(end - start)
            parcel.setDataPosition(end)
        }

        private fun header(id: Int, size: Int) {
            if (size >= EXTENDED) {
                parcel.writeInt(EXTENDED shl 16 or id)
                parcel.writeInt(size)
            } else {
                parcel.writeInt(size shl 16 or id)
            }
        }
    }

    /** 讀出一個 SafeParcel 物件的欄位：編號 → 值。字串、int 與 `Uri` 依 [types] 指定，其餘略過。 */
    fun unmarshall(bytes: ByteArray, types: Map<Int, Type>): Map<Int, Any?> {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val (magic, size) = readHeader(parcel)
            require(magic == OBJECT_MAGIC) { "not a SafeParcel object: header ${magic.toString(16)}" }
            val end = parcel.dataPosition() + size
            val fields = HashMap<Int, Any?>()
            while (parcel.dataPosition() < end) {
                val (id, length) = readHeader(parcel)
                val start = parcel.dataPosition()
                when (types[id]) {
                    Type.STRING -> fields[id] = parcel.readString()
                    Type.INT -> fields[id] = parcel.readInt()
                    Type.URI -> fields[id] = Uri.CREATOR.createFromParcel(parcel)
                    null -> Unit
                }
                parcel.setDataPosition(start + length)
            }
            return fields
        } finally {
            parcel.recycle()
        }
    }

    enum class Type { STRING, INT, URI }

    private fun readHeader(parcel: Parcel): Pair<Int, Int> {
        val header = parcel.readInt()
        val id = header and 0xFFFF
        val size = header ushr 16
        return id to if (size == EXTENDED) parcel.readInt() else size
    }
}
