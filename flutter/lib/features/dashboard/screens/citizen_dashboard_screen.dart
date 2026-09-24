import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/l10n/app_strings.dart';
import '../../complaints/models/complaint_status.dart';
import '../../complaints/screens/complaint_detail_screen.dart';
import '../../complaints/widgets/status_badge.dart';
import '../personal_dashboard_api.dart';
import '../widgets/stat_tile.dart';

/// Gap-backlog Patch 08 (Sep 2026 strict recheck): citizen dashboard - counts
/// by status plus the five most recent complaints with status, AI
/// classification status, priority (severity) and SLA due time, all from
/// GET /api/v1/citizen/dashboard.
class CitizenDashboardScreen extends StatefulWidget {
  const CitizenDashboardScreen({super.key});

  @override
  State<CitizenDashboardScreen> createState() => _CitizenDashboardScreenState();
}

class _CitizenDashboardScreenState extends State<CitizenDashboardScreen> {
  late Future<CitizenDashboard> _future;

  @override
  void initState() {
    super.initState();
    _future = PersonalDashboardApi.instance.citizen();
  }

  Future<void> _refresh() async {
    setState(() => _future = PersonalDashboardApi.instance.citizen());
    await _future;
  }

  static String _aiLabel(String? aiStatus) {
    switch (aiStatus) {
      case 'AUTO_CLASSIFIED':
        return 'AI classified';
      case 'MANUAL_REVIEW_REQUIRED':
        return 'Human review';
      case 'MODEL_UNAVAILABLE':
        return 'Manual verification';
      default:
        return 'AI pending';
    }
  }

  @override
  Widget build(BuildContext context) {
    final fmt = DateFormat('dd MMM, hh:mm a');
    return RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<CitizenDashboard>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            final message = snapshot.error is ApiException
                ? (snapshot.error as ApiException).message
                : 'Could not load your dashboard.';
            return ListView(children: [
              const SizedBox(height: 120),
              Center(child: Text(message, textAlign: TextAlign.center)),
              Center(child: TextButton(onPressed: _refresh, child: Text(AppStrings.of('action_retry')))),
            ]);
          }
          final d = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Wrap(spacing: 8, runSpacing: 8, children: [
                StatTile(label: AppStrings.of('dash_total'), value: d.total, color: Colors.indigo, icon: Icons.summarize_outlined),
                StatTile(label: AppStrings.of('dash_pending'), value: d.pending, color: Colors.orange, icon: Icons.hourglass_top),
                StatTile(label: AppStrings.of('status_in_progress'), value: d.inProgress, color: Colors.blue, icon: Icons.construction),
                StatTile(label: AppStrings.of('status_resolved'), value: d.resolved, color: Colors.green, icon: Icons.task_alt),
                StatTile(label: AppStrings.of('status_closed'), value: d.closed, color: Colors.grey, icon: Icons.lock_outline),
                if (d.rejected > 0)
                  StatTile(label: AppStrings.of('status_rejected'), value: d.rejected, color: Colors.red, icon: Icons.block),
              ]),
              const SizedBox(height: 20),
              Text(AppStrings.of('dash_recent'), style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              if (d.recentComplaints.isEmpty) Text(AppStrings.of('dash_none_yet')),
              ...d.recentComplaints.map((c) {
                final parts = <String>[
                  c.category.replaceAll('_', ' '),
                  _aiLabel(c.aiStatus),
                  if (c.severity != null) 'Priority: ${c.severity}',
                  if (c.slaBreached)
                    'SLA breached'
                  else if (c.slaDueAt != null)
                    'Due ${fmt.format(c.slaDueAt!.toLocal())}',
                ];
                return Card(
                  child: ListTile(
                    title: Text(c.referenceNumber),
                    subtitle: Text(parts.join(' · ')),
                    trailing: StatusBadge(status: ComplaintStatus.fromJson(c.status)),
                    onTap: () async {
                      await Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => ComplaintDetailScreen(complaintId: c.complaintId),
                      ));
                      if (mounted) _refresh();
                    },
                  ),
                );
              }),
            ],
          );
        },
      ),
    );
  }
}
