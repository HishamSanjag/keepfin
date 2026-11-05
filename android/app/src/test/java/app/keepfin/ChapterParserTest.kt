package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterParserTest {
    @Test fun readsChaptersInOrder() {
        val json = """{"duration":600,"chapters":[{"start_time":0,"end_time":60,"title":"Intro"},{"start_time":135.5,"title":" Part two "}]}"""
        val c = ChapterParser.parse(json)!!
        assertEquals(listOf(0.0, 135.5), c.map { it.start })
        assertEquals(listOf("Intro", "Part two"), c.map { it.title })
    }

    @Test fun sortsDedupesAndNamesEmptyTitles() {
        val json = """{"chapters":[{"start_time":50,"title":""},{"start_time":10,"title":"A"},{"start_time":10,"title":"B"},{"start_time":30,"title":"  "}]}"""
        val c = ChapterParser.parse(json)!!
        assertEquals(listOf(10.0, 30.0, 50.0), c.map { it.start })
        assertEquals(listOf("A", "Chapter 2", "Chapter 3"), c.map { it.title })
    }

    @Test fun dropsBadStartsAndThoseAtOrAfterTheEnd() {
        val json = """{"duration":100,"chapters":[{"start_time":-1,"title":"x"},{"title":"no start"},{"start_time":"5","title":"text"},{"start_time":20,"title":"ok"},{"start_time":100,"title":"end"},{"start_time":150,"title":"past"}]}"""
        val c = ChapterParser.parse(json)!!
        assertEquals(listOf("ok"), c.map { it.title })
        // an explicit duration wins over the one in the file
        assertEquals(1, ChapterParser.parse("""{"duration":1000,"chapters":[{"start_time":20,"title":"a"},{"start_time":300,"title":"b"}]}""", 100.0)!!.size)
    }

    @Test fun noneWhenThereAreNoUsableChapters() {
        assertNull(ChapterParser.parse("""{"title":"x"}"""))
        assertNull(ChapterParser.parse("""{"chapters":null}"""))
        assertNull(ChapterParser.parse("""{"chapters":[]}"""))
        assertNull(ChapterParser.parse("""{"chapters":[{"start_time":-3,"title":"x"}]}"""))
        assertNull(ChapterParser.parse("not json"))
    }
}
