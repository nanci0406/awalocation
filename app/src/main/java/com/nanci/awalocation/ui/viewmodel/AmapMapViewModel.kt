package com.nanci.awalocation.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Marker
import com.amap.api.services.geocoder.GeocodeSearch
import com.nanci.awalocation.R

class AmapMapViewModel : ViewModel() {
    var isExists = false
    lateinit var amap: AMap

    /**
     * Current location
     * WGS84
     */
    var currentLocation: Pair<Double, Double>? = null

    var markName: String? = null

    /**
     * Marked location
     * WGS84
     * first => latitude
     * second => longitude
     */
    var markedLoc: Pair<Double, Double>? = null
    var showDetailView = false

    val mMapIndicator: BitmapDescriptor? by lazy {
        BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
    }

    var geocodeSearch: GeocodeSearch? = null
    var selectedMarker: Marker? = null
}
