package com.nuvio.tv.ui.screens.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.nuvio.tv.core.player.MovieStartupTrace

/** Forward every session event. Kotlin interface delegation skips Player.Listener's Java defaults. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class PlaybackStateTimingListener(
    private val target: Player.Listener,
    private val startup: MovieStartupTrace.Attempt
) : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (startup.isRecentStartup()) {
            startup.sessionStateTiming.measure(playbackState) { target.onPlaybackStateChanged(playbackState) }
        } else {
            target.onPlaybackStateChanged(playbackState)
        }
    }

    // ForwardingPlayer also creates a fresh wrapper on remove. Equal wrappers must refer to
    // the same listener so removing it (or registering it twice) keeps Media3's semantics.
    override fun equals(other: Any?): Boolean =
        other is PlaybackStateTimingListener && other.target == target && other.startup === startup

    override fun hashCode(): Int = 31 * target.hashCode() + System.identityHashCode(startup)

    override fun onEvents(player: Player, events: Player.Events) = target.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = target.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = target.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = target.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(metadata: MediaMetadata) = target.onMediaMetadataChanged(metadata)
    override fun onPlaylistMetadataChanged(metadata: MediaMetadata) = target.onPlaylistMetadataChanged(metadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = target.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = target.onLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(commands: Player.Commands) = target.onAvailableCommandsChanged(commands)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = target.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = target.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = target.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(reason: Int) = target.onPlaybackSuppressionReasonChanged(reason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = target.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = target.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(enabled: Boolean) = target.onShuffleModeEnabledChanged(enabled)
    override fun onPlayerError(error: PlaybackException) = target.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = target.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(reason: Int) = target.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        target.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = target.onPlaybackParametersChanged(parameters)
    override fun onSeekBackIncrementChanged(incrementMs: Long) = target.onSeekBackIncrementChanged(incrementMs)
    override fun onSeekForwardIncrementChanged(incrementMs: Long) = target.onSeekForwardIncrementChanged(incrementMs)
    override fun onMaxSeekToPreviousPositionChanged(positionMs: Long) = target.onMaxSeekToPreviousPositionChanged(positionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = target.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(attributes: AudioAttributes) = target.onAudioAttributesChanged(attributes)
    override fun onVolumeChanged(volume: Float) = target.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(enabled: Boolean) = target.onSkipSilenceEnabledChanged(enabled)
    override fun onDeviceInfoChanged(info: DeviceInfo) = target.onDeviceInfoChanged(info)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = target.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = target.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = target.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = target.onRenderedFirstFrame()
    override fun onCues(cues: List<Cue>) = target.onCues(cues)
    override fun onCues(cues: CueGroup) = target.onCues(cues)
    override fun onMetadata(metadata: Metadata) = target.onMetadata(metadata)
}
