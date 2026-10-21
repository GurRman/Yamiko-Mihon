package eu.kanade.tachiyomi.extension.ru.yamiko

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Yamiko : KeiSource() {

    // Ссылки на тайтлы: /manga/{id-slug}; главы: /manga/{slug}/chapter/{id}
    private val mangaUrlRegex = Regex("""^/manga/\d+[^/]*$""")
    private val chapterUrlRegex = Regex("""^/manga/.+/chapter/\d+""")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)
    private val dateRegex = Regex("""\d{2}\.\d{2}\.\d{4}""")

    // ============================== Popular ===============================
    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get("$baseUrl/").use { it.asJsoup() }
        return MangasPage(parseMangaList(document), false)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get("$baseUrl/").use { it.asJsoup() }
        return MangasPage(parseMangaList(document), false)
    }

    // ============================== Search ===============================
    // [VERIFY] проверьте реальный URL поиска на сайте и поправьте путь/параметры.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .build()

        val document = client.get(url).use { it.asJsoup() }
        val hasNextPage = document.selectFirst("a[rel='next'], a:contains(Вперёд):not([aria-disabled=true])") != null
        return MangasPage(parseMangaList(document), hasNextPage)
    }

    private fun parseMangaList(document: org.jsoup.nodes.Document): List<SManga> {
        return document.select("a[href*='/manga/']")
            .mapNotNull { element ->
                val href = element.attr("href").substringBefore("?")
                if (!mangaUrlRegex.matches(href)) return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    title = element.selectFirst("h3, .title")?.text()?.takeIf { it.isNotBlank() }
                        ?: element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                        ?: element.attr("title").takeIf { it.isNotBlank() }
                        ?: "Без названия"
                    thumbnail_url = element.selectFirst("img")?.imgAttr()
                }
            }
            .distinctBy { it.url }
    }

    // =========================== Manga Details / Chapters ============================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) {
            val document = client.get("$baseUrl${manga.url}").use { it.asJsoup() }
            SManga.create().apply {
                url = manga.url
                title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: manga.title
                thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: document.selectFirst("img")?.imgAttr()
                    ?: manga.thumbnail_url
                genre = document.select("a[href*='genre'], .genres a").eachText().joinToString()
                description = document.selectFirst(".description, [itemprop='description']")?.text()
                    ?: document.selectFirst("meta[name='description']")?.attr("content")
                val meta = document.selectFirst("h1")?.parent()?.text()
                    ?: document.body().text().substringBefore("Похожие")
                status = when {
                    meta.contains("Онгоинг", true) -> SManga.ONGOING
                    meta.contains("Заверш", true) -> SManga.COMPLETED
                    meta.contains("Заморожен", true) -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            client.get("$baseUrl${manga.url}").use { it.asJsoup() }
                .select("a[href*='/chapter/']")
                .mapNotNull { element -> chapterFromElement(element) }
                .distinctBy { it.url }
                .reversed()
        } else {
            chapters
        }

        return SMangaUpdate(details, chapterList)
    }

    private fun chapterFromElement(element: Element): SChapter? {
        val href = element.attr("href").substringBefore("?")
        if (!chapterUrlRegex.matches(href)) return null
        val text = element.text().trim()
        if (text.isBlank()) return null
        return SChapter.create().apply {
            setUrlWithoutDomain(href)
            name = text.substringBefore("Ямико Бот").trim().ifBlank { "Глава" }
            val dateStr = dateRegex.find(text)?.value
            date_upload = dateStr?.let {
                runCatching {
                    LocalDate.parse(it, dateFormat).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }.getOrDefault(0L)
            } ?: 0L
        }
    }

    // =========================== Pages ============================
    // [VERIFY] если не сработает, найдите в DevTools (Network) XHR-запрос
    // с массивом страниц и добавьте его сюда.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").use { it.asJsoup() }

        val pages = mutableListOf<Page>()
        document.select("[class*='reader'] img, [class*='page'] img, main img").forEach { img ->
            img.imgAttr().takeIf { it.isNotBlank() && !it.contains("logo", true) }?.let {
                pages += Page(pages.size, imageUrl = it)
            }
        }
        if (pages.isNotEmpty()) return pages.distinctBy { it.imageUrl }

        extractImageUrls(document.html()).forEachIndexed { idx, url ->
            pages += Page(idx, imageUrl = url)
        }
        if (pages.isNotEmpty()) return pages.distinctBy { it.imageUrl }

        throw Exception(
            "Не найдены страницы главы. Откройте главу на сайте, включите DevTools → Network, " +
                "найдите запрос со ссылками на картинки и добавьте его в getPageList (Yamiko.kt)."
        )
    }

    private fun extractImageUrls(raw: String): List<String> {
        val regex = Regex("""https?://[^"'\s]+?\.(?:jpe?g|png|webp)""")
        return regex.findAll(raw)
            .map { it.value.replace("\\u002F", "/").replace("\\/", "/") }
            .filter {
                it.contains("/uploads/", true) || it.contains("/media/", true) || it.contains("/chapters/", true)
            }
            .toList()
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-src") && absUrl("data-src").isNotBlank() -> absUrl("data-src")
        hasAttr("data-lazy-src") && absUrl("data-lazy-src").isNotBlank() -> absUrl("data-lazy-src")
        else -> absUrl("src")
    }
}
