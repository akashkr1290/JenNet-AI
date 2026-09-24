import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../dashboard_api.dart';
import '../models/dashboard_models.dart';

/// Phase 16 (Government Dashboard Module, SRS 15.10 / 16.3 "Government
/// Dashboard (Overview)" / 24.3 "Admin Dashboard" / 24.4 "Government
/// Dashboard (Department Head / Jurisdiction level)"). One reusable
/// screen for both roles this phase serves:
///
/// - DEPARTMENT_HEAD: `departmentId` = their own department (fetched by
///   the caller, same convention as DepartmentPerformanceScreen, Phase
///   13), `showJurisdictionSections = false` - no department comparison,
///   no Admin summary (those are jurisdiction-wide/Admin-only concepts).
/// - ADMIN/SUPER_ADMIN: `departmentId = null` (jurisdiction-wide),
///   `showJurisdictionSections = true` - additionally fetches and
///   renders the department comparison chart and the full Admin summary
///   (SRS 24.3).
///
/// CHARTS: no charting package exists in this project's pubspec and this
/// environment has no outbound network to add one (same "no new
/// dependency" constraint Phase 13 already documented for its own
/// Export-Report clipboard-copy workaround) - the category-trend and
/// ward-heatmap "charts" SRS 15.10/24.4 ask for are rendered as simple,
/// hand-built horizontal bar lists (a `Container` sized proportionally
/// to its value) rather than a real plotting widget. Documented as a
/// Phase 16 KNOWN LIMITATION, not a silently simplified requirement.
///
/// DRILL-DOWN (SRS 15.10 Actions: "drill down into any KPI to underlying
/// complaint list"): out of scope for this screen - see
/// GovernmentDashboardController's Javadoc for why (the existing Officer
/// Queue screen, filtered, already IS that underlying list; wiring a tap
/// on a specific KPI tile to navigate there with a pre-applied filter is
/// left for a future phase's polish pass, not a Phase 16 requirement
/// this screen silently drops - see PROJECT_INTEGRATION.md Section 6).
class GovernmentDashboardScreen extends StatefulWidget {
  final int? departmentId;
  final bool showJurisdictionSections;

  const GovernmentDashboardScreen({
    super.key,
    this.departmentId,
    this.showJurisdictionSections = false,
  });

  @override
  State<GovernmentDashboardScreen> createState() => _GovernmentDashboardScreenState();
}

class _DashboardData {
  final GovernmentDashboardData overview;
  final List<DepartmentComparison>? comparison;
  final AdminDashboardSummary? adminSummary;

  _DashboardData({required this.overview, this.comparison, this.adminSummary});
}

class _GovernmentDashboardScreenState extends State<GovernmentDashboardScreen> {
  late Future<_DashboardData> _future;
  bool _exporting = false;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<_DashboardData> _load() async {
    final overviewFuture = DashboardApi.instance.overview(departmentId: widget.departmentId);
    if (!widget.showJurisdictionSections) {
      final overview = await overviewFuture;
      return _DashboardData(overview: overview);
    }
    final results = await Future.wait([
      overviewFuture,
      DashboardApi.instance.departmentComparison(),
      DashboardApi.instance.adminSummary(),
    ]);
    return _DashboardData(
      overview: results[0] as GovernmentDashboardData,
      comparison: results[1] as List<DepartmentComparison>,
      adminSummary: results[2] as AdminDashboardSummary,
    );
  }

  Future<void> _refresh() async {
    setState(() => _future = _load());
    await _future;
  }

  void _showError(Object e) {
    final message = e is ApiException ? e.message : 'Something went wrong.';
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _exportReport() async {
    setState(() => _exporting = true);
    try {
      final csv = await DashboardApi.instance.exportCsv(departmentId: widget.departmentId);
      await Clipboard.setData(ClipboardData(text: csv));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Dashboard snapshot copied to clipboard as CSV.')),
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
      child: FutureBuilder<_DashboardData>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            final message = snapshot.error is ApiException
                ? (snapshot.error as ApiException).message
                : 'Could not load the dashboard.';
            return ListView(
              children: [
                const SizedBox(height: 120),
                Center(child: Text(message, textAlign: TextAlign.center)),
                const SizedBox(height: 12),
                Center(child: OutlinedButton(onPressed: _refresh, child: const Text('Retry'))),
              ],
            );
          }
          final data = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    widget.showJurisdictionSections ? 'Jurisdiction Overview' : 'Department Overview',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  OutlinedButton.icon(
                    onPressed: _exporting ? null : _exportReport,
                    icon: _exporting
                        ? const SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.file_download_outlined, size: 18),
                    label: const Text('Export'),
                  ),
                ],
              ),
              Text(
                'Data as of ${DateFormat('dd MMM yyyy, hh:mm a').format(data.overview.dataAsOf)}',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
              ),
              const SizedBox(height: 16),

              // ---- KPI tiles ----
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  _kpiTile('Total', data.overview.kpis.totalComplaints.toString(), Colors.indigo),
                  _kpiTile('Open', data.overview.kpis.openComplaints.toString(), Colors.blueGrey),
                  _kpiTile('Resolved', data.overview.kpis.resolvedComplaints.toString(), Colors.green),
                  _kpiTile('Escalated', data.overview.kpis.escalatedComplaints.toString(), Colors.deepOrange),
                  _kpiTile(
                    'Avg Resolution',
                    data.overview.kpis.avgResolutionHours != null
                        ? '${data.overview.kpis.avgResolutionHours!.toStringAsFixed(1)}h'
                        : 'N/A',
                    Colors.teal,
                  ),
                ],
              ),
              const SizedBox(height: 20),

              Text('SLA Compliance', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              _slaComplianceBar(data.overview.kpis.slaCompliancePercent),
              const SizedBox(height: 20),

              // ---- Category trend (see class Javadoc-equivalent for the "no chart lib" note) ----
              Text('Category Trend (last 90 days)', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              _categoryTrendBars(data.overview.categoryTrend),
              const SizedBox(height: 20),

              // ---- Ward heatmap ----
              Text('Complaint Density by Ward', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              if (data.overview.heatmap.isEmpty) const Text('No located complaints in the last 90 days.'),
              ..._heatmapBars(data.overview.heatmap),

              if (widget.showJurisdictionSections) ...[
                const SizedBox(height: 24),
                Text('Department Comparison', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                ...?data.comparison?.map(_departmentComparisonRow),
                const SizedBox(height: 24),
                Text('Admin Summary', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                if (data.adminSummary != null) _adminSummarySection(data.adminSummary!),
              ],
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

  Widget _categoryTrendBars(List<CategoryTrendPoint> points) {
    if (points.isEmpty) {
      return const Text('No complaints submitted in the last 90 days.');
    }
    final totals = <String, int>{};
    for (final p in points) {
      totals.update(p.category, (v) => v + p.count, ifAbsent: () => p.count);
    }
    final maxValue = totals.values.reduce((a, b) => a > b ? a : b);
    final entries = totals.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: entries.map((e) => _barRow(_formatCategory(e.key), e.value, maxValue, Colors.indigo)).toList(),
    );
  }

  List<Widget> _heatmapBars(List<WardHeatmapPoint> heatmap) {
    if (heatmap.isEmpty) return [];
    final maxValue = heatmap.map((w) => w.complaintCount).reduce((a, b) => a > b ? a : b);
    return heatmap
        .map((w) => _barRow('${w.wardName} (${w.openComplaintCount} open)', w.complaintCount, maxValue, Colors.deepPurple))
        .toList();
  }

  Widget _barRow(String label, int value, int maxValue, Color color) {
    final fraction = maxValue == 0 ? 0.0 : value / maxValue;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(width: 140, child: Text(label, style: const TextStyle(fontSize: 12), overflow: TextOverflow.ellipsis)),
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: fraction,
                minHeight: 14,
                backgroundColor: color.withOpacity(0.08),
                valueColor: AlwaysStoppedAnimation(color),
              ),
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(width: 36, child: Text(value.toString(), textAlign: TextAlign.right)),
        ],
      ),
    );
  }

  Widget _departmentComparisonRow(DepartmentComparison d) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(d.departmentName, style: const TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            Wrap(
              spacing: 16,
              runSpacing: 4,
              children: [
                _statChip('Total', d.totalComplaints.toString()),
                _statChip('Open', d.openComplaints.toString()),
                _statChip('Resolved', d.resolvedComplaints.toString()),
                _statChip('SLA', '${d.slaCompliancePercent.toStringAsFixed(1)}%'),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _adminSummarySection(AdminDashboardSummary s) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            _kpiTile('Active Users', s.activeUserCount.toString(), Colors.blue),
            _kpiTile('Total Users', s.totalUserCount.toString(), Colors.blueGrey),
            _kpiTile(
              'AI Auto Rate',
              s.aiAutoProcessingRatePercent != null ? '${s.aiAutoProcessingRatePercent!.toStringAsFixed(1)}%' : 'N/A',
              Colors.purple,
            ),
            _kpiTile('Duplicate Rate', '${s.duplicateMergeRatePercent.toStringAsFixed(1)}%', Colors.brown),
            _kpiTile('Config Changes (30d)', s.configurationChangeCountLast30Days.toString(), Colors.orange),
            _kpiTile(
              'Notif. Failure Rate (30d)',
              '${s.notificationFailureRatePercent.toStringAsFixed(1)}%',
              s.notificationFailureRatePercent > 5 ? Colors.red : Colors.green,
            ),
          ],
        ),
        const SizedBox(height: 16),
        Text('Routing Rule Effectiveness', style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 8),
        if (s.routingRuleEffectiveness.isEmpty) const Text('No active routing rules yet.'),
        ...s.routingRuleEffectiveness.map((r) => Card(
              margin: const EdgeInsets.only(bottom: 8),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${_formatCategory(r.category)} \u2192 ${r.departmentName}',
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 16,
                      children: [
                        _statChip('Complaints', r.complaintCount.toString()),
                        _statChip('SLA Compliance', '${r.slaCompliancePercent.toStringAsFixed(1)}%'),
                      ],
                    ),
                  ],
                ),
              ),
            )),
      ],
    );
  }

  Widget _statChip(String label, String value, {Color? color}) {
    return Text('$label: $value', style: TextStyle(fontSize: 12, color: color ?? Colors.grey.shade700));
  }

  String _formatCategory(String raw) {
    return raw.split('_').map((w) => w.isEmpty ? w : '${w[0]}${w.substring(1).toLowerCase()}').join(' ');
  }
}
