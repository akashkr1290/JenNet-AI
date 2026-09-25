import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import '../widgets/category_visuals.dart';
import '../widgets/status_badge.dart';
import 'complaint_detail_screen.dart';

/// The citizen's own complaints (GET /complaints).
///
/// UI redesign: reference "My Complaints" - search box and status filter
/// over the already-loaded list (no extra API calls), status-tinted
/// complaint cards, skeleton loading, empty and no-results states, and a
/// two-column grid on wide screens.
class ComplaintListScreen extends StatefulWidget {
  /// Switches the citizen shell to the Submit tab (empty-state action).
  final VoidCallback? onSubmitComplaint;

  const ComplaintListScreen({super.key, this.onSubmitComplaint});

  @override
  State<ComplaintListScreen> createState() => _ComplaintListScreenState();
}

enum _StatusFilter { all, pending, inProgress, resolved, rejected }

extension on _StatusFilter {
  String get label => switch (this) {
        _StatusFilter.all => 'All',
        _StatusFilter.pending => 'Pending',
        _StatusFilter.inProgress => 'In Progress',
        _StatusFilter.resolved => 'Resolved',
        _StatusFilter.rejected => 'Rejected',
      };

  bool matches(ComplaintStatus s) => switch (this) {
        _StatusFilter.all => true,
        _StatusFilter.pending => s == ComplaintStatus.submitted ||
            s == ComplaintStatus.aiProcessing ||
            s == ComplaintStatus.verified ||
            s == ComplaintStatus.assigned,
        _StatusFilter.inProgress =>
          s == ComplaintStatus.inProgress || s == ComplaintStatus.reopened || s == ComplaintStatus.escalated,
        _StatusFilter.resolved => s == ComplaintStatus.resolved || s == ComplaintStatus.closed,
        _StatusFilter.rejected => s == ComplaintStatus.rejected || s == ComplaintStatus.duplicate,
      };
}

class _ComplaintListScreenState extends State<ComplaintListScreen> {
  late Future<List<ComplaintSummary>> _future;
  final _searchController = TextEditingController();
  _StatusFilter _filter = _StatusFilter.all;

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.list();
    _searchController.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.list());
    await _future;
  }

  void _clearSearch() {
    _searchController.clear();
    setState(() => _filter = _StatusFilter.all);
  }

  List<ComplaintSummary> _apply(List<ComplaintSummary> all) {
    final q = _searchController.text.trim().toLowerCase();
    return all.where((c) {
      if (!_filter.matches(c.status)) return false;
      if (q.isEmpty) return true;
      final category = CategoryVisual.of(c.category).label.toLowerCase();
      return c.referenceNumber.toLowerCase().contains(q) ||
          category.contains(q) ||
          (c.description ?? '').toLowerCase().contains(q);
    }).toList();
  }

  void _open(ComplaintSummary c) {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => ComplaintDetailScreen(complaintId: c.complaintId)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, 0),
          child: TextField(
            controller: _searchController,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              hintText: 'Search by ID, category or description',
              prefixIcon: const Icon(Icons.search_rounded),
              suffixIcon: _searchController.text.isEmpty
                  ? null
                  : IconButton(
                      tooltip: 'Clear search',
                      icon: const Icon(Icons.close_rounded),
                      onPressed: _searchController.clear,
                    ),
            ),
          ),
        ),
        SizedBox(
          height: 56,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: JanSpace.md, vertical: JanSpace.xs),
            children: [
              for (final f in _StatusFilter.values)
                Padding(
                  padding: const EdgeInsets.only(right: JanSpace.xs),
                  child: ChoiceChip(
                    label: Text(f.label),
                    selected: _filter == f,
                    onSelected: (_) => setState(() => _filter = f),
                  ),
                ),
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: _refresh,
            child: FutureBuilder<List<ComplaintSummary>>(
              future: _future,
              builder: (context, snapshot) {
                if (snapshot.connectionState == ConnectionState.waiting) {
                  return const JanSkeletonList(semanticLabel: 'Loading your complaints');
                }
                if (snapshot.hasError) {
                  return JanErrorState.fromError(
                    snapshot.error,
                    fallback: 'Could not load your complaints.',
                    onRetry: _refresh,
                  );
                }
                final all = snapshot.data ?? [];
                if (all.isEmpty) {
                  return JanEmptyState(
                    icon: Icons.inbox_outlined,
                    title: 'No complaints yet',
                    message: "Submit an issue and we'll track it here.",
                    actionLabel: widget.onSubmitComplaint != null ? 'Submit Complaint' : null,
                    onAction: widget.onSubmitComplaint,
                  );
                }
                final complaints = _apply(all);
                if (complaints.isEmpty) {
                  return JanEmptyState(
                    icon: Icons.search_off_rounded,
                    title: 'No matching complaints',
                    message: 'Try a different reference number or category, or clear the filters.',
                    actionLabel: 'Clear Search',
                    onAction: _clearSearch,
                    color: JanColors.primary,
                  );
                }
                return LayoutBuilder(builder: (context, constraints) {
                  // Fixed-height grid only when text is not enlarged, so large
                  // accessibility text sizes never clip inside a card.
                  final textScale = MediaQuery.textScalerOf(context).scale(16) / 16;
                  if (constraints.maxWidth >= 860 && textScale <= 1.3) {
                    return GridView.builder(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
                      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                        maxCrossAxisExtent: 560,
                        mainAxisExtent: 176,
                        crossAxisSpacing: JanSpace.md,
                        mainAxisSpacing: JanSpace.md,
                      ),
                      itemCount: complaints.length,
                      itemBuilder: (context, i) => ComplaintSummaryCard(
                        complaint: complaints[i],
                        onTap: () => _open(complaints[i]),
                      ),
                    );
                  }
                  return ListView.separated(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
                    itemCount: complaints.length,
                    separatorBuilder: (_, __) => const SizedBox(height: JanSpace.sm),
                    itemBuilder: (context, i) => ComplaintSummaryCard(
                      complaint: complaints[i],
                      onTap: () => _open(complaints[i]),
                    ),
                  );
                });
              },
            ),
          ),
        ),
      ],
    );
  }
}

/// Reference complaint card: category tile, reference ID chip, title, date
/// and status - tinted with the status colour. Reused by staff queues.
class ComplaintSummaryCard extends StatelessWidget {
  final ComplaintSummary complaint;
  final VoidCallback onTap;
  final Widget? trailing;

  const ComplaintSummaryCard({super.key, required this.complaint, required this.onTap, this.trailing});

  @override
  Widget build(BuildContext context) {
    final category = CategoryVisual.of(complaint.category);
    final status = StatusVisual.of(complaint.status);
    final title = (complaint.description?.isNotEmpty ?? false) ? complaint.description! : category.label;
    final created = complaint.createdAt;
    return JanCard(
      onTap: onTap,
      semanticLabel: '${category.label} complaint ${complaint.referenceNumber}, ${complaint.status.label}. Open details',
      padding: const EdgeInsets.all(JanSpace.sm),
      gradient: LinearGradient(
        begin: Alignment.centerLeft,
        end: Alignment.centerRight,
        colors: [status.background.withValues(alpha: 0.55), JanColors.white],
        stops: const [0.0, 0.6],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              CategoryTile(category: complaint.category, size: 72),
              const SizedBox(width: JanSpace.sm),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: JanColors.white,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: JanColors.divider),
                      ),
                      child: Text(
                        'ID: ${complaint.referenceNumber}',
                        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: JanColors.slate),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 15.5, fontWeight: FontWeight.w700, color: JanColors.navy, height: 1.3),
                    ),
                    Text(category.label, style: const TextStyle(fontSize: 12.5, color: JanColors.muted)),
                  ],
                ),
              ),
            ],
          ),
          const Divider(height: 18),
          Row(
            children: [
              const Icon(Icons.event_outlined, size: 16, color: JanColors.muted),
              const SizedBox(width: 4),
              Text(
                created != null ? DateFormat('dd MMM yyyy').format(created.toLocal()) : '-',
                style: const TextStyle(fontSize: 12.5, color: JanColors.slate, fontWeight: FontWeight.w600),
              ),
              if (complaint.corroborationCount > 0) ...[
                const SizedBox(width: JanSpace.sm),
                const Icon(Icons.groups_outlined, size: 16, color: JanColors.muted),
                const SizedBox(width: 4),
                Text('+${complaint.corroborationCount}', style: const TextStyle(fontSize: 12.5, color: JanColors.slate)),
              ],
              const Spacer(),
              if (trailing != null) ...[trailing!, const SizedBox(width: JanSpace.xs)],
              StatusBadge(status: complaint.status),
            ],
          ),
        ],
      ),
    );
  }
}
