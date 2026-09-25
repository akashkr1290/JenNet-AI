import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../period_report.dart';

/// Audit GAP-039 (SRS 15.12 / 21): daily, weekly or custom-range report for a
/// Department Head (own department, enforced server-side) or an Admin (all
/// departments). The CSV is copied to the clipboard - the app has no
/// file-save plugin (same limitation as the Department Performance export);
/// the PDF is available from GET /api/v1/reports/period.pdf and by e-mail.
class PeriodReportScreen extends StatefulWidget {
  const PeriodReportScreen({super.key});

  @override
  State<PeriodReportScreen> createState() => _PeriodReportScreenState();
}

class _PeriodReportScreenState extends State<PeriodReportScreen> {
  String _type = 'WEEKLY';
  DateTime? _from;
  DateTime? _to;
  bool _loading = false;
  PeriodReportSummary? _report;
  String? _error;

  Future<void> _pick(bool from) async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      firstDate: DateTime(now.year - 5),
      lastDate: now,
      initialDate: (from ? _from : _to) ?? now.subtract(const Duration(days: 1)),
    );
    if (picked != null) setState(() => from ? _from = picked : _to = picked);
  }

  Future<void> _run() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final r = await ReportApi.instance.period(_type, from: _from, to: _to);
      setState(() => _report = r);
    } catch (e) {
      setState(() => _error = e is ApiException ? e.message : 'Could not generate the report.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _copyCsv() async {
    try {
      final csv = await ReportApi.instance.periodCsv(_type, from: _from, to: _to);
      await Clipboard.setData(ClipboardData(text: csv));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Report copied to clipboard as CSV.')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e is ApiException ? e.message : 'Could not export the report.')));
      }
    }
  }

  String _d(DateTime? d) => d == null ? 'Choose' : '${d.day}/${d.month}/${d.year}';

  @override
  Widget build(BuildContext context) {
    final r = _report;
    return Scaffold(
      appBar: AppBar(title: const Text('Reports')),
      body: ListView(
        padding: const EdgeInsets.all(JanSpace.md),
        children: [
          JanCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'DAILY', label: Text('Daily')),
                  ButtonSegment(value: 'WEEKLY', label: Text('Weekly')),
                  ButtonSegment(value: 'CUSTOM', label: Text('Custom')),
                ],
                selected: {_type},
                onSelectionChanged: (s) => setState(() => _type = s.first),
              ),
              const SizedBox(height: JanSpace.sm),
              Text(
                switch (_type) {
                  'DAILY' => 'One day (default: yesterday). Pick a day with "To".',
                  'WEEKLY' => 'The 7 days ending on "To" (default: the week ending yesterday).',
                  _ => 'Any range up to one year.',
                },
                style: const TextStyle(color: JanColors.muted),
              ),
              Wrap(spacing: JanSpace.sm, children: [
                if (_type == 'CUSTOM')
                  OutlinedButton.icon(
                      onPressed: () => _pick(true), icon: const Icon(Icons.event), label: Text('From: ${_d(_from)}')),
                OutlinedButton.icon(
                    onPressed: () => _pick(false), icon: const Icon(Icons.event), label: Text('To: ${_d(_to)}')),
              ]),
              const SizedBox(height: JanSpace.sm),
              FilledButton(onPressed: _loading ? null : _run, child: Text(_loading ? 'Generating...' : 'Generate')),
            ]),
          ),
          if (_error != null) ...[
            const SizedBox(height: JanSpace.sm),
            JanBanner(tone: JanBannerTone.error, message: _error!),
          ],
          if (r != null) ...[
            const SizedBox(height: JanSpace.md),
            if (r.insufficientData)
              const JanBanner(
                tone: JanBannerTone.warning,
                message: 'INSUFFICIENT DATA - too few complaints were received in this period for representative figures.',
              ),
            JanSectionHeader(
              title: '${r.periodStart} to ${r.periodEnd} · ${r.departmentName ?? 'All departments'}',
              trailing: TextButton.icon(onPressed: _copyCsv, icon: const Icon(Icons.copy), label: const Text('CSV')),
            ),
            JanCard(
              child: Column(children: [
                for (final e in {
                  'Received': r.counts['received'],
                  'Verified': r.counts['verified'],
                  'Assigned': r.counts['assigned'],
                  'Resolved': r.counts['resolved'],
                  'Closed': r.counts['closed'],
                  'Rejected': r.counts['rejected'],
                  'Escalated': r.counts['escalated'],
                }.entries)
                  _row(e.key, '${e.value ?? 0}'),
                _row('SLA compliance', r.slaCompliancePercent == null ? '-' : '${r.slaCompliancePercent}%'),
                _row('Average resolution', r.averageResolutionHours == null ? '-' : '${r.averageResolutionHours} h'),
              ]),
            ),
            if (r.byCategory.isNotEmpty) ...[
              const JanSectionHeader(title: 'By category'),
              JanCard(child: Column(children: [for (final e in r.byCategory) _row(e.key.replaceAll('_', ' '), '${e.value}')])),
            ],
            if (r.byWard.isNotEmpty) ...[
              const JanSectionHeader(title: 'Top locations'),
              JanCard(child: Column(children: [for (final e in r.byWard.take(10)) _row(e.key, '${e.value}')])),
            ],
            if (r.snapshotId != null)
              Padding(
                padding: const EdgeInsets.only(top: JanSpace.sm),
                child: Text('Stored as snapshot #${r.snapshotId}', style: const TextStyle(color: JanColors.muted)),
              ),
          ],
        ],
      ),
    );
  }

  Widget _row(String label, String value) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          Expanded(child: Text(label)),
          Text(value, style: const TextStyle(fontWeight: FontWeight.w700)),
        ]),
      );
}
