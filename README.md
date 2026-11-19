# Anyfy

Android music streaming app with YouTube Music integration, built with Kotlin and Jetpack Compose.

## Implemented Features

- YouTube Music search and playback via InnerTube API
- Artist pages with discography, top tracks, similar artists
- Album detail views with tracklists
- Playlist management with YouTube Music as single source of truth
- Together Listening rooms (Socket.IO synchronized group playback)
- On-device audio extraction (yt-dlp, no cloud dependency)
- Media3/ExoPlayer with background playback and media controls
- Smart caching (cache-first, background refresh)
- In-app updates via GitHub Releases
- YouTube Music account authentication
- Liked songs sync
- Personalized home feed
- Monochrome grey UI

## Tech Stack

Kotlin, Jetpack Compose (Material 3), Media3/ExoPlayer, Room DB, OkHttp/Retrofit, Coil, Node.js/Express + Socket.IO backend, Firebase Auth, yt-dlp on-device, YouTube Music InnerTube API

## Development Setup

**Prerequisites:** Android Studio, JDK 17

1. Clone repo
2. Open in Android Studio
3. Create `.env` with `GEMINI_API_KEY` (see `.env.example`)
4. Gradle sync
5. Run on device/emulator (minSdk 26)

## Building

- **Debug:**
  ```bash
  ./gradlew assembleDebug
  ```
- **Tests:**
  ```bash
  ./gradlew testDebugUnitTest
  ```
- **Release:** *(needs signing config)*
  ```bash
  ./gradlew assembleRelease
  ```

## Backend

```bash
cd backend && npm install && npm start
```
(see [backend/README.md](backend/README.md))

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md)

## License

MIT License - see [LICENSE](LICENSE) file.  
Third-party libraries have their own licenses.

## Third-Party Attribution

- SimpMusic parsing code by maxrave-dev (GPL-3.0) - [github.com/maxrave-dev/SimpMusic](https://github.com/maxrave-dev/SimpMusic)
- AndroidX/Compose: Apache 2.0 (Google)
- Firebase: Apache 2.0 (Google)
- OkHttp/Retrofit: Apache 2.0 (Square)
- Coil: Apache 2.0
- Socket.IO Client: MIT (Automattic)
- youtubedl-android: LGPL-3.0/Apache 2.0

## Security

See [SECURITY.md](SECURITY.md)

## Status

Active development. Features listed above are implemented.

> **Note:** Keystore files, `google-services.json`, and server credentials are excluded from the repo.
