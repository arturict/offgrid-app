package ch.offgrid.android.domain

import java.net.IDN

/**
 * Finds web and e-mail links in message text. Pure and offline: it only returns ranges and the
 * address to open, nothing is ever fetched, previewed or reported.
 *
 * Rules:
 *  - `http://` and `https://` are links as typed (other schemes such as `javascript:` or
 *    `intent:` are never linked). `www.` links need a dot-separated host with a letters-only TLD.
 *  - Bare domains (`offgrid-mesh.ch`, `bücher.ch/laden`) need a TLD from the curated [TLDS] set
 *    written in lower case. The set keeps country codes of the DACH/EU region and common
 *    generic TLDs, and leaves out codes that are also German words or file extensions
 *    (`in`, `is`, `so`, `to`, `me`, `py`, `sh`, `zip`, `md`, ...). So `z.B.`, `usw.`, `v1.2`,
 *    `3.5`, `bild.png`, `hallo.du` and the typo `Hallo.Es` are not links. The price is that a
 *    rare TLD is only linked with `www.` or a scheme in front.
 *  - E-mail addresses (`name@domain.tld`, same TLD rule) become `mailto:` links, and their
 *    domain part is never matched as a bare domain. Phone numbers are not detected: separators
 *    and lengths are too ambiguous in free text to do reliably.
 *  - A match never starts inside a word, so `info@x.ch` and `foo.bar.baz` give no second hit.
 *  - Trailing punctuation, quotes, emoji and unbalanced closing brackets are cut off; brackets
 *    that are balanced inside the link stay (`.../Foo_(Bar)`), `(siehe offgrid-mesh.ch)` loses `)`.
 *  - Internationalised hosts keep their Unicode form in the text; the address to open has the
 *    host converted to punycode (`xn--bcher-kva.ch`) so every browser resolves it.
 */
object LinkDetector {
    enum class Kind { WEB, EMAIL }

    /** [start] inclusive, [end] exclusive, both as UTF-16 indices into the analysed text. */
    data class Link(val start: Int, val end: Int, val url: String, val kind: Kind)

    private val TLDS: Set<String> = (
        // generic
        "com org net edu gov info biz io app dev ai co xyz online site tech shop store blog cloud " +
            "page link news live club team wiki mobi name pro asia tv " +
            // country codes: Switzerland and neighbours, then wider Europe and common others
            "ch de at li fr it lu be nl uk ie es pt dk se fi eu pl cz sk hu si hr ro bg gr " +
            "us ca au nz jp cn kr br mx tr ru ua"
        ).split(' ').toSet()

    private const val LABEL = """[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?"""
    private const val HOST = """$LABEL(?:\.$LABEL)+"""
    private const val PORT_PATH = """(?::\d{1,5})?(?:[/?#][^\s<>]*)?"""

    private val PATTERN = Regex(
        """(?<![\p{L}\p{N}@._%+/\-])(?:""" +
            """(?<scheme>(?i:https?)://[^\s<>]+)""" +
            """|(?<email>[\p{L}\p{N}][\p{L}\p{N}._%+-]*@$HOST)""" +
            """|(?<www>(?i:www)\.$HOST(?!@)$PORT_PATH)""" +
            """|(?<bare>$HOST(?!@)$PORT_PATH)""" +
            """)""",
    )

    /** Closing bracket to its opening partner, for the balance check at the end of a link. */
    private val BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{')

    /** Characters a link may end with besides letters and digits. Everything else is trimmed. */
    private const val ENDING_SYMBOLS = "/-_~=&%+#"

    fun find(text: String): List<Link> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Link>()
        for (m in PATTERN.findAll(text)) {
            val groups = m.groups
            val range = (groups["scheme"] ?: groups["email"] ?: groups["www"] ?: groups["bare"])?.range ?: continue
            val end = trimmedEnd(text, range.first, range.last + 1)
            val raw = text.substring(range.first, end)
            val link = when {
                groups["scheme"] != null -> schemeLink(raw)
                groups["email"] != null -> emailLink(raw)
                groups["www"] != null -> hostLink(raw, requireKnownTld = false)
                else -> hostLink(raw, requireKnownTld = true)
            } ?: continue
            out += Link(range.first, end, link.first, link.second)
        }
        return out
    }

    private fun schemeLink(raw: String): Pair<String, Kind>? {
        val sep = raw.indexOf("://")
        val host = hostOf(raw.substring(sep + 3))
        if (host.isEmpty() || !host.first().isLetterOrDigit()) return null
        return withAsciiHost(raw.substring(0, sep).lowercase() + "://", raw.substring(sep + 3)) to Kind.WEB
    }

    private fun hostLink(raw: String, requireKnownTld: Boolean): Pair<String, Kind>? {
        val tld = hostOf(raw).substringAfterLast('.')
        val ok = if (requireKnownTld) tld in TLDS else tld.length >= 2 && tld.all { it.isLetter() }
        return if (ok) withAsciiHost("https://", raw) to Kind.WEB else null
    }

    private fun emailLink(raw: String): Pair<String, Kind>? {
        val tld = raw.substringAfterLast('@').substringAfterLast('.')
        return if (tld in TLDS) "mailto:$raw" to Kind.EMAIL else null
    }

    /** Host without userinfo, port, path, query and fragment. */
    private fun hostOf(authorityAndRest: String): String {
        val authority = authorityAndRest.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':')
    }

    /** [prefix] + [rest] with the host part converted to punycode; the rest is kept verbatim. */
    private fun withAsciiHost(prefix: String, rest: String): String {
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val hostStart = authority.lastIndexOf('@') + 1
        val hostEnd = authority.indexOf(':', hostStart).let { if (it < 0) authority.length else it }
        val host = authority.substring(hostStart, hostEnd)
        val ascii = try {
            IDN.toASCII(host)
        } catch (_: IllegalArgumentException) {
            host
        }
        return prefix + authority.substring(0, hostStart) + ascii + authority.substring(hostEnd) + rest.substring(authorityEnd)
    }

    /** Index after the last character that still belongs to the link that spans [start] until [end]. */
    private fun trimmedEnd(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start) {
            val cp = text.codePointBefore(e)
            val len = Character.charCount(cp)
            val keep = when {
                Character.isLetterOrDigit(cp) -> true
                cp < 128 && cp.toChar() in ENDING_SYMBOLS -> true
                cp < 128 && cp.toChar() in BRACKETS -> isBalanced(text, start, e, cp.toChar())
                else -> false
            }
            if (keep) break
            e -= len
        }
        return e
    }

    /** True when the closing bracket [close] at the end of `text[start, end)` has an opener inside the link. */
    private fun isBalanced(text: String, start: Int, end: Int, close: Char): Boolean {
        val open = BRACKETS.getValue(close)
        var depth = 0
        for (i in start until end) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
        }
        return depth >= 0
    }
}
