import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
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
    return Scaffold(
      appBar: widget.embedded ? null : AppBar(title: const Text('Verification Queue')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<ComplaintSummary>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              final message = snapshot.error is ApiException
                  ? (snapshot.error as ApiException).message
                  : 'Could not load the verification queue.';
              return ListView(children: [
                const SizedBox(height: 120),
                Center(child: Text(message, textAlign: TextAlign.center)),
                const SizedBox(height: 12),
                Center(child: OutlinedButton(onPressed: _refresh, child: const Text('Retry'))),
              ]);
            }
            final complaints = snapshot.data ?? [];
            if (complaints.isEmpty) {
              return ListView(children: const [
                SizedBox(height: 120),
                Center(child: Text('Nothing waiting for verification right now.')),
              ]);
            }
            return ListView.separated(
              padding: const EdgeInsets.all(12),
              itemCount: complaints.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, i) {
                final c = complaints[i];
                return Card(
                  child: ListTile(
                    title: Text(c.referenceNumber),
                    subtitle: Text(
                      c.description?.isNotEmpty == true ? c.description! : c.category,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () async {
                      await Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => VerificationReviewScreen(complaintId: c.complaintId),
                      ));
                      if (mounted) _refresh();
                    },
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}
