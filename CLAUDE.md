# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

fnPhoto TV is an Android TV photo/video gallery client for Feiniu NAS (fnOS). Written in Java (no Kotlin), targeting Leanback (Android TV) with D-pad remote navigation. Min SDK 19, target SDK 29.

## Build Commands

```bash
# From project root (h:\lcjai\fnphoto-tv\fnphoto-tv\)
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK

# Install on connected TV
adb install app/build/outputs/apk/debug/app-debug.apk
```

No test infrastructure exists (no src/test/ or src/androidTest/ directories).

## Architecture

Single-module Android app using Activity/Fragment pattern. No ViewModel, no DI, no data binding. Networking via Retrofit callbacks directly in Activities/Fragments.

### Activity Flow

`LoginActivity` (launcher, WebSocket RSA+AES login) → `MainActivity` (DrawerLayout with right-side nav drawer) → `MainFragment` (BrowseSupportFragment: timeline/folder/album/favorites/recent views) → `MediaDetailActivity` (full-screen photo viewer + ExoPlayer video, slideshow, zoom, EXIF overlay)

Other activities: `FolderBrowseActivity` (recursive folder nav), `SearchActivity` (debounced search), `SettingsActivity` (cache management).

### Authentication (two-layer)

1. **WebSocket login** (`FnWebSocketClient`): RSA public key exchange → AES-256-CBC encrypted credentials → receives token + secret, stored in SharedPreferences
2. **HTTP request signing** (`FnAuthUtils`): Every request adds `accesstoken` header + `authx` header (MD5-based signature: `{API_KEY}_{path}_{nonce}_{timestamp}_{payload_hash}_{API_SECRET}`)

See [API.md](API.md) for the full auth specification.

### Image Loading

`CachedImageLoader` wraps Glide with a custom `LazyHeaders` builder that injects auth headers. Two-tier cache: in-memory Bitmap check → disk cache (`ImageCacheManager`: MD5-hashed filenames, 200MB cap, 10-day TTL) → network.

### Video Playback

ExoPlayer with `AuthenticatedHttpDataSourceFactory` that injects `accesstoken` into HTTP range requests.

### Key Conventions

- **D-pad navigation**: All Activities override `onKeyDown` — LEFT/RIGHT for media switching, UP/DOWN for zoom, MENU for drawer toggle, BACK with double-tap-to-exit
- **Lazy loading**: `MainFragment` defers thumbnail loading with a 40-item visible-range buffer and 300ms debounce
- **Position persistence**: Scroll positions saved/restored when navigating between views
- **Cleartext HTTP allowed**: NAS devices are on local network; configured in `network_security_config.xml`

## Key Files

| File | Role |
|------|------|
| `api/FnHttpApi.java` | All 18+ Retrofit endpoints + response model inner classes |
| `api/FnAuthUtils.java` | Auth constants (API_KEY, API_SECRET) + authx signature generation |
| `api/FnWebSocketClient.java` | WebSocket login with RSA+AES encryption |
| `api/FnProtocolUtils.java` | Crypto utilities (AES-256-CBC, RSA, random string gen) |
| `MainFragment.java` | Core browsing UI with timeline/folder/album modes |
| `MediaDetailActivity.java` | Full-screen viewer with ExoPlayer, slideshow, zoom |
| `cache/CachedImageLoader.java` | Glide wrapper with auth-aware image loading |
| `cache/ImageCacheManager.java` | Disk bitmap cache (MD5 filenames, 200MB, 10-day TTL) |
| `MediaItem.java` | Primary data model (Serializable) for media items |

## Dependencies

Pinned to last versions supporting API 19: OkHttp 3.12.12, Retrofit 2.6.4, Glide 4.12.0, ExoPlayer 2.11.8. Uses AndroidX Leanback 1.0.0 for TV UI. MultiDex enabled for API 19 compatibility.

## Build Tooling

- Gradle 7.4, AGP 4.2.2, Java 8 source/target
- Alibaba Cloud Maven mirrors configured in root `build.gradle`
- Gradle wrapper downloads from Tencent mirror
