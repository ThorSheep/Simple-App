package tw.thorsheep.studentjournal

import android.content.Context

data class AnnouncementSource(
    val id: String,
    val school: String,
    val group: String,
    val name: String,
    val url: String,
    val parser: AnnouncementParser = AnnouncementParser.GENERIC,
    val categories: List<String> = emptyList(),
    val parent: String = ""
)

enum class AnnouncementParser { GENERIC, SHSD_JSON, CSIE_SECTIONS, NTOU_CSIE, NTOU_LIST }

object AnnouncementCatalog {
    private const val NCU_ASSET = "announcements/ncu.csv"
    private const val NTOU_ASSET = "announcements/ntou.csv"
    private const val AVAILABLE_ASSET = "announcements/available.csv"

    fun sources(context: Context): List<AnnouncementSource> =
        (loadCsv(context, NCU_ASSET) + loadCsv(context, NTOU_ASSET))
            .filter { (it.school to it.name) in loadAvailable(context) }

    private fun loadAvailable(context: Context): Set<Pair<String, String>> =
        context.assets.open(AVAILABLE_ASSET).bufferedReader().use { reader ->
            reader.readLines().drop(1).mapNotNull { line ->
                val fields = line.split(',').map { it.trim() }
                if (fields.size == 2 && fields.all { it.isNotBlank() }) fields[0] to fields[1] else null
            }.toSet()
        }

    private fun loadCsv(context: Context, assetName: String): List<AnnouncementSource> =
        context.assets.open(assetName).bufferedReader().use { reader ->
            reader.readLines().drop(1).mapNotNull { line ->
                val fields = line.split(',').map { it.trim().removeSurrounding("\"").replace("\"\"", "\"") }
                if (fields.size < 7) return@mapNotNull null
                val school = fields[0]
                val group = fields[1]
                val parent = fields[2]
                val name = fields[3]
                val url = fields[4]
                if (school.isBlank() || group.isBlank() || name.isBlank() || url.isBlank()) return@mapNotNull null
                val parser = runCatching { AnnouncementParser.valueOf(fields[5]) }
                    .getOrDefault(AnnouncementParser.GENERIC)
                AnnouncementSource(
                    id = sourceId(school, group, parent, name), school = school, group = group,
                    name = name, url = url, parser = parser,
                    categories = fields[6].split('|').filter { it.isNotBlank() }, parent = parent
                )
            }
        }

    private fun sourceId(school: String, group: String, parent: String, name: String) = when (school) {
        "國立中央大學" -> when (name) {
            "教務處" -> "ncu-academic"
            "電子計算機中心" -> "ncu-computer"
            "圖書館" -> "ncu-library"
            "住宿服務組" -> "ncu-accommodation"
            "資訊工程學系" -> "ncu-csie"
            else -> stableId(school, group, parent, name)
        }
        "國立臺灣海洋大學" -> when (name) {
            "學校公告" -> "ntou-campus"
            "教務處" -> "ntou-academic"
            "學務處" -> "ntou-student"
            "生活輔導組" -> "ntou-life"
            "課外活動指導組" -> "ntou-club"
            "校安中心" -> "ntou-safety"
            "國際事務處" -> "ntou-oia"
            "秘書組" -> "ntou-secretariat"
            "工學院" -> "ntou-engineering"
            "國際學院" -> "ntou-international"
            else -> stableId(school, group, parent, name)
        }
        else -> stableId(school, group, parent, name)
    }

    private fun stableId(school: String, group: String, parent: String, name: String) =
        "source-" + Integer.toUnsignedString("$school|$group|$parent|$name".hashCode(), 16)
}
