package com.drifly.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class StreetViewResult(
    val bitmap: Bitmap?,
    val embedHtml: String,
    val heading: Long = 0L
)

object StreetViewHelper {

    private const val TAG = "StreetViewHelper"

    // Chave padrão para visualização de mapas e streetview no Android
    private const val MAPS_KEY = "AIzaSyDfONk1O4oP2ki_MDcpthSJycBRVOOnhiY"

    // Cache LRU em memória (40 itens) para evitar re-geocodificar endereços repetidos
    private val addressCache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, String>(40, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 40
            }
        }
    )

    private val bitmapCache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Bitmap>(25, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
                return size > 25
            }
        }
    )

    fun buildStaticStreetViewUrl(
        panoId: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        rawAddress: String? = null,
        heading: Long = 0L
    ): String {
        return when {
            !panoId.isNullOrBlank() -> {
                "https://maps.googleapis.com/maps/api/streetview?size=640x320&pano=$panoId&heading=$heading&pitch=0&fov=90&source=outdoor&key=$MAPS_KEY"
            }
            lat != null && lng != null -> {
                "https://maps.googleapis.com/maps/api/streetview?size=640x320&location=$lat,$lng&heading=$heading&pitch=0&fov=90&source=outdoor&key=$MAPS_KEY"
            }
            else -> {
                val encoded = URLEncoder.encode("${rawAddress ?: ""}, Brasil", "UTF-8")
                "https://maps.googleapis.com/maps/api/streetview?size=640x320&location=$encoded&pitch=0&fov=90&source=outdoor&key=$MAPS_KEY"
            }
        }
    }

    /**
     * Baixa a foto real da fachada via Google Street View Static API (ImageView nativo, sem tela preta de WebGL)
     * e também prepara a URL/HTML caso o motorista queira girar em 360°.
     */
    suspend fun fetchStreetViewPreview(context: Context, rawAddress: String): StreetViewResult = withContext(Dispatchers.IO) {
        val cleanAddr = rawAddress.trim().replace(Regex("""\s+"""), " ")
        if (cleanAddr.isEmpty()) return@withContext StreetViewResult(null, "")

        val cachedBmp = bitmapCache[cleanAddr]
        val cachedHtml = addressCache[cleanAddr]
        if (cachedBmp != null && cachedHtml != null) {
            return@withContext StreetViewResult(cachedBmp, cachedHtml)
        }

        var staticUrl: String? = null
        var headingVal = 0L

        try {
            val geocoder = Geocoder(context, Locale("pt", "BR"))
            val query = "$cleanAddr, Brasil"

            @Suppress("DEPRECATION")
            val addresses = try {
                geocoder.getFromLocationName(query, 1)
            } catch (e: Exception) {
                null
            }

            if (!addresses.isNullOrEmpty()) {
                val dest = addresses[0]
                val lat = dest.latitude
                val lng = dest.longitude

                // Consulta a API de Metadados do Google Street View
                val metaUrl = "https://maps.googleapis.com/maps/api/streetview/metadata?location=$lat,$lng&source=outdoor&key=$MAPS_KEY"
                val conn = URL(metaUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 3500
                conn.readTimeout = 3500
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(responseText)
                val status = json.optString("status")

                if (status == "OK") {
                    val panoId = json.optString("pano_id")
                    val locObj = json.optJSONObject("location")
                    val panoLat = locObj?.optDouble("lat") ?: lat
                    val panoLng = locObj?.optDouble("lng") ?: lng

                    // Cálculo trigonométrico do ângulo azimute (heading) para a câmera apontar para o imóvel
                    val dLng = Math.toRadians(lng - panoLng)
                    val lat1 = Math.toRadians(panoLat)
                    val lat2 = Math.toRadians(lat)
                    val y = Math.sin(dLng) * Math.cos(lat2)
                    val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
                    headingVal = Math.round((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0)

                    staticUrl = buildStaticStreetViewUrl(panoId = panoId, lat = lat, lng = lng, heading = headingVal)
                } else {
                    staticUrl = buildStaticStreetViewUrl(lat = lat, lng = lng, heading = 0L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao resolver coordenadas do Street View: ${e.message}")
        }

        if (staticUrl == null) {
            staticUrl = buildStaticStreetViewUrl(rawAddress = cleanAddr)
        }

        // Baixa o Bitmap real da fachada
        var downloadedBitmap: Bitmap? = null
        try {
            val imgConn = URL(staticUrl).openConnection() as HttpURLConnection
            imgConn.connectTimeout = 4000
            imgConn.readTimeout = 4000
            imgConn.doInput = true
            imgConn.connect()
            if (imgConn.responseCode == 200) {
                imgConn.inputStream.use { input ->
                    downloadedBitmap = BitmapFactory.decodeStream(input)
                }
            }
            imgConn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao baixar imagem estática do Street View: ${e.message}")
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>
            * { margin:0; padding:0; box-sizing:border-box; }
            html, body { width:100%; height:100%; background:#0D0E12; overflow:hidden; display:flex; align-items:center; justify-content:center; }
            img { width:100%; height:100%; object-fit:cover; display:block; }
            </style>
            </head>
            <body>
            <img src="$staticUrl" alt="Street View" />
            </body>
            </html>
        """.trimIndent()

        if (downloadedBitmap != null) {
            bitmapCache[cleanAddr] = downloadedBitmap!!
        }
        addressCache[cleanAddr] = html

        StreetViewResult(downloadedBitmap, html, headingVal)
    }

    suspend fun getStreetViewHtml(context: Context, rawAddress: String, currentLocation: Location?): String {
        return fetchStreetViewPreview(context, rawAddress).embedHtml
    }
}
