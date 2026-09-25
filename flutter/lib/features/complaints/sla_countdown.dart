/// Audit GAP-040 (SRS 16.2 Officer Queue "SLA countdown"): a short label for
/// the time left until a complaint's persisted SLA deadline. Pure Dart so it
/// can be unit-tested.
class SlaCountdown {
  final String label;
  final bool overdue;

  /// Less than 24 hours left (not yet overdue).
  final bool dueSoon;

  const SlaCountdown(this.label, {required this.overdue, required this.dueSoon});

  static SlaCountdown? of(DateTime? dueAt, DateTime now) {
    if (dueAt == null) return null;
    final diff = dueAt.difference(now);
    if (diff.isNegative) {
      return SlaCountdown('Overdue by ${_span(-diff)}', overdue: true, dueSoon: false);
    }
    return SlaCountdown('Due in ${_span(diff)}', overdue: false, dueSoon: diff.inHours < 24);
  }

  static String _span(Duration d) {
    if (d.inDays >= 1) return '${d.inDays}d ${d.inHours % 24}h';
    if (d.inHours >= 1) return '${d.inHours}h ${d.inMinutes % 60}m';
    return '${d.inMinutes < 1 ? 1 : d.inMinutes}m';
  }
}

/// Queue orders understood by GET /api/v1/complaints?sort=... (backend ComplaintSort).
const Map<String, String> complaintQueueSorts = {
  'NEWEST': 'Newest',
  'SEVERITY': 'Severity',
  'SLA_DUE': 'SLA due',
};
