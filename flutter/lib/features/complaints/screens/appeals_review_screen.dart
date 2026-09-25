import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../complaints_api.dart';
import 'officer_complaint_detail_screen.dart';

/// Gap-backlog Patch 14 (Sep 2026 strict recheck): the "Review" step of the
/// appeal flow. The backend review endpoints existed (ComplaintAppealService)
/// but no staff screen used them, so a submitted appeal could never be
/// decided from the app. Lists PENDING appeals, oldest first.
class AppealsReviewScreen extends StatefulWidget {
  final bool embedded;
  const AppealsReviewScreen({super.key, this.embedded = false});

  @override
  State<AppealsReviewScreen> createState() => _AppealsReviewScreenState();
}

class _AppealsReviewScreenState extends State<AppealsReviewScreen> {
  late Future<List<Map<String, dynamic>>> _future;

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.listPendingAppeals();
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.listPendingAppeals());
    await _future;
  }

  Future<void> _decide(int appealId, String decision) async {
    final noteController = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(decision == 'APPROVED' ? 'Approve appeal' : 'Deny appeal'),
        content: TextField(
          controller: noteController,
          maxLength: 1000,
          maxLines: 3,
          decoration: const InputDecoration(labelText: 'Note to record (optional)'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Confirm')),
        ],
      ),
    );
    final note = noteController.text.trim();
    noteController.dispose();
    if (confirmed != true) return;
    try {
      await ComplaintsApi.instance.reviewAppeal(appealId, decision: decision, note: note);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Appeal $decision.')));
      _refresh();
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final fmt = DateFormat('dd MMM yyyy');
    final body = RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<List<Map<String, dynamic>>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanSkeletonList(semanticLabel: 'Loading appeals');
          }
          if (snapshot.hasError) {
            return JanErrorState.fromError(snapshot.error, fallback: 'Could not load appeals.', onRetry: _refresh);
          }
          final appeals = snapshot.data ?? [];
          if (appeals.isEmpty) {
            return const JanEmptyState(
              icon: Icons.gavel_outlined,
              title: 'No pending appeals',
              message: 'Citizen appeals against rejected complaints will appear here.',
            );
          }
          return ListView.separated(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
            itemCount: appeals.length,
            separatorBuilder: (_, __) => const SizedBox(height: JanSpace.sm),
            itemBuilder: (context, i) {
              final a = appeals[i];
              final appealId = (a['appealId'] as num).toInt();
              final complaintId = (a['complaintId'] as num).toInt();
              final created = a['createdAt'] != null ? DateTime.tryParse(a['createdAt'] as String) : null;
              return JanCard(
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Row(children: [
                    Container(
                      width: 40,
                      height: 40,
                      decoration: const BoxDecoration(color: JanColors.amberLight, borderRadius: JanRadius.smAll),
                      child: const Icon(Icons.gavel_rounded, color: JanColors.amberDark, size: 22),
                    ),
                    const SizedBox(width: JanSpace.sm),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('Complaint #$complaintId',
                            style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                        if (created != null)
                          Text('Appealed ${fmt.format(created)}',
                              style: const TextStyle(fontSize: 12.5, color: JanColors.muted)),
                      ]),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(color: JanColors.amberLight, borderRadius: BorderRadius.circular(10)),
                      child: const Text('Pending',
                          style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: JanColors.amberDark)),
                    ),
                  ]),
                  const SizedBox(height: JanSpace.sm),
                  Container(
                    padding: const EdgeInsets.all(JanSpace.sm),
                    decoration: const BoxDecoration(color: JanColors.surfaceAlt, borderRadius: JanRadius.mdAll),
                    child: Text(a['reason'] as String? ?? '', style: const TextStyle(color: JanColors.slate, height: 1.4)),
                  ),
                  const SizedBox(height: JanSpace.sm),
                  Wrap(spacing: JanSpace.xs, runSpacing: JanSpace.xs, alignment: WrapAlignment.end, children: [
                    TextButton.icon(
                      onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => OfficerComplaintDetailScreen(complaintId: complaintId),
                      )),
                      icon: const Icon(Icons.open_in_new_rounded, size: 18),
                      label: const Text('View complaint'),
                    ),
                    OutlinedButton(onPressed: () => _decide(appealId, 'DENIED'), child: const Text('Deny')),
                    FilledButton(onPressed: () => _decide(appealId, 'APPROVED'), child: const Text('Approve')),
                  ]),
                ]),
              );
            },
          );
        },
      ),
    );
    if (widget.embedded) return body;
    return JanPage(title: 'Pending Appeals', maxWidth: 820, body: body);
  }
}
