import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import 'complaint_list_screen.dart';
import 'officer_complaint_detail_screen.dart';

/// Phase 12 (SRS 16.2 "Officer Queue"). Permissions ("Government Officer
/// (own queue), Department Head (whole department queue)") are enforced
/// entirely server-side by ComplaintService.list/findForOfficerOrDepartment
/// - this screen just calls the same GET /complaints endpoint the citizen
/// tracking screen uses and renders whatever comes back, same as
/// ComplaintListScreen. The only client-side addition is the status
/// filter chip row (SRS 16.2 lists status as a queue filter) and routing
/// each row to the Officer-specific detail screen rather than the
/// citizen-read-only one.
///
/// UI redesign: the shared ComplaintSummaryCard with escalation and
/// severity shown as labelled chips (text + icon, not colour alone),
/// skeleton loading, empty/error states and a two-column grid on web.
class OfficerQueueScreen extends StatefulWidget {
  const OfficerQueueScreen({super.key});

  @override
  State<OfficerQueueScreen> createState() => _OfficerQueueScreenState();
}

class _OfficerQueueScreenState extends State<OfficerQueueScreen> {
  ComplaintStatus? _filter;
  late Future<List<ComplaintSummary>> _future;

  static const _filterable = [
    ComplaintStatus.assigned,
    ComplaintStatus.inProgress,
    ComplaintStatus.resolved,
    ComplaintStatus.closed,
  ];

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.list(status: _filter);
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.list(status: _filter));
    await _future;
  }

  void _setFilter(ComplaintStatus? status) {
    setState(() {
      _filter = status;
      _future = ComplaintsApi.instance.list(status: _filter);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 56,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: JanSpace.md, vertical: JanSpace.xs),
            children: [
              _filterChip(null, 'All'),
              const SizedBox(width: JanSpace.xs),
              for (final s in _filterable) ...[
                _filterChip(s, s.label),
                const SizedBox(width: JanSpace.xs),
              ],
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: _refresh,
            child: FutureBuilder<List<ComplaintSummary>>(
              future: _future,
              builder: (context, snapshot) {
                if (snapshot.connectionState == ConnectionState.waiting) {
                  return const JanSkeletonList(semanticLabel: 'Loading the queue');
                }
                if (snapshot.hasError) {
                  return JanErrorState.fromError(
                    snapshot.error,
                    fallback: 'Could not load the queue.',
                    onRetry: _refresh,
                  );
                }
                final complaints = snapshot.data ?? [];
                if (complaints.isEmpty) {
                  return const JanEmptyState(
                    icon: Icons.inbox_outlined,
                    title: 'Queue is clear',
                    message: 'Nothing in your queue right now.',
                  );
                }
                return LayoutBuilder(builder: (context, constraints) {
                  final textScale = MediaQuery.textScalerOf(context).scale(16) / 16;
                  Widget card(int index) => _queueCard(complaints[index]);
                  if (constraints.maxWidth >= 860 && textScale <= 1.3) {
                    return GridView.builder(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
                      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                        maxCrossAxisExtent: 560,
                        mainAxisExtent: 176,
                        crossAxisSpacing: JanSpace.md,
                        mainAxisSpacing: JanSpace.md,
                      ),
                      itemCount: complaints.length,
                      itemBuilder: (context, index) => card(index),
                    );
                  }
                  return ListView.separated(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
                    itemCount: complaints.length,
                    separatorBuilder: (_, __) => const SizedBox(height: JanSpace.sm),
                    itemBuilder: (context, index) => card(index),
                  );
                });
              },
            ),
          ),
        ),
      ],
    );
  }

  Widget _queueCard(ComplaintSummary c) {
    return ComplaintSummaryCard(
      complaint: c,
      trailing: c.isEscalated
          ? _chip('Escalated', Icons.priority_high_rounded, JanColors.orange, JanColors.amberLight, fg: JanColors.amberDark)
          : (c.severity != null ? _severityDot(c.severity!) : null),
      onTap: () async {
        await Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => OfficerComplaintDetailScreen(complaintId: c.complaintId),
          ),
        );
        if (mounted) _refresh();
      },
    );
  }

  Widget _filterChip(ComplaintStatus? status, String label) {
    final selected = _filter == status;
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => _setFilter(status),
    );
  }

  /// Severity as a labelled chip (the previous dot relied on colour alone).
  Widget _severityDot(String severity) {
    switch (severity) {
      case 'CRITICAL':
        return _chip('Critical', Icons.circle, JanColors.error, JanColors.errorLight);
      case 'HIGH':
        return _chip('High', Icons.circle, JanColors.orange, JanColors.amberLight, fg: JanColors.amberDark);
      case 'MEDIUM':
        return _chip('Medium', Icons.circle, JanColors.amber, JanColors.amberLight, fg: JanColors.amberDark);
      default:
        return _chip(
          severity.isEmpty ? 'Low' : severity[0] + severity.substring(1).toLowerCase(),
          Icons.circle,
          JanColors.muted,
          JanColors.surfaceAlt,
        );
    }
  }

  Widget _chip(String label, IconData icon, Color iconColor, Color background, {Color? fg}) {
    return Semantics(
      label: 'Severity: $label',
      excludeSemantics: true,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(10)),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 10, color: iconColor),
            const SizedBox(width: 4),
            Text(label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: fg ?? iconColor)),
          ],
        ),
      ),
    );
  }
}
