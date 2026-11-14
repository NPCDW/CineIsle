package app.marlboroadvance.mpvex.ui.browser.emby.photo

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoRandomQueueTest {
  @Test
  fun `缓存保留过滤前原始消费数量`() {
    val parent = "filtered-page"
    try {
      PhotoRandomQueue.put(parent, listOf(EmbyItem(Id = "photo", Type = "Photo")), 40, 20)
      val cached = PhotoRandomQueue.get(parent)!!
      assertEquals(1, cached.items.size)
      assertEquals(20, cached.consumedCount)
      assertEquals(40, cached.totalCount)
    } finally {
      PhotoRandomQueue.invalidate(parent)
    }
  }

  @Test
  fun `超过缓存上限不保存与游标不匹配的截断队列`() {
    val parent = "oversized-page"
    try {
      val photos = (0..2000).map { EmbyItem(Id = "$it", Type = "Photo") }
      PhotoRandomQueue.put(parent, photos.take(1), 3000, 1)
      PhotoRandomQueue.put(parent, photos, 3000, 2500)
      assertNull(PhotoRandomQueue.get(parent))
    } finally {
      PhotoRandomQueue.invalidate(parent)
    }
  }
}
