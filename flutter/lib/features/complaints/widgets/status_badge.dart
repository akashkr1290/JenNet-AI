import 'package:flutter/material.dart';

import '../models/complaint_status.dart';

class StatusBadge extends StatelessWidget {
  final ComplaintStatus status;
  const StatusBadge({super.key, required this.status});

  Color get _color {
    switch (status) {
      case ComplaintStatus.submitted:
      case ComplaintStatus.aiProcessing:
        return Colors.blueGrey;
      case ComplaintStatus.verified:
      case ComplaintStatus.assigned:
        return Colors.indigo;
      case ComplaintStatus.inProgress:
      case ComplaintStatus.reopened:
        return Colors.orange;
      case ComplaintStatus.resolved:
      case ComplaintStatus.closed:
        return Colors.green;
      case ComplaintStatus.rejected:
      case ComplaintStatus.duplicate:
        return Colors.red;
      case ComplaintStatus.escalated:
        return Colors.deepOrange;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: _color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: _color.withOpacity(0.4)),
      ),
      child: Text(
        status.label,
        style: TextStyle(color: _color, fontWeight: FontWeight.w600, fontSize: 12),
      ),
    );
  }
}
