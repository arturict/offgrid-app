package ch.offgrid.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDetectorTest {
    private fun urls(text: String) = LinkDetector.find(text).map { it.url }

    /** The linked slice of [text], to check ranges and not only targets. */
    private fun shown(text: String) = LinkDetector.find(text).map { text.substring(it.start, it.end) }

    @Test
    fun httpAndHttpsAreKeptAsTyped() {
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("https://offgrid-mesh.ch"))
        assertEquals(listOf("http://example.com/a"), urls("Schau http://example.com/a an"))
        assertEquals(listOf("https://Example.com"), urls("HTTPS://Example.com"))
    }

    @Test
    fun wwwAndBareDomainsGetHttps() {
        assertEquals(listOf("https://www.offgrid-mesh.ch"), urls("www.offgrid-mesh.ch"))
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("mehr auf offgrid-mesh.ch"))
        assertEquals(listOf("https://www.example.photography"), urls("www.example.photography"))
    }

    @Test
    fun umlautDomainsOpenAsPunycode() {
        assertEquals(listOf("https://xn--bcher-kva.ch"), urls("bücher.ch"))
        assertEquals(listOf("https://xn--zrich-kva.ch/karte"), urls("zürich.ch/karte"))
        assertEquals(listOf("bücher.ch"), shown("im Laden bücher.ch."))
        assertEquals(listOf("https://xn--bcher-kva.ch/ü?x=1"), urls("https://bücher.ch/ü?x=1"))
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheLink() {
        for (p in listOf(".", ",", ";", ":", "!", "?", "...", "\"", "'", "»", "“", "”", "’", "!?")) {
            assertEquals("p=$p", listOf("https://offgrid-mesh.ch"), urls("https://offgrid-mesh.ch$p"))
            assertEquals("p=$p", listOf("https://offgrid-mesh.ch"), urls("offgrid-mesh.ch$p Danke"))
        }
        assertEquals(listOf("https://a.ch/x"), shown("\"https://a.ch/x\""))
    }

    @Test
    fun balancedParenthesesAreKept() {
        assertEquals(
            listOf("https://de.wikipedia.org/wiki/Foo_(Bar)"),
            urls("https://de.wikipedia.org/wiki/Foo_(Bar)"),
        )
        assertEquals(
            listOf("https://de.wikipedia.org/wiki/Foo_(Bar)"),
            urls("siehe https://de.wikipedia.org/wiki/Foo_(Bar)."),
        )
        assertEquals(
            listOf("https://de.wikipedia.org/wiki/Foo_(Bar)"),
            urls("(https://de.wikipedia.org/wiki/Foo_(Bar))"),
        )
    }

    @Test
    fun enclosingParenthesesAreDropped() {
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("(siehe offgrid-mesh.ch)"))
        assertEquals(listOf("offgrid-mesh.ch"), shown("(siehe offgrid-mesh.ch)"))
        assertEquals(listOf("https://a.ch/x"), urls("[https://a.ch/x]"))
        assertEquals(listOf("https://a.ch/x"), urls("(https://a.ch/x), und weiter"))
    }

    @Test
    fun quotesAroundALinkAreDropped() {
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("«https://offgrid-mesh.ch»"))
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("„offgrid-mesh.ch“"))
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("'offgrid-mesh.ch'"))
    }

    @Test
    fun multipleLinksKeepTheirRanges() {
        val text = "a.ch und https://b.org/x, dann www.c.de!"
        assertEquals(listOf("a.ch", "https://b.org/x", "www.c.de"), shown(text))
        assertEquals(
            listOf("https://a.ch", "https://b.org/x", "https://www.c.de"),
            urls(text),
        )
    }

    @Test
    fun textWithoutLinksGivesNothing() {
        assertTrue(LinkDetector.find("").isEmpty())
        assertTrue(LinkDetector.find("Hallo, wie geht es dir? Bis später!").isEmpty())
        assertTrue(LinkDetector.find("https://").isEmpty())
        assertTrue(LinkDetector.find("www.").isEmpty())
    }

    @Test
    fun abbreviationsVersionsAndNumbersAreNoLinks() {
        for (s in listOf(
            "z.B. heute", "z. B. heute", "d.h. morgen", "u.a. das", "usw. und so", "ca. 5 km", "bzw.",
            "v1.2", "Version 1.2.3", "3.5", "1.000.000,50", "12.10.2026", "um 10.30 Uhr",
            "bild.png", "Foto.jpg", "notes.txt", "main.py", "run.sh", "archiv.zip", "readme.md",
            "hallo.du", "Hallo.Es", "Ok.Gut", "offgrid.chat", "192.168.1.1", "e.V.", "Dr.med.",
        )) {
            assertTrue("no link in <$s> but got ${urls(s)}", LinkDetector.find(s).isEmpty())
        }
    }

    @Test
    fun unknownSchemesAreNeverLinked() {
        assertTrue(LinkDetector.find("javascript:alert(1)").isEmpty())
        assertTrue(LinkDetector.find("intent://scan/#Intent;end").isEmpty())
        assertTrue(LinkDetector.find("ftp://example.com/a").isEmpty())
    }

    @Test
    fun emailBecomesMailto() {
        assertEquals(listOf("mailto:info@offgrid-mesh.ch"), urls("Schreib an info@offgrid-mesh.ch."))
        assertEquals(listOf("info@offgrid-mesh.ch"), shown("(info@offgrid-mesh.ch)"))
        assertEquals(listOf("mailto:max.muster+x@bücher.ch"), urls("max.muster+x@bücher.ch"))
        assertEquals(LinkDetector.Kind.EMAIL, LinkDetector.find("a@b.ch").single().kind)
    }

    @Test
    fun emailDomainIsNeverALinkOnItsOwn() {
        assertEquals(1, LinkDetector.find("info@offgrid-mesh.ch").size)
        assertEquals(1, LinkDetector.find("info@www.offgrid-mesh.ch").size)
        // Unknown TLD: not an address, and the domain half stays unlinked as well.
        assertTrue(LinkDetector.find("info@offgrid-mesh.chat").isEmpty())
        assertTrue(LinkDetector.find("name@host").isEmpty())
    }

    @Test
    fun veryLongUrlIsOneLink() {
        val path = "a".repeat(500) + "/" + "b=c&".repeat(100) + "end"
        val text = "Lang: https://example.com/$path ok"
        val link = LinkDetector.find(text).single()
        assertEquals("https://example.com/$path", link.url)
        assertEquals(text.indexOf("https"), link.start)
        assertEquals(text.length - 3, link.end)
    }

    @Test
    fun emojiAroundLinksAreNotPartOfThem() {
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("👉https://offgrid-mesh.ch👈"))
        assertEquals(listOf("https://offgrid-mesh.ch"), urls("😀 offgrid-mesh.ch 🎉"))
        assertEquals(listOf("https://offgrid-mesh.ch/x"), urls("offgrid-mesh.ch/x😀"))
        val text = "🎉 www.a.ch 🎉"
        val link = LinkDetector.find(text).single()
        assertEquals("www.a.ch", text.substring(link.start, link.end))
    }

    @Test
    fun pathQueryFragmentAndPortAreKept() {
        val u = "https://example.com:8443/a/b.html?x=1&y=%C3%A4#frag"
        assertEquals(listOf(u), urls(u))
        assertEquals(listOf("https://offgrid-mesh.ch:8080/x?q=1#top"), urls("offgrid-mesh.ch:8080/x?q=1#top"))
        assertEquals(listOf("https://example.com/path/"), urls("example.com/path/"))
        assertEquals(listOf("https://xn--bcher-kva.ch:81/x"), urls("https://bücher.ch:81/x"))
        assertEquals(listOf("https://user@example.com/"), urls("https://user@example.com/"))
    }
}
