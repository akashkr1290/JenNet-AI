# Flutter in Docker - Web frontend and Android builds

Two Flutter workflows are added to the existing Docker setup. They are different
on purpose:

| | Flutter Web (`flutter-frontend`) | Flutter Android (`flutter-android`) |
|---|---|---|
| Kind | **Continuously running service** | **On-demand build environment** |
| Started by `docker compose up -d` | Yes | No (Compose profile `android-build`) |
| Output | Web app on http://localhost:3000 | APK / AAB files on the host |
| Runs the app? | Yes, nginx serves it to your browser | No - no emulator; install the APK on a device/emulator outside Docker |

Normal Flutter development is unchanged: `cd flutter && flutter run` on your machine.

## 1. Flutter Web architecture

```
Browser -> http://localhost:3000 -> flutter-frontend container (nginx :80)
        -> Flutter Web release bundle (index.html, main.dart.js, assets)
Browser (the running app) -> http://localhost:8080/api/v1 -> backend container
```

`docker/Dockerfile.flutter-web` is multi-stage:
1. **build** - Debian + Flutter 3.47.1: `flutter pub get --enforce-lockfile`,
   `flutter build web --release --dart-define=API_BASE_URL=...`
2. **runtime** - `nginx:1.27-alpine` with only the built files and
   `docker/flutter-web-nginx.conf`. The runtime image contains no Flutter SDK.

The web app is served by nginx but talks to the backend **directly from the
browser** - nginx does not proxy the API.

## 2. Flutter Android build architecture

```
flutter/ (bind-mounted) -> flutter-android container (Flutter 3.47.1 + JDK 17 + Android SDK)
                        -> flutter/build/app/outputs/{flutter-apk,bundle}/ on the HOST
```

`docker/Dockerfile.flutter-android` contains Flutter, JDK 17 and the Android SDK
(platform 36, build-tools 36.0.0, platform-tools, NDK 28.2.13676358). The project
directory is mounted, so nothing is copied into the image and build outputs
appear directly on the host. Pub and Gradle caches live in named volumes
(`flutter_pub_cache`, `flutter_gradle_cache`) so later builds are fast.

### Where the versions come from (not guesses)

| Component | Version | Source |
|---|---|---|
| Flutter | 3.47.1 | `flutter/.metadata` revision `6655482e` = tag 3.47.1 |
| Dart | 3.13.1 | bundled with Flutter 3.47.1 (satisfies `pubspec.lock`: dart >= 3.12.0) |
| JDK | 17 | Flutter 3.47.1 minimum and default Java for AGP >= 8.0 |
| Gradle / AGP / Kotlin | 9.3.1 / 9.1.0 / 2.4.0 | Flutter 3.47.1 app template defaults |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 | Flutter 3.47.1 `FlutterExtension` defaults |
| NDK | 28.2.13676358 | Flutter 3.47.1 default `ndkVersion` |

Note: `.github/workflows/flutter-ci.yml` pins Flutter 3.24.5, which does not
satisfy `pubspec.lock` (flutter >= 3.44.0). That CI setting predates this setup
and is not changed here.

### Android and web platform folders

The repository had no `flutter/android/` or `flutter/web/` folder, so neither
target could be built. Both were added, rendered from the official Flutter
3.47.1 templates (the same files `flutter create` produces), with these changes:
- `AndroidManifest.xml` declares INTERNET (the template only does so for debug
  builds) and fine/coarse location (used by `geolocator`).
- Application id `com.example.jannet_ai`, matching the existing Linux target.
  **Change it before any store release** - Google Play rejects `com.example.*`.
- The launcher icon is a text-only vector drawable instead of template PNGs.
- Release signing and cleartext-HTTP handling (sections 13 and 11).
- The web template's PNG icons are copied from the Flutter SDK during the image
  build instead of being committed.

## 3. Frontend port

Host **3000** -> container **80** (nginx). Override with `FLUTTER_WEB_PORT`.

## 4. Backend port

Spring Boot listens on **8080** in its container, published on the host as
`${SERVER_PORT:-8080}` (existing `backend` service, unchanged).

## 5. Docker services

| Service | Status | Notes |
|---|---|---|
| mysql, backend, ai-service | unchanged | existing |
| redis | unchanged | optional profile `shared-cache` |
| **flutter-frontend** | new, always running | `restart: unless-stopped`, port 3000 |
| **flutter-android** | new, on demand | profile `android-build`, never started by `up` |

A root `compose.yaml` was added that `include`s `docker/docker-compose.yml`, so
Compose commands work from the repository root (Docker Compose v2.20+). The
long form `docker compose -f docker/docker-compose.yml ...` still works, but pass
`--env-file .env` with it: Compose otherwise reads `.env` from `docker/`, leaving
MySQL/DB variables blank.

## 6. Flutter Web startup

```bash
cp .env.example .env          # once; fill in real secrets (never commit .env)
docker compose up -d          # mysql, backend, ai-service, flutter-frontend
docker compose ps
# open http://localhost:3000
docker compose logs -f flutter-frontend
docker compose build flutter-frontend     # after Flutter code or API URL changes
docker compose up -d flutter-frontend     # recreate with the new image
docker compose restart flutter-frontend
```

nginx returns `index.html` for any path that is not a real file, so refreshing
`/login` or `/complaints/123` never produces an nginx 404. (The app itself uses
in-app navigation, not URL routes, so a refresh starts at the app's home.)
Missing asset files still return 404.

## 7. APK build

```bash
docker compose build flutter-android      # first time only (downloads SDKs)
docker compose run --rm flutter-android flutter pub get
docker compose run --rm flutter-android flutter build apk --release \
  --dart-define=API_BASE_URL=https://api.example.org/api/v1
# -> flutter/build/app/outputs/flutter-apk/app-release.apk
docker compose run --rm flutter-android flutter build apk --debug \
  --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
# -> flutter/build/app/outputs/flutter-apk/app-debug.apk (10.0.2.2 = host, from the Android emulator)
```

On Linux, set `HOST_UID`/`HOST_GID` in `.env` to `id -u`/`id -g` (then rebuild the
image) so generated files are owned by you.

## 8. AAB build

```bash
docker compose run --rm flutter-android flutter build appbundle --release \
  --dart-define=API_BASE_URL=https://api.example.org/api/v1
# -> flutter/build/app/outputs/bundle/release/app-release.aab
```

`build/` is git-ignored; never commit APK/AAB files.

## 9. Flutter testing

```bash
docker compose run --rm flutter-android flutter test
```

## 10. Flutter analysis

```bash
docker compose run --rm flutter-android flutter analyze
```

## 11. API configuration

`lib/core/api/api_config.dart` reads `API_BASE_URL` via `String.fromEnvironment`
at **build time** (default `http://localhost:8080/api/v1`).

- **Web:** set `FLUTTER_WEB_API_BASE_URL` in `.env` (default
  `http://localhost:8080/api/v1`), then rebuild `flutter-frontend`. It must be
  reachable from the **browser** - use the backend's host address, never
  `http://backend:8080` (that name only resolves inside Docker).
- **Android:** pass `--dart-define=API_BASE_URL=...` to `flutter build`.
  Emulator -> host backend: `http://10.0.2.2:8080/api/v1`. Physical device: the
  host's LAN IP or a real HTTPS domain.
- **Cleartext HTTP:** debug builds may use `http://`. Release builds require
  HTTPS unless built with `JANNET_ALLOW_CLEARTEXT=true` (local testing only):
  `docker compose run --rm -e JANNET_ALLOW_CLEARTEXT=true flutter-android flutter build apk --release --dart-define=API_BASE_URL=http://192.168.1.10:8080/api/v1`

## 12. CORS

The backend previously allowed every origin (`*`), with a comment saying it should
be tightened once a real web client existed. The Flutter Web frontend is that
client, so origins are now an explicit list: `app.cors.allowed-origins`
(`CORS_ALLOWED_ORIGINS`, comma-separated), default
`http://localhost:3000,http://127.0.0.1:3000`. Methods, headers and
`allowCredentials=false` are unchanged. The mobile app sends no `Origin` header
and Swagger UI is same-origin, so neither is affected. **Production:** set
`CORS_ALLOWED_ORIGINS` to the real HTTPS web origin(s); if you change
`FLUTTER_WEB_PORT`, add the new origin.

## 13. Android signing

No signing credentials are in the repository, Dockerfiles or Compose files.
`android/app/build.gradle.kts` reads `flutter/android/key.properties` if present
(git-ignored, as are `*.jks`/`*.keystore`):

```properties
storePassword=...
keyPassword=...
keyAlias=upload
storeFile=/workspace/flutter/android/upload-keystore.jks
```

Create a keystore once (outside Git), place it under `flutter/android/` (ignored) or
mount it, and write `key.properties`. `storeFile` must be the path **inside the
container** (the project is mounted at `/workspace/flutter`). Without
`key.properties`, release builds are signed with the debug key and Gradle prints
a warning: fine for testing, **never publish such an artifact**. Debug builds
always work.

## 14. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Web app loads but API calls fail | `FLUTTER_WEB_API_BASE_URL` not reachable from the browser, or origin missing from `CORS_ALLOWED_ORIGINS`. Check the browser console. Rebuild the frontend after changing the URL. |
| CORS error in the browser | Add the exact origin (scheme + host + port) to `CORS_ALLOWED_ORIGINS`; restart `backend`. |
| **Photo upload fails on the web** | Known limitation of the existing app code: uploads use `http.MultipartFile.fromPath` (dart:io), which is unavailable in browsers. Login, dashboards, tracking and officer workflows work on the web; complaint submission with a photo requires the Android app. |
| `"/flutter": not found` during build | Build is not using BuildKit, so `docker/Dockerfile.flutter-web.dockerignore` is ignored and the root `.dockerignore` (which excludes `flutter/`) applies. Use Docker Engine 23+ / Docker Desktop, or `DOCKER_BUILDKIT=1`. |
| MySQL fails with the long `-f docker/...` form | Add `--env-file .env`, or run from the repo root with the root `compose.yaml`. |
| Android build: permission denied writing `build/` | Set `HOST_UID`/`HOST_GID` to your `id -u`/`id -g` and `docker compose build flutter-android`. |
| Gradle runs out of memory | The Flutter template sets `org.gradle.jvmargs=-Xmx8G`; give Docker enough memory or lower it locally. |
| First Android build is slow | Gradle 9.3.1 and dependencies download once into `flutter_gradle_cache`. |
| AGP asks for another build-tools version | It installs it automatically (licenses are pre-accepted). |
| Release APK cannot reach an `http://` backend | Use HTTPS, or build with `JANNET_ALLOW_CLEARTEXT=true` for local testing. |
