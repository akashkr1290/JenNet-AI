import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_stat_card.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../department_api.dart';
import '../models/department_performance.dart';

/// Phase 13 (SRS 16.2 "Department Performance View (Department Head)":
/// "Monitor officer workload, SLA compliance, and department KPIs" -
/// "UI Components: KPI tiles, officer workload table, SLA compliance
/// chart" - "Buttons: Reassign Officer, Export Report"). Deliberately
/// scoped to a single department's own performance - see
/// DepartmentPerformanceService's class Javadoc for why the broader,
/// multi-department Government Dashboard (SRS 15.10/16.3) stays out of
/// scope for Phase 13 (Phase 16).
///
/// "Reassign Officer" here reassigns a specific complaint to a different
/// officer within the department (SRS 16.2's own wording: "Actions:
/// view officer-level breakdown, reassign complaints, export performance
/// report") - implemented as a per-complaint action from the shared
/// Officer Queue/Detail screens (see OfficerComplaintDetailScreen's
/// Phase 13 addition) rather than duplicated here; this screen's own
/// "Reassign Officer" affordance is the officer workload table itself,
/// which a Department Head reads before deciding which complaint(s) to
/// reassign and to whom.
///
/// UI redesign: coloured KPI cards, an SLA compliance card whose rating is
/// written out (not colour alone) and officer workload cards; KPIs and the
/// workload list sit side by side on wide screens.
class DepartmentPerformanceScreen extends StatefulWidget {
  final int departmentId;
  const DepartmentPerformanceScreen({super.key, required this.departmentId});

  @override
  State<DepartmentPerformanceScreen> createState() => _DepartmentPerformanceScreenState();
}

class _DepartmentPerformanceScreenState extends State<DepartmentPerformanceScreen> {
  late Future<DepartmentPerformance> _future;
  bool _exporting = false;

  @override
  void initState() {
    super.initState();
    _future = DepartmentApi.instance.performance(widget.departmentId);
  }

  Future<void> _refresh() async {
    setState(() => _future = DepartmentApi.instance.performance(widget.departmentId));
    await _future;
  }

  void _showError(Object e) {
    final message = e is ApiException ? e.message : 'Something went wrong.';
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  /// SRS 16.2 "Export Report" button. No file-save/share plugin is
  /// available in this environment (no outbound network access to add a
  /// new pub package - see PROJECT_PROGRESS.md's persistent environment
  /// constraint), so the CSV is copied to the clipboard via Flutter's
  /// built-in `services.dart` (no new dependency) rather than written to
  /// disk - documented as a Phase 13 KNOWN LIMITATION, not a silent
  /// simplification.
  Future<void> _exportReport() async {
    setState(() => _exporting = true);
    try {
      final csv = await DepartmentApi.instance.exportPerformanceCsv(widget.departmentId);
      await Clipboard.setData(ClipboardData(text: csv));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Report copied to clipboard as CSV.')),
        );
      }
    } catch (e) {
      _showError(e);
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<DepartmentPerformance>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanLoadingView(message: 'Loading department performance...');
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return JanErrorState.fromError(
              snapshot.error,
              fallback: 'Could not load department performance.',
              onRetry: _refresh,
            );
          }
          final perf = snapshot.data!;
          return LayoutBuilder(builder: (context, constraints) {
            final wide = constraints.maxWidth >= 900;
            final kpis = Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ---- KPI tiles ----
                const JanSectionHeader(title: 'Key Indicators'),
                JanStatGrid(
                  maxColumns: 3,
                  children: [
                    _kpiTile('Total', perf.totalComplaints.toString(), Icons.apartment_rounded, JanTone.navy),
                    _kpiTile('Open', perf.openComplaints.toString(), Icons.pending_actions_rounded, JanTone.amber),
                    _kpiTile('Resolved', perf.resolvedComplaints.toString(), Icons.task_alt_rounded, JanTone.teal),
                    _kpiTile('Escalated', perf.escalatedComplaints.toString(), Icons.priority_high_rounded, JanTone.light),
                    _kpiTile(
                      'Avg Resolution',
                      perf.avgResolutionHours != null ? '${perf.avgResolutionHours!.toStringAsFixed(1)}h' : 'N/A',
                      Icons.timer_outlined,
                      JanTone.blue,
                    ),
                  ],
                ),
                // ---- SLA compliance ----
                const JanSectionHeader(title: 'SLA Compliance'),
                _slaComplianceBar(perf.slaCompliancePercent),
              ],
            );
            final workload = Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ---- Officer workload table ----
                JanSectionHeader(
                  title: 'Officer Workload',
                  trailing: OutlinedButton.icon(
                    onPressed: _exporting ? null : _exportReport,
                    icon: _exporting
                        ? const SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.file_download_outlined, size: 18),
                    label: const Text('Export Report'),
                  ),
                ),
                if (perf.officerWorkloads.isEmpty)
                  const JanCard(child: Text('No officers assigned to this department yet.')),
                ...perf.officerWorkloads.map(_officerWorkloadRow),
              ],
            );
            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
              children: [
                Text(perf.departmentName,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(color: JanColors.navy)),
                if (perf.generatedAt != null)
                  Text(
                    'As of ${DateFormat('dd MMM yyyy, hh:mm a').format(perf.generatedAt!)}',
                    style: const TextStyle(fontSize: 12.5, color: JanColors.muted),
                  ),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: kpis),
                      const SizedBox(width: JanSpace.xl),
                      Expanded(child: workload),
                    ],
                  )
                else ...[
                  kpis,
                  workload,
                ],
              ],
            );
          });
        },
      ),
    );
  }

  Widget _kpiTile(String label, String value, IconData icon, JanTone tone) {
    return JanStatCard(label: label, value: value, icon: icon, tone: tone);
  }

  Widget _slaComplianceBar(double percent) {
    final clamped = percent.clamp(0, 100).toDouble();
    final (Color color, String rating) = clamped >= 90
        ? (JanColors.teal, 'Good')
        : (clamped >= 70 ? (JanColors.amberDark, 'Needs attention') : (JanColors.error, 'Critical'));
    return JanCard(
      child: Semantics(
        label: 'SLA compliance ${clamped.toStringAsFixed(1)} percent, $rating',
        excludeSemantics: true,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text('${clamped.toStringAsFixed(1)}%',
                    style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800, color: color)),
                const SizedBox(width: JanSpace.xs),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(10)),
                  child: Text(rating, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: color)),
                ),
              ],
            ),
            const SizedBox(height: JanSpace.xs),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: clamped / 100,
                minHeight: 10,
                backgroundColor: color.withValues(alpha: 0.12),
                valueColor: AlwaysStoppedAnimation(color),
              ),
            ),
            const SizedBox(height: 6),
            Text('${clamped.toStringAsFixed(1)}% compliant (never escalated)',
                style: const TextStyle(color: JanColors.slate)),
          ],
        ),
      ),
    );
  }

  Widget _officerWorkloadRow(OfficerWorkload w) {
    final initials = w.officerName.trim().isEmpty ? '?' : w.officerName.trim()[0].toUpperCase();
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.sm),
      child: JanCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 18,
                  backgroundColor: JanColors.primaryLight,
                  child: Text(initials, style: const TextStyle(color: JanColors.navy, fontWeight: FontWeight.w800)),
                ),
                const SizedBox(width: JanSpace.sm),
                Expanded(
                  child: Text(w.officerName,
                      style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                ),
              ],
            ),
            const SizedBox(height: JanSpace.xs),
            Wrap(
              spacing: JanSpace.xs,
              runSpacing: JanSpace.xs,
              children: [
                _statChip('Open', w.assignedOpenCount.toString()),
                _statChip('Resolved', w.resolvedCount.toString()),
                _statChip(
                  'Avg Resolution',
                  w.avgResolutionHours != null ? '${w.avgResolutionHours!.toStringAsFixed(1)}h' : 'N/A',
                ),
                _statChip('SLA Breaches', w.slaBreachCount.toString(),
                    color: w.slaBreachCount > 0 ? JanColors.error : null),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _statChip(String label, String value, {Color? color}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color != null ? JanColors.errorLight : JanColors.surfaceAlt,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: JanColors.divider),
      ),
      child: Text(
        '$label: $value',
        style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: color ?? JanColors.slate),
      ),
    );
  }
}
