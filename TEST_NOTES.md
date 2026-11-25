# Test build notes

<!--
What's changed on dev since the last test build. When Build Test App's "What changed" box is left
empty, the test build uses this list (headings with nothing under them are left out), and then
removes the lines it used, so the next test build only shows what's new since. Add lines under the
headings as changes land on dev. Typing in the box uses the box instead and leaves this file alone.
-->

## Live TV
- A playlist hidden on your profile can be shown again when there's only one profile, and it says it's hidden on this profile
- The same guide given twice in different forms (e.g. a playlist's Xtream guide link and the one in its playlist file) is downloaded and read once
- Guide clear-up runs gently in the background instead of holding up the guide
- Stalker portals that hand the login over to another server keep playing from that server
- Match Frame Rate Off is really off now, in the guide and Sports previews too
- Audio passthrough Off now turns Dolby/DTS into normal sound on HDMI outputs that support passthrough
- Leaving Live TV for a movie stops the catch-up download in the background

## Rewind Anytime
- Fixes for playing, then Loading, then playing again when a channel starts: it now waits until 8 seconds are saved before it starts (a channel may take a few seconds longer to start on some providers)
- New pieces of the rewind buffer are picked up straight away instead of up to 20 seconds later
- Switching channels quickly no longer lets the old channel's buffer mess up the new one

## Recordings
- Catch-up recordings to a network folder download only as fast as they can be copied across
- The recording service isn't started again while it's already running

## Sports
- My Sports cards that showed no channels now get the channels you saved for their league

## Other
- Removing an add-on no longer crashes the add-on list
- Crash reports are clearer about memory, and say when a saved CPU snapshot is from an earlier run
