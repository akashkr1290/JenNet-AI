import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../widgets/detection_overlay_image.dart';

/// Gap-backlog Patch 25 (Sep 2026 audit): the actual "Human AI
/// Verification Workflow" review screen the patch asked for - the
/// verifier sees the image, the AI's prediction/confidence/reasoning
/// (via the same AI explainability fields Gap-backlog Patch 30/43 added
/// to ComplaintResponse), and can Accept (confirm the AI's own category
/// as VERIFIED), Override (pick a different category), or Reject/mark
/// Duplicate - each calling the real, pre-existing
/// PATCH /api/v1/complaints/{id}/verify endpoint
/// (ComplaintService.verify), which was previously only reachable via
/// Postman/curl with no Flutter UI at all.
///
/// "Every override should be audited" (the patch's own requirement) is
/// already true server-side without any extra work here: ComplaintService
/// records status history + AuditService entries for every verify() call
/// regardless of which decision was made - accepting the AI's own
/// category still goes through the identical audited code path as
/// overriding it.
class VerificationReviewScreen extends StatefulWidget {
  final int complaintId;
  const VerificationReviewScreen({super.key, required this.complaintId});

  @override
  State<VerificationReviewScreen> createState() => _VerificationReviewScreenState();
}

class _VerificationReviewScreenState extends State<VerificationReviewScreen> {
  late Future<ComplaintDetail> _future;
  bool _submitting = false;
  String? _overrideCategory;
  String? _rejectionReasonCode;
  final _noteController = TextEditingController();

  static const _categories = [
    'POTHOLE',
    'GARBAGE_OVERFLOW',
    'WATER_LEAKAGE',
    'BROKEN_STREET_LIGHT',
    'OPEN_MANHOLE',
    'ILLEGAL_CONSTRUCTION',
    'GENERAL',
  ];

  static const _rejectionReasons = [
    'NOT_A_CIVIC_ISSUE',
    'DUPLICATE_SUBMISSION',
    'INSUFFICIENT_EVIDENCE',
    'OUTSIDE_JURISDICTION',
    'SPAM_OR_ABUSE',
  ];

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.getDetail(widget.complaintId);
  }

  @override
  void dispose() {
    _noteController.dispose();
    super.dispose();
  }

  Future<void> _submit(String decision, {String? category, String? rejectionReasonCode}) async {
    setState(() => _submitting = true);
    try {
      await ComplaintsApi.instance.verify(
        widget.complaintId,
        decision: decision,
        category: category,
        rejectionReasonCode: rejectionReasonCode,
        note: _noteController.text.trim(),
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Marked as $decision.')),
      );
      Navigator.of(context).pop();
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Verify Complaint')),
      body: FutureBuilder<ComplaintDetail>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return Center(child: Text(
                snapshot.error is ApiException ? (snapshot.error as ApiException).message : 'Could not load this complaint.'));
          }
          final c = snapshot.data!;
          _overrideCategory ??= c.category;

          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(c.referenceNumber, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              if (c.images.isNotEmpty && c.images.first.viewUrl != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: DetectionOverlayImage(url: c.images.first.viewUrl!, boxes: c.aiClassification?.detections ?? const []),
                ),
              const SizedBox(height: 16),
              _aiPredictionCard(c),
              const SizedBox(height: 16),
              if (c.description != null && c.description!.isNotEmpty) ...[
                Text('Citizen description', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 4),
                Text(c.description!),
                const SizedBox(height: 16),
              ],
              Text('Verification decision', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                value: _overrideCategory,
                decoration: const InputDecoration(labelText: 'Category', border: OutlineInputBorder()),
                items: _categories.map((cat) => DropdownMenuItem(value: cat, child: Text(cat.replaceAll('_', ' ')))).toList(),
                onChanged: (v) => setState(() => _overrideCategory = v),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _noteController,
                maxLines: 2,
                decoration: const InputDecoration(labelText: 'Note (optional)', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: _submitting ? null : () => _submit('VERIFIED', category: _overrideCategory),
                icon: const Icon(Icons.check),
                label: Text(_overrideCategory == c.category ? 'Accept AI Classification' : 'Verify with Override'),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                value: _rejectionReasonCode,
                decoration: const InputDecoration(labelText: 'Rejection reason (if rejecting)', border: OutlineInputBorder()),
                items: _rejectionReasons.map((r) => DropdownMenuItem(value: r, child: Text(r.replaceAll('_', ' ')))).toList(),
                onChanged: (v) => setState(() => _rejectionReasonCode = v),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: _submitting || _rejectionReasonCode == null
                    ? null
                    : () => _submit('REJECTED', rejectionReasonCode: _rejectionReasonCode),
                icon: const Icon(Icons.close),
                label: const Text('Reject'),
              ),
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: _submitting ? null : () => _submit('DUPLICATE'),
                icon: const Icon(Icons.content_copy),
                label: const Text('Mark as Duplicate (needs a parent complaint - use full form)'),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _aiPredictionCard(ComplaintDetail c) {
    final ai = c.aiClassification;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.indigo.withOpacity(0.06),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.indigo.withOpacity(0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: const [
            Icon(Icons.smart_toy_outlined, size: 18, color: Colors.indigo),
            SizedBox(width: 6),
            Text('AI Prediction', style: TextStyle(fontWeight: FontWeight.w600)),
          ]),
          const SizedBox(height: 6),
          Text('Predicted category: ${c.category.replaceAll('_', ' ')}'),
          if (ai?.confidence != null) Text('Confidence: ${ai!.confidence!.toStringAsFixed(0)}%'),
          if (ai?.duplicateFlagged == true)
            const Text('Flagged as a possible duplicate', style: TextStyle(color: Colors.orange)),
          if (ai == null) const Text('AI classification unavailable - manual review required.'),
        ],
      ),
    );
  }
}
