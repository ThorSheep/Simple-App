package tw.thorsheep.studentjournal

import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest

object AnnouncementFetcher {
    private val date = Regex("(20\\d{2}|1[01]\\d)[./-]\\d{1,2}[./-]\\d{1,2}")
    suspend fun fetch(source: AnnouncementSource): List<Announcement> {
        val document = Jsoup.connect(source.url).userAgent("Simple-App/2.0 announcement reader").timeout(15_000).get()
        val base = URI(source.url)
        return document.select("a[href]").mapNotNull { link ->
            val title = link.text().replace(Regex("\\s+"), " ").trim()
            val href = link.absUrl("href")
            if (title.length !in 8..180 || href.isBlank() || !href.startsWith("https://") ||
                title in setOf("更多", "More", "最新消息", "首頁", "English") || href == source.url) null
            else {
                val host = runCatching { URI(href).host }.getOrNull() ?: return@mapNotNull null
                if (!host.endsWith("ncu.edu.tw") && !host.endsWith("ntou.edu.tw")) return@mapNotNull null
                val surrounding = listOf(link.text(), link.parent()?.text(), link.parent()?.parent()?.text()).joinToString(" ")
                val published = date.find(surrounding)?.value?.replace('/', '-')?.replace('.', '-') ?: return@mapNotNull null
                Announcement(id = digest(source.id + href), sourceId = source.id, title = title, date = published, url = href)
            }
        }.distinctBy { it.url }.take(80)
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
