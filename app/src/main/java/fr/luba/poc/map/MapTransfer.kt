package fr.luba.poc.map
import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto.*
/** Bounded collection, frame indices 1..total as upstream. */
class MapTransfer(private val syncType:Int = 2, initialStation: MapStation? = null) {
    private val stationReader = StationReader(initialStation)
    private val roots=sortedMapOf<Int,NavGetHashListAck>()
    private val frames=mutableMapOf<Long,MutableMap<Int,NavGetCommDataAck>>()
    private val requested=mutableSetOf<Long>()
    private var total=0
    var unsupported=false;private set
    fun accept(message:LubaMsg,commands:fr.luba.poc.protocol.Commands):List<ByteArray> {
        stationReader.accept(message)
        if(!message.hasNav()) return emptyList()
        val n=message.nav;val out=mutableListOf<ByteArray>()
        if(n.hasToappGethashAck()) { val f=n.toappGethashAck
            if(f.subCmd!=0) return out
            require(f.result==0 && f.totalFrame in 1..1024 && f.currentFrame in 1..f.totalFrame) { tr("Invalid hash list", "Liste de hashes invalide") }
            total=f.totalFrame;roots[f.currentFrame]=f
            out.add(commands.mapHashesAck(f.totalFrame,f.currentFrame))
        }
        if(n.hasToappGetCommondataAck()) { val f=n.toappGetCommondataAck
            if (roots.values.none { f.hash in it.dataCoupleList }) return out
            require(f.result==0 && f.totalFrame in 1..4096 && f.currentFrame in 1..f.totalFrame && f.dataCoupleCount<=10000) { tr("Invalid map frame", "Trame carte invalide") }
            require(f.dataCoupleList.all { it.x.isFinite() && it.y.isFinite() })
            if(f.type !in ShapeKind.SUPPORTED_TYPES) unsupported=true
            val bucket=frames.getOrPut(f.hash){mutableMapOf()}
            require(bucket.values.all { it.totalFrame==f.totalFrame && it.type==f.type }) { tr("Inconsistent boundary metadata", "Métadonnées de contour incohérentes") }
            require(frames.values.sumOf { values -> values.values.sumOf { it.dataCoupleCount } } + f.dataCoupleCount <= 100000) { tr("Map too large", "Carte trop volumineuse") }
            bucket[f.currentFrame]=f
            out.add(commands.mapDataAck(f))
        }
        if(total>0 && roots.size==total) {
            val hashes=roots.values.flatMap{it.dataCoupleList}.distinct();require(hashes.size<=1024)
            val next=hashes.firstOrNull { h -> val fs=frames[h]; fs==null || fs.size!=fs.values.first().totalFrame }
            if(next!=null && requested.add(next)) { out.add(commands.sync(syncType=syncType));out.add(commands.mapData(next)) }
        };return out
    }
    fun snapshot():RobotMap {
        val hashes=roots.values.flatMap{it.dataCoupleList}.distinct()
        val done=total>0 && roots.size==total && hashes.all { h -> frames[h]?.let { it.size==it.values.first().totalFrame }==true }
        val shapes=frames.mapNotNull { (h,fs) -> val first=fs.values.first();if(fs.size!=first.totalFrame) null else MapShape(h,first.type,fs.toSortedMap().values.flatMap{it.dataCoupleList}.map{Point(it.x.toDouble(),it.y.toDouble())}, first.nameTime.name) }
        return RobotMap(shapes,done && !unsupported && shapes.all { !it.kind.isContour || it.points.size >= 3 },tr("${if(syncType==2) "BLE" else "Cloud"} map · ${shapes.size}/${hashes.size} items", "Carte ${if(syncType==2) "BLE" else "cloud"} · ${shapes.size}/${hashes.size} éléments") +
            if(unsupported) tr(" · unsupported types", " · types non pris en charge") else "", stationReader.station)
    }
}
