import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
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
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            final message = snapshot.error is ApiException
                ? (snapshot.error as ApiException).message
                : 'Could not load department performance.';
            return ListView(
              children: [
                const SizedBox(height: 120),
                Center(child: Text(message, textAlign: TextAlign.center)),
                const SizedBox(height: 12),
                Center(child: OutlinedButton(onPressed: _refresh, child: const Text('Retry'))),
              ],
            );
          }
          final perf = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(perf.departmentName, style: Theme.of(context).textTheme.titleLarge),
              if (perf.generatedAt != null)
                Text(
                  'As of ${DateFormat('dd MMM yyyy, hh:mm a').format(perf.generatedAt!)}',
                  style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                ),
              const SizedBox(height: 16),

              // ---- KPI tiles ----
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  _kpiTile('Total', perf.totalComplaints.toString(), Colors.indigo),
                  _kpiTile('Open', perf.openComplaints.toString(), Colors.blueGrey),
                  _kpiTile('Resolved', perf.resolvedComplaints.toString(), Colors.green),
                  _kpiTile('Escalated', perf.escalatedComplaints.toString(), Colors.deepOrange),
                  _kpiTile(
                    'Avg Resolution',
                    perf.avgResolutionHours != null ? '${perf.avgResolutionHours!.toStringAsFixed(1)}h' : 'N/A',
                    Colors.teal,
                  ),
                ],
              ),
              const SizedBox(height: 20),

              // ---- SLA compliance ----
              Text('SLA Compliance', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              _slaComplianceBar(perf.slaCompliancePercent),
              const SizedBox(height: 20),

              // ---- Officer workload table ----
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Officer Workload', style: Theme.of(context).textTheme.labelLarge),
                  OutlinedButton.icon(
                    onPressed: _exporting ? null : _exportReport,
                    icon: _exporting
                        ? const SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.file_download_outlined, size: 18),
                    label: const Text('Export Report'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              if (perf.officerWorkloads.isEmpty) const Text('No officers assigned to this department yet.'),
              ...perf.officerWorkloads.map(_officerWorkloadRow),
            ],
          );
        },
      ),
    );
  }

  Widget _kpiTile(String label, String value, Color color) {
    return Container(
      width: 130,
      padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.08),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(value, style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: color)),
          const SizedBox(height: 4),
          Text(label, style: const TextStyle(fontSize: 12)),
        ],
      ),
    );
  }

  Widget _slaComplianceBar(double percent) {
    final clamped = percent.clamp(0, 100).toDouble();
    final color = clamped >= 90 ? Colors.green : (clamped >= 70 ? Colors.amber : Colors.red);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(
            value: clamped / 100,
            minHeight: 10,
            backgroundColor: color.withOpacity(0.12),
            valueColor: AlwaysStoppedAnimation(color),
          ),
        ),
        const SizedBox(height: 4),
        Text('${clamped.toStringAsFixed(1)}% compliant (never escalated)'),
      ],
    );
  }

  Widget _officerWorkloadRow(OfficerWorkload w) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(w.officerName, style: const TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            Wrap(
              spacing: 16,
              runSpacing: 4,
              children: [
                _statChip('Open', w.assignedOpenCount.toString()),
                _statChip('Resolved', w.resolvedCount.toString()),
                _statChip(
                  'Avg Resolution',
                  w.avgResolutionHours != null ? '${w.avgResolutionHours!.toStringAsFixed(1)}h' : 'N/A',
                ),
                _statChip('SLA Breaches', w.slaBreachCount.toString(),
                    color: w.slaBreachCount > 0 ? Colors.deepOrange : null),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _statChip(String label, String value, {Color? color}) {
    return Text(
      '$label: $value',
      style: TextStyle(fontSize: 12, color: color ?? Colors.grey.shade700),
    );
  }
}
