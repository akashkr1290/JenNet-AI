import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/auth/session.dart';
import '../../department/screens/reassign_officer_dialog.dart';
import '../../users/user_api.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import '../widgets/budget_approval_card.dart';
import '../widgets/status_badge.dart';

const _categories = [
  'POTHOLE',
  'GARBAGE_OVERFLOW',
  'WATER_LEAKAGE',
  'BROKEN_STREET_LIGHT',
  'OPEN_MANHOLE',
  'ILLEGAL_CONSTRUCTION',
  'GENERAL',
];
const _severities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/// Phase 12 (SRS 16.2 "Complaint Detail (Officer View)"). Extends the
/// Phase 6 citizen ComplaintDetailScreen's read-only layout with the
/// Officer/Department Head action set: Status Update Form (17.3),
/// Override Classification, Escalate, and Add Internal Note. Kept as a
/// separate screen from ComplaintDetailScreen rather than one screen
/// branching on role - the citizen screen must never accidentally render
/// a staff-only action if a citizen ever reached it, and the two views'
/// button sets share almost no logic.
class OfficerComplaintDetailScreen extends StatefulWidget {
  final int complaintId;
  const OfficerComplaintDetailScreen({super.key, required this.complaintId});

  @override
  State<OfficerComplaintDetailScreen> createState() => _OfficerComplaintDetailScreenState();
}

class _OfficerComplaintDetailScreenState extends State<OfficerComplaintDetailScreen> {
  late Future<ComplaintDetail> _future;
  bool _busy = false;

  /// Phase 13: resolved once in initState (not per-build) since it needs
  /// an async GET /users/me call - see UserApi's Javadoc-equivalent
  /// comment for why a Department Head's own departmentId isn't already
  /// available client-side. Null for every other role (the FutureBuilder
  /// below just never resolves to a DEPARTMENT_HEAD department, so the
  /// Reassign button never renders - see _isDepartmentHead/_ownDepartmentId
  /// usage in build()).
  late final Future<UserProfile?> _selfFuture;

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.getDetail(widget.complaintId);
    _selfFuture = _loadSelfIfDepartmentHead();
  }

  Future<UserProfile?> _loadSelfIfDepartmentHead() async {
    if (!await Session.instance.isDepartmentHead) return null;
    return UserApi.instance.me();
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.getDetail(widget.complaintId));
    await _future;
  }

  void _showError(Object e) {
    final message = e is ApiException ? e.message : 'Something went wrong.';
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _runAction(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
      await _refresh();
    } catch (e) {
      if (mounted) _showError(e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _escalate() => _runAction(() => ComplaintsApi.instance.escalate(widget.complaintId));

  Future<void> _openStatusUpdateSheet(ComplaintDetail c) async {
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => _StatusUpdateSheet(
        complaint: c,
        onSubmit: (status, note, photo) => _runAction(
          () => ComplaintsApi.instance.updateStatus(
            complaintId: widget.complaintId,
            newStatus: status,
            note: note,
            afterPhoto: photo,
          ),
        ),
      ),
    );
  }

  Future<void> _openClassificationDialog(ComplaintDetail c) async {
    await showDialog(
      context: context,
      builder: (_) => _ClassificationOverrideDialog(
        currentCategory: c.category,
        currentSeverity: c.severity,
        onSubmit: (category, severity, reason) => _runAction(
          () => ComplaintsApi.instance.overrideClassification(
            complaintId: widget.complaintId,
            category: category,
            severity: severity,
            reason: reason,
          ),
        ),
      ),
    );
  }

  Future<void> _addNote(String note) => _runAction(
        () => ComplaintsApi.instance.addInternalNote(complaintId: widget.complaintId, note: note),
      );

  /// Phase 13 (SRS 16.2 "Reassign" - Department Head only). [ownDepartmentId]
  /// is always the signed-in Department Head's own department (see
  /// ReassignOfficerDialog's Javadoc for why no department picker is
  /// offered here) - the backend independently re-enforces this
  /// regardless (ComplaintService.reassign's Phase 13 scoping fix).
  Future<void> _openReassignDialog(ComplaintDetail c, int ownDepartmentId) async {
    await showDialog(
      context: context,
      builder: (_) => ReassignOfficerDialog(
        departmentId: ownDepartmentId,
        currentOfficerId: c.assignedOfficerId,
        onSubmit: (officerId, note) => ComplaintsApi.instance.reassign(
          complaintId: widget.complaintId,
          departmentId: ownDepartmentId,
          officerId: officerId,
          note: note,
        ).then((_) => _refresh()),
      ),
    );
  }

  /// Client-side hint only for which status transitions to *offer* - the
  /// authoritative check is ComplaintStateMachine server-side (see
  /// ComplaintsApi.updateStatus's Javadoc); an offer that turns out to be
  /// invalid just surfaces as a normal ApiException snackbar.
  List<ComplaintStatus> _nextStatusOptions(ComplaintStatus current) {
    switch (current) {
      case ComplaintStatus.assigned:
        return [ComplaintStatus.inProgress, ComplaintStatus.rejected];
      case ComplaintStatus.inProgress:
        return [ComplaintStatus.resolved, ComplaintStatus.rejected];
      case ComplaintStatus.resolved:
        return [ComplaintStatus.closed];
      default:
        return [];
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Officer View')),
      body: FutureBuilder<ComplaintDetail>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            final message = snapshot.error is ApiException
                ? (snapshot.error as ApiException).message
                : 'Could not load this complaint.';
            return Center(child: Text(message));
          }
          final c = snapshot.data!;
          final nextOptions = _nextStatusOptions(c.status);

          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Text(c.referenceNumber, style: Theme.of(context).textTheme.titleLarge),
                    ),
                    StatusBadge(status: c.status),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  '${c.category.replaceAll('_', ' ')}'
                  '${c.severity != null ? ' · ${c.severity}' : ''}',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
                if (c.isReopened) _flagChip('Reopened', Colors.orange),
                if (c.isEscalated) _flagChip('Escalated', Colors.deepOrange),
                const SizedBox(height: 12),
                if (c.location != null) ...[
                  Text('Location', style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 4),
                  Text(
                    c.location!.formattedAddress ??
                        (c.location!.latitude != null
                            ? '${c.location!.latitude!.toStringAsFixed(6)}, ${c.location!.longitude!.toStringAsFixed(6)}'
                            : 'Unknown'),
                  ),
                  if (c.location!.wardName != null) Text(c.location!.wardName!, style: const TextStyle(fontSize: 12)),
                  const SizedBox(height: 16),
                ],
                if (c.description != null && c.description!.isNotEmpty) ...[
                  Text('Description', style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 4),
                  Text(c.description!),
                  const SizedBox(height: 16),
                ],
                Text('Photos (${c.images.length})', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 4),
                Text(c.images.isEmpty ? 'No photos' : c.images.map((i) => i.imageType).join(', ')),
                const SizedBox(height: 20),

                // ---- Officer actions (SRS 16.2) ----
                Text('Actions', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    if (nextOptions.isNotEmpty)
                      FilledButton.icon(
                        onPressed: _busy ? null : () => _openStatusUpdateSheet(c),
                        icon: const Icon(Icons.sync_alt),
                        label: const Text('Update Status'),
                      ),
                    OutlinedButton.icon(
                      onPressed: _busy ? null : () => _openClassificationDialog(c),
                      icon: const Icon(Icons.edit_outlined),
                      label: const Text('Override Classification'),
                    ),
                    if (!c.isEscalated)
                      OutlinedButton.icon(
                        onPressed: _busy ? null : _escalate,
                        icon: const Icon(Icons.priority_high),
                        label: const Text('Escalate'),
                        style: OutlinedButton.styleFrom(foregroundColor: Colors.deepOrange),
                      ),
                    // Phase 13 (SRS 16.2 "Reassign" - Department Head
                    // only). Rendered only once _selfFuture resolves to a
                    // Department Head's own department id - a plain
                    // GOVERNMENT_OFFICER (or a still-loading state) never
                    // sees this button; the backend would reject the call
                    // with a 403 regardless (defense in depth, not the
                    // only line of defense - see ComplaintService.reassign).
                    FutureBuilder<UserProfile?>(
                      future: _selfFuture,
                      builder: (context, selfSnapshot) {
                        final self = selfSnapshot.data;
                        if (self == null || self.departmentId == null) return const SizedBox.shrink();
                        return OutlinedButton.icon(
                          onPressed: _busy ? null : () => _openReassignDialog(c, self.departmentId!),
                          icon: const Icon(Icons.swap_horiz),
                          label: const Text('Reassign'),
                        );
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 20),

                // Gap-backlog Patch 15: budget + approve/reject for authorized roles.
                BudgetApprovalCard(complaint: c, onChanged: _refresh),
                const SizedBox(height: 16),
                Text('Status Timeline', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 8),
                ...c.statusHistory.map(_timelineTile),

                const SizedBox(height: 20),
                Text('Internal Notes', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 4),
                Text(
                  'Visible to staff only - never shown to the citizen.',
                  style: TextStyle(fontSize: 11, color: Colors.grey.shade600),
                ),
                const SizedBox(height: 8),
                if (c.internalNotes.isEmpty) const Text('No internal notes yet.'),
                ...c.internalNotes.map(_noteTile),
                const SizedBox(height: 8),
                _AddNoteField(busy: _busy, onSubmit: _addNote),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _flagChip(String label, Color color) {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Chip(
        label: Text(label, style: const TextStyle(fontSize: 11)),
        backgroundColor: color.withOpacity(0.12),
        side: BorderSide(color: color.withOpacity(0.4)),
        visualDensity: VisualDensity.compact,
      ),
    );
  }

  Widget _timelineTile(StatusHistoryEntry entry) {
    final formatter = DateFormat('dd MMM yyyy, hh:mm a');
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 4, right: 8),
            child: Icon(Icons.circle, size: 10, color: Colors.indigo),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(entry.newStatus.replaceAll('_', ' '), style: const TextStyle(fontWeight: FontWeight.w600)),
                if (entry.reason != null && entry.reason!.isNotEmpty)
                  Text(entry.reason!, style: const TextStyle(fontSize: 13)),
                Text(
                  [
                    if (entry.changedAt != null) formatter.format(entry.changedAt!),
                    if (entry.actorName != null) 'by ${entry.actorName}' else entry.actorType,
                  ].join(' · '),
                  style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _noteTile(InternalNote note) {
    final formatter = DateFormat('dd MMM yyyy, hh:mm a');
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: Colors.amber.withOpacity(0.08),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Colors.amber.withOpacity(0.3)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(note.note),
            const SizedBox(height: 4),
            Text(
              [
                if (note.authorName != null) note.authorName!,
                if (note.createdAt != null) formatter.format(note.createdAt!),
              ].join(' · '),
              style: TextStyle(fontSize: 11, color: Colors.grey.shade600),
            ),
          ],
        ),
      ),
    );
  }
}

class _AddNoteField extends StatefulWidget {
  final bool busy;
  final Future<void> Function(String note) onSubmit;
  const _AddNoteField({required this.busy, required this.onSubmit});

  @override
  State<_AddNoteField> createState() => _AddNoteFieldState();
}

class _AddNoteFieldState extends State<_AddNoteField> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final text = _controller.text.trim();
    if (text.isEmpty) return;
    await widget.onSubmit(text);
    _controller.clear();
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: TextField(
            controller: _controller,
            maxLength: 1000,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'Add an internal note',
              border: OutlineInputBorder(),
            ),
          ),
        ),
        const SizedBox(width: 8),
        IconButton.filled(
          onPressed: widget.busy ? null : _submit,
          icon: const Icon(Icons.send),
        ),
      ],
    );
  }
}

/// SRS 17.3 "Officer Status Update Form" - newStatus + conditionally-
/// mandatory note (Resolved/Rejected) + conditionally-mandatory
/// after_photo (Resolved only). Client-side validation here exists only
/// to avoid a pointless round trip; ComplaintService.updateStatus is the
/// actual source of truth (see its Javadoc).
class _StatusUpdateSheet extends StatefulWidget {
  final ComplaintDetail complaint;
  final Future<void> Function(ComplaintStatus status, String? note, File? photo) onSubmit;
  const _StatusUpdateSheet({required this.complaint, required this.onSubmit});

  @override
  State<_StatusUpdateSheet> createState() => _StatusUpdateSheetState();
}

class _StatusUpdateSheetState extends State<_StatusUpdateSheet> {
  late ComplaintStatus _target;
  final _noteController = TextEditingController();
  File? _photo;
  String? _error;
  bool _submitting = false;

  static const _options = {
    ComplaintStatus.assigned: [ComplaintStatus.inProgress, ComplaintStatus.rejected],
    ComplaintStatus.inProgress: [ComplaintStatus.resolved, ComplaintStatus.rejected],
    ComplaintStatus.resolved: [ComplaintStatus.closed],
  };

  @override
  void initState() {
    super.initState();
    _target = (_options[widget.complaint.status] ?? const []).first;
  }

  bool get _noteRequired => _target == ComplaintStatus.resolved || _target == ComplaintStatus.rejected;
  bool get _photoRequired => _target == ComplaintStatus.resolved;

  Future<void> _pickPhoto() async {
    final picked = await ImagePicker().pickImage(source: ImageSource.camera, imageQuality: 85);
    if (picked != null) setState(() => _photo = File(picked.path));
  }

  Future<void> _submit() async {
    final note = _noteController.text.trim();
    if (_noteRequired && note.length < 10) {
      setState(() => _error = 'A note of at least 10 characters is required for this status.');
      return;
    }
    if (_photoRequired && _photo == null) {
      setState(() => _error = 'A photo is required to mark this complaint Resolved.');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    await widget.onSubmit(_target, note.isEmpty ? null : note, _photo);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final options = _options[widget.complaint.status] ?? const [];
    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: MediaQuery.of(context).viewInsets.bottom + 16,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Update Status', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          DropdownButtonFormField<ComplaintStatus>(
            value: _target,
            items: options
                .map((s) => DropdownMenuItem(value: s, child: Text(s.label)))
                .toList(),
            onChanged: (v) => setState(() => _target = v ?? _target),
            decoration: const InputDecoration(labelText: 'New status', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _noteController,
            maxLength: 500,
            maxLines: 3,
            decoration: InputDecoration(
              labelText: _noteRequired ? 'Note (required, min 10 characters)' : 'Note (optional)',
              border: const OutlineInputBorder(),
            ),
          ),
          if (_photoRequired) ...[
            const SizedBox(height: 4),
            Row(
              children: [
                Expanded(
                  child: Text(
                    _photo == null ? 'No after-photo selected' : 'Photo selected',
                    style: TextStyle(color: _photo == null ? Colors.red : Colors.green),
                  ),
                ),
                OutlinedButton.icon(
                  onPressed: _pickPhoto,
                  icon: const Icon(Icons.camera_alt_outlined),
                  label: const Text('After Photo'),
                ),
              ],
            ),
          ],
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: Colors.red)),
          ],
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _submitting ? null : _submit,
            child: _submitting
                ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Submit'),
          ),
        ],
      ),
    );
  }
}

/// SRS 16.2 "Override Classification" - category and/or severity, plus a
/// mandatory (min 10 char) justification.
class _ClassificationOverrideDialog extends StatefulWidget {
  final String currentCategory;
  final String? currentSeverity;
  final Future<void> Function(String? category, String? severity, String reason) onSubmit;
  const _ClassificationOverrideDialog({
    required this.currentCategory,
    required this.currentSeverity,
    required this.onSubmit,
  });

  @override
  State<_ClassificationOverrideDialog> createState() => _ClassificationOverrideDialogState();
}

class _ClassificationOverrideDialogState extends State<_ClassificationOverrideDialog> {
  late String _category;
  String? _severity;
  final _reasonController = TextEditingController();
  String? _error;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _category = widget.currentCategory;
    _severity = widget.currentSeverity;
  }

  Future<void> _submit() async {
    final reason = _reasonController.text.trim();
    if (reason.length < 10) {
      setState(() => _error = 'Justification must be at least 10 characters.');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    await widget.onSubmit(
      _category != widget.currentCategory ? _category : null,
      _severity != widget.currentSeverity ? _severity : null,
      reason,
    );
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Override Classification'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DropdownButtonFormField<String>(
              value: _category,
              items: _categories.map((c) => DropdownMenuItem(value: c, child: Text(c.replaceAll('_', ' ')))).toList(),
              onChanged: (v) => setState(() => _category = v ?? _category),
              decoration: const InputDecoration(labelText: 'Category', border: OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              value: _severity,
              items: _severities.map((s) => DropdownMenuItem(value: s, child: Text(s))).toList(),
              onChanged: (v) => setState(() => _severity = v),
              decoration: const InputDecoration(labelText: 'Severity', border: OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _reasonController,
              maxLength: 500,
              maxLines: 3,
              decoration: const InputDecoration(
                labelText: 'Justification (required, min 10 characters)',
                border: OutlineInputBorder(),
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 4),
              Text(_error!, style: const TextStyle(color: Colors.red)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          child: _submitting
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
