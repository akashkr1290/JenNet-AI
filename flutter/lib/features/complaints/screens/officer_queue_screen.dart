import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import '../widgets/status_badge.dart';
import 'officer_complaint_detail_screen.dart';

/// Phase 12 (SRS 16.2 "Officer Queue"). Permissions ("Government Officer
/// (own queue), Department Head (whole department queue)") are enforced
/// entirely server-side by ComplaintService.list/findForOfficerOrDepartment
/// - this screen just calls the same GET /complaints endpoint the citizen
/// tracking screen uses and renders whatever comes back, same as
/// ComplaintListScreen. The only client-side addition is the status
/// filter chip row (SRS 16.2 lists status as a queue filter) and routing
/// each row to the Officer-specific detail screen rather than the
/// citizen-read-only one.
class OfficerQueueScreen extends StatefulWidget {
  const OfficerQueueScreen({super.key});

  @override
  State<OfficerQueueScreen> createState() => _OfficerQueueScreenState();
}

class _OfficerQueueScreenState extends State<OfficerQueueScreen> {
  ComplaintStatus? _filter;
  late Future<List<ComplaintSummary>> _future;

  static const _filterable = [
    ComplaintStatus.assigned,
    ComplaintStatus.inProgress,
    ComplaintStatus.resolved,
    ComplaintStatus.closed,
  ];

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.list(status: _filter);
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.list(status: _filter));
    await _future;
  }

  void _setFilter(ComplaintStatus? status) {
    setState(() {
      _filter = status;
      _future = ComplaintsApi.instance.list(status: _filter);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        SizedBox(
          height: 48,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            children: [
              _filterChip(null, 'All'),
              const SizedBox(width: 8),
              for (final s in _filterable) ...[
                _filterChip(s, s.label),
                const SizedBox(width: 8),
              ],
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
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.hasError) {
                  final message = snapshot.error is ApiException
                      ? (snapshot.error as ApiException).message
                      : 'Could not load the queue.';
                  return ListView(
                    children: [
                      const SizedBox(height: 120),
                      Center(child: Text(message, textAlign: TextAlign.center)),
                      const SizedBox(height: 12),
                      Center(child: OutlinedButton(onPressed: _refresh, child: const Text('Retry'))),
                    ],
                  );
                }
                final complaints = snapshot.data ?? [];
                if (complaints.isEmpty) {
                  return ListView(
                    children: const [
                      SizedBox(height: 120),
                      Center(child: Text('Nothing in your queue right now.')),
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
                        leading: c.isEscalated
                            ? const Icon(Icons.priority_high, color: Colors.deepOrange)
                            : (c.severity != null ? _severityDot(c.severity!) : null),
                        trailing: StatusBadge(status: c.status),
                        onTap: () async {
                          await Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (_) => OfficerComplaintDetailScreen(complaintId: c.complaintId),
                            ),
                          );
                          if (mounted) _refresh();
                        },
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ),
      ],
    );
  }

  Widget _filterChip(ComplaintStatus? status, String label) {
    final selected = _filter == status;
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => _setFilter(status),
    );
  }

  Widget _severityDot(String severity) {
    Color color;
    switch (severity) {
      case 'CRITICAL':
        color = Colors.red;
        break;
      case 'HIGH':
        color = Colors.deepOrange;
        break;
      case 'MEDIUM':
        color = Colors.amber;
        break;
      default:
        color = Colors.grey;
    }
    return Icon(Icons.circle, size: 14, color: color);
  }
}
