package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailSegmentGeneratorTest {

    // ── Test data ─────────────────────────────────────────────────────────

    private val section11 = TocEntry("1.1", "chapter1.xhtml#s1")
    private val section12 = TocEntry("1.2", "chapter1.xhtml#s2")
    private val chapter1 = TocEntry("Chapter 1", "chapter1.xhtml", listOf(section11, section12))

    private val section21 = TocEntry("2.1", "chapter2.xhtml#s1")
    private val section22 = TocEntry("2.2", "chapter2.xhtml#s2")
    private val section23 = TocEntry("2.3", "chapter2.xhtml#s3")
    private val chapter2 = TocEntry("Chapter 2", "chapter2.xhtml", listOf(section21, section22, section23))

    private val chapter3 = TocEntry("Chapter 3", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    // ── buildRailSegments: top-level ──────────────────────────────────────

    @Test
    fun `returns one segment per top-level entry`() {
        val segments = buildRailSegments(toc)
        assertEquals(
            listOf(
                RailSegment("Chapter 1", "chapter1.xhtml"),
                RailSegment("Chapter 2", "chapter2.xhtml"),
                RailSegment("Chapter 3", "chapter3.xhtml"),
            ),
            segments,
        )
    }

    @Test
    fun `empty toc returns empty list`() {
        assertEquals(emptyList<RailSegment>(), buildRailSegments(emptyList()))
    }

    @Test
    fun `subchapters are NOT promoted to top level for normally-titled entries`() {
        assertEquals(3, buildRailSegments(toc).size)
    }

    // ── buildRailSegments: blank-title flattening ─────────────────────────

    @Test
    fun `blank-titled entry is replaced by its children at top level`() {
        val story1 = TocEntry("Story 1", "story1.xhtml")
        val story2 = TocEntry("Story 2", "story2.xhtml")
        val blank = TocEntry("  \n ", "container.xhtml", listOf(story1, story2))
        val cover = TocEntry("Cover", "cover.xhtml")
        val credits = TocEntry("Credits", "credits.xhtml")
        val toc = listOf(cover, blank, credits)

        assertEquals(
            listOf(
                RailSegment("Cover", "cover.xhtml"),
                RailSegment("Story 1", "story1.xhtml"),
                RailSegment("Story 2", "story2.xhtml"),
                RailSegment("Credits", "credits.xhtml"),
            ),
            buildRailSegments(toc),
        )
    }

    @Test
    fun `empty title entry is replaced by its children at top level`() {
        val story1 = TocEntry("Story 1", "story1.xhtml")
        val blank = TocEntry("", "container.xhtml", listOf(story1))
        assertEquals(
            listOf(RailSegment("Story 1", "story1.xhtml")),
            buildRailSegments(listOf(blank)),
        )
    }

    @Test
    fun `nested blank-titled entries are recursively expanded at top level`() {
        val leaf = TocEntry("Leaf", "leaf.xhtml")
        val innerBlank = TocEntry("", "inner.xhtml", listOf(leaf))
        val outerBlank = TocEntry("  ", "outer.xhtml", listOf(innerBlank))
        val toc = listOf(outerBlank, TocEntry("Other", "other.xhtml"))

        assertEquals(
            listOf(
                RailSegment("Leaf", "leaf.xhtml"),
                RailSegment("Other", "other.xhtml"),
            ),
            buildRailSegments(toc),
        )
    }

    @Test
    fun `blank-titled leaf entry is kept as a segment with blank title`() {
        val blank = TocEntry("", "lonely.xhtml")
        assertEquals(
            listOf(RailSegment("", "lonely.xhtml")),
            buildRailSegments(listOf(blank)),
        )
    }

    // ── buildRailSegments: book-title-match flattening ────────────────────

    @Test
    fun `container with title equal to book title is flattened`() {
        val ch1 = TocEntry("Chapter 1", "ch1.xhtml")
        val ch2 = TocEntry("Chapter 2", "ch2.xhtml")
        val container = TocEntry("Les Misérables", "container.xhtml", listOf(ch1, ch2))
        val cover = TocEntry("Cover", "cover.xhtml")
        val toc = listOf(cover, container)

        assertEquals(
            listOf(
                RailSegment("Cover", "cover.xhtml"),
                RailSegment("Chapter 1", "ch1.xhtml"),
                RailSegment("Chapter 2", "ch2.xhtml"),
            ),
            buildRailSegments(toc, bookTitle = "Les Misérables"),
        )
    }

    @Test
    fun `container title match is case-insensitive`() {
        val ch1 = TocEntry("Chapter 1", "ch1.xhtml")
        val container = TocEntry("Lucky Starr And The Rings Of Saturn", "container.xhtml", listOf(ch1))
        assertEquals(
            listOf(RailSegment("Chapter 1", "ch1.xhtml")),
            buildRailSegments(listOf(container), bookTitle = "Lucky Starr and the Rings of Saturn"),
        )
    }

    @Test
    fun `container title match is whitespace-insensitive`() {
        val ch1 = TocEntry("Chapter 1", "ch1.xhtml")
        val container = TocEntry("  The   Metamorphosis  ", "container.xhtml", listOf(ch1))
        assertEquals(
            listOf(RailSegment("Chapter 1", "ch1.xhtml")),
            buildRailSegments(listOf(container), bookTitle = "The Metamorphosis"),
        )
    }

    @Test
    fun `container whose title is a prefix of book title is NOT flattened`() {
        // Guards against "Бай Ганьо тръгна по Европа" being treated as "Бай Ганьо".
        // Children are same-file anchors — only book-title-match is under test here.
        val s1 = TocEntry("Section 1", "container.xhtml#s1")
        val container = TocEntry("Foo Bar Baz", "container.xhtml", listOf(s1))
        val segments = buildRailSegments(listOf(container), bookTitle = "Foo")
        assertEquals(1, segments.size)
        assertEquals(RailSegment("Foo Bar Baz", "container.xhtml"), segments[0])
    }

    @Test
    fun `container whose title differs from book title is NOT flattened`() {
        // Children are same-file anchors — only book-title-match is under test here.
        val s1 = TocEntry("Part 1", "appendix.xhtml#p1")
        val container = TocEntry("Appendix", "appendix.xhtml", listOf(s1))
        val other = TocEntry("Chapter 1", "real-ch1.xhtml")
        val toc = listOf(other, container)

        assertEquals(
            listOf(
                RailSegment("Chapter 1", "real-ch1.xhtml"),
                RailSegment("Appendix", "appendix.xhtml"),
            ),
            buildRailSegments(toc, bookTitle = "Some Other Book"),
        )
    }

    @Test
    fun `empty book title disables book-title-match rule`() {
        // A container whose title happens to be "" would still flatten via blank-title rule,
        // but a non-blank container should NOT be flattened when no book title is provided.
        // Children are same-file anchors — only book-title-match is under test here.
        val s1 = TocEntry("Section 1", "container.xhtml#s1")
        val container = TocEntry("Some Container", "container.xhtml", listOf(s1))
        val segments = buildRailSegments(listOf(container), bookTitle = "")
        assertEquals(1, segments.size)
        assertEquals("Some Container", segments[0].title)
    }

    @Test
    fun `blank container is flattened even when book title is provided`() {
        val ch1 = TocEntry("Ch 1", "ch1.xhtml")
        val blank = TocEntry("", "container.xhtml", listOf(ch1))
        assertEquals(
            listOf(RailSegment("Ch 1", "ch1.xhtml")),
            buildRailSegments(listOf(blank), bookTitle = "Some Book"),
        )
    }

    @Test
    fun `book-title-match flattening applies recursively for nested redundant containers`() {
        // Outer container with book title, inner container ALSO with book title (unusual but
        // possible). Both should be flattened, exposing the leaves at top level.
        val leaf = TocEntry("Leaf", "leaf.xhtml")
        val inner = TocEntry("My Book", "inner.xhtml", listOf(leaf))
        val outer = TocEntry("My Book", "outer.xhtml", listOf(inner))
        assertEquals(
            listOf(RailSegment("Leaf", "leaf.xhtml")),
            buildRailSegments(listOf(outer), bookTitle = "My Book"),
        )
    }

    // ── buildRailSegments: multi-file children (part→chapter expansion) ───

    @Test
    fun `part entry with chapter children that have section anchors is expanded into chapters`() {
        // "Influence without authority" structure: Part I → [ch1 (with sections), ch2, ch3]
        // The section anchors on ch1/ch2 are the grandchildren signal that triggers expansion.
        val sec1 = TocEntry("1.1", "c01.xhtml#s1")
        val sec4 = TocEntry("4.1", "c04.xhtml#s1")
        val ch1 = TocEntry("Chapter 1", "c01.xhtml", listOf(sec1))
        val ch2 = TocEntry("Chapter 2", "c02.xhtml")
        val ch3 = TocEntry("Chapter 3", "c03.xhtml")
        val ch4 = TocEntry("Chapter 4", "c04.xhtml", listOf(sec4))
        val ch5 = TocEntry("Chapter 5", "c05.xhtml")
        val part1 = TocEntry("Part I", "part01.xhtml", listOf(ch1, ch2, ch3))
        val part2 = TocEntry("Part II", "part02.xhtml", listOf(ch4, ch5))

        assertEquals(
            listOf(
                RailSegment("Chapter 1", "c01.xhtml"),
                RailSegment("Chapter 2", "c02.xhtml"),
                RailSegment("Chapter 3", "c03.xhtml"),
                RailSegment("Chapter 4", "c04.xhtml"),
                RailSegment("Chapter 5", "c05.xhtml"),
            ),
            buildRailSegments(listOf(part1, part2)),
        )
    }

    @Test
    fun `chapter entry with same-file section heading children is kept as-is`() {
        // "Lean Customer Development" structure: Chapter → [section anchors on same file]
        val s1 = TocEntry("Section 1", "ch01.html#s1")
        val s2 = TocEntry("Section 2", "ch01.html#s2")
        val ch1 = TocEntry("Chapter 1", "ch01.html", listOf(s1, s2))
        val ch2 = TocEntry("Chapter 2", "ch02.html")

        assertEquals(
            listOf(
                RailSegment("Chapter 1", "ch01.html"),
                RailSegment("Chapter 2", "ch02.html"),
            ),
            buildRailSegments(listOf(ch1, ch2)),
        )
    }

    @Test
    fun `three-level nesting — part expands to chapters, chapters keep and drop same-file section anchors`() {
        val sec1a = TocEntry("1a", "c01.xhtml#s1")
        val sec1b = TocEntry("1b", "c01.xhtml#s2")
        val sec2a = TocEntry("2a", "c02.xhtml#s1")
        val ch1 = TocEntry("Chapter 1", "c01.xhtml", listOf(sec1a, sec1b))
        val ch2 = TocEntry("Chapter 2", "c02.xhtml", listOf(sec2a))
        val part1 = TocEntry("Part I", "part01.xhtml", listOf(ch1, ch2))

        assertEquals(
            listOf(
                RailSegment("Chapter 1", "c01.xhtml"),
                RailSegment("Chapter 2", "c02.xhtml"),
            ),
            buildRailSegments(listOf(part1)),
        )
    }

    @Test
    fun `part with mixed children expands when the cross-file child has grandchildren`() {
        // Same-file anchor intro + cross-file chapter that itself has section anchors.
        val sec1 = TocEntry("Ch 1 intro", "c01.xhtml#s1")
        val intro = TocEntry("Intro", "part01.xhtml#intro")
        val ch1 = TocEntry("Chapter 1", "c01.xhtml", listOf(sec1))
        val part1 = TocEntry("Part I", "part01.xhtml", listOf(intro, ch1))

        assertEquals(
            listOf(
                RailSegment("Intro", "part01.xhtml#intro"),
                RailSegment("Chapter 1", "c01.xhtml"),
            ),
            buildRailSegments(listOf(part1)),
        )
    }

    @Test
    fun `chapter with cross-file leaf children and no grandchildren is NOT expanded`() {
        // "The Martian" structure: Chapter 20 → [SOL 376 (leaf), SOL 380 (leaf)].
        // Log-entry files have no sub-entries, so the grandchildren guard blocks expansion.
        // Without this guard, the cursor would jump between log-entry segments on page turn.
        val sol376 = TocEntry("LOG ENTRY: SOL 376", "sol376.xhtml")  // leaf — no children
        val sol380 = TocEntry("LOG ENTRY: SOL 380", "sol380.xhtml")  // leaf — no children
        val ch20 = TocEntry("Chapter 20", "chapter20.xhtml", listOf(sol376, sol380))

        assertEquals(
            listOf(RailSegment("Chapter 20", "chapter20.xhtml")),
            buildRailSegments(listOf(ch20)),
        )
    }

    // ── buildRailSegments: length-based expansion (positions supplied) ────

    @Test
    fun `Barkley-shape — part with short title page and long chapter leaves is expanded`() {
        // Real subset of "Taking Charge of ADHD, Fourth Edition" NCX: each Part points to a
        // short title-page file, and its leaf-chapter children each occupy their own full
        // chapter file. Under the current grandchildren guard the Parts collapse and the
        // chapters vanish from the rail. With position info the length rule keeps them.
        val ch01 = TocEntry("1 What Is ADHD?", "ch01.html")
        val ch02 = TocEntry("2 Poor Self-Regulation", "ch02.html")
        val ch03 = TocEntry("3 What Causes ADHD?", "ch03.html")
        val ch07 = TocEntry("7 Deciding to Evaluate", "ch07.html")
        val ch08 = TocEntry("8 Preparing for the Evaluation", "ch08.html")
        val partI = TocEntry("Part I. Understanding ADHD", "pt01.html", listOf(ch01, ch02, ch03))
        val partII = TocEntry("Part II. Taking Charge", "pt02.html", listOf(ch07, ch08))

        val spineHrefs = listOf(
            "pt01.html", "ch01.html", "ch02.html", "ch03.html",
            "pt02.html", "ch07.html", "ch08.html",
        )
        // Part title pages are short (~2 positions); real chapters are substantial (~25).
        val positionCounts = listOf(2, 25, 25, 25, 2, 25, 25)

        assertEquals(
            listOf(
                RailSegment("1 What Is ADHD?", "ch01.html"),
                RailSegment("2 Poor Self-Regulation", "ch02.html"),
                RailSegment("3 What Causes ADHD?", "ch03.html"),
                RailSegment("7 Deciding to Evaluate", "ch07.html"),
                RailSegment("8 Preparing for the Evaluation", "ch08.html"),
            ),
            buildRailSegments(listOf(partI, partII), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `Martian-shape — chapter with short SOL log-entry leaves is NOT expanded when positions supplied`() {
        // Same shape as the existing Martian test but with position info. The chapter file
        // is substantial (~15 positions); each SOL log entry is a short vignette (~3). The
        // length rule must keep the chapter as one segment even though every child lives in
        // a different spine file.
        val sol63 = TocEntry("LOG ENTRY: SOL 63", "sol063.xhtml")
        val sol64 = TocEntry("LOG ENTRY: SOL 64", "sol064.xhtml")
        val sol65 = TocEntry("LOG ENTRY: SOL 65", "sol065.xhtml")
        val ch4 = TocEntry("Chapter 4", "chapter4.xhtml", listOf(sol63, sol64, sol65))

        val spineHrefs = listOf("chapter4.xhtml", "sol063.xhtml", "sol064.xhtml", "sol065.xhtml")
        val positionCounts = listOf(15, 3, 3, 3)

        assertEquals(
            listOf(RailSegment("Chapter 4", "chapter4.xhtml")),
            buildRailSegments(listOf(ch4), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `length rule expands empty-content container even against non-trivial children`() {
        // A parent that resolves to a spine resource with 0 positions is a pure grouping
        // container. Any positive-length child should still qualify — the parent has no
        // content of its own that we'd be replacing.
        val a = TocEntry("A", "a.xhtml")
        val b = TocEntry("B", "b.xhtml")
        val container = TocEntry("Group", "group.xhtml", listOf(a, b))
        val spineHrefs = listOf("group.xhtml", "a.xhtml", "b.xhtml")
        val positionCounts = listOf(0, 10, 10)

        assertEquals(
            listOf(RailSegment("A", "a.xhtml"), RailSegment("B", "b.xhtml")),
            buildRailSegments(listOf(container), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `Silyan-shape — non-blank stub-titled container with mostly-substantial children IS expanded`() {
        // Real subset of "Силян Щърка" (Ангел Каралийчев). The EPUB3 nav supplies a stub
        // title ("2") for the container that wraps all 19 folk tales at chapter-0.xhtml
        // — Readium prefers nav over NCX, so the historical blank-title fallback (the NCX
        // had "\n\n") no longer catches this book. The container's own spine file is a short
        // section-title page; most children are substantial full tales, with a couple of very
        // short outliers. Requiring EVERY child to be ≥ 2× parent would fail on the outliers
        // and leave the whole book behind one segment labelled "2". Majority (> half of
        // cross-file children) catches the pattern.
        val titles = listOf(
            "Българска земя хубава", "Силян Щърка", "Слънчовата щерка", "Приказка за дърваря",
            "Ерген-девойче", "Омагьосаната царкиня", "Пастирчето и русалките",
            "Човекът, що заместил слънцето", "Падишахът — папагал", "Славеят Гизар",
            "Старата круша", "Да имаш късмет", "Доброто нахалост", "Завист човешка",
            "Късметът на сиромаха", "Ковачът в рая", "Хитрият петел",
            "Всяка работа си иска майстора", "Глупавата мечка", "Лъв и мишка",
        )
        val children = titles.mapIndexed { i, t -> TocEntry(t, "chapter-$i.xhtml") }
        val container = TocEntry("2", "chapter-0.xhtml", children)
        val spineHrefs = (0..19).map { "chapter-$it.xhtml" }
        // Real position counts, in order chapter-0..chapter-19.
        val positionCounts = listOf(3, 31, 21, 8, 16, 12, 4, 19, 11, 28, 13, 15, 15, 5, 4, 7, 2, 6, 3, 3)

        val segments = buildRailSegments(
            listOf(container),
            spineHrefs = spineHrefs,
            positionCounts = positionCounts,
        )
        // Expansion: first child (chapter-0.xhtml) collides with the parent by same-file
        // dedup, but the parent is thrown away first — so the first surviving segment is
        // the first child, "Българска земя хубава". Remaining 19 children each survive as
        // their own segment (chapter-1.xhtml through chapter-19.xhtml, all distinct spine
        // files). Locked here so any future heuristic change is caught before it silently
        // collapses this book back to a single "2" segment.
        val expected = titles.mapIndexed { i, t -> RailSegment(t, "chapter-$i.xhtml") }
        assertEquals(expected, segments)
    }

    @Test
    fun `1001N-flat shape — sub-chapter runs numbered N are absorbed under preceding bare parent`() {
        // Real subset of "Приказки от хиляда и една нощ" Том I: the NCX lists three story
        // titles at the top level, each followed by its "1. Sub, 2. Sub, ..." sub-chapters
        // as siblings — no nesting. Without preprocessing the rail balloons to 50+ tiny
        // segments; each sub-chapter is one short spine file. Preprocessing groups each
        // numeric run under the bare-title parent that precedes it; the length rule then
        // correctly keeps them collapsed because the sub-chapters are all short.
        val abd = TocEntry("Абдуллах земният", "chapter-1.xhtml")
        val abdSubs = (2..15).map { TocEntry("${it - 1}. Sub", "chapter-$it.xhtml") }
        val car = TocEntry("Цар Аджиб", "chapter-16.xhtml")
        val carSubs = (17..32).map { TocEntry("${it - 16}. Sub", "chapter-$it.xhtml") }
        val his = TocEntry("Хусраушах", "chapter-33.xhtml")
        val hisSubs = (34..53).map { TocEntry("${it - 33}. Sub", "chapter-$it.xhtml") }
        val toc = listOf(abd) + abdSubs + listOf(car) + carSubs + listOf(his) + hisSubs
        val spineHrefs = (1..53).map { "chapter-$it.xhtml" }
        // All sub-chapters and parents are 1 position each — every child fails the length
        // threshold, so post-absorption the length rule keeps each parent as one segment.
        val positionCounts = List(53) { 1 }

        assertEquals(
            listOf(
                RailSegment("Абдуллах земният", "chapter-1.xhtml"),
                RailSegment("Цар Аджиб", "chapter-16.xhtml"),
                RailSegment("Хусраушах", "chapter-33.xhtml"),
            ),
            buildRailSegments(toc, spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `flat numeric-prefix run — a single run is NOT absorbed (could be real numbered chapters)`() {
        // A book with a single "1., 2., 3., ..." sequence at top level is more likely a
        // real numbered chapter list than a sub-run. The absorption heuristic requires at
        // least two restart cycles as proof that the numbering is subordinate.
        val toc = listOf(
            TocEntry("Preface", "preface.xhtml"),
            TocEntry("1. Introduction", "ch1.xhtml"),
            TocEntry("2. Body", "ch2.xhtml"),
            TocEntry("3. Conclusion", "ch3.xhtml"),
        )
        val spineHrefs = listOf("preface.xhtml", "ch1.xhtml", "ch2.xhtml", "ch3.xhtml")
        val positionCounts = listOf(5, 20, 20, 20)

        val segments = buildRailSegments(toc, spineHrefs = spineHrefs, positionCounts = positionCounts)
        assertEquals(4, segments.size)
        assertEquals("1. Introduction", segments[1].title)
    }

    @Test
    fun `flat numeric-prefix run — NOT absorbed when preceding parent already has children`() {
        // Guards against clobbering an existing hierarchy. If the bare-title entry that
        // would become the parent already has TOC children, absorbing flat siblings would
        // mix two structurally distinct sets of children.
        val existingChild = TocEntry("sub", "existing-sub.xhtml")
        val a = TocEntry("Story A", "a.xhtml", listOf(existingChild))
        val aSubs = listOf(TocEntry("1. Foo", "af1.xhtml"), TocEntry("2. Bar", "af2.xhtml"))
        val b = TocEntry("Story B", "b.xhtml")
        val bSubs = listOf(TocEntry("1. Baz", "bf1.xhtml"), TocEntry("2. Qux", "bf2.xhtml"))
        val toc = listOf(a) + aSubs + listOf(b) + bSubs
        val spineHrefs = listOf("a.xhtml", "existing-sub.xhtml", "af1.xhtml", "af2.xhtml",
                                "b.xhtml", "bf1.xhtml", "bf2.xhtml")
        val positionCounts = List(7) { 1 }

        val segments = buildRailSegments(toc, spineHrefs = spineHrefs, positionCounts = positionCounts)
        // "Story A" is skipped for absorption (has existing child), so its "1. Foo/2. Bar"
        // siblings stay at top level. "Story B" is a bare leaf but there's now only one
        // eligible absorbable run — fails the ≥2-runs safety check — so nothing is absorbed.
        assertTrue(
            "expected '1. Foo' to remain top-level: $segments",
            segments.any { it.title == "1. Foo" },
        )
    }

    @Test
    fun `flat-top-level shape — every TOC leaf becomes a segment untouched by heuristics`() {
        // The most common shape in the wild (13 of 20 sampled books: Way of Kings, Космос,
        // Heretics of Dune, Azazel, A Hat Full of Sky, Guards!, Fantastic Beasts, Moving
        // Pictures, Narnia, Forward the Foundation, Children of Húrin, Art of Deception,
        // Андерсенови приказки). No entry has children, so no expand/keep decision runs —
        // rail is a straight 1:1 mapping. Documents that the heuristic doesn't perturb the
        // majority of real books.
        val chapters = (1..8).map { TocEntry("Chapter $it", "ch$it.xhtml") }
        val spineHrefs = chapters.map { it.href }
        val positionCounts = List(8) { 25 }

        val segments = buildRailSegments(chapters, spineHrefs = spineHrefs, positionCounts = positionCounts)
        assertEquals(chapters.map { RailSegment(it.title, it.href) }, segments)
    }

    @Test
    fun `siblings at same level are evaluated independently — one Part expands, another keeps`() {
        // Real-world mix: a book with two Parts, only one of which has substantial chapters.
        // Pins that parent decisions don't leak: the length-fail on Part II must not stop
        // Part I from expanding, and vice versa.
        val partIChaps = (1..3).map { TocEntry("Ch $it", "p1c$it.xhtml") }
        val partIIChaps = (1..3).map { TocEntry("Sub $it", "p2c$it.xhtml") }
        val partI = TocEntry("Part I", "p1.xhtml", partIChaps)
        val partII = TocEntry("Part II", "p2.xhtml", partIIChaps)
        val spineHrefs = listOf("p1.xhtml", "p1c1.xhtml", "p1c2.xhtml", "p1c3.xhtml",
                                "p2.xhtml", "p2c1.xhtml", "p2c2.xhtml", "p2c3.xhtml")
        // Part I: parent=2, chapters=25 each → length-majority (all substantial) → expand.
        // Part II: parent=1, sub-entries=2 each → length-fail (2 < max(2, 3)=3) → keep.
        val positionCounts = listOf(2, 25, 25, 25, 1, 2, 2, 2)

        assertEquals(
            listOf(
                RailSegment("Ch 1", "p1c1.xhtml"),
                RailSegment("Ch 2", "p1c2.xhtml"),
                RailSegment("Ch 3", "p1c3.xhtml"),
                RailSegment("Part II", "p2.xhtml"),
            ),
            buildRailSegments(listOf(partI, partII), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `Ancient-Greek-Legends shape — three-level hierarchy IS expanded via grandchildren`() {
        // Real shape of "Старогръцки легенди и митове". Part 1 has a short title-page
        // spine file, and its direct children are themselves sub-part containers ("Богове",
        // "Герои") whose own spine files are also short — the real content lives one level
        // deeper (individual god stories, hero stories). Length alone collapses this to a
        // single Part segment because the direct children look short. The grandchildren
        // signal is what proves the parent is a hierarchical grouping worth expanding.
        // Numbers reflect the actual EPUB.
        val godsChildren = (1..15).map { TocEntry("Bog $it", "chapter-$it.xhtml") }
        val heroesChildren = (17..47).map { TocEntry("Hero $it", "chapter-$it.xhtml") }
        val bogove = TocEntry("Богове", "chapter-1.xhtml", godsChildren)   // 3 pos title
        val geroi = TocEntry("Герои", "chapter-16.xhtml", heroesChildren)  // 5 pos title
        val part1 = TocEntry("Първа част. Богове и герои", "chapter-1.xhtml", listOf(bogove, geroi))
        val spineHrefs = (1..47).map { "chapter-$it.xhtml" }
        // chapter-1: 3 pos, chapter-2..15: various, chapter-16: 5 pos, chapter-17..47: various.
        // Only the two direct children of part1 (chapter-1, chapter-16) matter for the
        // parent's expand decision. Both are short vs part1's 3-pos length → length rule
        // fails. Grandchildren rule fires because "Герои" has 31 children.
        val positionCounts = List(47) { i -> when (i + 1) {
            1 -> 3; 16 -> 5
            else -> 5   // any positive value — irrelevant to the decision
        } }

        val segments = buildRailSegments(
            listOf(part1),
            spineHrefs = spineHrefs,
            positionCounts = positionCounts,
        )
        // Part 1 expands into its two sub-part containers. Those sub-parts, in turn, get
        // their own decision: "Богове" has 15 leaf grandchildren, "Герои" has 31 — each
        // fires the grandchildren-OR rule and expands. But that's out of scope for this
        // test; we only assert Part 1 was replaced (i.e. the label "Първа част…" does not
        // appear as a top-level segment).
        assertTrue(
            "Part 1 should have been expanded but appears in segments: $segments",
            segments.none { it.title == "Първа част. Богове и герои" },
        )
    }

    @Test
    fun `Bulgarian-tales shape — tiny parent with tiny children NOT expanded (Детето съдия)`() {
        // Real subset of "Приказки от хиляда и една нощ" Pattern B story "Четвърта глава.
        // Детето съдия" at chapter-78.xhtml with 9 children (first same-file). Every spine
        // file in this region is 1-3 positions — flash-fiction-length sub-chapters. Ratio
        // alone (2×parent) is a trivial bar when parent is 1 position, so children of 2-3
        // positions falsely qualify as "substantial". An absolute floor blocks it: no child
        // is long enough on its own to warrant a separate rail segment.
        val subs = (78..86).map { TocEntry("$it", "chapter-$it.xhtml") }
        val parent = TocEntry("Четвърта глава. Детето съдия", "chapter-78.xhtml", subs)
        val spineHrefs = (78..86).map { "chapter-$it.xhtml" }
        val positionCounts = listOf(1, 1, 3, 2, 2, 1, 2, 2, 1)

        assertEquals(
            listOf(RailSegment("Четвърта глава. Детето съдия", "chapter-78.xhtml")),
            buildRailSegments(listOf(parent), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `Bulgarian-tales shape — tiny parent with tiny children NOT expanded (Меден-Трета)`() {
        // Same book, second bad expansion: "Трета глава" from "Медният град" at chapter-102
        // with 4 children (first same-file). Parent=1, children=[2,3,1]. Two children just
        // barely pass 2×parent=2, forming a false majority (2/3). The absolute floor is what
        // catches this — no child is at least 3 positions, so none is "substantial enough"
        // to become its own rail segment.
        val subs = (102..105).map { TocEntry("$it", "chapter-$it.xhtml") }
        val parent = TocEntry("Трета глава", "chapter-102.xhtml", subs)
        val spineHrefs = (102..105).map { "chapter-$it.xhtml" }
        val positionCounts = listOf(1, 2, 3, 1)

        assertEquals(
            listOf(RailSegment("Трета глава", "chapter-102.xhtml")),
            buildRailSegments(listOf(parent), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `Bulgarian-tales shape — grouping-of-similar-size sub-chapters is NOT expanded`() {
        // Real subset of "Приказки от хиляда и една нощ" (Bulgarian 1001 Nights). Each story
        // is grouped under a "Първа глава / Втора глава" NCX container; the container's own
        // spine file is roughly the same size as each of its sub-chapters, because the sub-
        // chapters are themselves short (a page or so each). The length rule must NOT expand
        // in this shape — six-plus tiny sub-chapters per story would flood the rail.
        val sub1 = TocEntry("1. Бурята", "chapter-91.xhtml")
        val sub2 = TocEntry("2. Разговорът", "chapter-92.xhtml")
        val sub3 = TocEntry("3. Покой", "chapter-93.xhtml")
        val sub4 = TocEntry("4. Заключеният град", "chapter-94.xhtml")
        val sub5 = TocEntry("5. Огромната стълба", "chapter-95.xhtml")
        val sub6 = TocEntry("6. Надпреварване", "chapter-96.xhtml")
        val sub7 = TocEntry("7. Военачалникът", "chapter-97.xhtml")
        val parent = TocEntry("Първа глава", "chapter-91.xhtml", listOf(sub1, sub2, sub3, sub4, sub5, sub6, sub7))
        val spineHrefs = listOf(
            "chapter-91.xhtml", "chapter-92.xhtml", "chapter-93.xhtml", "chapter-94.xhtml",
            "chapter-95.xhtml", "chapter-96.xhtml", "chapter-97.xhtml",
        )
        // Parent and each sub-chapter are all in the same order of magnitude — no child is
        // dramatically longer than the parent, so treating any one of them as its own rail
        // segment would fragment the story arc without gaining navigational granularity.
        val positionCounts = listOf(2, 2, 2, 2, 2, 5, 2)

        assertEquals(
            listOf(RailSegment("Първа глава", "chapter-91.xhtml")),
            buildRailSegments(listOf(parent), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `length rule keeps parent when any cross-file child is 0-length`() {
        // Guards against expanding into empty spine resources (e.g. a stub file with no
        // content). If the child has 0 positions there's nothing to navigate to.
        val real = TocEntry("Real chapter", "real.xhtml")
        val stub = TocEntry("Stub", "stub.xhtml")
        val parent = TocEntry("Part", "part.xhtml", listOf(real, stub))
        val spineHrefs = listOf("part.xhtml", "real.xhtml", "stub.xhtml")
        val positionCounts = listOf(1, 20, 0)

        assertEquals(
            listOf(RailSegment("Part", "part.xhtml")),
            buildRailSegments(listOf(parent), spineHrefs = spineHrefs, positionCounts = positionCounts),
        )
    }

    @Test
    fun `length rule falls back to grandchildren guard when positions are empty`() {
        // Existing callers that don't (yet) supply position info must see today's behavior.
        // Uses the Martian fixture — grandchildren guard keeps it as one segment.
        val sol1 = TocEntry("LOG 1", "sol1.xhtml")
        val sol2 = TocEntry("LOG 2", "sol2.xhtml")
        val ch = TocEntry("Chapter 20", "chapter20.xhtml", listOf(sol1, sol2))
        assertEquals(
            listOf(RailSegment("Chapter 20", "chapter20.xhtml")),
            buildRailSegments(listOf(ch)),
        )
    }

    @Test
    fun `entry with children all on same file is NOT expanded (title kept)`() {
        // An entry whose every child is a same-file anchor should stay as one segment —
        // this is the normal Chapter → [Section headings] pattern.
        val s1 = TocEntry("Section A", "ch.xhtml#secA")
        val s2 = TocEntry("Section B", "ch.xhtml#secB")
        val ch = TocEntry("The Chapter", "ch.xhtml", listOf(s1, s2))

        assertEquals(
            listOf(RailSegment("The Chapter", "ch.xhtml")),
            buildRailSegments(listOf(ch)),
        )
    }

    // ── buildRailSegments: misc invariants ────────────────────────────────

    @Test
    fun `URL-encoded hrefs are preserved`() {
        val encodedHref = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val segments = buildRailSegments(listOf(TocEntry("PROLOGUE", encodedHref)))
        assertEquals(1, segments.size)
        assertEquals(encodedHref, segments[0].href)
    }

    @Test
    fun `top-level entries sharing a spine resource collapse to one segment`() {
        // Regression: Asimov "Lucky Starr" books pair each chapter with a subtitle entry at
        // the SAME TOC level, sharing the same spine resource via a fragment:
        //   Chapter 1         → section4.xhtml
        //   The Doomed Ship   → section4.xhtml#heading_id_3
        // The fragment entry can never become the active segment (locator.href has no
        // fragment during natural reading), so it visually "skips" when advancing chapters.
        // The rail should show one segment per spine resource — keep the first, drop the rest.
        val toc = listOf(
            TocEntry("Chapter 1", "section4.xhtml"),
            TocEntry("The Doomed Ship", "section4.xhtml#heading_id_3"),
            TocEntry("Chapter 2", "section5.xhtml"),
            TocEntry("Sub 2", "section5.xhtml#heading_id_3"),
        )
        assertEquals(
            listOf(
                RailSegment("Chapter 1", "section4.xhtml"),
                RailSegment("Chapter 2", "section5.xhtml"),
            ),
            buildRailSegments(toc),
        )
    }

    @Test
    fun `fragment-only top-level entry is kept when no plain-href sibling exists`() {
        // If the only entry pointing to a spine resource has a fragment, keep it — there's
        // nothing to dedup it against, and dropping it would lose the resource entirely.
        val toc = listOf(
            TocEntry("Intro", "section1.xhtml#part1"),
            TocEntry("Chapter 1", "section2.xhtml"),
        )
        assertEquals(
            listOf(
                RailSegment("Intro", "section1.xhtml#part1"),
                RailSegment("Chapter 1", "section2.xhtml"),
            ),
            buildRailSegments(toc),
        )
    }

    @Test
    fun `segments with duplicate titles but unique hrefs are all retained`() {
        val toc = listOf(
            TocEntry("EDDARD", "chapter_eddard1.xhtml"),
            TocEntry("EDDARD", "chapter_eddard2.xhtml"),
            TocEntry("EDDARD", "chapter_eddard3.xhtml"),
        )
        val segments = buildRailSegments(toc)
        assertEquals(3, segments.size)
        assertEquals("chapter_eddard1.xhtml", segments[0].href)
        assertEquals("chapter_eddard2.xhtml", segments[1].href)
        assertEquals("chapter_eddard3.xhtml", segments[2].href)
    }

    // ── findActiveSegmentIndex ─────────────────────────────────────────────

    private val bookSegments = listOf(
        RailSegment("Chapter 1: The Beginning", "chapter1.xhtml"),
        RailSegment("Chapter 2: The Middle", "chapter2.xhtml"),
        RailSegment("Chapter 3: The End", "chapter3.xhtml"),
    )

    @Test
    fun `exact href match selects correct chapter`() {
        assertEquals(2, findActiveSegmentIndex(bookSegments, "chapter3.xhtml"))
    }

    @Test
    fun `fragment href falls back to base href match`() {
        assertEquals(1, findActiveSegmentIndex(bookSegments, "chapter2.xhtml#s3"))
        assertEquals(0, findActiveSegmentIndex(bookSegments, "chapter1.xhtml#s1"))
    }

    @Test
    fun `returns 0 when href matches no segment and no spine info`() {
        assertEquals(0, findActiveSegmentIndex(bookSegments, "unknown.xhtml"))
    }

    @Test
    fun `unmatched href falls back to preceding chapter by spine order, not chapter 0`() {
        // Spine has an intermezzo resource between chapter2 and chapter3 with no TOC entry.
        // Without spine awareness the chapter label would flicker to "Chapter 1" when the
        // navigator emits a locator for the intermezzo.
        val spine = listOf(
            "chapter1.xhtml",
            "chapter2.xhtml",
            "intermezzo.xhtml",
            "chapter3.xhtml",
        )
        assertEquals(1, findActiveSegmentIndex(bookSegments, "intermezzo.xhtml", spine))
    }

    @Test
    fun `unmatched href before first mapped segment returns 0`() {
        // A pre-chapter1 resource (e.g. front-matter) with no TOC entry should map to 0.
        val spine = listOf("frontmatter.xhtml", "chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml")
        assertEquals(0, findActiveSegmentIndex(bookSegments, "frontmatter.xhtml", spine))
    }

    @Test
    fun `unmatched href after last mapped segment returns last segment`() {
        val spine = listOf("chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml", "appendix.xhtml")
        assertEquals(2, findActiveSegmentIndex(bookSegments, "appendix.xhtml", spine))
    }

    @Test
    fun `unmatched href not in spine returns 0`() {
        val spine = listOf("chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml")
        assertEquals(0, findActiveSegmentIndex(bookSegments, "unrelated.xhtml", spine))
    }

    @Test
    fun `returns 0 for empty segment list`() {
        assertEquals(0, findActiveSegmentIndex(emptyList(), "chapter1.xhtml"))
    }

    @Test
    fun `single-chapter book returns one segment`() {
        val toc = listOf(TocEntry("Only Chapter", "only.xhtml"))
        val segments = buildRailSegments(toc)
        assertEquals(1, segments.size)
        assertEquals(RailSegment("Only Chapter", "only.xhtml"), segments[0])
    }

    // ── weightSegmentsByChapterLength ─────────────────────────────────────

    @Test
    fun `weights match spine position counts for one-to-one mapping`() {
        val segs = listOf(
            RailSegment("A", "a.xhtml"),
            RailSegment("B", "b.xhtml"),
            RailSegment("C", "c.xhtml"),
        )
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("a.xhtml", "b.xhtml", "c.xhtml"),
            positionCounts = listOf(10, 30, 60),
        )
        assertEquals(10f, weighted[0].weight, 0f)
        assertEquals(30f, weighted[1].weight, 0f)
        assertEquals(60f, weighted[2].weight, 0f)
    }

    @Test
    fun `weights split equally when multiple segments share one spine resource`() {
        val segs = listOf(
            RailSegment("1.1", "chapter1.xhtml#s1"),
            RailSegment("1.2", "chapter1.xhtml#s2"),
            RailSegment("2", "chapter2.xhtml"),
        )
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("chapter1.xhtml", "chapter2.xhtml"),
            positionCounts = listOf(40, 20),
        )
        assertEquals(20f, weighted[0].weight, 0f)
        assertEquals(20f, weighted[1].weight, 0f)
        assertEquals(20f, weighted[2].weight, 0f)
    }

    @Test
    fun `segments without spine match fall back to weight 1`() {
        val segs = listOf(
            RailSegment("A", "a.xhtml"),
            RailSegment("Unknown", "missing.xhtml"),
        )
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("a.xhtml"),
            positionCounts = listOf(50),
        )
        assertEquals(50f, weighted[0].weight, 0f)
        assertEquals(1f, weighted[1].weight, 0f)
    }

    @Test
    fun `empty position list leaves weights at default 1`() {
        val segs = listOf(RailSegment("A", "a.xhtml"), RailSegment("B", "b.xhtml"))
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("a.xhtml", "b.xhtml"),
            positionCounts = emptyList(),
        )
        assertEquals(1f, weighted[0].weight, 0f)
        assertEquals(1f, weighted[1].weight, 0f)
    }

    @Test
    fun `segment spanning multiple spine resources accumulates all their positions`() {
        // "Part I" TOC entry at part01.xhtml (1 pos) owns c01.xhtml (20) + c02.xhtml (30)
        // until the next TOC entry "Part II" at part02.xhtml.
        val segs = listOf(
            RailSegment("Part I", "part01.xhtml"),
            RailSegment("Part II", "part02.xhtml"),
        )
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("part01.xhtml", "c01.xhtml", "c02.xhtml", "part02.xhtml", "c03.xhtml"),
            positionCounts = listOf(1, 20, 30, 1, 15),
        )
        // Part I spans indices 0..2 (part01+c01+c02 = 1+20+30 = 51)
        assertEquals(51f, weighted[0].weight, 0f)
        // Part II spans indices 3..4 (part02+c03 = 1+15 = 16)
        assertEquals(16f, weighted[1].weight, 0f)
    }

    // ── railSegmentBounds ─────────────────────────────────────────────────

    @Test
    fun `bounds widths are proportional to weights`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        val bounds = railSegmentBounds(segs, totalWidth = 1000f)
        assertEquals(0f, bounds[0].first, 0.001f)
        assertEquals(100f, bounds[0].second, 0.001f)
        assertEquals(100f, bounds[1].first, 0.001f)
        assertEquals(300f, bounds[1].second, 0.001f)
        assertEquals(400f, bounds[2].first, 0.001f)
        assertEquals(600f, bounds[2].second, 0.001f)
    }

    @Test
    fun `bounds widths sum exactly to total width with no overlap or gap`() {
        // Pathological weights designed to expose accumulated FP drift.
        val segs = (1..17).map { RailSegment("c$it", "c$it.xhtml", weight = it.toFloat() * 1.7f) }
        val total = 1080f
        val bounds = railSegmentBounds(segs, totalWidth = total)
        // Sum of widths == total (within fp tolerance)
        val sumWidths = bounds.sumOf { it.second.toDouble() }
        assertEquals(total.toDouble(), sumWidths, 0.0005)
        // Adjacent segments touch exactly: bounds[i].end == bounds[i+1].start
        for (i in 0 until bounds.size - 1) {
            val endI = bounds[i].first + bounds[i].second
            assertEquals("segment $i end != segment ${i + 1} start", bounds[i + 1].first, endI, 0f)
        }
        // First starts at 0, last ends at total.
        assertEquals(0f, bounds.first().first, 0f)
        assertEquals(total, bounds.last().first + bounds.last().second, 0.0005f)
    }

    @Test
    fun `equal weights fall back when total weight is zero`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 0f),
            RailSegment("B", "b", weight = 0f),
        )
        val bounds = railSegmentBounds(segs, totalWidth = 100f)
        // With zero total, neutral fallback should still produce a valid layout summing to total.
        val sum = bounds.sumOf { it.second.toDouble() }
        assertEquals(100.0, sum, 0.0005)
    }

    @Test
    fun `empty segments produces empty bounds`() {
        assertEquals(emptyList<Pair<Float, Float>>(), railSegmentBounds(emptyList(), 1000f))
    }

    @Test
    fun `zero or negative total width produces empty bounds`() {
        val segs = listOf(RailSegment("A", "a"))
        assertEquals(emptyList<Pair<Float, Float>>(), railSegmentBounds(segs, 0f))
        assertEquals(emptyList<Pair<Float, Float>>(), railSegmentBounds(segs, -10f))
    }

    // ── weightedRailCursorPosition ────────────────────────────────────────

    @Test
    fun `cursor at progression 0 sits at active segment left edge`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        // Segment 1 ("B") covers [10/100, 40/100] = [0.1, 0.4]
        assertEquals(0.1f, weightedRailCursorPosition(1, segs, 0f), 0.0001f)
    }

    @Test
    fun `cursor at progression 1 sits at active segment right edge`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        assertEquals(0.4f, weightedRailCursorPosition(1, segs, 1f), 0.0001f)
    }

    @Test
    fun `cursor at progression 0_5 sits at active segment midpoint`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        assertEquals(0.25f, weightedRailCursorPosition(1, segs, 0.5f), 0.0001f)
    }

    @Test
    fun `cursor always stays within active segment bounds for any weights`() {
        // Cover several weight distributions, including extreme imbalance.
        val weightSets: List<List<Float>> = listOf(
            listOf(1f, 1f, 1f, 1f),
            listOf(1f, 50f, 1f, 50f),
            listOf(100f, 1f, 100f, 1f),
            listOf(7f, 13f, 23f, 41f, 59f),
        )
        for (weights in weightSets) {
            val segs = weights.mapIndexed { i, w -> RailSegment("$i", "c$i.xhtml", weight = w) }
            val bounds = railSegmentBounds(segs, totalWidth = 1f)
            for (active in segs.indices) {
                val (left, width) = bounds[active]
                val right = left + width
                for (p in listOf(0f, 0.001f, 0.25f, 0.5f, 0.75f, 0.999f, 1f)) {
                    val cursor = weightedRailCursorPosition(active, segs, p)
                    assertTrue(
                        "weights=$weights active=$active p=$p cursor=$cursor not in [$left,$right]",
                        cursor in left..right,
                    )
                }
            }
        }
    }

    @Test
    fun `cursor returns 0 for empty segments`() {
        assertEquals(0f, weightedRailCursorPosition(0, emptyList(), 0.5f), 0f)
    }

    @Test
    fun `cursor clamps out-of-range active index`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
        )
        // activeIndex past end → clamped to last segment ("B" at [0.25, 1.0])
        assertEquals(0.25f, weightedRailCursorPosition(99, segs, 0f), 0.0001f)
        assertEquals(1.0f, weightedRailCursorPosition(99, segs, 1f), 0.0001f)
        // activeIndex negative → clamped to first ("A" at [0.0, 0.25])
        assertEquals(0f, weightedRailCursorPosition(-3, segs, 0f), 0.0001f)
        assertEquals(0.25f, weightedRailCursorPosition(-3, segs, 1f), 0.0001f)
    }

    // ── railSegmentIndexAt (tap hit-test) ─────────────────────────────────

    @Test
    fun `tap hit-test returns segment containing the x position`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        // Width 100 → segment boundaries at 10, 40
        assertEquals(0, railSegmentIndexAt(segs, 0f, 100f))
        assertEquals(0, railSegmentIndexAt(segs, 9.99f, 100f))
        assertEquals(1, railSegmentIndexAt(segs, 10.01f, 100f))
        assertEquals(1, railSegmentIndexAt(segs, 39.99f, 100f))
        assertEquals(2, railSegmentIndexAt(segs, 40.01f, 100f))
        assertEquals(2, railSegmentIndexAt(segs, 100f, 100f))
    }

    @Test
    fun `tap hit-test clamps out-of-range x to the nearest end`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 1f),
            RailSegment("B", "b", weight = 1f),
        )
        assertEquals(0, railSegmentIndexAt(segs, -50f, 100f))
        assertEquals(1, railSegmentIndexAt(segs, 9999f, 100f))
    }

    @Test
    fun `tap hit-test on empty segments returns -1`() {
        assertEquals(-1, railSegmentIndexAt(emptyList(), 5f, 100f))
    }
}
