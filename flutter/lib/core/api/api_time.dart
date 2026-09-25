/// Audit GAP-026 (SRS 20.6: API timestamps are UTC).
///
/// The backend now sends `2026-09-25T10:00:00Z`. Older responses (and any
/// cached/offline data) carry the same UTC wall-clock time WITHOUT the `Z`,
/// which `DateTime.parse` would read as device-local time - shifting every
/// time by the device's offset (5 h 30 min in India). This parser treats a
/// timestamp without an offset as UTC and returns it in device-local time, so
/// screens can format the result directly (calling `.toLocal()` again is
/// harmless).
///
/// Returns null for null, empty or unparseable input (same contract as the
/// `DateTime.tryParse` calls it replaces). Use only for date-TIME fields; a
/// plain date such as `2026-09-25` is not a timestamp.
DateTime? parseApiTimestamp(Object? value) {
  if (value is! String || value.isEmpty) return null;
  final hasOffset = RegExp(r'(Z|z|[+-]\d{2}(:?\d{2})?)$').hasMatch(value);
  final parsed = DateTime.tryParse(hasOffset ? value : '${value}Z');
  return parsed?.toLocal();
}
