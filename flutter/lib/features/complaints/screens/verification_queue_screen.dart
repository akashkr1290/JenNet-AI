import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import 'complaint_list_screen.dart';
import 'verification_review_screen.dart';

/// Gap-backlog Patch 25 (Sep 2026 audit): "Human AI Verification
/// Workflow" queue - every AI_PROCESSING complaint waiting for a
/// VERIFICATION_TEAM (or ADMIN/SUPER_ADMIN) member to accept, override,
/// or reject the AI's classification. Same list-and-filter pattern as
/// OfficerQueueScreen, scoped to a single fixed status rather than a
/// filter row (a verifier's queue is always "what needs reviewing right
/// now", not a browse-by-status view).
class VerificationQueueScreen extends StatefulWidget {
  final bool embedded;
  const VerificationQueueScreen({super.key, this.embedded = false});

  @override
  State<VerificationQueueScreen> createState() => _VerificationQueueScreenState();
}

class _VerificationQueueScreenState extends State<VerificationQueueScreen> {
  late Future<List<ComplaintSummary>> _future;

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.list(status: ComplaintStatus.aiProcessing, pageSize: 50);
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.list(status: ComplaintStatus.aiProcessing, pageSize: 50));
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    final body = RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<List<ComplaintSummary>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanSkeletonList(semanticLabel: 'Loading the verification queue');
          }
          if (snapshot.hasError) {
            return JanErrorState.fromError(
              snapshot.error,
              fallback: 'Could not load the verification queue.',
              onRetry: _refresh,
            );
          }
          final complaints = snapshot.data ?? [];
          if (complaints.isEmpty) {
            return const JanEmptyState(
              icon: Icons.fact_check_outlined,
              title: 'All caught up',
              message: 'Nothing waiting for verification right now.',
            );
          }
          return ListView.separated(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
            itemCount: complaints.length,
            separatorBuilder: (_, __) => const SizedBox(height: JanSpace.sm),
            itemBuilder: (context, i) {
              final c = complaints[i];
              return ComplaintSummaryCard(
                complaint: c,
                trailing: const Icon(Icons.chevron_right_rounded, color: JanColors.muted),
                onTap: () async {
                  await Navigator.of(context).push(MaterialPageRoute(
                    builder: (_) => VerificationReviewScreen(complaintId: c.complaintId),
                  ));
                  if (mounted) _refresh();
                },
              );
            },
          );
        },
      ),
    );
    if (widget.embedded) return body;
    return JanPage(title: 'Verification Queue', maxWidth: 820, body: body);
  }
}
