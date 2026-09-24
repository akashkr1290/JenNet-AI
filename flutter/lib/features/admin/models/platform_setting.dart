/// Mirrors SettingResponse (backend, dto/admin/) - one row of the Admin
/// Settings screen (SRS 16.3). See PlatformSettingsService's Javadoc for
/// what [overridden] false vs. true means.
class PlatformSetting {
  final String key;
  final String? value;
  final String recommendedDefault;
  final bool overridden;
  final DateTime? updatedAt;
  final String? updatedByName;

  PlatformSetting({
    required this.key,
    this.value,
    required this.recommendedDefault,
    required this.overridden,
    this.updatedAt,
    this.updatedByName,
  });

  /// What's actually in effect for display purposes: the override if one
  /// exists, otherwise the recommended default shown as a reference (the
  /// true runtime fallback may differ per-deployment - see
  /// PlatformSettingsService's Javadoc - but this is the best a client
  /// can show without a dedicated "effective value" endpoint).
  String get displayValue => value ?? recommendedDefault;

  factory PlatformSetting.fromJson(Map<String, dynamic> json) {
    return PlatformSetting(
      key: json['key'] as String,
      value: json['value'] as String?,
      recommendedDefault: json['recommendedDefault'] as String,
      overridden: json['overridden'] as bool,
      updatedAt: json['updatedAt'] != null ? DateTime.tryParse(json['updatedAt'] as String) : null,
      updatedByName: json['updatedByName'] as String?,
    );
  }
}

/// Human-readable label + short helper text per known key - mirrors
/// PlatformSettingKey (backend) one-to-one; kept as a simple lookup table
/// here rather than derived from the API response, since the API doesn't
/// (and shouldn't) send display copy.
const Map<String, String> platformSettingLabels = {
  'ai_confidence_threshold': 'AI Confidence Threshold (%)',
  'duplicate_similarity_threshold': 'Duplicate Similarity Threshold (%)',
  'sla_hours_critical': 'SLA Hours — Critical',
  'sla_hours_high': 'SLA Hours — High',
  'sla_hours_medium': 'SLA Hours — Medium',
  'sla_hours_low': 'SLA Hours — Low',
  'budget_approval_threshold_inr': 'Budget Approval Threshold (INR)',
};
