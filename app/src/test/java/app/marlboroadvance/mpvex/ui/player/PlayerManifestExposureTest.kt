package app.marlboroadvance.mpvex.ui.player

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PlayerManifestExposureTest {
  private val android = "http://schemas.android.com/apk/res/android"

  private fun manifest(): Element {
    val file = sequenceOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
      .firstOrNull { it.isFile } ?: error("Cannot locate app main manifest")
    return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
      .newDocumentBuilder().parse(file).documentElement
  }

  private fun activities(): List<Element> {
    val nodes = manifest().getElementsByTagName("activity")
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  private fun activity(name: String): Element =
    activities().single { it.getAttributeNS(android, "name") == name }

  @Test
  fun `mpv video activities are internal without external intent filters`() {
    val orientations = mapOf(
      ".ui.player.PlayerActivity" to "locked",
      ".ui.player.feed.VerticalFeedActivity" to "sensorLandscape",
    )
    for ((name, orientation) in orientations) {
      val activity = activity(name)
      assertEquals(name, "false", activity.getAttributeNS(android, "exported"))
      assertEquals(name, 0, activity.getElementsByTagName("intent-filter").length)
      assertEquals(name, "singleTask", activity.getAttributeNS(android, "launchMode"))
      assertEquals(name, orientation, activity.getAttributeNS(android, "screenOrientation"))
    }
    assertEquals(
      "true",
      activity(".ui.player.PlayerActivity").getAttributeNS(android, "supportsPictureInPicture"),
    )
  }

  @Test
  fun `removed GSY activity is absent while launcher stays portrait`() {
    assertFalse(activities().any { it.getAttributeNS(android, "name").contains("Gsy") })
    assertEquals("portrait", activity(".MainActivity").getAttributeNS(android, "screenOrientation"))
  }

  @Test
  fun `standalone media info keeps its approved external share and view entry points`() {
    val activity = activity(".ui.mediainfo.MediaInfoActivity")
    assertEquals("true", activity.getAttributeNS(android, "exported"))
    assertEquals(2, activity.getElementsByTagName("intent-filter").length)
    val actions = activity.getElementsByTagName("action")
    val names = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(android, "name") }
    assertTrue(names.contains("android.intent.action.SEND"))
    assertTrue(names.contains("android.intent.action.VIEW"))
    assertFalse(names.contains("android.intent.action.MAIN"))
    val data = activity.getElementsByTagName("data")
    val schemes = (0 until data.length).map { (data.item(it) as Element).getAttributeNS(android, "scheme") }.toSet()
    assertTrue(schemes.containsAll(setOf("file", "content", "http", "https")))
  }

  @Test
  fun `launcher remains exported without external player visibility queries`() {
    assertEquals("true", activity(".MainActivity").getAttributeNS(android, "exported"))
    assertEquals(0, manifest().getElementsByTagName("queries").length)
  }
}
