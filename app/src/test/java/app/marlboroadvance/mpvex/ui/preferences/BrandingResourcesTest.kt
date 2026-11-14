package app.marlboroadvance.mpvex.ui.preferences

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BrandingResourcesTest {
  private fun strings(locale: String): Map<String, String> {
    val path = "src/main/res/$locale/strings.xml"
    val file = sequenceOf(File(path), File("app/$path"))
      .firstOrNull { it.isFile } ?: error("Cannot locate $locale strings")
    val nodes = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder().parse(file).getElementsByTagName("string")
    return (0 until nodes.length).associate {
      val element = nodes.item(it) as Element
      element.getAttribute("name") to element.textContent
    }
  }

  @Test fun `app name and media info footer use loembyA in both locales`() {
    for (locale in listOf("values", "values-zh-rCN")) {
      val strings = strings(locale)
      assertEquals(locale, "loembyA", strings["app_name"])
      assertTrue(locale, strings.getValue("i18n_mediainfo_footer").contains("loembyA"))
      assertFalse(locale, strings.containsKey("i18n_project_name_en"))
    }
  }

  @Test fun `both locales acknowledge CineIsle and retain the original project link`() {
    for (locale in listOf("values", "values-zh-rCN")) {
      val strings = strings(locale)
      assertEquals(locale, "https://github.com/nu1lkali/CineIsle", strings["github_repo_url"])
      assertTrue(locale, strings.getValue("i18n_ack_cineisle_summary").contains("loembyA"))
      assertTrue(locale, strings.getValue("i18n_ack_mpvex_summary").contains("CineIsle"))
      assertTrue(locale, strings.getValue("i18n_about_upstream_project").isNotBlank())
    }
  }
}
