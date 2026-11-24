package com.nanci.awalocation.nmea

import java.util.Locale

private fun fmt(pattern: String, value: Any): String = String.format(Locale.US, pattern, value)

sealed class NmeaValue (
    val statementId: String,
) {
    data class DTM(
        val localCoordinateSystemCode: String?,
        val coordinateSystemSubCode: String?,
        val latitudeOffset: Double?,
        val latitudeHemisphere: String?,
        val longitudeOffset: Double?,
        val longitudeHemisphere: String?,
        val heightOffset: Double?,
        val coordinateSystemCode: String?,
    ): NmeaValue("DTM") {
//        Datum (DTM) 大地坐标系信息
//        $GPDTM,<1>,<2>,<3>,<4>,<5>,<6>,<7>,<8>*hh<CR><LF>
//        <1>本地坐标系代码 W84
//        <2>坐标系子代码 空
//        <3>纬度偏移量
//        <4>纬度半球N（北半球）或S（南半球）
//        <5>经度偏移量
//        <6>经度半球E（东经）或W（西经）
//        <7>高度偏移量
//        <8>坐标系代码 W84
        override fun toNmeaString() = "$statementId,${localCoordinateSystemCode ?: ""},${coordinateSystemSubCode ?: ""},${latitudeOffset?.let { fmt("%011.6f", it) } ?: ""},${latitudeHemisphere ?: ""},${longitudeOffset?.let { fmt("%012.6f", it) } ?: ""},${longitudeHemisphere ?: ""},${heightOffset?.let { fmt("%.3f", it) } ?: ""},${coordinateSystemCode ?: ""}"
    }

    data class GGA(
        val utc: String?,
        var latitude: Double?,
        var latitudeHemisphere: String?,
        var longitude: Double?,
        var longitudeHemisphere: String?,
        val fixQuality: Int,
        val satellites: Int?,
        val hdop: Double?,
        val antennaAltitude: Double?,
        val antennaAltitudeUnit: String?,
        val geoidalSeparation: Double?,
        val geoidalSeparationUnit: String?,
        val dgpsAge: Double?,
        val dgpsStationId: String?,
    ): NmeaValue("GGA") {
//        1) Time (UTC)
//        2) Latitude 注释①
//        3) N or S (North or South)
//        4) Longitude 注释②
//        5) E or W (East or West)
//        6) GPS Quality Indicator,  注释③
//            0 - fix not available,
//            1 - GPS fix,
//            2 - Differential GPS fix
//        7) Number of satellites in view, 00 - 12
//        8) Horizontal Dilution of precision
//        9) Antenna Altitude above/below mean-sea-level (geoid)
//        10) Units of antenna altitude, meters
//        11) Geoidal separation, the difference between the WGS-84 earth ellipsoid and mean-sea-level (geoid), "-" means mean-sea-level below ellipsoid
//        12) Units of geoidal separation, meters
//        13) Age of differential GPS data, time in seconds since last SC104 type 1 or 9 update, null field when DGPS is not used 注释④
//        14) Differential reference station ID, 0000-1023 注释⑤
//        15) Checksum
        override fun toNmeaString() = "$statementId,$utc,${latitude?.let { fmt("%011.6f", it) } ?: ""},$latitudeHemisphere,${longitude?.let { fmt("%011.6f", it) } ?: ""},$longitudeHemisphere,$fixQuality,${satellites?.let { fmt("%02d", it) } ?: ""},${hdop?.let { fmt("%.1f", it) } ?: ""},${antennaAltitude?.let { fmt("%.1f", it) } ?: ""},$antennaAltitudeUnit,${geoidalSeparation?.let { fmt("%.1f", it) } ?: ""},$geoidalSeparationUnit,${dgpsAge?.let { fmt("%.1f", it) } ?: ""},$dgpsStationId"
    }

    data class GNS(
        val utc: String?,
        var latitude: Double?,
        var latitudeHemisphere: String?,
        var longitude: Double?,
        var longitudeHemisphere: String?,
        /**
         * Mode
         * N = 无定位
         * A = 自主定位
         * D = 差分
         * P = 精密
         * R = RTK固定解
         * F = RTK浮点解
         * E = 估算
         * M = 手动输入
         */
        val mode: String,
        val satellites: Int?,
        val hdop: Double?,
        val altitude: Double?,
        val geoidHeight: Double?,
        val ageOfDifferentialData: Double?,
        val differentialStationId: String?,
        val navStatus: String,
    ): NmeaValue("GNS") {
        override fun toNmeaString() = "$statementId,$utc,${latitude?.let { fmt("%011.6f", it) } ?: ""},$latitudeHemisphere,${longitude?.let { fmt("%011.6f", it) } ?: ""},$longitudeHemisphere,$mode,${satellites?.let { fmt("%02d", it) } ?: ""},${hdop?.let { fmt("%.1f", it) } ?: ""},${altitude?.let { fmt("%.1f", it) } ?: ""},${geoidHeight?.let { fmt("%.1f", it) } ?: ""},${ageOfDifferentialData?.let { fmt("%.1f", it) } ?: ""},${differentialStationId ?: ""},$navStatus"
    }

    data class GSA(
        /**
         * Mode
         * M = Manual
         * A = Automatic
         */
        val mode: String,
        /**
         * Fix status
         * 1 = No fix
         * 2 = 2D fix
         * 3 = 3D fix
         */
        val fixStatus: Int,
        val prn: List<Int?>,
        val pdop: Double?,
        val hdop: Double?,
        val vdop: Double?,
        val systemId: String? = null,
    ): NmeaValue("GSA") {
        init {
            require(prn.size == 12) { "prn size should be 12" }
        }

        override fun toNmeaString(): String = "$statementId,$mode,$fixStatus,${prn.joinToString(separator = ",") { it?.let { fmt("%02d", it) } ?: "" }},${pdop ?: ""},${hdop ?: ""},${vdop ?: ""},${systemId ?: ""}"
    }

    data class GSV(
        val totalMessages: Int,
        val messageNumber: Int,
        val totalSatellitesInView: Int,
        val satellites: List<Satellite>,
        val infoId: String? = null,
    ): NmeaValue("GSV") {
        data class Satellite(
            val prn: Int,
            val elevation: Int,
            val azimuth: Int,
            val snr: Int?,
        )

        override fun toNmeaString(): String {
            val satellitesStr = satellites.joinToString(separator = ",") { satellite ->
                "${fmt("%02d", satellite.prn)},${fmt("%02d", satellite.elevation)},${fmt("%03d", satellite.azimuth)},${satellite.snr ?: ""}"
            }
            val base = "$statementId,$totalMessages,$messageNumber,${fmt("%02d", totalSatellitesInView)}"
            val withSats = if (satellitesStr.isNotEmpty()) "$base,$satellitesStr" else base
            return if (infoId != null) "$withSats,$infoId" else withSats
        }
    }

    data class RMC(
        val utc: String?,
        val status: String,
        var latitude: Double?,
        var latitudeHemisphere: String?,
        var longitude: Double?,
        var longitudeHemisphere: String?,
        val speedKnots: Double?,
        val trackAngle: Double?,
        val date: String?,
        val magneticVariation: Double?,
        val magneticVariationDirection: String,
        val mode: String,
        val navStatus: String,
    ): NmeaValue("RMC") {
//        $GPRMC
//        字段0：$GPRMC，语句ID，表明该语句为Recommended Minimum Specific GPS/TRANSIT Data（RMC）推荐最小定位信息
//        字段1：UTC时间，hhmmss.sss格式
//        字段2：状态，A=定位，V=未定位
//        字段3：纬度ddmm.mmmm，度分格式（前导位数不足则补0）
//        字段4：纬度N（北纬）或S（南纬）
//        字段5：经度dddmm.mmmm，度分格式（前导位数不足则补0）
//        字段6：经度E（东经）或W（西经）
//        字段7：速度，节，Knots
//        字段8：方位角，度
//        字段9：UTC日期，DDMMYY格式
//        字段10：磁偏角，（000 - 180）度（前导位数不足则补0）
//        字段11：磁偏角方向，E=东W=西
//        字段12：Mode指示定位系统模式，A=自主定位，R=差分，F=估算，N=数据无效
//        字段13：校验值
        override fun toNmeaString() = "$statementId,$utc,$status,${latitude?.let { fmt("%011.6f", it) } ?: ""},$latitudeHemisphere,${longitude?.let { fmt("%011.6f", it) } ?: ""},$longitudeHemisphere,${speedKnots?.let { fmt("%.1f", it) } ?: ""},${trackAngle?.let { fmt("%.1f", it) } ?: ""},${date ?: ""},${magneticVariation?.let { fmt("%.1f", it) } ?: ""},$magneticVariationDirection,$mode,$navStatus"
    }

    data class VTG(
        val trueTrack: Double?,
        val magneticTrack: Double?,
        val groundSpeedKnots: Double?,
        val groundSpeedUnit: String,
        val groundSpeedKph: Double?,
        val groundSpeedKphUnit: String,
        val trueTrackMode: String,
        val magneticTrackMode: String,
        val mode: String
    ): NmeaValue("VTG") {
        override fun toNmeaString() = "$statementId,${trueTrack?.let { fmt("%.1f", it) } ?: ""},$trueTrackMode,${magneticTrack?.let { fmt("%.1f", it) } ?: ""},$magneticTrackMode,${groundSpeedKnots?.let { fmt("%.1f", it) } ?: ""},$groundSpeedUnit,${groundSpeedKph?.let { fmt("%.1f", it) } ?: ""},$groundSpeedKphUnit,$mode"
    }

    abstract fun toNmeaString(): String
}
