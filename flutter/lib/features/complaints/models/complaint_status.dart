/// Mirrors backend ComplaintStatus (V6__create_complaints.sql's locked
/// 12-value list). ESCALATED/REOPENED exist in this list for schema
/// completeness but the backend's ComplaintStateMachine (Phase 6) never
/// actually sets a complaint's status to either value - see that class's
/// Javadoc ("annotations, not statuses"). Flutter never needs to render
/// those two as a status pill because of that; is_escalated/is_reopened
/// flags are rendered as separate badges instead (see ComplaintDetailScreen).
enum ComplaintStatus {
  submitted,
  aiProcessing,
  verified,
  assigned,
  inProgress,
  resolved,
  closed,
  rejected,
  duplicate,
  escalated,
  reopened;

  static ComplaintStatus fromJson(String value) {
    return ComplaintStatus.values.firstWhere(
      (s) => s.wireName == value,
      orElse: () => ComplaintStatus.submitted,
    );
  }

  String get wireName {
    switch (this) {
      case ComplaintStatus.submitted:
        return 'SUBMITTED';
      case ComplaintStatus.aiProcessing:
        return 'AI_PROCESSING';
      case ComplaintStatus.verified:
        return 'VERIFIED';
      case ComplaintStatus.assigned:
        return 'ASSIGNED';
      case ComplaintStatus.inProgress:
        return 'IN_PROGRESS';
      case ComplaintStatus.resolved:
        return 'RESOLVED';
      case ComplaintStatus.closed:
        return 'CLOSED';
      case ComplaintStatus.rejected:
        return 'REJECTED';
      case ComplaintStatus.duplicate:
        return 'DUPLICATE';
      case ComplaintStatus.escalated:
        return 'ESCALATED';
      case ComplaintStatus.reopened:
        return 'REOPENED';
    }
  }

  /// Citizen-facing label (SRS Table 10's plain-language names).
  String get label {
    switch (this) {
      case ComplaintStatus.submitted:
        return 'Submitted';
      case ComplaintStatus.aiProcessing:
        return 'Under Review';
      case ComplaintStatus.verified:
        return 'Verified';
      case ComplaintStatus.assigned:
        return 'Assigned';
      case ComplaintStatus.inProgress:
        return 'In Progress';
      case ComplaintStatus.resolved:
        return 'Resolved';
      case ComplaintStatus.closed:
        return 'Closed';
      case ComplaintStatus.rejected:
        return 'Rejected';
      case ComplaintStatus.duplicate:
        return 'Duplicate';
      case ComplaintStatus.escalated:
        return 'Escalated';
      case ComplaintStatus.reopened:
        return 'Reopened';
    }
  }
}
