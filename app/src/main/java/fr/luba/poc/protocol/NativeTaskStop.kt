package fr.luba.poc.protocol

/** A GATT write is not enough: require a new READY / OFF state. */
object NativeTaskStop {
    fun present(state: Int?) = state in setOf(13,14,19,39)
    fun confirmed(writtenAt: Long, deviceAt: Long, ageMs: Long, state: Int?, sensors: Int?) =
        writtenAt>0 && deviceAt>writtenAt && ageMs in 0..2000 && state==11 &&
            sensors?.let { ((it shr 9) and 7)==0 }==true
}
