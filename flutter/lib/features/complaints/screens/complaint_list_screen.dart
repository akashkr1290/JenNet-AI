import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../widgets/status_badge.dart';
import 'complaint_detail_screen.dart';

/// SRS 15.1: "citizens can track the status of their submitted complaints".
class ComplaintListScreen extends StatefulWidget {
  const ComplaintListScreen({super.key});

  @override
  State<ComplaintListScreen> createState() => _ComplaintListScreenState();
}

class _ComplaintListScreenState extends State<ComplaintListScreen> {
  late Future<List<ComplaintSummary>> _future;

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.list();
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.list());
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
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
                : 'Could not load your complaints.';
            return _ErrorView(message: message, onRetry: _refresh);
          }
          final complaints = snapshot.data ?? [];
          if (complaints.isEmpty) {
            return ListView(
              children: const [
                SizedBox(height: 120),
                Center(child: Text('No complaints yet. Report an issue from the Submit tab.')),
              ],
            );
          }
          return ListView.separated(
            padding: const EdgeInsets.all(12),
            itemCount: complaints.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) {
              final c = complaints[index];
              return Card(
                child: ListTile(
                  title: Text(c.referenceNumber),
                  subtitle: Text(
                    c.description?.isNotEmpty == true ? c.description! : c.category,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  trailing: StatusBadge(status: c.status),
                  onTap: () {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => ComplaintDetailScreen(complaintId: c.complaintId),
                      ),
                    );
                  },
                ),
              );
            },
          );
        },
      ),
    );
  }
}

class _ErrorView extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _ErrorView({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return ListView(
      children: [
        const SizedBox(height: 120),
        Icon(Icons.error_outline, size: 40, color: Colors.red.shade300),
        const SizedBox(height: 8),
        Center(child: Text(message, textAlign: TextAlign.center)),
        const SizedBox(height: 12),
        Center(child: OutlinedButton(onPressed: onRetry, child: const Text('Retry'))),
      ],
    );
  }
}
