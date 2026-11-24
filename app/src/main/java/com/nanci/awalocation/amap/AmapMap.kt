package com.nanci.awalocation.amap

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.help.Tip
import com.nanci.awalocation.R
import com.nanci.awalocation.ext.Loc4j

/**
 * Data carried by the selected marker so the custom info window can render the
 * WGS84 coordinates and the latest reverse-geocoded address.
 */
data class MapSelection(
    val wgsLocation: Pair<Double, Double>,
    val gcjLocation: LatLng,
    val name: String?,
)

class AmapInfoWindowAdapter(private val context: Context) : AMap.InfoWindowAdapter {
    override fun getInfoWindow(marker: Marker): View {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_loc_detail, null)
        val selection = marker.getObject() as? MapSelection
        view.findViewById<TextView>(R.id.loc_detail).text = selection?.let {
            "${it.wgsLocation.second.toString().take(10)}, ${it.wgsLocation.first.toString().take(10)}"
        } ?: ""
        view.findViewById<TextView>(R.id.loc_addr).text = selection?.name ?: "未知地址"
        return view
    }

    override fun getInfoContents(marker: Marker): View? = null
}

fun AMap.setupMap(context: Context) {
    // 自带缩放按钮是旧版样式，改用页面上的 Material 控件；3D 楼块、
    // 旋转、倾斜和指南针保持开启。
    uiSettings.isZoomControlsEnabled = false
    uiSettings.isMyLocationButtonEnabled = false
    uiSettings.isCompassEnabled = true
    uiSettings.isScaleControlsEnabled = true
    uiSettings.isRotateGesturesEnabled = true
    uiSettings.isTiltGesturesEnabled = true
    uiSettings.setLogoPosition(AMapOptions.LOGO_POSITION_BOTTOM_RIGHT)
    showBuildings(true)
    showIndoorMap(true)
    setInfoWindowAdapter(AmapInfoWindowAdapter(context))

    // 使用连续定位但不抢夺相机视角（LOCATION_TYPE_SHOW 只定位一次，首次失败后不会再重试）
    val style = MyLocationStyle()
        .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
        .interval(3000L)
    setMyLocationStyle(style)
    setMyLocationEnabled(true)
}

/**
 * 过滤高德 OnMyLocationChangeListener 定位失败时下发的 (0.0, 0.0) 或非 0 errorCode 假回调。
 */
fun Location?.isValidAmapLocation(): Boolean {
    if (this == null) return false
    val errorCode = extras?.getInt(MyLocationStyle.ERROR_CODE, 0) ?: 0
    if (errorCode != 0) return false
    if (latitude == 0.0 && longitude == 0.0) return false
    return latitude in -90.0..90.0 && longitude in -180.0..180.0
}

fun AMap.showSelection(
    selection: MapSelection,
    icon: BitmapDescriptor?,
    showInfoWindow: Boolean = false
): Marker {
    clear()
    val options = MarkerOptions().position(selection.gcjLocation)
    if (icon != null) {
        options.icon(icon)
    }
    val marker = addMarker(options)
    marker.setObject(selection)
    if (showInfoWindow) {
        marker.showInfoWindow()
    }
    return marker
}

fun AMap.moveTo(latLng: LatLng, zoom: Float? = null) {
    moveCamera(
        if (zoom != null) {
            CameraUpdateFactory.newLatLngZoom(latLng, zoom)
        } else {
            CameraUpdateFactory.newLatLng(latLng)
        }
    )
}

fun AMap.animateTo(latLng: LatLng, zoom: Float? = null) {
    animateCamera(
        if (zoom != null) {
            CameraUpdateFactory.newLatLngZoom(latLng, zoom)
        } else {
            CameraUpdateFactory.newLatLng(latLng)
        }
    )
}

fun AMap.zoomIn() = animateCamera(CameraUpdateFactory.zoomIn())

fun AMap.zoomOut() = animateCamera(CameraUpdateFactory.zoomOut())

fun Tip.toPoi(currentLocation: Pair<Double, Double>? = null): Poi? {
    val latLonPoint = point ?: return null
    val wgs = Loc4j.gcj2wgs(latLonPoint.latitude, latLonPoint.longitude)
    val displayAddress = listOfNotNull(district, address)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    val poi = Poi(
        name = name ?: "",
        address = displayAddress,
        longitude = wgs.second,
        latitude = wgs.first,
        tag = typeCode ?: "",
    )

    if (currentLocation != null) {
        val distance = poi.distanceTo(currentLocation.first, currentLocation.second).toInt()
        poi.address = if (distance < 1000) {
            "${distance}m ${poi.address}"
        } else {
            "${(distance / 1000.0).toString().take(4)}km ${poi.address}"
        }
    }
    return poi
}

fun PoiItemV2.toPoi(currentLocation: Pair<Double, Double>? = null): Poi? {
    val latLonPoint = latLonPoint ?: return null
    val wgs = Loc4j.gcj2wgs(latLonPoint.latitude, latLonPoint.longitude)
    val region = listOfNotNull(cityName, adName)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString("")
    val detail = snippet?.takeIf { it.isNotBlank() }
    val displayAddress = listOfNotNull(region.takeIf { it.isNotBlank() }, detail)
        .joinToString(" · ")
        .ifBlank { title.orEmpty() }

    val poi = Poi(
        name = title.orEmpty(),
        address = displayAddress,
        longitude = wgs.second,
        latitude = wgs.first,
        tag = typeDes?.substringAfterLast(";").orEmpty(),
    )

    if (currentLocation != null) {
        val distance = poi.distanceTo(currentLocation.first, currentLocation.second).toInt()
        poi.address = if (distance < 1000) {
            "${distance}m ${poi.address}"
        } else {
            "${(distance / 1000.0).toString().take(4)}km ${poi.address}"
        }
    }
    return poi
}

fun Context.isAmapKeyMissing(): Boolean {
    val key = runCatching {
        val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        appInfo.metaData?.getString("com.amap.api.v2.apikey")
    }.getOrNull().orEmpty().trim()
    return key.isBlank() || key == "YOUR_AMAP_KEY"
}

fun Context.describeAmapError(errorCode: Int): String {
    if (isAmapKeyMissing()) {
        return "高德 API Key 未配置（当前为占位符 YOUR_AMAP_KEY）"
    }
    return when (errorCode) {
        1001, 1003, 1013 -> "高德 API Key 无效或未启用检索服务 ($errorCode)"
        1008 -> "高德签名 SHA1 与当前安装包不匹配 ($errorCode)"
        1009, 1004 -> "高德接口请求配额受限，请稍后再试 ($errorCode)"
        1002 -> "高德检索服务暂不可用 ($errorCode)"
        1802, 1804, 1806 -> "网络连接异常，请检查网络或代理 ($errorCode)"
        AMapException.CODE_AMAP_SUCCESS -> ""
        else -> "高德检索失败 (错误码 $errorCode)"
    }
}
