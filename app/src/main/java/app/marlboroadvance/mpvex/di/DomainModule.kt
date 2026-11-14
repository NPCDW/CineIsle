package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext

val domainModule = module {
    single { Anime4KManager(androidContext()) }
    single {
        app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer(androidContext(), get(), get())
    }
    single {
        app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSyncNotifier(androidContext())
    }
    single {
        val notifier = get<app.marlboroadvance.mpvex.domain.emby.playback.PlaybackSyncNotifier>()
        app.marlboroadvance.mpvex.domain.emby.playback.PlaybackScrobbler(
            get<app.marlboroadvance.mpvex.domain.emby.playback.LoopbackPlaybackServer>().http,
            get(),
            notifier::show,
        )
    }
    single {
        app.marlboroadvance.mpvex.ui.player.LoembyPlaybackCoordinator(get(), get(), get(), get(), androidContext())
    }
}
