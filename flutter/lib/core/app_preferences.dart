import 'package:flutter/foundation.dart';

import '../features/settings/models/personal_settings.dart';
import 'l10n/app_strings.dart';

/// Gap-backlog Patches 46/47 (Sep 2026 strict recheck): the high-contrast and
/// language settings were stored server-side (PersonalSettings) but never
/// applied to the UI. main.dart listens to [highContrast]; screens using
/// AppStrings listen to AppStrings.currentLanguage.
class AppPreferences {
  AppPreferences._();

  static final ValueNotifier<bool> highContrast = ValueNotifier<bool>(false);

  static void apply(PersonalSettings settings) {
    highContrast.value = settings.highContrastEnabled;
    AppStrings.currentLanguage.value = settings.language;
  }
}
