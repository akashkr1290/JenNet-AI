import 'package:flutter/widgets.dart';

/// Gap-backlog Patch 34 (Sep 2026 audit): "for a public Indian civic
/// application, support at least English and Hindi."
///
/// Deliberately NOT built on flutter_localizations' ARB/gen-l10n
/// toolchain: that toolchain runs `flutter gen-l10n` to generate Dart
/// classes from .arb files at build time, and no Flutter SDK is
/// installable in this sandbox to run it or verify its output compiles
/// (see this audit's own sandbox-constraints notes) - shipping
/// hand-written "generated" code for a codegen tool would be a real risk
/// of a subtly broken result. This is a plain Dart map-based lookup
/// instead: no codegen, no new dependency, works today.
///
/// Honest scope: covers the labels most visible across the app's main
/// flows (navigation, key actions, common status words) - NOT every
/// string in every screen. Extending coverage means adding more keys to
/// both maps below; screens not yet updated to call `AppStrings.of(context)`
/// still show their original hardcoded English text, which is never
/// wrong, just not yet localized.
///
/// Wiring: PersonalSettingsScreen already lets a user choose EN/HI
/// (`_languageLabels`, stored server-side via PersonalSettings.language)
/// - that preference was previously stored but had no effect on the UI
/// itself. AppStrings.currentLanguage should be set from
/// PersonalSettings.language once loaded (see main.dart / wherever the
/// app's root reads the signed-in user's settings) so the two are
/// actually connected.
class AppStrings {
  AppStrings._();

  /// 'EN' or 'HI' - mirrors PersonalSettings.language's own values
  /// exactly, so no translation layer is needed between the two.
  static final ValueNotifier<String> currentLanguage = ValueNotifier<String>('EN');

  static const Map<String, Map<String, String>> _strings = {
    'EN': {
      'app_title': 'JanNet AI',
      'nav_home': 'Home',
      'nav_my_complaints': 'My Complaints',
      'nav_submit': 'Submit',
      'nav_settings': 'Settings',
      'action_submit_complaint': 'Submit Complaint',
      'action_reopen': 'Reopen This Complaint',
      'action_rate': 'Submit Rating',
      'action_appeal': 'Appeal Rejection',
      'action_retry': 'Retry',
      'label_description': 'Description',
      'label_photos': 'Photos',
      'label_status_timeline': 'Status Timeline',
      'label_estimated_budget': 'Estimated Budget',
      'status_submitted': 'Submitted',
      'status_verified': 'Verified',
      'status_assigned': 'Assigned',
      'status_in_progress': 'In Progress',
      'status_resolved': 'Resolved',
      'status_closed': 'Closed',
      'status_rejected': 'Rejected',
      'privacy_policy': 'Privacy & Data Use',
      'terms_of_use': 'Terms of Use',
      // UI redesign (reference headings / greeting / dashboard hero)
      'title_report_issue': 'Report an Issue',
      'subtitle_report_issue': 'Follow the steps below to file your complaint.',
      'subtitle_community': 'Where civic issues are being reported across your city.',
      'greeting_morning': 'Good morning',
      'greeting_afternoon': 'Good afternoon',
      'greeting_evening': 'Good evening',
      'hero_make_city_better': 'Make your city better.',
      'action_report_civic_issue': 'Report a Civic Issue',
      'dash_in_progress': 'In Progress',
      'dash_resolved': 'Resolved',
      'nav_dashboard': 'Dashboard',
      'nav_community': 'Community',
      'title_community_heatmap': 'Community Heatmap',
      'dash_total': 'Total',
      'dash_pending': 'Pending',
      'dash_recent': 'Recent Complaints',
      'dash_none_yet': 'You have not submitted any complaints yet.',
      'action_confirm_resolution': 'Yes - Confirm Resolution',
      'action_reopen_not_resolved': 'Issue Not Resolved - Reopen',
    },
    'HI': {
      'app_title': 'जनNet AI',
      'nav_home': 'होम',
      'nav_my_complaints': 'मेरी शिकायतें',
      'nav_submit': 'दर्ज करें',
      'nav_settings': 'सेटिंग्स',
      'action_submit_complaint': 'शिकायत दर्ज करें',
      'action_reopen': 'यह शिकायत फिर से खोलें',
      'action_rate': 'रेटिंग जमा करें',
      'action_appeal': 'अस्वीकृति के विरुद्ध अपील करें',
      'action_retry': 'पुनः प्रयास करें',
      'label_description': 'विवरण',
      'label_photos': 'तस्वीरें',
      'label_status_timeline': 'स्थिति समयरेखा',
      'label_estimated_budget': 'अनुमानित बजट',
      'status_submitted': 'दर्ज की गई',
      'status_verified': 'सत्यापित',
      'status_assigned': 'सौंपी गई',
      'status_in_progress': 'प्रगति पर',
      'status_resolved': 'हल हो गई',
      'status_closed': 'बंद',
      'status_rejected': 'अस्वीकृत',
      'privacy_policy': 'गोपनीयता और डेटा उपयोग',
      'terms_of_use': 'उपयोग की शर्तें',
      'title_report_issue': 'समस्या की रिपोर्ट करें',
      'subtitle_report_issue': 'शिकायत दर्ज करने के लिए नीचे दिए चरणों का पालन करें।',
      'subtitle_community': 'आपके शहर में नागरिक समस्याएँ कहाँ दर्ज हो रही हैं।',
      'greeting_morning': 'सुप्रभात',
      'greeting_afternoon': 'नमस्कार',
      'greeting_evening': 'शुभ संध्या',
      'hero_make_city_better': 'अपने शहर को बेहतर बनाएँ।',
      'action_report_civic_issue': 'नागरिक समस्या दर्ज करें',
      'dash_in_progress': 'प्रगति में',
      'dash_resolved': 'हल हुई',
      'nav_dashboard': 'डैशबोर्ड',
      'nav_community': 'समुदाय',
      'title_community_heatmap': 'सामुदायिक हीटमैप',
      'dash_total': 'कुल',
      'dash_pending': 'लंबित',
      'dash_recent': 'हाल की शिकायतें',
      'dash_none_yet': 'आपने अभी तक कोई शिकायत दर्ज नहीं की है।',
      'action_confirm_resolution': 'हाँ - समाधान की पुष्टि करें',
      'action_reopen_not_resolved': 'समस्या हल नहीं हुई - फिर से खोलें',
    },
  };

  /// Falls back to the English string, then to the raw key itself, so a
  /// key missing from one language map never renders as literally blank.
  static String of(String key) {
    return _strings[currentLanguage.value]?[key] ?? _strings['EN']![key] ?? key;
  }
}
