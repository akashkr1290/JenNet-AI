import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/auth/session.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_surfaces.dart';
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
    final (Color fg, Color bg, IconData icon) = !b.approvalRequired
        ? (JanColors.teal, JanColors.tealLight, Icons.check_circle_outline_rounded)
        : switch (status) {
            'APPROVED' => (JanColors.teal, JanColors.tealLight, Icons.verified_outlined),
            'REJECTED' => (JanColors.error, JanColors.errorLight, Icons.block_rounded),
            _ => (JanColors.amberDark, JanColors.amberLight, Icons.hourglass_top_rounded),
          };
    return JanCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Container(
            width: 40,
            height: 40,
            decoration: const BoxDecoration(color: JanColors.infoLight, borderRadius: JanRadius.smAll),
            child: const Icon(Icons.currency_rupee_rounded, color: JanColors.primary, size: 22),
          ),
          const SizedBox(width: JanSpace.sm),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Estimated Budget', style: TextStyle(fontSize: 12.5, color: JanColors.muted, fontWeight: FontWeight.w600)),
              Text(range, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16, color: JanColors.navy)),
            ]),
          ),
        ]),
        const SizedBox(height: JanSpace.sm),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          decoration: BoxDecoration(color: bg, borderRadius: JanRadius.smAll),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Icon(icon, size: 18, color: fg),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                b.approvalRequired
                    ? 'Budget approval: $status'
                        '${b.approvedByName != null ? ' by ${b.approvedByName}' : ''}'
                        '${b.approvedAt != null ? ' on ${DateFormat('dd MMM yyyy').format(b.approvedAt!.toLocal())}' : ''}'
                    : 'Within the standard threshold - no approval needed',
                style: TextStyle(color: fg, fontWeight: FontWeight.w700, fontSize: 13),
              ),
            ),
          ]),
        ),
        if (b.approvalRequired)
          FutureBuilder<bool>(
            future: _canDecide,
            builder: (context, snap) {
              if (snap.data != true) return const SizedBox.shrink();
              return Padding(
                padding: const EdgeInsets.only(top: JanSpace.sm),
                child: Wrap(spacing: JanSpace.xs, runSpacing: JanSpace.xs, children: [
                  if (status != 'APPROVED')
                    FilledButton.icon(
                      onPressed: _busy
                          ? null
                          : () => _run(() => ComplaintsApi.instance.approveBudget(widget.complaint.complaintId),
                              'Budget approved.'),
                      icon: const Icon(Icons.check_rounded),
                      label: const Text('Approve'),
                    ),
                  if (status != 'REJECTED')
                    OutlinedButton.icon(
                      onPressed: _busy ? null : _reject,
                      icon: const Icon(Icons.close_rounded),
                      label: const Text('Reject'),
                    ),
                ]),
              );
            },
          ),
      ]),
    );
  }
}
