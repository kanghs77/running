package app.runmate.core

/** 표시 언어. 화면 쪽에서 provider를 연결한다(기본: 한국어). */
object Lang {
    @Volatile var provider: () -> Boolean = { false }
    val en: Boolean get() = provider()
}

/** 현재 언어에 맞는 문구를 고른다. */
fun tr(ko: String, en: String): String = if (Lang.en) en else ko
