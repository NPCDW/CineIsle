package app.marlboroadvance.mpvex.ui.player.feed

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSourcePolicy
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.displayTitle
import app.marlboroadvance.mpvex.domain.emby.requireSupportedConnection
import app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSpec
import java.io.Serializable

/** 视界流中的一条 Emby 视频；mpv 根据这些身份字段通过 loopback 获取播放地址。 */
data class FeedItem(
  val itemId: String,
  val title: String,
  val isFavorite: Boolean = false,
  val mediaSourceId: String? = null,
  val seriesId: String? = null,
  val itemName: String = title,
  val seriesName: String? = null,
  val itemType: String? = null,
  val seasonNumber: Int? = null,
  val episodeNumber: Int? = null,
  val positionTicks: Long = 0,
) : Serializable {
  companion object {
    private const val serialVersionUID = 3L
  }
}

/** 从已有 Emby 元数据构造 feed 条目，不生成也不保存远程播放 URL。 */
internal fun feedPlaybackItemOf(
  item: EmbyItem,
  title: String,
  @Suppress("UNUSED_PARAMETER") streamUrl: ((String?) -> String)? = null,
): FeedItem? {
  val id = item.Id?.takeIf { it.isNotBlank() } ?: return null
  val source = item.MediaSources?.firstOrNull { !EmbyMediaSourcePolicy.isIsoSource(it) }
  if (!item.MediaSources.isNullOrEmpty() && source == null) return null
  if (item.MediaSources.orEmpty().size > 1 && source?.Id.isNullOrBlank()) return null
  return FeedItem(
    itemId = id,
    title = item.displayTitle().ifEmpty { title },
    mediaSourceId = source?.Id,
    seriesId = item.SeriesId,
    itemName = item.Name ?: title,
    seriesName = item.SeriesName,
    itemType = item.Type,
    seasonNumber = item.ParentIndexNumber,
    episodeNumber = item.IndexNumber,
    positionTicks = item.UserData?.PlaybackPositionTicks?.coerceAtLeast(0) ?: 0,
    isFavorite = item.UserData?.IsFavorite == true,
  )
}

internal fun FeedItem.mpvPlaybackSpec(
  server: EmbyServer,
  playlistIndex: Int,
  playlistTotal: Int,
  resumeSec: Double? = null,
): PlaybackSpec {
  server.requireSupportedConnection()
  require(isFeedItemPlayable(this)) { "缺少可播放的 Emby 条目标识" }
  require(playlistIndex in 1..playlistTotal) { "Invalid playlist index" }
  val ticks = resumeSec?.takeIf { it.isFinite() && it >= 0.0 }?.let {
    (it * 10_000_000.0).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
  } ?: positionTicks.coerceAtLeast(0)
  return PlaybackSpec(
    server = server,
    itemId = itemId,
    seriesId = seriesId,
    itemName = itemName,
    seriesName = seriesName,
    title = title,
    itemType = itemType,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    positionTicks = ticks,
    explicitMediaSourceId = mediaSourceId?.takeIf { it.isNotBlank() },
    playlistIndex = playlistIndex,
    playlistTotal = playlistTotal,
  )
}

internal fun isFeedItemPlayable(item: FeedItem): Boolean = item.itemId.isNotBlank()
