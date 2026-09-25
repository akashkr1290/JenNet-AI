# Post-UI-redesign gap fix

Patch: `jannet-ai-post-ui-gap-fix.patch`. It applies on top of `jannet-ai-complete-ui-redesign.patch`.

**Scope.** Flutter and documentation only. The patch makes no backend, database, AI-service or schema change, and adds no new API.

**Where the SRS evidence comes from.** The SRS document itself is not in the repository. Each item below was classified from the SRS clauses quoted in the project's living documents (`PROJECT_INTEGRATION.md`, `ARCHITECTURE.md`, `PROJECT_PROGRESS.md`) and in code comments. For every item, the actual backend and Flutter code was then inspected.

Classification key:

- **A**: required by the SRS and missing
- **B**: already implemented
- **C**: partially implemented
- **D**: not required by the SRS
- **E**: blocked by an external provider or configuration

## Summary

| # | Gap | Class | Outcome |
|---|---|---|---|
| 1 | Web image, camera and gallery | C (SRS 15.1 photo submission; web path missing) | Fixed |
| 2 | Complaint image upload | A (a real defect on all platforms) | Fixed |
| 3 | Ward at registration | C (SRS 16.1 "Ward/Area (dropdown)"; backend ready, Flutter missing) | Fixed |
| 4 / 10 | Onboarding | D for the SRS; in the approved design reference | Implemented (client-only; easy to remove) |
| 5 | Community map | Ward heatmap B; geographic map E | Not built; requirements documented |
| 6 | Notification read/unread | D (not in the SRS 20.5 contract; no schema support) | Not built; documented |
| 7 | Complaint card photos | D (list DTO deliberately has no images) | Not built; documented |
| 8 | Profile screen | C (SRS 15.1 profile management; backend ready) | Added "My Profile" |
| 9 | Login with OTP | D (no SRS requirement, no endpoint) | Not built; documented |
| 11 | Responsive regressions | Static review only | See "Verification" |

## 1 and 2. Complaint photo: Web support and an upload defect on every platform

### What was wrong

**Web:** photo picking was disabled.

- The screens held a `dart:io` `File` and previewed it with `Image.file`. Browsers have no file paths, so neither works there.
- The upload used `http.MultipartFile.fromPath`, which also depends on `dart:io`.

**All platforms:** every uploaded photo was sent with the wrong content type.

- `MultipartFile.fromPath` without a `contentType` sends the part as `application/octet-stream`. This was confirmed in the `http` package source: *"contentType currently defaults to application/octet-stream"*.
- `ComplaintService.validatePhoto` rejects any part whose `Content-Type` is not `image/jpeg`, `image/png` or `image/webp`. The message is "Photo must be JPEG, PNG, or WEBP".
- So complaint photos from Android were rejected by the backend, and so were officers' "after" photos when marking a complaint Resolved.

### Fix

The backend multipart contract is unchanged: part `photo` / `afterPhoto`, the same form fields, and the same limits.

- **`core/api/upload_file.dart` (new): the upload representation.**
  - A photo is held as bytes plus a filename and content type.
  - The real format is detected from the file's leading bytes (JPEG, PNG or WEBP). If that fails, the declared MIME type is used, then the file extension.
  - The same accepted types and the 10 MB limit as the backend are exposed, so bad files are rejected with a clear message before upload.
- **`features/complaints/picked_photo.dart` (new): reading picked files.**
  - Reads an image_picker `XFile` on Android and on Web.
  - Keeps the Android file path only, for the existing offline draft and upload queue.
- **`ApiClient`: multipart parts.** Parts are now built with `MultipartFile.fromBytes(..., contentType: MediaType.parse(type))`.
  - `http_parser` is now declared as a direct dependency.
  - It was already resolved transitively in `pubspec.lock` at 4.1.2, so nothing new is downloaded.
- **Complaint submission screen:**
  - **Preview:** uses `Image.memory`, with a fallback if the image cannot be displayed.
  - **Remove:** still works, and also clears any photo error.
  - **Replace and retake:** still work.
  - **During upload:** shows the photo's size and an uploading overlay.
  - **On Web:** "Choose Photo" opens the browser file chooser. "Use Camera" adds the HTML `capture` hint, so phone browsers open the camera and desktop browsers show the file chooser.
  - **On Android:** the native Camera and Gallery buttons are unchanged.
- **Offline queue:** it needs a durable file path.
  - On Android it re-reads the queued photo as bytes.
  - On Web nothing is queued. If there is no connection, the photo stays selected and the user is asked to press Submit again.
- **Officer "after" photo:** the same byte-based upload is used, with an in-sheet preview.

## 3. Ward selection at registration

**SRS basis:** SRS 16.1 lists "Ward/Area (dropdown)" on the Registration screen.

**Existing backend support:**

- `RegisterRequest.wardId` (nullable).
- `AuthService` stores the ward.
- `users.ward_id` has a foreign key to `wards`.
- Gap-backlog Patch 8 added a public `GET /api/v1/public/wards` endpoint (`PublicWardController`, permitAll). This removed the Phase 17 blocker: the authenticated `/wards` endpoint cannot be called before sign-in.

**The gap:** Flutter never called the public endpoint. It always sent `wardId: null`.

**Fix:**

- `WardsApi.listPublicWards()` calls the existing public endpoint. No duplicate API was added.
- `RegisterScreen` gets a Ward/Area dropdown:
  - It loads the real ward list from the backend and shows loading and error states, with a Retry button.
  - The ward is required whenever the list has loaded.
  - If the list cannot load, the user can still register. The field is optional in the backend, and the ward can be set later in My Profile.
  - The selected `wardId` is sent through the existing `/auth/register` call.
- The OTP flow and all other validation are untouched.

## 4 and 10. First-launch onboarding

**Classification:** no SRS clause cited anywhere in the project mentions onboarding, so it is class D against the SRS. However, the approved design reference (UI Photo.zip) contains a three-screen onboarding: Report Civic Issues / Track Every Update / Improve Your Community, with Skip, Next and Get Started. It was implemented as client-only product scope. It adds no API and no data. If the stakeholders decide it is out of scope, it can be removed by reverting `main.dart`'s startup gate.

**Flow** (the startup gate in `main.dart`):

- Signed in → home screen. Onboarding is never shown to a signed-in user.
- Signed out, onboarding not yet completed → onboarding, then the login screen.
- Signed out, onboarding already completed → login screen.

**Persistence:**

- Completion is stored in `flutter_secure_storage` under its own key, and works on Android and Web.
- Signing out does not reset it.
- If storage cannot be read, onboarding is skipped, so it can never block sign-in.

**Startup side effect:** the startup checks now run once per app start. Previously they also re-ran whenever the theme changed.

## 5. Community map / heatmap: not built

**What exists:**

- `GET /api/v1/community/heatmap` returns `WardHeatmapPointResponse`: wardId, wardName, complaintCount and openComplaintCount. There are no coordinates.
- `GovernmentDashboardService` uses the same ward buckets.
- The Community screen already shows this data as a ward-level heatmap: ranked wards with intensity bars.

**Why a geographic map is class E:**

- `pubspec.yaml` has no map package, and no tile or map provider is configured.
- `wards.boundary_geojson` exists, but it is not exposed by any API and is not seeded. The V15 seed wards have no geometry.
- Individual complaint coordinates are deliberately not published to citizens. The community endpoint is aggregate-only, for privacy.
- `ARCHITECTURE.md` §7 lists a map-rendering layer as a non-goal.

**What a real map needs:**

- A map package, for example `flutter_map` with OpenStreetMap tiles, or `google_maps_flutter` with an API key. Either one also needs a tile or usage agreement.
- Real ward boundary GeoJSON seeded in `wards.boundary_geojson`.
- An API decision on exposing either ward geometry or anonymised, grid-bucketed points.

## 6. Notification read/unread: not built

**What exists:**

- The `notifications` table (V11) and `NotificationResponse` have no read flag.
- `NotificationController` has no mark-read or unread-count endpoint.
- The SRS 20.5 contract lists only GET `/notifications` and GET/PUT `/preferences`.

**Why it was not built:**

- The design reference shows read and unread labels. However, implementing them properly needs server persistence: a schema migration plus new endpoints.
- That would extend the SRS API contract, and the brief says not to change the schema unless it is genuinely required.
- A local-only read flag was explicitly ruled out.

**What remains:** the Notifications screen still groups items into Today and Earlier. The change needs stakeholder sign-off on the contract extension.

## 7. Photos on complaint cards: not built

- `ComplaintSummaryResponse`, used by `GET /complaints`, deliberately carries no images, to keep list payloads small.
- Image URLs are presigned per request, and only in the complaint detail response.
- The Community view shows no individual complaints at all.
- So the cards keep the category tile. Real photos are shown on the complaint detail screen.

**What adding thumbnails would take:** a backend DTO change, plus one presigned URL per list row.

## 8. Profile

**SRS basis:** SRS 15.1 lists "registration and profile management" and "reputation score display".

**What existed:** the backend has supported both since Phases 4 and 5 (`GET` and `PUT /api/v1/users/me`). In Flutter, Settings only showed a read-only name and role header. The Phase 17 documentation also recorded that no screen let a citizen set their ward after registration.

**Fix:** a new "My Profile" screen, opened from Settings (from the header and from an Account → My Profile row).

- **Editable:** full name, and ward (citizens only).
- **Read-only:** mobile number, email, role and status. Mobile number and email are sign-in identifiers, and `PUT /users/me` deliberately does not accept them.
- **Reputation score:** shown for citizens.
- **Ward handling:** `wardId: null` clears the ward on the server, so the current ward is always sent back unless the user changes it.

## 9. Login with OTP: not built

**Where OTP is already used:**

- Registration verification (`verify-otp`).
- The `LOGIN_MFA` second factor (`MfaVerificationScreen`).
- Password reset.

**Why passwordless login is class D:**

- No SRS clause cited in the project requires passwordless OTP login.
- No endpoint exists for it.
- SRS 18 names "registration, login, and OTP verification" as the public endpoints, and those already exist.

No API was invented.

## Verification

**Executed:**

- A tree-sitter Dart parse of all changed and new Dart files.
- A named-parameter and required-parameter check against the Flutter 3.47.1 framework source.
- A member-name check and an import resolution check.
- `tool/check_accessibility.py`, with 0 violations.
- The `http` and `image_picker` / `image_picker_for_web` package sources were read to confirm:
  - the `fromPath` / `fromBytes` content-type defaults
  - `ImageSource.camera` on the web (the `capture` attribute)
  - `imageQuality` support on the web
- `git diff --check` and `git apply --check`.

**Not executed:**

- `flutter analyze`, `flutter test` and `flutter build web`/`apk`. No Flutter or Dart SDK is available; `pub.dev` and `storage.googleapis.com` are blocked by the proxy policy.
- `mvn test`. No backend code changed, and Maven Central is blocked.
- Any end-to-end run of the flows below. No running backend, emulator or browser session was possible against a built app.
  - Android camera → submit
  - Android gallery → submit
  - Web file picker → submit
  - Registration → ward → OTP
  - Profile save
  - Onboarding

**New tests (written, not executed):**

- `test/core/api/upload_file_test.dart`
- `test/features/onboarding/onboarding_screen_test.dart`

**Browser requirements for the Web build:**

- The Web build needs HTTPS, or localhost, for:
  - browser geolocation
  - `flutter_secure_storage`'s WebCrypto
  - mobile-browser camera capture
- The web origin must be listed in `CORS_ALLOWED_ORIGINS`. See `FLUTTER_DOCKER_SETUP.md` §12.
