package tw.thorsheep.studentjournal

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest

/** Add sources in Data.kt; use a dedicated parser only for a public API or a special layout. */
object AnnouncementFetcher {
    private val date = Regex("(20\\d{2}|1[01]\\d)[./-]\\d{1,2}[./-]\\d{1,2}")
    private const val userAgent = "Simple-App/2.1 announcement reader"

    suspend fun fetch(source: AnnouncementSource): List<Announcement> = when (source.parser) {
        AnnouncementParser.SHSD_JSON -> fetchShsd(source)
        AnnouncementParser.CSIE_SECTIONS -> fetchCsieSections(source)
        AnnouncementParser.NTOU_CSIE, AnnouncementParser.NTOU_LIST -> fetchNtouList(source)
        AnnouncementParser.GENERIC -> fetchGeneric(source)
    }.distinctBy { it.url }.take(80)

    private fun fetchShsd(source: AnnouncementSource): List<Announcement> {
        val endpoint = source.url.trimEnd('/') + "/Home/GetNews?page=1&pageSize=80"
        val news = JSONObject(get(endpoint)).getJSONArray("news")
        return (0 until news.length()).mapNotNull { index ->
            val item = news.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val title = normalized(item.optString("title"))
            val published = item.optString("startDate")
            if (id.isBlank() || title.length !in 2..180 || !date.containsMatchIn(published)) null else Announcement(
                id = digest(source.id + id), sourceId = source.id, title = title,
                date = normalizedDate(published), url = source.url.trimEnd('/') + "/News/Detail/$id",
                category = normalized(item.optString("categoryText"))
            )
        }
    }

    private fun fetchCsieSections(source: AnnouncementSource): List<Announcement> {
        val document = document(source.url)
        return document.select(".announcement-scope[data-scope-tag]").flatMap { scope ->
            val category = normalized(scope.attr("data-scope-tag"))
            scope.select("a.link[href]").mapNotNull { link -> announcementFromLink(source, link, category) }
        }
    }

    private fun fetchNtouList(source: AnnouncementSource): List<Announcement> =
        document(source.url).select(".d-item.d-title .mtitle").mapNotNull { row ->
            val link = row.selectFirst("a[href]") ?: return@mapNotNull null
            val title = normalized(link.text())
            val href = link.absUrl("href")
            val published = normalized(row.selectFirst(".mdate")?.text().orEmpty())
            if (title.length !in 8..180 || href.isBlank() || !href.startsWith("https://") ||
                !date.containsMatchIn(published) || !isAllowedSchoolUrl(href)) return@mapNotNull null
            Announcement(
                id = digest(source.id + href), sourceId = source.id, title = title,
                date = normalizedDate(published), url = href, category = "重要公告"
            )
        }

    private fun fetchGeneric(source: AnnouncementSource): List<Announcement> =
        document(source.url).select("a[href]").mapNotNull { link ->
            announcementFromLink(source, link, categoryFrom(link))
        }

    private fun announcementFromLink(source: AnnouncementSource, link: Element, category: String): Announcement? {
        val title = normalized(link.attr("title").ifBlank { link.text() })
        val href = link.absUrl("href")
        if (title.length !in 8..180 || href.isBlank() || !href.startsWith("https://") ||
            title in ignoredTitles || href == source.url || !isAllowedSchoolUrl(href)) return null
        val surrounding = sequenceOf(link.text(), link.parent()?.text(), link.parent()?.parent()?.text())
            .filterNotNull().joinToString(" ")
        val published = date.find(surrounding)?.value ?: return null
        return Announcement(digest(source.id + href), source.id, title, normalizedDate(published), href, category)
    }

    private fun categoryFrom(link: Element): String {
        link.closest("tr")?.select("td")?.let { cells ->
            if (cells.size >= 3) return normalized(cells[1].text())
        }
        val container = generateSequence(link.parent()) { it.parent() }.take(5)
            .firstOrNull { it.hasAttr("data-category") || it.hasAttr("data-category-tag") } ?: return ""
        return normalized(container.attr("data-category-tag").ifBlank { container.attr("data-category") })
    }

    private fun document(url: String) = Jsoup.connect(url).userAgent(userAgent).timeout(15_000).get()
    private fun get(url: String) = Jsoup.connect(url).userAgent(userAgent).timeout(15_000).ignoreContentType(true).execute().body()
    private fun normalized(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun normalizedDate(value: String) = value.replace('/', '-').replace('.', '-')
    private fun isAllowedSchoolUrl(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host.endsWith("ncu.edu.tw") || host.endsWith("ntou.edu.tw")
    }
    private val ignoredTitles = setOf("更多", "More", "最新消息", "首頁", "English")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
