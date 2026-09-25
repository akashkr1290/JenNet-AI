import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/l10n/app_strings.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_illustrations.dart';
import '../../../core/widgets/jan_stat_card.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../../complaints/models/complaint_status.dart';
import '../../complaints/screens/complaint_detail_screen.dart';
import '../../complaints/widgets/category_visuals.dart';
import '../../complaints/widgets/status_badge.dart';
import '../../users/user_api.dart';
import '../personal_dashboard_api.dart';

/// Gap-backlog Patch 08 citizen dashboard - counts by status plus the five
/// most recent complaints with status, AI classification status, priority
/// and SLA due time, all from GET /api/v1/citizen/dashboard.
///
/// UI redesign: reference "Make your city better" dashboard - greeting,
/// hero call to action, coloured statistic cards and recent complaint
/// cards; statistics and recent complaints sit side by side on web.
class CitizenDashboardScreen extends StatefulWidget {
  /// Switches the citizen shell to the Submit tab (hero call to action).
  final VoidCallback? onReportIssue;

  const CitizenDashboardScreen({super.key, this.onReportIssue});

  @override
  State<CitizenDashboardScreen> createState() => _CitizenDashboardScreenState();
}

class _CitizenDashboardScreenState extends State<CitizenDashboardScreen> {
  late Future<CitizenDashboard> _future;
  String? _firstName;

  @override
  void initState() {
    super.initState();
    _future = PersonalDashboardApi.instance.citizen();
    _loadName();
  }

  // Greeting only - a failure simply leaves the generic greeting.
  Future<void> _loadName() async {
    try {
      final me = await UserApi.instance.me();
      final first = me.fullName.trim().split(RegExp(r'\s+')).first;
      if (mounted && first.isNotEmpty) setState(() => _firstName = first);
    } catch (_) {}
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

  String _greeting() {
    final hour = DateTime.now().hour;
    final key = hour < 12 ? 'greeting_morning' : (hour < 17 ? 'greeting_afternoon' : 'greeting_evening');
    return '${AppStrings.of(key)}, ${_firstName ?? 'Citizen'}';
  }

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<CitizenDashboard>(
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
            final stats = _statistics(d);
            final recent = _recentComplaints(d);
            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
              children: [
                Semantics(
                  header: true,
                  child: Text(
                    _greeting(),
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
                const SizedBox(height: JanSpace.sm),
                _HeroCard(onReportIssue: widget.onReportIssue),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(flex: 3, child: stats),
                      const SizedBox(width: JanSpace.xl),
                      Expanded(flex: 2, child: recent),
                    ],
                  )
                else ...[
                  stats,
                  recent,
                ],
              ],
            );
          });
        },
      ),
    );
  }

  Widget _statistics(CitizenDashboard d) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const JanSectionHeader(title: 'Complaint Statistics'),
        JanStatGrid(
          children: [
            JanStatCard(label: AppStrings.of('dash_total'), value: '${d.total}', icon: Icons.apartment_rounded, tone: JanTone.navy),
            JanStatCard(label: AppStrings.of('dash_pending'), value: '${d.pending}', icon: Icons.schedule_rounded, tone: JanTone.amber),
            JanStatCard(label: AppStrings.of('status_in_progress'), value: '${d.inProgress}', icon: Icons.speed_rounded, tone: JanTone.blue),
            JanStatCard(label: AppStrings.of('status_resolved'), value: '${d.resolved}', icon: Icons.check_circle_outline_rounded, tone: JanTone.teal),
            JanStatCard(label: AppStrings.of('status_closed'), value: '${d.closed}', icon: Icons.lock_outline_rounded, tone: JanTone.slate),
            if (d.rejected > 0)
              JanStatCard(label: AppStrings.of('status_rejected'), value: '${d.rejected}', icon: Icons.block_rounded, tone: JanTone.light),
          ],
        ),
      ],
    );
  }

  Widget _recentComplaints(CitizenDashboard d) {
    final fmt = DateFormat('dd MMM, hh:mm a');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        JanSectionHeader(title: AppStrings.of('dash_recent')),
        if (d.recentComplaints.isEmpty)
          JanCard(
            child: Row(
              children: [
                const JanStateIllustration(icon: Icons.inbox_outlined, color: JanColors.teal, size: 84),
                const SizedBox(width: JanSpace.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(AppStrings.of('dash_none_yet'), style: const TextStyle(fontWeight: FontWeight.w600)),
                      if (widget.onReportIssue != null)
                        TextButton(onPressed: widget.onReportIssue, child: Text(AppStrings.of('action_report_civic_issue'))),
                    ],
                  ),
                ),
              ],
            ),
          ),
        for (final c in d.recentComplaints) ...[
          _RecentComplaintCard(
            complaint: c,
            meta: [
              _aiLabel(c.aiStatus),
              if (c.severity != null) 'Priority: ${c.severity}',
              if (c.slaBreached)
                'SLA breached'
              else if (c.slaDueAt != null)
                'Due ${fmt.format(c.slaDueAt!.toLocal())}',
            ].join(' · '),
            onTap: () async {
              await Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => ComplaintDetailScreen(complaintId: c.complaintId),
              ));
              if (mounted) _refresh();
            },
          ),
          const SizedBox(height: JanSpace.sm),
        ],
      ],
    );
  }
}

class _HeroCard extends StatelessWidget {
  final VoidCallback? onReportIssue;
  const _HeroCard({required this.onReportIssue});

  @override
  Widget build(BuildContext context) {
    final text = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          AppStrings.of('hero_make_city_better'),
          style: const TextStyle(fontSize: 28, height: 1.12, fontWeight: FontWeight.w800, color: JanColors.navy, letterSpacing: -0.6),
        ),
        if (onReportIssue != null) ...[
          const SizedBox(height: JanSpace.md),
          FilledButton.icon(
            onPressed: onReportIssue,
            icon: const Icon(Icons.add_a_photo_outlined),
            label: Text(AppStrings.of('action_report_civic_issue')),
          ),
        ],
      ],
    );
    return JanCard(
      padding: const EdgeInsets.all(JanSpace.lg),
      gradient: const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFE6F6F4), JanColors.white, Color(0xFFE3EEFA)],
      ),
      child: LayoutBuilder(builder: (context, constraints) {
        if (constraints.maxWidth >= 520) {
          return Row(
            children: [
              Expanded(child: text),
              const SizedBox(width: JanSpace.md),
              const Expanded(child: JanCivicHero(height: 150)),
            ],
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const JanCivicHero(height: 110),
            const SizedBox(height: JanSpace.sm),
            text,
          ],
        );
      }),
    );
  }
}

class _RecentComplaintCard extends StatelessWidget {
  final RecentComplaint complaint;
  final String meta;
  final VoidCallback onTap;

  const _RecentComplaintCard({required this.complaint, required this.meta, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final category = CategoryVisual.of(complaint.category);
    final created = complaint.createdAt;
    return JanCard(
      onTap: onTap,
      semanticLabel: '${category.label} complaint ${complaint.referenceNumber}, open details',
      padding: const EdgeInsets.all(JanSpace.sm),
      child: Row(
        children: [
          CategoryTile(category: complaint.category, size: 58),
          const SizedBox(width: JanSpace.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(category.label, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15.5, color: JanColors.navy)),
                Text(complaint.referenceNumber, style: const TextStyle(fontWeight: FontWeight.w600, color: JanColors.slate)),
                const SizedBox(height: 2),
                Text(
                  [if (created != null) DateFormat('dd MMM yyyy').format(created.toLocal()), meta].join(' · '),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12.5, color: JanColors.muted),
                ),
              ],
            ),
          ),
          const SizedBox(width: JanSpace.xs),
          StatusBadge(status: ComplaintStatus.fromJson(complaint.status)),
        ],
      ),
    );
  }
}
