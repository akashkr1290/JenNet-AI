import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../complaints/models/complaint_status.dart';
import '../../complaints/screens/officer_complaint_detail_screen.dart';
import '../../complaints/widgets/status_badge.dart';
import '../personal_dashboard_api.dart';
import '../widgets/stat_tile.dart';

/// Gap-backlog Patch 09 (Sep 2026 strict recheck): officer dashboard - own
/// workload by status, SLA status of open work (overdue / at risk / on track),
/// today's tasks (overdue or due within 24h), and 30-day personal
/// performance, from GET /api/v1/officer/dashboard. Department-level
/// comparison stays on the Department Head's existing Performance and
/// Dashboard tabs, which already enforce department scope.
class OfficerDashboardScreen extends StatefulWidget {
  const OfficerDashboardScreen({super.key});

  @override
  State<OfficerDashboardScreen> createState() => _OfficerDashboardScreenState();
}

class _OfficerDashboardScreenState extends State<OfficerDashboardScreen> {
  late Future<OfficerDashboard> _future;

  @override
  void initState() {
    super.initState();
    _future = PersonalDashboardApi.instance.officer();
  }

  Future<void> _refresh() async {
    setState(() => _future = PersonalDashboardApi.instance.officer());
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    final fmt = DateFormat('dd MMM, hh:mm a');
    return RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<OfficerDashboard>(
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
              Center(child: TextButton(onPressed: _refresh, child: const Text('Retry'))),
            ]);
          }
          final d = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text('My Workload', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                StatTile(label: 'Assigned', value: d.assigned, color: Colors.indigo, icon: Icons.assignment_ind_outlined),
                StatTile(label: 'In Progress', value: d.inProgress, color: Colors.blue, icon: Icons.construction),
                StatTile(label: 'Resolved', value: d.resolved, color: Colors.green, icon: Icons.task_alt),
                StatTile(label: 'Closed', value: d.closed, color: Colors.grey, icon: Icons.lock_outline),
              ]),
              const SizedBox(height: 16),
              Text('SLA Status (open work)', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                StatTile(label: 'Overdue', value: d.overdue, color: Colors.red, icon: Icons.warning_amber),
                StatTile(label: 'At Risk (80%+)', value: d.atRisk, color: Colors.orange, icon: Icons.schedule),
                StatTile(label: 'On Track', value: d.onTrack, color: Colors.green, icon: Icons.check_circle_outline),
                if (d.noSla > 0)
                  StatTile(label: 'No SLA yet', value: d.noSla, color: Colors.grey, icon: Icons.help_outline),
              ]),
              const SizedBox(height: 16),
              Text('Personal Performance (30 days)', style: Theme.of(context).textTheme.titleMedium),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('Resolved: ${d.resolvedLast30Days}'),
                    Text('Average resolution time: '
                        '${d.avgResolutionHoursLast30Days == null ? '-' : '${d.avgResolutionHoursLast30Days} h'}'),
                    Text('Resolved within SLA: '
                        '${d.slaCompliancePercentLast30Days == null ? '-' : '${d.slaCompliancePercentLast30Days}%'}'),
                  ]),
                ),
              ),
              const SizedBox(height: 16),
              Text("Today's Tasks", style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              if (d.todaysTasks.isEmpty) const Text('Nothing overdue or due in the next 24 hours.'),
              ...d.todaysTasks.map((t) => Card(
                    child: ListTile(
                      leading: Icon(t.overdue ? Icons.warning_amber : Icons.schedule,
                          color: t.overdue ? Colors.red : Colors.orange),
                      title: Text(t.referenceNumber),
                      subtitle: Text([
                        t.category.replaceAll('_', ' '),
                        if (t.severity != null) t.severity!,
                        if (t.slaDueAt != null) (t.overdue ? 'Was due ' : 'Due ') + fmt.format(t.slaDueAt!.toLocal()),
                      ].join(' · ')),
                      trailing: StatusBadge(status: ComplaintStatus.fromJson(t.status)),
                      onTap: () async {
                        await Navigator.of(context).push(MaterialPageRoute(
                          builder: (_) => OfficerComplaintDetailScreen(complaintId: t.complaintId),
                        ));
                        if (mounted) _refresh();
                      },
                    ),
                  )),
            ],
          );
        },
      ),
    );
  }
}
