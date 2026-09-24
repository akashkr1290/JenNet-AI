import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
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
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            final message = snapshot.error is ApiException
                ? (snapshot.error as ApiException).message
                : 'Could not load appeals.';
            return ListView(children: [const SizedBox(height: 120), Center(child: Text(message))]);
          }
          final appeals = snapshot.data ?? [];
          if (appeals.isEmpty) {
            return ListView(children: const [SizedBox(height: 120), Center(child: Text('No pending appeals.'))]);
          }
          return ListView.separated(
            padding: const EdgeInsets.all(12),
            itemCount: appeals.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, i) {
              final a = appeals[i];
              final appealId = (a['appealId'] as num).toInt();
              final complaintId = (a['complaintId'] as num).toInt();
              final created = a['createdAt'] != null ? DateTime.tryParse(a['createdAt'] as String) : null;
              return Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('Complaint #$complaintId${created != null ? ' · appealed ${fmt.format(created)}' : ''}',
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 6),
                    Text(a['reason'] as String? ?? ''),
                    const SizedBox(height: 8),
                    Wrap(spacing: 8, children: [
                      TextButton(
                        onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                          builder: (_) => OfficerComplaintDetailScreen(complaintId: complaintId),
                        )),
                        child: const Text('View complaint'),
                      ),
                      OutlinedButton(onPressed: () => _decide(appealId, 'DENIED'), child: const Text('Deny')),
                      FilledButton(onPressed: () => _decide(appealId, 'APPROVED'), child: const Text('Approve')),
                    ]),
                  ]),
                ),
              );
            },
          );
        },
      ),
    );
    if (widget.embedded) return body;
    return Scaffold(appBar: AppBar(title: const Text('Pending Appeals')), body: body);
  }
}
