package fr.luba.poc.mowing

import fr.luba.poc.map.Point
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto.*
import fr.luba.poc.protocol.Commands
import fr.luba.poc.i18n.tr

/** Reading of the firmware route: subCmd=3 hashes, transactions of at most 20 lines. */
class MowPathTransfer(private val transactionSeed: Long) {
    private val roots = sortedMapOf<Int, NavGetHashListAck>()
    private val frames = sortedMapOf<Int, cover_path_upload_t>()
    private val completed = linkedMapOf<Long, List<Point>>()
    private var pending = emptyList<Long>()
    private var transaction = transactionSeed
    private var total = 0
    var complete = false; private set
    fun start(commands: Commands) = listOf(commands.sync(), commands.mowPathHashes())
    fun accept(message: LubaMsg, commands: Commands): List<ByteArray> {
        if (!message.hasNav() || complete) return emptyList()
        val nav = message.nav
        val out = mutableListOf<ByteArray>()
        if (nav.hasToappGethashAck() && nav.toappGethashAck.subCmd == 3) {
            val f = nav.toappGethashAck
            require(f.result == 0 && f.totalFrame in 1..1024 && f.currentFrame in 1..f.totalFrame)
            require(total == 0 || total == f.totalFrame)
            total = f.totalFrame; roots[f.currentFrame] = f
            out.add(commands.mapHashesAck(f.totalFrame, f.currentFrame))
            if (roots.size == total && pending.isEmpty()) requestNext(commands, out)
        }
        if (nav.hasCoverPathUpload()) {
            val f = nav.coverPathUpload
            if (pending.isEmpty() || f.transactionId != transaction) return out
            require(f.result == 0 && f.totalFrame in 1..4096 && f.currentFrame in 1..f.totalFrame)
            require(frames.values.all { it.totalFrame == f.totalFrame })
            require(f.pathPacketsList.all { it.pathHash in pending && it.dataCoupleList.all { p -> p.x.isFinite() && p.y.isFinite() } })
            frames[f.currentFrame] = f
            require(completed.values.sumOf { it.size } + frames.values.sumOf { v -> v.pathPacketsList.sumOf { it.dataCoupleCount } } <= 100000)
            if (frames.size == f.totalFrame) {
                val packets = frames.values.flatMap { it.pathPacketsList }.groupBy { it.pathHash }
                pending.forEach { hash ->
                    val ordered = packets[hash].orEmpty().distinctBy { it.pathCur }.sortedBy { it.pathCur }
                    require(ordered.isNotEmpty() && ordered.withIndex().all { (index, packet) -> packet.pathTotal == ordered.size && packet.pathCur == index + 1 }) { tr("Incomplete firmware line", "Ligne firmware incomplète") }
                    completed[hash] = ordered.flatMap { it.dataCoupleList }.map { Point(it.x.toDouble(), it.y.toDouble()) }
                }
                pending = emptyList(); frames.clear(); requestNext(commands, out)
            }
        }
        return out
    }
    private fun requestNext(commands: Commands, out: MutableList<ByteArray>) {
        val hashes = roots.values.flatMap { it.dataCoupleList }.filter { it != 0L }.distinct()
        require(hashes.size <= 4096)
        pending = hashes.filter { it !in completed }.take(20)
        if (pending.isEmpty()) complete = true else {
            transaction++
            out.add(commands.sync()); out.add(commands.mowPaths(pending, transaction))
        }
    }
    fun paths(): List<List<Point>> = completed.values.filter { it.size >= 2 }
}
