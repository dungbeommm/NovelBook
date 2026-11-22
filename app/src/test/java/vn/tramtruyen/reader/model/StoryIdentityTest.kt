package vn.tramtruyen.reader.model

import org.junit.Assert.*
import org.junit.Test

class StoryIdentityTest {
    @Test fun sameNameSameWebsiteIgnoresCaseWhitespaceAndWww() {
        assertEquals(StoryIds.titleIdentity("Hệ Thống Cửa Hàng", "https://www.example.com/old", null, "Label A"),
            StoryIds.titleIdentity("  HỆ   THỐNG CỬA HÀNG ", "https://example.com/new", null, "Label B"))
    }
    @Test fun sameNameDifferentWebsiteNeverMatchesByDisplayLabel() {
        assertNotEquals(StoryIds.titleIdentity("Truyện A", "https://a.com/book", null, "TruyệnFull"),
            StoryIds.titleIdentity("Truyện A", "https://b.com/book", null, "TruyệnFull"))
    }
    @Test fun unknownWebsiteDoesNotCreateTitleAlias() {
        assertNull(StoryIds.titleIdentity("Truyện A", null, null, "Nguồn"))
    }
}
