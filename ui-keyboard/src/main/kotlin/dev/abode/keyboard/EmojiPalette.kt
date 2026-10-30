package dev.abode.keyboard

/**
 * The tabs of the emoji page, in order: Recent, the Unicode categories, then
 * Kaomoji. Recent is filled by the keyboard from what the person has used.
 */
object EmojiPalette {
    /** Recently used emoji and kaomoji, most recent first. */
    var recents: List<String> = emptyList()

    val tabs: List<EmojiCategory>
        get() = listOf(EmojiCategory("Recent", "🕘", recents)) +
            EmojiCatalog.categories +
            EmojiCategory(KAOMOJI_NAME, "ツ", KAOMOJI)

    const val KAOMOJI_NAME = "Kaomoji"

    val KAOMOJI: List<String> = listOf(
        "(^_^)", "(^o^)", "(-_-)", "(;_;)", "(T_T)", "(>_<)", "(¬_¬)", "(ಠ_ಠ)",
        "(╥_╥)", "(⊙_⊙)", "(°ロ°)", "(◕‿◕)", "(✿◠‿◠)",
        "(｡♥‿♥｡)", "(≧▽≦)", "(*´▽`*)", "(＾▽＾)",
        "(´∀`)", "(¬‿¬)", "(•̀ᴗ•́)و", "(｡•̀ᴗ-)✧",
        "ʕ•ᴥ•ʔ", "(=^･ω･^=)", "(・ω・)", "(･ω･)ﾉ",
        "¯\\_(ツ)_/¯", "(づ｡◕‿‿◕｡)づ", "( ͡° ͜ʖ ͡°)",
        "(ง'̀-'́)ง", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "(◎_◎;)", "(╬ಠ益ಠ)",
        "(´・ω・`)", "(×_×)", "(・_・)", "(ﾟДﾟ)", "(･ω･)ﾉ✧",
        "(╥﹏╥)", "(っ˘ω˘ς)", "(ﾉ´ヮ`)ﾉ", "(ﾟヮﾟ)",
        "(°□°)╯︵ ┻━┻", "(╯°□°)╯︵ ┻━┻", "ヽ(°〇°)ﾉ",
    )
}
