package com.EOKYEONGBIN.pleosfinder.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.EOKYEONGBIN.pleosfinder.core.R as CoreR
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolZOrder
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** 지도에 찍을 사람 또는 차량 */
data class MapMarker(val id: String, val label: String, val kind: Kind, val latitude: Double, val longitude: Double) {
    enum class Kind { ME, PERSON, CAR }
}

/**
 * 폰 앱 지도. MapLibre + OpenFreeMap(OpenStreetMap 자료) 무료 지도에
 * 나에게 공유된 사람·차량을 표시하고, 픽업 중에는 차와 나를 잇는 선을 그린다.
 * 기본 내비 경로는 외부 앱이 받을 수 없어 선은 직선으로 그린다(기획서 5장).
 */
class FinderMap(private val mapView: MapView) {

    private val context: Context = mapView.context
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var markers: List<MapMarker> = emptyList()
    private var route: List<MapMarker>? = null
    private val addedImages = mutableSetOf<String>()

    /** 마커 구성이 바뀔 때만 화면을 맞춘다(사용자가 옮긴 지도를 매번 되돌리지 않도록). */
    private var framedKey: String? = null

    init {
        // 스크롤 화면 안의 지도라, 지도를 끄는 동안은 화면 스크롤이 가로채지 않게 한다.
        @SuppressLint("ClickableViewAccessibility")
        mapView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        mapView.getMapAsync { map ->
            this.map = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder().target(DEFAULT_CENTER).zoom(11.0).build()
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                style.addSource(GeoJsonSource(ROUTE_SOURCE))
                style.addSource(GeoJsonSource(MARKER_SOURCE))
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(ContextCompat.getColor(context, CoreR.color.pleos_informative_positive)),
                        lineWidth(4f),
                        lineDasharray(arrayOf(1.5f, 1.5f)),
                        lineCap(Property.LINE_CAP_ROUND),
                    )
                )
                style.addLayer(
                    SymbolLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
                        iconImage(get(ICON)),
                        iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        // 겹치면 목록 뒤쪽(나)이 위에 그려진다.
                        symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
                    )
                )
                this.style = style
                apply()
            }
        }
    }

    /** route = 픽업 중일 때 [차, 나] */
    fun show(markers: List<MapMarker>, route: List<MapMarker>?) {
        this.markers = markers
        this.route = route
        apply()
    }

    private fun apply() {
        val style = style ?: return
        val features = markers.map { marker ->
            val image = "${marker.kind}:${marker.label}"
            if (addedImages.add(image)) style.addImage(image, markerBitmap(marker))
            Feature.fromGeometry(Point.fromLngLat(marker.longitude, marker.latitude)).apply { addStringProperty(ICON, image) }
        }
        style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
        val line = route?.map { Point.fromLngLat(it.longitude, it.latitude) }
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(if (line == null) emptyList() else listOf(Feature.fromGeometry(LineString.fromLngLats(line))))
        )
        frame()
    }

    private fun frame() {
        val map = map ?: return
        val key = markers.map { it.id }.sorted().joinToString() + "|" + (route != null)
        if (key == framedKey) return
        framedKey = key
        val points = markers.map { LatLng(it.latitude, it.longitude) }
        when (points.size) {
            0 -> Unit
            1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 14.0))
            else -> {
                val padding = dp(48)
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding, dp(72), padding, padding))
            }
        }
    }

    /** 아이콘 원 + 이름이 들어간 말풍선 모양 마커 */
    private fun markerBitmap(marker: MapMarker): Bitmap {
        @ColorRes val accent = when (marker.kind) {
            MapMarker.Kind.ME -> CoreR.color.pleos_informative_positive
            MapMarker.Kind.PERSON -> CoreR.color.pleos_informative_active
            MapMarker.Kind.CAR -> CoreR.color.pleos_basic_600
        }
        @DrawableRes val icon = if (marker.kind == MapMarker.Kind.CAR) CoreR.drawable.pleos_ic_car else CoreR.drawable.pleos_ic_person
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, CoreR.color.pleos_text_primary)
            textSize = dp(13).toFloat()
            typeface = ResourcesCompat.getFont(context, R.font.asta_sans_family)
            isFakeBoldText = true
        }
        val circle = dp(24)
        val pad = dp(4)
        val tail = dp(6)
        val width = pad + circle + dp(6) + textPaint.measureText(marker.label).toInt() + dp(10)
        val height = pad * 2 + circle
        val bitmap = Bitmap.createBitmap(width, height + tail, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x33000000)
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), height / 2f, height / 2f, fill)
        // 지점을 가리키는 꼬리
        val center = width / 2f
        val path = android.graphics.Path().apply {
            moveTo(center - tail, height.toFloat() - 1)
            lineTo(center + tail, height.toFloat() - 1)
            lineTo(center, (height + tail).toFloat())
            close()
        }
        canvas.drawPath(path, fill)
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, accent) }
        canvas.drawCircle(pad + circle / 2f, height / 2f, circle / 2f, accentPaint)
        ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
            setTint(Color.WHITE)
            val inset = dp(5)
            setBounds(pad + inset, pad + inset, pad + circle - inset, pad + circle - inset)
            draw(canvas)
        }
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(marker.label, (pad + circle + dp(6)).toFloat(), baseline, textPaint)
        return bitmap
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        const val MARKER_SOURCE = "finder-markers"
        const val MARKER_LAYER = "finder-markers"
        const val ROUTE_SOURCE = "finder-route"
        const val ROUTE_LAYER = "finder-route"
        const val ICON = "icon"

        /** 공유된 위치가 없을 때 보여 줄 곳(판교) */
        val DEFAULT_CENTER = LatLng(37.4013, 127.1086)
    }
}
