package com.mapconductor.openmobilemaps

import io.openmobilemaps.mapscore.shared.map.LayerInterface
import io.openmobilemaps.mapscore.shared.map.MapInterface
import io.openmobilemaps.mapscore.shared.map.layers.icon.IconLayerInterface
import io.openmobilemaps.mapscore.shared.map.layers.line.LineLayerInterface
import io.openmobilemaps.mapscore.shared.map.layers.polygon.PolygonLayerInterface

/**
 * このドライバーが地図に載せるレイヤ一式と、その重ね順。
 *
 * ## なぜレイヤを 1 箇所で持つのか
 *
 * Open Mobile Maps は「オーバーレイ 1 つ = ネイティブオブジェクト 1 つ」ではなく
 * 「**種別ごとに 1 枚のレイヤ**があり、そこへ要素のリストを流し込む」形の SDK である
 * （MapLibre の GeoJSON ソースに近い）。レンダラが個別にレイヤを作ると重ね順が
 * 生成順に依存して不定になるので、ここでまとめて作り、索引を割り当てる。
 *
 * ## 重ね順（下から）
 *
 * ```
 * 地図デザイン → ラスターレイヤ・グラウンドイメージ → ポリゴンのタイル → ポリゴン塗り → ポリゴン輪郭
 *   → 円の塗り → 円の輪郭 → ポリライン → マーカー
 * ```
 *
 * すべてのプロバイダ共通の決まり（[com.mapconductor.core.controller.OverlayStacking]）。
 * ラスターとグラウンドイメージ、ポリゴン・マーカーのタイルは数が変わるので、帯ごとに索引の区間を分けて
 * 割り当てる（以前はラスターとグラウンドイメージが同じ区間を空き番号から使い回していたため、
 * 追加・送り直しの順で上下が入れ替わった）。
 *
 * 他プロバイダのクリックのカスケード（marker → circle → groundImage → polyline →
 * polygon → map）と**上下が逆に見える**が、これは正しい。カスケードは「上にあるものから
 * 順に当たり判定する」ので、描画の重ね順を上から読むとカスケード順に一致する。
 *
 * ## この SDK のレイヤ API には罠が 2 つある
 *
 * **1. `insertLayerAt(layer, index)` は挿入ではなく上書きである。**
 * `MapScene` はレイヤを「索引 → レイヤ」の map で持っていて、`insertLayerAt` は
 * その索引に居たレイヤを `onRemoved()` して置き換える。地図デザインを索引 0 に
 * 入れると、索引 0 に居た**ポリゴン塗りレイヤが黙って外れる**。
 * 症状は「円は塗れるのにポリゴンだけ塗れない」で、例外もログも出ない。
 * だから索引は [DESIGN_INDEX] / [BELOW_INDEX_FIRST] / [FIXED_INDEX_FIRST] に
 * 分けて**衝突させない**。
 *
 * **2. `asLayerInterface()` は呼ぶたびに別のオブジェクトを返す。**
 * `insertLayerBelow(x, polygonFillLayer.asLayerInterface())` は「そんなレイヤは無い」で
 * GL スレッドから落ちる（`RuntimeException: MapScene does not contain below layer`）。
 * `removeLayer` も黙って何も外さない。載せたときの値を持っておいて、それを渡すこと。
 */
class OpenMobileMapsLayers {
    val polygonFillLayer: PolygonLayerInterface = PolygonLayerInterface.create()
    val polygonOutlineLayer: LineLayerInterface = LineLayerInterface.create()
    val circleFillLayer: PolygonLayerInterface = PolygonLayerInterface.create()
    val circleOutlineLayer: LineLayerInterface = LineLayerInterface.create()
    val polylineLayer: LineLayerInterface = LineLayerInterface.create()
    val iconLayer: IconLayerInterface = IconLayerInterface.create()

    /** 地図デザインのレイヤ。差し替え時に外すため、**載せた値そのもの**を持っておく。 */
    private var designLayer: LayerInterface? = null

    /** 地図デザインとオーバーレイ群の間に入っているレイヤ（ラスター / グラウンドイメージ）と、その索引。 */
    private val belowOverlayIndices = mutableMapOf<LayerInterface, Int>()

    /** 帯ごとの索引の割り当て。外したレイヤの索引は使い回す（張り替えを繰り返すうちに尽きないよう）。 */
    private class Band(
        val first: Int,
        val end: Int,
    ) {
        val free = sortedSetOf<Int>()
        var next = first

        fun take(): Int = free.pollFirst() ?: next++.also { check(it < end) { "layer index band exhausted" } }
    }

    private val rasterBand = Band(RASTER_INDEX_FIRST, GROUND_IMAGE_INDEX_FIRST)
    private val groundImageBand = Band(GROUND_IMAGE_INDEX_FIRST, POLYGON_TILE_INDEX_FIRST)
    private val polygonTileBand = Band(POLYGON_TILE_INDEX_FIRST, FIXED_INDEX_FIRST)
    private val circleTileBand = Band(FIXED_INDEX_FIRST + 2, FIXED_INDEX_FIRST + 6)
    private val polylineTileBand = Band(POLYLINE_TILE_INDEX_FIRST, POLYLINE_INDEX)
    private val markerTileBand = Band(MARKER_TILE_INDEX_FIRST, ICON_INDEX)
    private val bandOfLayer = mutableMapOf<LayerInterface, Band>()

    private val fixedLayers: List<Pair<LayerInterface, Int>> by lazy {
        listOf(
            polygonFillLayer.asLayerInterface() to FIXED_INDEX_FIRST,
            polygonOutlineLayer.asLayerInterface() to FIXED_INDEX_FIRST + 1,
            circleFillLayer.asLayerInterface() to FIXED_INDEX_FIRST + 6,
            circleOutlineLayer.asLayerInterface() to FIXED_INDEX_FIRST + 7,
            polylineLayer.asLayerInterface() to POLYLINE_INDEX,
            iconLayer.asLayerInterface() to ICON_INDEX,
        )
    }

    /**
     * 固定レイヤを地図へ載せる。
     *
     * ## クリックは SDK に取らせない
     *
     * どのレイヤも `setLayerClickable(false)` にする。当たり判定はコアの Manager が
     * 地理座標で行う（そうしないとプロバイダごとに結果が変わる）ので、SDK 側の
     * ヒットテストが先に食べてしまうと `clickable = false` の透過もカスケードの順序も
     * 効かなくなる。**ここを消すと「ポリゴンをタップしても下のマーカーに当たらない」
     * という形で壊れる。**
     */
    fun attachTo(map: MapInterface) {
        polygonFillLayer.setLayerClickable(false)
        circleFillLayer.setLayerClickable(false)
        polygonOutlineLayer.setLayerClickable(false)
        circleOutlineLayer.setLayerClickable(false)
        polylineLayer.setLayerClickable(false)
        iconLayer.setLayerClickable(false)

        fixedLayers.forEach { (layer, index) ->
            map.insertLayerAt(layer, index)
        }
    }

    /** 地図デザインのレイヤを差し替える。 */
    fun setDesignLayer(
        map: MapInterface,
        layer: LayerInterface?,
    ) {
        designLayer?.let { map.removeLayer(it) }
        designLayer = layer
        layer ?: return
        map.insertLayerAt(layer, DESIGN_INDEX)
    }

    /**
     * ラスターレイヤ／グラウンドイメージを、オーバーレイ群より下・地図デザインより上へ入れる。
     *
     * @return 外すときに [remove] へ渡す値。**別途 `asLayerInterface()` を呼び直さないこと。**
     */
    fun insertBelowOverlays(
        map: MapInterface,
        layer: LayerInterface,
    ): LayerInterface = insertInBand(map, layer, rasterBand)

    /** グラウンドイメージ: ラスターレイヤの上、ポリゴンの下。 */
    fun insertGroundImage(
        map: MapInterface,
        layer: LayerInterface,
    ): LayerInterface = insertInBand(map, layer, groundImageBand)

    /** ポリゴンのタイル: ポリゴンの帯の一番下（グラウンドイメージの上、ポリゴン塗りの下）。 */
    fun insertPolygonTile(
        map: MapInterface,
        layer: LayerInterface,
    ): LayerInterface = insertInBand(map, layer, polygonTileBand)

    /** ポリラインのタイル: ポリラインの帯の一番下（サークルの上、ネイティブのポリラインの下）。 */
    fun insertCircleTile(map: MapInterface, layer: LayerInterface): LayerInterface =
        insertInBand(map, layer, circleTileBand)

    fun insertPolylineTile(
        map: MapInterface,
        layer: LayerInterface,
    ): LayerInterface = insertInBand(map, layer, polylineTileBand)

    /** マーカーのタイル: ポリラインの上、マーカー（アイコン）の下。 */
    fun insertMarkerTile(
        map: MapInterface,
        layer: LayerInterface,
    ): LayerInterface = insertInBand(map, layer, markerTileBand)

    private fun insertInBand(
        map: MapInterface,
        layer: LayerInterface,
        band: Band,
    ): LayerInterface {
        val index = band.take()
        belowOverlayIndices[layer] = index
        bandOfLayer[layer] = band
        map.insertLayerAt(layer, index)
        return layer
    }

    fun remove(
        map: MapInterface,
        layer: LayerInterface,
    ) {
        val index = belowOverlayIndices.remove(layer)
        val band = bandOfLayer.remove(layer)
        if (index != null && band != null) band.free.add(index)
        map.removeLayer(layer)
    }

    fun clearAll() {
        polygonFillLayer.clear()
        polygonOutlineLayer.clear()
        circleFillLayer.clear()
        circleOutlineLayer.clear()
        polylineLayer.clear()
        iconLayer.clear()
    }

    private companion object {
        /** 地図デザイン。一番下。 */
        const val DESIGN_INDEX = 0

        /** ラスターレイヤ。地図デザインの上。 */
        const val RASTER_INDEX_FIRST = 1

        /** 以前の名前（ラスターとグラウンドイメージが共用していた区間の先頭）。 */
        @Suppress("unused")
        const val BELOW_INDEX_FIRST = RASTER_INDEX_FIRST

        /** グラウンドイメージ。ラスターの上、ポリゴンの下。 */
        const val GROUND_IMAGE_INDEX_FIRST = 500

        /** ポリゴンのタイル。グラウンドイメージの上、ポリゴン塗りの下。 */
        const val POLYGON_TILE_INDEX_FIRST = 900

        /** 固定のオーバーレイ 6 種。下の枠を使い切らないよう十分離す。 */
        const val FIXED_INDEX_FIRST = 1_000

        /** ポリラインのタイル。サークルの上、ネイティブのポリラインの下。 */
        const val POLYLINE_TILE_INDEX_FIRST = FIXED_INDEX_FIRST + 10

        /** ネイティブのポリライン（固定）。ポリラインのタイルの上。 */
        const val POLYLINE_INDEX = FIXED_INDEX_FIRST + 50

        /** マーカーのタイル。ポリラインの上、マーカーの下。 */
        const val MARKER_TILE_INDEX_FIRST = 1_100

        /** マーカー（アイコン）。一番上。 */
        const val ICON_INDEX = 2_000
    }
}
