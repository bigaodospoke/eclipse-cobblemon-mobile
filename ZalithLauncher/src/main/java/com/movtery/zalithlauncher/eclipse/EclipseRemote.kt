/*
 * Eclipse Cobblemon Launcher - versão modificada não oficial do Zalith Launcher 2
 * Copyright (C) 2026 Eclipse Cobblemon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.eclipse

import com.movtery.zalithlauncher.path.GLOBAL_JSON
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

private const val TAG = "EclipseRemote"

/**
 * Formato do manifest.json do painel Voxelith (formatVersion 1), o mesmo lido pelo launcher de PC.
 */
@Serializable
data class EclipseManifest(
    val formatVersion: Int,
    val name: String = "Modpack",
    val version: String = "",
    val minecraft: String,
    val loader: Loader,
    val files: List<ManifestFile>
) {
    @Serializable
    data class Loader(
        val type: String,
        val version: String
    )

    /**
     * @param keep arquivo que o jogador pode alterar (ex.: options.txt): só é baixado se não existir
     */
    @Serializable
    data class ManifestFile(
        val path: String,
        val url: String,
        val sha1: String,
        val size: Long,
        val keep: Boolean = false
    )

    /**
     * Mesmas validações do launcher de PC, para um manifest quebrado nunca apagar ou sobrescrever nada
     */
    fun validate() {
        require(formatVersion == 1) { "unsupported formatVersion $formatVersion" }
        require(minecraft.isNotBlank()) { "missing minecraft version" }
        require(loader.type == "fabric") { "unsupported loader ${loader.type}" }
        require(loader.version.isNotBlank()) { "missing loader version" }
        val seen = HashSet<String>()
        files.forEach { file ->
            require(isSafeRelativePath(file.path)) { "unsafe path ${file.path}" }
            require(seen.add(file.path)) { "duplicate path ${file.path}" }
            require(file.url.startsWith("https://")) { "${file.path} has a disallowed URL" }
            require(SHA1_REGEX.matches(file.sha1)) { "${file.path} has an invalid sha1" }
            require(file.size >= 0) { "${file.path} has an invalid size" }
        }
    }
}

@Serializable
data class EclipsePanel(
    val server: Server = Server(),
    val links: Links = Links()
) {
    @Serializable
    data class Server(
        val name: String = EclipseConfig.DEFAULT_SERVER_NAME,
        val address: String = EclipseConfig.DEFAULT_SERVER_ADDRESS
    )

    @Serializable
    data class Links(
        val discord: String? = EclipseConfig.DEFAULT_DISCORD,
        val store: String? = EclipseConfig.DEFAULT_STORE,
        val website: String? = EclipseConfig.DEFAULT_WEBSITE
    )
}

data class EclipseNews(
    val id: String,
    val title: String,
    val body: String?,
    val image: String?,
    val link: String?,
    val date: String?
)

private val SHA1_REGEX = Regex("^[0-9a-f]{40}$")

/**
 * Caminho relativo seguro: sem raiz, sem "..", sem barra invertida
 */
fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
    return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

object EclipseRemote {
    private val cacheDir: File
        get() = File(PathManager.DIR_FILES_PRIVATE, "eclipse").apply { mkdirs() }

    private val manifestCache get() = File(cacheDir, "manifest-cache.json")
    private val panelCache get() = File(cacheDir, "panel-cache.json")
    private val newsCache get() = File(cacheDir, "news-cache.json")

    /**
     * Baixa o manifest; sem internet, usa o último que deu certo.
     * @throws IOException se não houver internet nem cache
     */
    suspend fun fetchManifest(): EclipseManifest = fetchWithCache(
        url = EclipseConfig.MANIFEST_URL,
        cache = manifestCache,
        parse = { raw ->
            GLOBAL_JSON.decodeFromString(EclipseManifest.serializer(), raw).also { it.validate() }
        }
    ) ?: throw IOException("Não foi possível baixar a lista de arquivos do servidor")

    /**
     * IP do servidor e links; nunca falha (cai para os valores padrão).
     */
    suspend fun fetchPanel(): EclipsePanel = fetchWithCache(
        url = EclipseConfig.PANEL_URL,
        cache = panelCache,
        parse = { raw -> GLOBAL_JSON.decodeFromString(EclipsePanel.serializer(), raw) }
    ) ?: EclipsePanel()

    /** Painel salvo da última vez, para a tela já abrir com o IP certo */
    fun cachedPanel(): EclipsePanel = runCatching {
        GLOBAL_JSON.decodeFromString(EclipsePanel.serializer(), panelCache.readText())
    }.getOrDefault(EclipsePanel())

    suspend fun fetchNews(): List<EclipseNews> = fetchWithCache(
        url = EclipseConfig.NEWS_URL,
        cache = newsCache,
        parse = ::parseNews
    ) ?: emptyList()

    private suspend fun <T> fetchWithCache(
        url: String,
        cache: File,
        parse: (String) -> T
    ): T? = withContext(Dispatchers.IO) {
        try {
            val raw = fetchStringFromUrl(url)
            val parsed = parse(raw)
            runCatching { cache.writeText(raw) }
            return@withContext parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.warning(TAG, "Could not load $url, trying the cache", e)
        }
        runCatching { parse(cache.readText()) }.getOrNull()
    }

    /**
     * Aceita `{"news": [...]}` ou uma lista direta, com os campos do painel
     * (title, description, image, url, created_at|date)
     */
    private fun parseNews(raw: String): List<EclipseNews> {
        val root = GLOBAL_JSON.parseToJsonElement(raw)
        val items = (root as? JsonArray) ?: ((root as? JsonObject)?.get("news") as? JsonArray)
            ?: throw IllegalArgumentException("not a news feed")

        fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        return items.mapIndexedNotNull { index, element: JsonElement ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val title = obj.text("title") ?: return@mapIndexedNotNull null
            EclipseNews(
                id = obj.text("id") ?: index.toString(),
                title = title,
                body = obj.text("description"),
                image = obj.text("image")?.takeIf { it.startsWith("https://") },
                link = obj.text("url")?.takeIf { it.startsWith("https://") },
                date = obj.text("created_at") ?: obj.text("date")
            )
        }.sortedByDescending { it.date ?: "" }.take(20)
    }
}
