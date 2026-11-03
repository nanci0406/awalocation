package com.nanci.awalocation.amap

import android.content.Context
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
    // 旋转、倾斜和指南针保持开启，卡顿优化放在绘制与控件层处理。
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

    val style = MyLocationStyle()
        .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
        .interval(2000L)
    setMyLocationStyle(style)
    setMyLocationEnabled(true)
}

fun AMap.showSelection(selection: MapSelection, icon: BitmapDescriptor?): Marker {
    clear()
    val options = MarkerOptions().position(selection.gcjLocation)
    if (icon != null) {
        options.icon(icon)
    }
    val marker = addMarker(options)
    marker.setObject(selection)
    marker.showInfoWindow()
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
