import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import 'status_badge.dart';

/// Connected vertical timeline built from a complaint's real status history
/// (oldest first); the latest entry is highlighted as the current step.
/// Shared by the citizen and officer complaint detail screens. Each step is
/// read by screen readers as one phrase, with "current status" spoken
/// rather than conveyed by colour alone.
class StatusTimeline extends StatelessWidget {
  final List<StatusHistoryEntry> history;

  const StatusTimeline({super.key, required this.history});

  @override
  Widget build(BuildContext context) {
    if (history.isEmpty) {
      return const JanCard(child: Text('No status updates yet.', style: TextStyle(color: JanColors.muted)));
    }
    final entries = [...history]
      ..sort((a, b) => (a.changedAt ?? DateTime(0)).compareTo(b.changedAt ?? DateTime(0)));
    final formatter = DateFormat('dd MMM yyyy, hh:mm a');
    return JanCard(
      child: Column(
        children: [
          for (var i = 0; i < entries.length; i++)
            _tile(entries[i], formatter, isFirst: i == 0, isLast: i == entries.length - 1),
        ],
      ),
    );
  }

  Widget _tile(StatusHistoryEntry entry, DateFormat formatter, {required bool isFirst, required bool isLast}) {
    final status = ComplaintStatus.fromJson(entry.newStatus);
    final visual = StatusVisual.of(status);
    final current = isLast;
    final label = entry.newStatus == status.wireName ? status.label : entry.newStatus.replaceAll('_', ' ');
    final meta = [
      if (entry.changedAt != null) formatter.format(entry.changedAt!.toLocal()),
      if (entry.actorName != null) 'by ${entry.actorName}' else entry.actorType,
    ].join(' · ');
    return Semantics(
      label: '$label${current ? ', current status' : ''}. $meta${entry.reason != null && entry.reason!.isNotEmpty ? '. ${entry.reason}' : ''}',
      excludeSemantics: true,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              width: 28,
              child: Column(
                children: [
                  Container(width: 2, height: 6, color: isFirst ? Colors.transparent : JanColors.tealBrand),
                  Container(
                    width: 22,
                    height: 22,
                    decoration: BoxDecoration(
                      color: current ? JanColors.white : JanColors.teal,
                      shape: BoxShape.circle,
                      border: Border.all(color: current ? visual.color : JanColors.teal, width: current ? 3 : 0),
                    ),
                    child: current
                        ? Center(
                            child: Container(
                              width: 8,
                              height: 8,
                              decoration: BoxDecoration(color: visual.color, shape: BoxShape.circle),
                            ),
                          )
                        : const Icon(Icons.check_rounded, size: 14, color: JanColors.white),
                  ),
                  Expanded(
                    child: Container(width: 2, color: isLast ? Colors.transparent : JanColors.tealBrand),
                  ),
                ],
              ),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: Padding(
                padding: EdgeInsets.only(top: 4, bottom: isLast ? 0 : JanSpace.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      label,
                      style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15, color: current ? visual.color : JanColors.navy),
                    ),
                    if (entry.reason != null && entry.reason!.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(entry.reason!, style: const TextStyle(fontSize: 13.5, height: 1.4)),
                      ),
                    const SizedBox(height: 2),
                    Text(meta, style: const TextStyle(fontSize: 12.5, color: JanColors.muted)),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
