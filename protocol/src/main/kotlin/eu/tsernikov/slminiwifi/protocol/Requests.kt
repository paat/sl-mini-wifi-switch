package eu.tsernikov.slminiwifi.protocol

/** Only the documented reads and initial setup writes are exposed. IDs use uint64 bit patterns. */
object Requests {
    fun getDeviceInfo(id: Long = 1): ByteArray = request(1008, id)

    fun wifiGetConfig(id: Long = 1): ByteArray = request(3009, id)

    fun getStatus(id: Long = 1): ByteArray = request(1004, id)

    /** The app must obtain explicit confirmation before sending this write. */
    fun setupBypass(id: Long = 1): ByteArray =
        request(3003, id, Wire.Writer().varint(4, 1).toByteArray())

    /** A null password omits the password field. Device input limits remain unverified. */
    fun setupWifi(name: String, password: String? = null, id: Long = 1): ByteArray {
        val setup = Wire.Writer().string(2, name)
        if (password != null) setup.string(3, password)
        return request(3003, id, setup.toByteArray())
    }

    private fun request(field: Int, id: Long, payload: ByteArray = byteArrayOf()): ByteArray =
        Frames.request(Wire.Writer().varint(1, id).bytes(field, payload).toByteArray())
}
