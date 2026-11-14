package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.database.repository.PostgresPlaybackHistoryRepository
import app.marlboroadvance.mpvex.domain.postgres.JdbcPostgresPlaybackHistoryMarkWriter
import app.marlboroadvance.mpvex.domain.postgres.PostgresPlaybackHistoryMarkWriter
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.domain.postgres.JdbcPostgresConnectionChecker
import app.marlboroadvance.mpvex.domain.postgres.JdbcPostgresExecutor
import app.marlboroadvance.mpvex.domain.postgres.PostgresExecutor
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfigStorage
import app.marlboroadvance.mpvex.domain.postgres.PostgresConfigStore
import app.marlboroadvance.mpvex.domain.postgres.PostgresConnectionChecker
import app.marlboroadvance.mpvex.domain.postgres.SharedConfigurationRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.AppLockPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.AndroidPreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val PreferencesModule =
  module {
    single { AndroidPreferenceStore(androidContext()) }.bind(PreferenceStore::class)

    single { AppearancePreferences(get()) }
    singleOf(::PlayerPreferences)
    singleOf(::GesturePreferences)
    singleOf(::DecoderPreferences)
    singleOf(::SubtitlesPreferences)
    singleOf(::AudioPreferences)
    singleOf(::AdvancedPreferences)
    single { BrowserPreferences(get(), androidContext()) }
    singleOf(::DanmakuPreferences)
    singleOf(::AppLockPreferences)
    single { PostgresConfigStore(androidContext()) }.bind(PostgresConfigStorage::class)
    single { JdbcPostgresConnectionChecker() }.bind(PostgresConnectionChecker::class)
    single { JdbcPostgresExecutor(get()) }.bind(PostgresExecutor::class)
    // Shared read-only display projection belongs with preferences, not Room.
    single { SharedConfigurationRepository(get(), get()) }
    single<PostgresPlaybackHistoryMarkWriter> { JdbcPostgresPlaybackHistoryMarkWriter(get()) }
    single { PostgresPlaybackHistoryRepository(get(), get()) }
  }
