import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_stat_card.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../../complaints/models/complaint_status.dart';
import '../../complaints/screens/officer_complaint_detail_screen.dart';
import '../../complaints/widgets/status_badge.dart';
import '../personal_dashboard_api.dart';

/// Gap-backlog Patch 09 (Sep 2026 strict recheck): officer dashboard - own
/// workload by status, SLA status of open work (overdue / at risk / on track),
/// today's tasks (overdue or due within 24h), and 30-day personal
/// performance, from GET /api/v1/officer/dashboard. Department-level
/// comparison stays on the Department Head's existing Performance and
/// Dashboard tabs, which already enforce department scope.
///
/// UI redesign: reference dashboard styling - coloured stat cards, an SLA
/// section whose labels carry the meaning (not colour alone), a
/// performance card and today's task cards; two columns on wide screens.
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
            return const JanLoadingView(message: 'Loading your dashboard...');
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return JanErrorState.fromError(
              snapshot.error,
              fallback: 'Could not load your dashboard.',
              onRetry: _refresh,
            );
          }
          final d = snapshot.data!;
          return LayoutBuilder(builder: (context, constraints) {
            final wide = constraints.maxWidth >= 900;
            final left = Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const JanSectionHeader(title: 'My Workload'),
                JanStatGrid(children: [
                  JanStatCard(label: 'Assigned', value: '${d.assigned}', icon: Icons.assignment_ind_outlined, tone: JanTone.navy),
                  JanStatCard(label: 'In Progress', value: '${d.inProgress}', icon: Icons.construction_rounded, tone: JanTone.blue),
                  JanStatCard(label: 'Resolved', value: '${d.resolved}', icon: Icons.task_alt_rounded, tone: JanTone.teal),
                  JanStatCard(label: 'Closed', value: '${d.closed}', icon: Icons.lock_outline_rounded, tone: JanTone.slate),
                ]),
                const JanSectionHeader(title: 'SLA Status (open work)'),
                JanStatGrid(children: [
                  JanStatCard(label: 'Overdue', value: '${d.overdue}', icon: Icons.warning_amber_rounded, tone: JanTone.light),
                  JanStatCard(label: 'At Risk (80%+)', value: '${d.atRisk}', icon: Icons.schedule_rounded, tone: JanTone.amber),
                  JanStatCard(label: 'On Track', value: '${d.onTrack}', icon: Icons.check_circle_outline_rounded, tone: JanTone.teal),
                  if (d.noSla > 0)
                    JanStatCard(label: 'No SLA yet', value: '${d.noSla}', icon: Icons.help_outline_rounded, tone: JanTone.slate),
                ]),
                const JanSectionHeader(title: 'Personal Performance (30 days)'),
                JanCard(
                  child: Column(
                    children: [
                      _metricRow(Icons.task_alt_rounded, 'Resolved', '${d.resolvedLast30Days}'),
                      const Divider(height: 20),
                      _metricRow(
                        Icons.timer_outlined,
                        'Average resolution time',
                        d.avgResolutionHoursLast30Days == null ? '-' : '${d.avgResolutionHoursLast30Days} h',
                      ),
                      const Divider(height: 20),
                      _metricRow(
                        Icons.verified_outlined,
                        'Resolved within SLA',
                        d.slaCompliancePercentLast30Days == null ? '-' : '${d.slaCompliancePercentLast30Days}%',
                      ),
                    ],
                  ),
                ),
              ],
            );
            final right = Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const JanSectionHeader(title: "Today's Tasks"),
                if (d.todaysTasks.isEmpty)
                  const JanCard(
                    child: Row(
                      children: [
                        Icon(Icons.celebration_outlined, color: JanColors.teal),
                        SizedBox(width: JanSpace.sm),
                        Expanded(child: Text('Nothing overdue or due in the next 24 hours.')),
                      ],
                    ),
                  ),
                for (final t in d.todaysTasks) ...[
                  JanCard(
                    padding: const EdgeInsets.all(JanSpace.sm),
                    semanticLabel: '${t.overdue ? 'Overdue' : 'Due soon'}: ${t.referenceNumber}. Open details',
                    onTap: () async {
                      await Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => OfficerComplaintDetailScreen(complaintId: t.complaintId),
                      ));
                      if (mounted) _refresh();
                    },
                    child: Row(
                      children: [
                        Container(
                          width: 44,
                          height: 44,
                          decoration: BoxDecoration(
                            color: t.overdue ? JanColors.errorLight : JanColors.amberLight,
                            borderRadius: JanRadius.mdAll,
                          ),
                          child: Icon(
                            t.overdue ? Icons.warning_amber_rounded : Icons.schedule_rounded,
                            color: t.overdue ? JanColors.error : JanColors.amberDark,
                          ),
                        ),
                        const SizedBox(width: JanSpace.sm),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(t.referenceNumber,
                                  style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                              Text(
                                [
                                  t.category.replaceAll('_', ' '),
                                  if (t.severity != null) t.severity!,
                                  if (t.slaDueAt != null)
                                    (t.overdue ? 'Was due ' : 'Due ') + fmt.format(t.slaDueAt!.toLocal()),
                                ].join(' · '),
                                style: TextStyle(
                                  fontSize: 12.5,
                                  color: t.overdue ? JanColors.error : JanColors.muted,
                                  fontWeight: t.overdue ? FontWeight.w700 : FontWeight.w500,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: JanSpace.xs),
                        StatusBadge(status: ComplaintStatus.fromJson(t.status)),
                      ],
                    ),
                  ),
                  const SizedBox(height: JanSpace.sm),
                ],
              ],
            );
            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(JanSpace.md, 0, JanSpace.md, JanSpace.xxl),
              children: [
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(flex: 3, child: left),
                      const SizedBox(width: JanSpace.xl),
                      Expanded(flex: 2, child: right),
                    ],
                  )
                else ...[
                  right,
                  left,
                ],
              ],
            );
          });
        },
      ),
    );
  }

  Widget _metricRow(IconData icon, String label, String value) {
    return Semantics(
      label: '$label: $value',
      excludeSemantics: true,
      child: Row(
        children: [
          Icon(icon, color: JanColors.primary, size: 22),
          const SizedBox(width: JanSpace.sm),
          Expanded(child: Text(label, style: const TextStyle(color: JanColors.slate))),
          Text(value, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16, color: JanColors.navy)),
        ],
      ),
    );
  }
}
