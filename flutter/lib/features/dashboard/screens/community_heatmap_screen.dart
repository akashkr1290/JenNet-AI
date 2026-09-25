import 'package:flutter/material.dart';

import '../../../core/api/api_client.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_illustrations.dart';
import '../../../core/widgets/jan_stat_card.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';

/// Gap-backlog Patch 12: public, aggregated complaint density per ward
/// (GET /community/heatmap - ward name, complaint count, open count; no
/// individual complaints or personal data).
///
/// UI redesign: reference "Community" screen - summary cards, ranked ward
/// hotspot cards with an intensity bar and a text activity label (never
/// colour alone), plus the reference empty / loading / error states. The
/// endpoint carries no coordinates or categories, so no map is drawn.
class CommunityHeatmapScreen extends StatefulWidget {
  final bool embedded;

  /// Switches the citizen shell to the Submit tab (empty-state action).
  final VoidCallback? onSubmitComplaint;

  const CommunityHeatmapScreen({super.key, this.embedded = false, this.onSubmitComplaint});

  @override
  State<CommunityHeatmapScreen> createState() => _CommunityHeatmapScreenState();
}

class _CommunityHeatmapScreenState extends State<CommunityHeatmapScreen> {
  late Future<List<_WardHeatmapPoint>> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<List<_WardHeatmapPoint>> _load() async {
    final json = await ApiClient.instance.get('/community/heatmap') as List<dynamic>;
    return json.map((e) => _WardHeatmapPoint.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> _refresh() async {
    setState(() => _future = _load());
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    final body = RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<List<_WardHeatmapPoint>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanLoadingView(message: 'Fetching community updates...');
          }
          if (snapshot.hasError) {
            return JanErrorState.fromError(
              snapshot.error,
              fallback: 'Could not load the community heatmap.',
              onRetry: _refresh,
            );
          }
          final points = [...(snapshot.data ?? <_WardHeatmapPoint>[])]
            ..sort((a, b) => b.complaintCount.compareTo(a.complaintCount));
          if (points.isEmpty || points.every((p) => p.complaintCount == 0)) {
            return JanEmptyState(
              icon: Icons.volunteer_activism_outlined,
              title: 'Community is looking good!',
              message: 'No civic issues have been reported in your area yet. Submit an issue and inspire others.',
              actionLabel: widget.onSubmitComplaint != null ? 'Submit an Issue' : null,
              onAction: widget.onSubmitComplaint,
            );
          }
          final maxCount = points.map((p) => p.complaintCount).reduce((a, b) => a > b ? a : b);
          final total = points.fold<int>(0, (sum, p) => sum + p.complaintCount);
          final open = points.fold<int>(0, (sum, p) => sum + p.openComplaintCount);
          final active = points.where((p) => p.complaintCount > 0).length;
          return LayoutBuilder(builder: (context, constraints) {
            final columns = constraints.maxWidth >= 860 ? 2 : 1;
            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
              children: [
                const _InsightBanner(),
                const JanSectionHeader(title: 'City Overview'),
                JanStatGrid(
                  maxColumns: 3,
                  children: [
                    JanStatCard(label: 'Total reports', value: '$total', icon: Icons.apartment_rounded, tone: JanTone.navy),
                    JanStatCard(label: 'Open issues', value: '$open', icon: Icons.schedule_rounded, tone: JanTone.amber),
                    JanStatCard(label: 'Wards reporting', value: '$active', icon: Icons.location_city_rounded, tone: JanTone.teal),
                  ],
                ),
                const JanSectionHeader(title: 'Hotspots by Ward'),
                if (columns == 1) ...[
                  for (var i = 0; i < points.length; i++) ...[
                    _WardCard(rank: i + 1, point: points[i], maxCount: maxCount),
                    const SizedBox(height: JanSpace.sm),
                  ],
                ] else
                  Wrap(
                    spacing: JanSpace.md,
                    runSpacing: JanSpace.md,
                    children: [
                      for (var i = 0; i < points.length; i++)
                        SizedBox(
                          width: ((constraints.maxWidth - JanSpace.md * 3) / 2).floorToDouble(),
                          child: _WardCard(rank: i + 1, point: points[i], maxCount: maxCount),
                        ),
                    ],
                  ),
              ],
            );
          });
        },
      ),
    );
    if (widget.embedded) return body;
    return JanPage(title: 'Community Heatmap', body: body);
  }
}

class _InsightBanner extends StatelessWidget {
  const _InsightBanner();

  @override
  Widget build(BuildContext context) {
    return JanCard(
      padding: const EdgeInsets.all(JanSpace.md),
      gradient: const LinearGradient(colors: [Color(0xFFE6F6F4), JanColors.white, Color(0xFFE3EEFA)]),
      child: Row(
        children: [
          const SizedBox(width: 120, child: JanCivicHero(height: 96)),
          const SizedBox(width: JanSpace.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Community Insights', style: Theme.of(context).textTheme.titleMedium?.copyWith(color: JanColors.navy)),
                const SizedBox(height: 4),
                const Text(
                  'Aggregated reports by ward - no personal details are ever shown.',
                  style: TextStyle(color: JanColors.muted, height: 1.4),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _WardCard extends StatelessWidget {
  final int rank;
  final _WardHeatmapPoint point;
  final int maxCount;

  const _WardCard({required this.rank, required this.point, required this.maxCount});

  @override
  Widget build(BuildContext context) {
    final intensity = maxCount == 0 ? 0.0 : point.complaintCount / maxCount;
    final (String level, Color color, Color tint) = intensity >= 0.66
        ? ('High activity', JanColors.error, JanColors.errorLight)
        : intensity >= 0.33
            ? ('Moderate activity', JanColors.amberDark, JanColors.amberLight)
            : ('Low activity', JanColors.teal, JanColors.tealLight);
    return Semantics(
      label: '${point.wardName}: ${point.complaintCount} reports, ${point.openComplaintCount} open, $level',
      excludeSemantics: true,
      child: JanCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: JanColors.navy.withValues(alpha: 0.08), shape: BoxShape.circle),
                  child: Text('$rank', style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                ),
                const SizedBox(width: JanSpace.sm),
                Expanded(
                  child: Text(
                    point.wardName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 15.5, fontWeight: FontWeight.w800, color: JanColors.navy),
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(color: tint, borderRadius: BorderRadius.circular(10)),
                  child: Text(level, style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w700)),
                ),
              ],
            ),
            const SizedBox(height: JanSpace.sm),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: intensity,
                minHeight: 10,
                backgroundColor: JanColors.divider,
                color: color,
              ),
            ),
            const SizedBox(height: JanSpace.xs),
            Text(
              '${point.complaintCount} report(s) · ${point.openComplaintCount} open',
              style: const TextStyle(color: JanColors.slate, fontWeight: FontWeight.w600),
            ),
          ],
        ),
      ),
    );
  }
}

class _WardHeatmapPoint {
  final String wardName;
  final int complaintCount;
  final int openComplaintCount;

  _WardHeatmapPoint({required this.wardName, required this.complaintCount, required this.openComplaintCount});

  factory _WardHeatmapPoint.fromJson(Map<String, dynamic> json) {
    return _WardHeatmapPoint(
      wardName: json['wardName'] as String? ?? 'Unknown ward',
      complaintCount: (json['complaintCount'] as num?)?.toInt() ?? 0,
      openComplaintCount: (json['openComplaintCount'] as num?)?.toInt() ?? 0,
    );
  }
}
