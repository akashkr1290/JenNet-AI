# JanNet AI – Flutter UI Design System

This describes the Flutter UI redesign (patch `jannet-ai-complete-ui-redesign.patch`).
It restyles every existing screen to match the reference designs in `UI Photo.zip`.
The functional behaviour did not change. No screens were added or removed, and there
are no new endpoints or new dependencies.

## 1. Where things live

| File | Purpose |
|---|---|
| `lib/core/theme/jan_tokens.dart` | Design tokens: `JanColors`, `JanSpace`, `JanRadius`, `JanShadows`, `JanBreakpoints` |
| `lib/core/theme/jan_theme.dart` | `JanTheme.light({highContrast})` builds the full Material 3 `ThemeData` from the tokens |
| `lib/core/widgets/jan_logo.dart` | Vector logo mark (`JanLogoMark`) and wordmark (`JanLogo`), with no image assets |
| `lib/core/widgets/jan_illustrations.dart` | Skyline / civic-network painters, dashboard hero, state illustrations |
| `lib/core/widgets/jan_surfaces.dart` | `JanBackdrop`, `ResponsiveCenter`, `JanCard`, `JanSectionHeader`, `JanPageHeading`, `JanFieldLabel`, `JanPage` |
| `lib/core/widgets/jan_states.dart` | `JanLoadingView`, `JanSkeletonList`, `JanEmptyState`, `JanErrorState(.fromError)`, `JanBanner`, `showJanSuccessDialog` |
| `lib/core/widgets/jan_stat_card.dart` | `JanStatCard` (tones navy/amber/blue/teal/slate/light) and `JanStatGrid` |
| `lib/core/widgets/jan_shell.dart` | `JanShell`, the adaptive role home shell used by all five roles |
| `lib/core/widgets/jan_form_widgets.dart` | `JanOtpInput` (segmented OTP on one real TextField), `JanPasswordStrength` |
| `lib/features/auth/widgets/auth_layout.dart` | `JanAuthLayout` / `JanAuthHeader` / `JanButtonSpinner` for all auth screens |
| `lib/features/complaints/widgets/status_badge.dart` | `StatusBadge` + `StatusVisual` (colour + icon + label per status) |
| `lib/features/complaints/widgets/category_visuals.dart` | `CategoryVisual` / `CategoryTile` per complaint category |
| `lib/features/complaints/widgets/status_timeline.dart` | `StatusTimeline`, shared by the citizen and officer detail screens |

## 2. Colour

The reference palette has sky blue `#5C9FD8`, navy `#203A5F`, teal `#38A9A2` and
amber `#F3A83F`. Some of these fail WCAG AA for small text:

- white on sky: 2.8:1
- white on teal: 2.9:1
- white on amber: 2.0:1

So each colour has two roles:

| Reference colour | Decorative use (logo, illustrations) | Text and filled-control use (AA) |
|---|---|---|
| Sky `#5C9FD8` | `JanColors.sky` | `JanColors.primary` `#2C6FAE` (5.3:1 with white) |
| Teal `#38A9A2` | `JanColors.tealBrand` | `JanColors.teal` `#1F7F79` (4.8:1 with white) |
| Amber `#F3A83F` | `JanColors.amber` (surfaces only) | Navy text on amber (5.7:1); `JanColors.amberDark` for amber-family text |

Secondary text is `JanColors.muted` `#5B6B80`, at 4.7:1 or better on white and on the page
background. The high-contrast user setting (`AppPreferences.highContrast`) is passed to
`JanTheme.light(highContrast: true)`. That mode darkens the text and borders.

### Complaint status colours

Every status has a colour, an icon and a text label. The label is always shown, so
colour is never the only signal.

- Submitted / Under review: amber
- Verified / Assigned: navy
- In progress / Reopened: blue
- Resolved / Closed: teal
- Rejected / Duplicate: red
- Escalated: orange-brown

## 3. Responsive rules

- **Breakpoints** (`JanBreakpoints`):
  - Medium is at 720 px.
  - Expanded is at 1024 px.
  - Content is capped at 1180 px and forms at 560 px.
- **Home shells** (`JanShell`):
  - Phones and tablets get the JanNet top bar and a floating rounded bottom navigation.
  - At 1024 px and wider there is a navy side rail (logo, role, destinations, sign out), a slim top bar, and centred content.
- **Auth screens**:
  - Phones get a single column form.
  - At 1024 px and wider, a navy brand panel ("Report. Track. Improve." plus the three onboarding points) sits beside the form.
- **Wide layouts at 860–900 px and up**:
  - These screens switch to two columns: dashboards, submit, detail, verification review, officer detail, department performance and government dashboard.
  - Complaint lists and queues switch to a grid.
  - Grids with fixed-height cards are only used when the text scale is 1.3 or less. Larger accessibility text falls back to lists, so text is never clipped.

## 4. Accessibility

- Every icon button has a tooltip. Images have either a semantic label or `excludeFromSemantics`. Decorative painters sit inside `ExcludeSemantics`. This is enforced by `tool/check_accessibility.py`, which finds 0 violations.
- Cards, stat cards, timeline steps and ward cards are read as one phrase each, for example "Pending: 8" or "In Progress, current status".
- Error text (`ErrorText`) is a live region that begins with "Error:".
- On mobile, touch targets keep Material's padded tap-target size (a 48 dp hit area). Filled buttons are at least 50 dp high.
- User text scaling is never overridden.

## 5. Rules for new UI code

- Use the tokens (`JanColors`, `JanSpace`, `JanRadius`). Do not use raw `Colors.*` or magic numbers.
- Use `withValues(alpha:)`, not `withOpacity`.
- On `DropdownButtonFormField`, use `initialValue`, not `value`.
- Use the shared loading, empty and error widgets instead of a bare `CircularProgressIndicator` or `Text(error)`.
- Never put `JanEmptyState` or `JanErrorState` inside another scroll view. Return them directly: they are already scrollable, so pull-to-refresh keeps working.
