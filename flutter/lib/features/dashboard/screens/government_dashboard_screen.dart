import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_stat_card.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
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
///
/// UI redesign: reference dashboard styling - coloured KPI cards, SLA card
/// with a written rating, bar-list cards for trends and ward density, and
/// a two-column layout on wide screens. Same data, same export.
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
            return const JanLoadingView(message: 'Loading the dashboard...');
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return JanErrorState.fromError(
              snapshot.error,
              fallback: 'Could not load the dashboard.',
              onRetry: _refresh,
            );
          }
          final data = snapshot.data!;
          final kpis = data.overview.kpis;
          return LayoutBuilder(builder: (context, constraints) {
            final wide = constraints.maxWidth >= 900;
            final overview = Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ---- KPI tiles ----
                const JanSectionHeader(title: 'Key Indicators'),
                JanStatGrid(
                  maxColumns: 5,
                  children: [
                    _kpiTile('Total', kpis.totalComplaints.toString(), Icons.apartment_rounded, JanTone.navy),
                    _kpiTile('Open', kpis.openComplaints.toString(), Icons.pending_actions_rounded, JanTone.amber),
                    _kpiTile('Resolved', kpis.resolvedComplaints.toString(), Icons.task_alt_rounded, JanTone.teal),
                    _kpiTile('Escalated', kpis.escalatedComplaints.toString(), Icons.priority_high_rounded, JanTone.light),
                    _kpiTile(
                      'Avg Resolution',
                      kpis.avgResolutionHours != null ? '${kpis.avgResolutionHours!.toStringAsFixed(1)}h' : 'N/A',
                      Icons.timer_outlined,
                      JanTone.blue,
                    ),
                  ],
                ),
                const JanSectionHeader(title: 'SLA Compliance'),
                _slaComplianceBar(kpis.slaCompliancePercent),
              ],
            );
            final trends = Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ---- Category trend (see class Javadoc-equivalent for the "no chart lib" note) ----
                const JanSectionHeader(title: 'Category Trend (last 90 days)'),
                JanCard(child: _categoryTrendBars(data.overview.categoryTrend)),
                const JanSectionHeader(title: 'Next 7 Days Outlook'),
                JanCard(child: _forecastList(data.overview.categoryForecast)),
              ],
            );
            final heatmap = Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ---- Ward heatmap ----
                const JanSectionHeader(title: 'Complaint Density by Ward'),
                JanCard(
                  child: data.overview.heatmap.isEmpty
                      ? const Text('No located complaints in the last 90 days.')
                      : Column(children: _heatmapBars(data.overview.heatmap)),
                ),
              ],
            );
            final jurisdiction = widget.showJurisdictionSections
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const JanSectionHeader(title: 'Department Comparison'),
                      ...?data.comparison?.map(_departmentComparisonRow),
                      const JanSectionHeader(title: 'Admin Summary'),
                      if (data.adminSummary != null) _adminSummarySection(data.adminSummary!),
                    ],
                  )
                : null;

            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            widget.showJurisdictionSections ? 'Jurisdiction Overview' : 'Department Overview',
                            style: Theme.of(context).textTheme.titleLarge?.copyWith(color: JanColors.navy),
                          ),
                          Text(
                            'Data as of ${DateFormat('dd MMM yyyy, hh:mm a').format(data.overview.dataAsOf)}',
                            style: const TextStyle(fontSize: 12.5, color: JanColors.muted),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: JanSpace.xs),
                    OutlinedButton.icon(
                      onPressed: _exporting ? null : _exportReport,
                      icon: _exporting
                          ? const SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.file_download_outlined, size: 18),
                      label: const Text('Export'),
                    ),
                  ],
                ),
                overview,
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: trends),
                      const SizedBox(width: JanSpace.xl),
                      Expanded(child: heatmap),
                    ],
                  )
                else ...[
                  trends,
                  heatmap,
                ],
                if (jurisdiction != null) jurisdiction,
              ],
            );
          });
        },
      ),
    );
  }

  Widget _kpiTile(String label, String value, IconData icon, JanTone tone, {String? caption}) {
    return JanStatCard(label: label, value: value, icon: icon, tone: tone, caption: caption);
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

  /// Remaining-gaps item 10: SRS 15.14 trend predictions (refreshed with the
  /// nightly analytics snapshot). Shows the method's own uncertainty range and
  /// says plainly when a category has too little history to forecast.
  Widget _forecastList(List<CategoryForecast> forecasts) {
    if (forecasts.isEmpty) {
      return const Text('No outlook yet - complaint history is still building up.');
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: forecasts.map((f) {
        final name = f.category.replaceAll('_', ' ');
        final text = f.hasForecast
            ? '$name: about ${f.forecastNext7Days!.toStringAsFixed(0)} '
                '(likely ${f.lower80!.toStringAsFixed(0)}-${f.upper80!.toStringAsFixed(0)}), '
                'last week ${f.lastWeekCount}'
            : '$name: not enough history yet (${f.weeksOfHistory} week(s))';
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                f.hasForecast ? Icons.trending_up_rounded : Icons.hourglass_empty_rounded,
                size: 18,
                color: f.hasForecast ? JanColors.primary : JanColors.muted,
              ),
              const SizedBox(width: JanSpace.xs),
              Expanded(child: Text(text, style: const TextStyle(color: JanColors.slate, height: 1.35))),
            ],
          ),
        );
      }).toList(),
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
      children: entries.map((e) => _barRow(_formatCategory(e.key), e.value, maxValue, JanColors.primary)).toList(),
    );
  }

  List<Widget> _heatmapBars(List<WardHeatmapPoint> heatmap) {
    if (heatmap.isEmpty) return [];
    final maxValue = heatmap.map((w) => w.complaintCount).reduce((a, b) => a > b ? a : b);
    return heatmap
        .map((w) => _barRow('${w.wardName} (${w.openComplaintCount} open)', w.complaintCount, maxValue, JanColors.teal))
        .toList();
  }

  Widget _barRow(String label, int value, int maxValue, Color color) {
    final fraction = maxValue == 0 ? 0.0 : value / maxValue;
    return Semantics(
      label: '$label: $value',
      excludeSemantics: true,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(
          children: [
            SizedBox(
              width: 140,
              child: Text(label,
                  style: const TextStyle(fontSize: 12.5, color: JanColors.slate), overflow: TextOverflow.ellipsis),
            ),
            Expanded(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(6),
                child: LinearProgressIndicator(
                  value: fraction,
                  minHeight: 14,
                  backgroundColor: color.withValues(alpha: 0.10),
                  valueColor: AlwaysStoppedAnimation(color),
                ),
              ),
            ),
            const SizedBox(width: 8),
            SizedBox(
              width: 40,
              child: Text(value.toString(),
                  textAlign: TextAlign.right,
                  style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.navy)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _departmentComparisonRow(DepartmentComparison d) {
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.sm),
      child: JanCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.account_balance_outlined, color: JanColors.primary, size: 20),
                const SizedBox(width: JanSpace.xs),
                Expanded(
                  child: Text(d.departmentName,
                      style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                ),
              ],
            ),
            const SizedBox(height: JanSpace.xs),
            Wrap(
              spacing: JanSpace.xs,
              runSpacing: JanSpace.xs,
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
    final failureHigh = s.notificationFailureRatePercent > 5;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        JanStatGrid(
          maxColumns: 3,
          children: [
            _kpiTile('Active Users', s.activeUserCount.toString(), Icons.person_outline_rounded, JanTone.blue),
            _kpiTile('Total Users', s.totalUserCount.toString(), Icons.groups_outlined, JanTone.navy),
            _kpiTile(
              'AI Auto Rate',
              s.aiAutoProcessingRatePercent != null ? '${s.aiAutoProcessingRatePercent!.toStringAsFixed(1)}%' : 'N/A',
              Icons.smart_toy_outlined,
              JanTone.teal,
            ),
            _kpiTile('Duplicate Rate', '${s.duplicateMergeRatePercent.toStringAsFixed(1)}%', Icons.copy_all_rounded,
                JanTone.slate),
            _kpiTile('Config Changes (30d)', s.configurationChangeCountLast30Days.toString(), Icons.tune_rounded,
                JanTone.amber),
            _kpiTile(
              'Notif. Failure Rate (30d)',
              '${s.notificationFailureRatePercent.toStringAsFixed(1)}%',
              failureHigh ? Icons.error_outline_rounded : Icons.check_circle_outline_rounded,
              JanTone.light,
              caption: failureHigh ? 'Above 5% - check delivery' : 'Within normal range',
            ),
          ],
        ),
        const JanSectionHeader(title: 'Routing Rule Effectiveness'),
        if (s.routingRuleEffectiveness.isEmpty) const JanCard(child: Text('No active routing rules yet.')),
        ...s.routingRuleEffectiveness.map((r) => Padding(
              padding: const EdgeInsets.only(bottom: JanSpace.sm),
              child: JanCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${_formatCategory(r.category)} → ${r.departmentName}',
                        style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                    const SizedBox(height: JanSpace.xs),
                    Wrap(
                      spacing: JanSpace.xs,
                      runSpacing: JanSpace.xs,
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
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: JanColors.surfaceAlt,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: JanColors.divider),
      ),
      child: Text('$label: $value',
          style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: color ?? JanColors.slate)),
    );
  }

  String _formatCategory(String raw) {
    return raw.split('_').map((w) => w.isEmpty ? w : '${w[0]}${w.substring(1).toLowerCase()}').join(' ');
  }
}
