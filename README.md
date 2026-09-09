# IwaraFlow v0.1
Android-only vertical Iwara video feed.

## Implemented
- Trending / Popularity / Latest feeds
- Vertical swipe (ViewPager2)
- Media3 ExoPlayer streaming
- Iwara `fileUrl` resolver with current `X-Version` SHA-1 algorithm
- Highest available source selection
- Tap pause/play
- Double tap local-like animation
- Local favorite toggle
- Immersive portrait layout

## Build
Requires Android SDK 36, Build Tools 35+, JDK 17+, Gradle 8.13.

```bash
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Note
Iwara APIs are unofficial and can change. The Iwara transport/resolver is isolated in `IwaraApi.kt` for easy fixes.
