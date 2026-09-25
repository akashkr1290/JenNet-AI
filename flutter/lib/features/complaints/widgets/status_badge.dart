import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';
import '../models/complaint_status.dart';

/// Visual treatment for each complaint status, following the reference
/// badge set (Submitted = amber, In Progress = blue, Resolved = teal,
/// Assigned/Verified = navy, Rejected = red). Text colours are the dark
/// member of each family (>= 4.5:1 on their tint), and every status also
/// carries its own icon, so status is never communicated by colour alone.
class StatusVisual {
  final Color color;
  final Color background;
  final IconData icon;

  const StatusVisual(this.color, this.background, this.icon);

  static StatusVisual of(ComplaintStatus status) {
    switch (status) {
      case ComplaintStatus.submitted:
        return const StatusVisual(JanColors.amberDark, JanColors.amberLight, Icons.inbox_outlined);
      case ComplaintStatus.aiProcessing:
        return const StatusVisual(JanColors.amberDark, JanColors.amberLight, Icons.auto_awesome_outlined);
      case ComplaintStatus.verified:
        return const StatusVisual(JanColors.navy, Color(0xFFE3E9F2), Icons.verified_outlined);
      case ComplaintStatus.assigned:
        return const StatusVisual(JanColors.navy, Color(0xFFE3E9F2), Icons.assignment_ind_outlined);
      case ComplaintStatus.inProgress:
        return const StatusVisual(JanColors.primary, JanColors.infoLight, Icons.construction_outlined);
      case ComplaintStatus.reopened:
        return const StatusVisual(JanColors.primary, JanColors.infoLight, Icons.restart_alt_rounded);
      case ComplaintStatus.resolved:
        return const StatusVisual(JanColors.teal, JanColors.tealLight, Icons.task_alt_rounded);
      case ComplaintStatus.closed:
        return const StatusVisual(JanColors.teal, JanColors.tealLight, Icons.lock_outline_rounded);
      case ComplaintStatus.rejected:
        return const StatusVisual(JanColors.error, JanColors.errorLight, Icons.block_rounded);
      case ComplaintStatus.duplicate:
        return const StatusVisual(JanColors.error, JanColors.errorLight, Icons.content_copy_rounded);
      case ComplaintStatus.escalated:
        return const StatusVisual(Color(0xFF9A4E0E), Color(0xFFFCE9D8), Icons.priority_high_rounded);
    }
  }
}

/// Rounded status pill: icon + label.
class StatusBadge extends StatelessWidget {
  final ComplaintStatus status;
  const StatusBadge({super.key, required this.status});

  @override
  Widget build(BuildContext context) {
    final v = StatusVisual.of(status);
    return Semantics(
      label: 'Status: ${status.label}',
      excludeSemantics: true,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: v.background,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: v.color.withValues(alpha: 0.35)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(v.icon, size: 14, color: v.color),
            const SizedBox(width: 5),
            Text(
              status.label,
              style: TextStyle(color: v.color, fontWeight: FontWeight.w700, fontSize: 12, letterSpacing: 0.2),
            ),
          ],
        ),
      ),
    );
  }
}
