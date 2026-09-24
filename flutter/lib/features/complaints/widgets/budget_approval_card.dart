import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/auth/session.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';

/// Gap-backlog Patch 15 (Sep 2026 strict recheck): estimated budget, approval
/// status/approver/time, and [Approve]/[Reject] controls. The controls render
/// only for DEPARTMENT_HEAD/ADMIN/SUPER_ADMIN (convenience only - the backend
/// @PreAuthorize and department-scope check are the real enforcement).
class BudgetApprovalCard extends StatefulWidget {
  final ComplaintDetail complaint;
  final VoidCallback onChanged;
  const BudgetApprovalCard({super.key, required this.complaint, required this.onChanged});

  @override
  State<BudgetApprovalCard> createState() => _BudgetApprovalCardState();
}

class _BudgetApprovalCardState extends State<BudgetApprovalCard> {
  late final Future<bool> _canDecide =
      Future.wait([Session.instance.isDepartmentHead, Session.instance.isAdminOrSuperAdmin])
          .then((r) => r[0] || r[1]);
  bool _busy = false;

  Future<void> _run(Future<void> Function() action, String done) async {
    setState(() => _busy = true);
    try {
      await action();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(done)));
      widget.onChanged();
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _reject() async {
    final controller = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Reject budget estimate'),
        content: TextField(controller: controller, maxLength: 500, decoration: const InputDecoration(labelText: 'Reason (optional)')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Reject')),
        ],
      ),
    );
    final note = controller.text.trim();
    controller.dispose();
    if (ok != true) return;
    await _run(() => ComplaintsApi.instance.rejectBudget(widget.complaint.complaintId, note: note), 'Budget rejected.');
  }

  @override
  Widget build(BuildContext context) {
    final b = widget.complaint.budget;
    if (b == null) return const SizedBox.shrink();
    final money = NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 0);
    final range = (b.estimatedCostMin != null && b.estimatedCostMax != null)
        ? '${money.format(b.estimatedCostMin)} - ${money.format(b.estimatedCostMax)}'
        : 'Not yet estimated';
    final status = b.approvalStatus;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Estimated Budget: $range', style: const TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Text(b.approvalRequired
              ? 'Budget approval: $status'
                  '${b.approvedByName != null ? ' by ${b.approvedByName}' : ''}'
                  '${b.approvedAt != null ? ' on ${DateFormat('dd MMM yyyy').format(b.approvedAt!.toLocal())}' : ''}'
              : 'Within the standard threshold - no approval needed'),
          if (b.approvalRequired)
            FutureBuilder<bool>(
              future: _canDecide,
              builder: (context, snap) {
                if (snap.data != true) return const SizedBox.shrink();
                return Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Wrap(spacing: 8, children: [
                    if (status != 'APPROVED')
                      FilledButton(
                        onPressed: _busy
                            ? null
                            : () => _run(() => ComplaintsApi.instance.approveBudget(widget.complaint.complaintId),
                                'Budget approved.'),
                        child: const Text('Approve'),
                      ),
                    if (status != 'REJECTED')
                      OutlinedButton(onPressed: _busy ? null : _reject, child: const Text('Reject')),
                  ]),
                );
              },
            ),
        ]),
      ),
    );
  }
}
