import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/error_text.dart';
import '../department_api.dart';
import '../models/department_performance.dart';

/// Phase 13 (SRS 16.2 Officer Queue "Buttons: ... Reassign ..."
/// + "Validation: ... Reassign requires selecting a valid officer within
/// permission scope"; "Department Performance View... Actions: ...
/// reassign complaints"). Reuses Phase 11's
/// `PATCH .../complaints/{id}/assign` (via `ComplaintsApi.reassign`) -
/// this dialog only collects the target officer; the target
/// [departmentId] is always the Department Head's own department (a
/// Department Head has no cross-department authority - see
/// ComplaintService.reassign's Phase 13 scoping fix), so this dialog
/// never offers a department picker at all, unlike a hypothetical
/// Admin-facing reassignment screen would.
///
/// The officer list comes from `GET /departments/{id}/officers` (also
/// new this phase) - "leave unassigned (department-level)" is offered
/// as an explicit option per SRS 15.7's Exceptions clause ("the
/// complaint remains 'Assigned' at department level").
class ReassignOfficerDialog extends StatefulWidget {
  final int departmentId;
  final int? currentOfficerId;
  final Future<void> Function(int? officerId, String? note) onSubmit;

  const ReassignOfficerDialog({
    super.key,
    required this.departmentId,
    this.currentOfficerId,
    required this.onSubmit,
  });

  @override
  State<ReassignOfficerDialog> createState() => _ReassignOfficerDialogState();
}

class _ReassignOfficerDialogState extends State<ReassignOfficerDialog> {
  late Future<List<OfficerSummary>> _officersFuture;
  int? _selectedOfficerId;
  final _noteController = TextEditingController();
  String? _error;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _selectedOfficerId = widget.currentOfficerId;
    _officersFuture = DepartmentApi.instance.officers(widget.departmentId);
  }

  @override
  void dispose() {
    _noteController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final note = _noteController.text.trim();
      await widget.onSubmit(_selectedOfficerId, note.isEmpty ? null : note);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      final message = e is ApiException ? e.message : 'Could not reassign this complaint.';
      setState(() {
        _error = message;
        _submitting = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      icon: const Icon(Icons.swap_horiz_rounded, color: JanColors.primary),
      title: const Text('Reassign Officer'),
      content: SizedBox(
        width: double.maxFinite,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FutureBuilder<List<OfficerSummary>>(
                future: _officersFuture,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Padding(
                      padding: EdgeInsets.symmetric(vertical: 16),
                      child: Center(child: CircularProgressIndicator()),
                    );
                  }
                  if (snapshot.hasError) {
                    final message = snapshot.error is ApiException
                        ? (snapshot.error as ApiException).message
                        : 'Could not load officers.';
                    return ErrorText(message);
                  }
                  final officers = snapshot.data ?? [];
                  return DropdownButtonFormField<int?>(
                    initialValue: _selectedOfficerId,
                    isExpanded: true,
                    items: [
                      const DropdownMenuItem<int?>(
                        value: null,
                        child: Text('Unassigned (department-level)'),
                      ),
                      ...officers.map(
                        (o) => DropdownMenuItem<int?>(value: o.userId, child: Text(o.fullName)),
                      ),
                    ],
                    onChanged: (v) => setState(() => _selectedOfficerId = v),
                    decoration: const InputDecoration(labelText: 'Officer', prefixIcon: Icon(Icons.badge_outlined)),
                  );
                },
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _noteController,
                maxLength: 500,
                maxLines: 3,
                decoration: const InputDecoration(labelText: 'Note (optional)'),
              ),
              if (_error != null) ...[
                const SizedBox(height: 4),
                ErrorText(_error!),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          child: _submitting
              ? const SizedBox(
                  height: 16,
                  width: 16,
                  child: CircularProgressIndicator(strokeWidth: 2, color: JanColors.white),
                )
              : const Text('Reassign'),
        ),
      ],
    );
  }
}
