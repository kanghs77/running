package app.runmate.core

/** 표시 언어. 화면 쪽에서 provider를 연결한다(기본: 한국어). */
object Lang {
    @Volatile var provider: () -> String = { "ko" }
    val code: String get() = provider()
}

/**
 * 현재 언어에 맞는 문구를 고른다. 한국어/영어는 여기 적힌 문구를 그대로 쓰고,
 * 그 밖의 언어는 영어 문구를 열쇠로 번역표(I18n)에서 찾는다. 없으면 영어.
 */
fun tr(ko: String, en: String): String = when (val l = Lang.code) {
    "ko" -> ko
    "en" -> en
    else -> I18n.translate(l, en)
}
