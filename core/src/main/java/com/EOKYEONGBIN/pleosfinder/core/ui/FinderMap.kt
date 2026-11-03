package com.EOKYEONGBIN.pleosfinder.core.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.EOKYEONGBIN.pleosfinder.core.R
import org.maplibre.android.camera.CameraPosition
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

/** 지도에 찍을 사람·차량·목적지. ME = 폰의 나, MY_CAR = 차량 앱의 내 차, DESTINATION = 그룹에 공유된 목적지 */
data class MapMarker(val id: String, val label: String, val kind: Kind, val latitude: Double, val longitude: Double) {
    enum class Kind { ME, MY_CAR, PERSON, CAR, DESTINATION }
}

/**
 * 차량·폰 앱 공용 지도. MapLibre + OpenFreeMap(OpenStreetMap 자료) 무료 지도에
 * 나에게 공유된 사람·차량과 그룹 목적지를 표시하고, 픽업 중에는 차와 기다리는 사람을,
 * 그룹 목적지로 가는 중에는 나와 목적지를 잇는 선을 그린다.
 * 기본 내비 경로는 외부 앱이 받을 수 없어 선은 직선으로 그린다(기획서 5장).
 * 마커 크기는 앱마다 다른 Pleos dimens(pleos_text_label: 차량 24sp / 폰 13sp)를 따른다.
 * 기본 화면은 내 위치를 가깝게 보여 주고, 지도 카드(finder_map_card)의 '내 위치 보기' 버튼으로 언제든 돌아온다.
 */
class FinderMap(private val mapView: MapView, private val typeface: Typeface? = null) {

    private val context: Context = mapView.context
    private val labelSize = context.resources.getDimension(R.dimen.pleos_text_label)
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var markers: List<MapMarker> = emptyList()
    private var route: List<MapMarker>? = null
    private val addedImages = mutableSetOf<String>()

    /**
     * 자동으로 맞춘 화면의 기준(내 위치 / 픽업 경로 / 전체). 기준이 바뀔 때만 다시 맞춰,
     * 사용자가 옮겨 본 지도를 위치가 갱신될 때마다 되돌리지 않는다.
     */
    private var framed: String? = null

    init {
        (mapView.parent as? android.view.View)?.findViewById<android.view.View>(R.id.myLocationButton)?.setOnClickListener { showMe() }
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
            map.cameraPosition = CameraPosition.Builder().target(DEFAULT_CENTER).zoom(11.0).build()
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                style.addSource(GeoJsonSource(ROUTE_SOURCE))
                style.addSource(GeoJsonSource(MARKER_SOURCE))
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(ContextCompat.getColor(context, R.color.pleos_informative_positive)),
                        lineWidth(labelSize / context.resources.displayMetrics.density / 3.25f),
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

    /** route = 픽업 중일 때 [차, 기다리는 사람], 그룹 목적지로 가는 중일 때 [나, 목적지] */
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

    /** 내 위치(폰의 나, 차량의 내 차)로 돌아온다. */
    fun showMe() {
        val me = me() ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.latitude, me.longitude), ME_ZOOM))
    }

    /** 한 지점(목록에서 두 번 누른 사람·차량)을 가깝게 보여 준다. */
    fun focus(latitude: Double, longitude: Double) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), FOCUS_ZOOM))
    }

    private fun me(): MapMarker? = markers.lastOrNull { it.kind == MapMarker.Kind.ME || it.kind == MapMarker.Kind.MY_CAR }

    private fun frame() {
        val map = map ?: return
        val route = route
        val basis = when {
            route != null -> "route"
            me() != null -> "me"
            markers.isNotEmpty() -> "all"
            else -> return
        }
        if (basis == framed) return
        framed = basis
        when (basis) {
            "me" -> showMe()
            // 픽업 중에는 차와 상대가 함께 보이게, 내 위치를 모를 때는 공유된 위치가 모두 보이게
            else -> fit((route ?: markers).map { LatLng(it.latitude, it.longitude) }, map)
        }
    }

    private fun fit(points: List<LatLng>, map: MapLibreMap) {
        if (points.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], ME_ZOOM))
            return
        }
        // 마커는 지점 위로 올라가 그려지므로(높이 ≈ 글자 3배) 위쪽 여백을 더 둔다.
        val side = (labelSize * 1.5f).toInt()
        val top = (labelSize * 3.3f).toInt()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), side, top, side, side))
    }

    /** 아이콘 원 + 이름이 들어간 말풍선 모양 마커. 크기는 글자 크기에 비례한다. */
    private fun markerBitmap(marker: MapMarker): Bitmap {
        @ColorRes val accent = when (marker.kind) {
            MapMarker.Kind.ME, MapMarker.Kind.MY_CAR -> R.color.pleos_informative_positive
            MapMarker.Kind.PERSON -> R.color.pleos_informative_active
            MapMarker.Kind.CAR -> R.color.pleos_basic_600
            MapMarker.Kind.DESTINATION -> R.color.pleos_informative_negative
        }
        @DrawableRes val icon = when (marker.kind) {
            MapMarker.Kind.MY_CAR, MapMarker.Kind.CAR -> R.drawable.pleos_ic_car
            MapMarker.Kind.ME, MapMarker.Kind.PERSON -> R.drawable.pleos_ic_person
            MapMarker.Kind.DESTINATION -> R.drawable.pleos_ic_flag
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pleos_text_primary)
            textSize = labelSize
            typeface = this@FinderMap.typeface ?: Typeface.DEFAULT
            isFakeBoldText = true
        }
        val circle = (labelSize * 1.85f).toInt()
        val pad = (labelSize * 0.31f).toInt()
        val tail = (labelSize * 0.46f).toInt()
        val gap = (labelSize * 0.46f).toInt()
        val width = pad + circle + gap + textPaint.measureText(marker.label).toInt() + (labelSize * 0.77f).toInt()
        val height = pad * 2 + circle
        val bitmap = Bitmap.createBitmap(width, height + tail, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(labelSize * 0.15f, 0f, labelSize * 0.08f, 0x33000000)
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), height / 2f, height / 2f, fill)
        // 지점을 가리키는 꼬리
        val center = width / 2f
        val path = Path().apply {
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
            val inset = (labelSize * 0.38f).toInt()
            setBounds(pad + inset, pad + inset, pad + circle - inset, pad + circle - inset)
            draw(canvas)
        }
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(marker.label, (pad + circle + gap).toFloat(), baseline, textPaint)
        return bitmap
    }

    private companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        const val MARKER_SOURCE = "finder-markers"
        const val MARKER_LAYER = "finder-markers"
        const val ROUTE_SOURCE = "finder-route"
        const val ROUTE_LAYER = "finder-route"
        const val ICON = "icon"

        /** 내 위치를 보여 줄 때(동네 길까지 보이는 정도)와 목록에서 두 번 누른 사람을 보여 줄 때의 확대 단계 */
        const val ME_ZOOM = 15.0
        const val FOCUS_ZOOM = 16.0

        /** 공유된 위치가 없을 때 보여 줄 곳(판교) */
        val DEFAULT_CENTER = LatLng(37.4013, 127.1086)
    }
}
