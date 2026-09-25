import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../widgets/category_visuals.dart';
import '../widgets/detection_overlay_image.dart';
import '../widgets/status_badge.dart';

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
///
/// UI redesign: evidence (photo + AI card) and the decision panel side by
/// side on wide screens; decision actions grouped as Accept/Override,
/// Reject (with a required reason) and Duplicate.
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
    return JanPage(
      title: 'Verify Complaint',
      body: FutureBuilder<ComplaintDetail>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanLoadingView(message: 'Loading complaint...');
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return JanErrorState.fromError(
              snapshot.error,
              fallback: 'Could not load this complaint.',
              onRetry: () => setState(() => _future = ComplaintsApi.instance.getDetail(widget.complaintId)),
            );
          }
          final c = snapshot.data!;
          _overrideCategory ??= c.category;

          final evidence = Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  CategoryTile(category: c.category, size: 52),
                  const SizedBox(width: JanSpace.sm),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(c.referenceNumber,
                            style: Theme.of(context).textTheme.titleLarge?.copyWith(color: JanColors.navy)),
                        Text(CategoryVisual.of(c.category).label, style: const TextStyle(color: JanColors.muted)),
                      ],
                    ),
                  ),
                  StatusBadge(status: c.status),
                ],
              ),
              const SizedBox(height: JanSpace.md),
              if (c.images.isNotEmpty && c.images.first.viewUrl != null)
                ClipRRect(
                  borderRadius: JanRadius.lgAll,
                  child: DetectionOverlayImage(url: c.images.first.viewUrl!, boxes: c.aiClassification?.detections ?? const []),
                )
              else
                const JanBanner(message: 'No photo is attached to this complaint.', tone: JanBannerTone.info),
              const SizedBox(height: JanSpace.md),
              _aiPredictionCard(c),
              if (c.description != null && c.description!.isNotEmpty) ...[
                const JanSectionHeader(title: 'Citizen description'),
                JanCard(child: Text(c.description!, style: const TextStyle(height: 1.45))),
              ],
            ],
          );

          final decision = JanCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Semantics(
                  header: true,
                  child: Text('Verification decision',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(color: JanColors.navy)),
                ),
                const SizedBox(height: JanSpace.md),
                const JanFieldLabel('Category'),
                DropdownButtonFormField<String>(
                  initialValue: _overrideCategory,
                  isExpanded: true,
                  decoration: const InputDecoration(hintText: 'Category'),
                  items: _categories
                      .map((cat) => DropdownMenuItem(value: cat, child: Text(cat.replaceAll('_', ' '))))
                      .toList(),
                  onChanged: (v) => setState(() => _overrideCategory = v),
                ),
                const SizedBox(height: JanSpace.sm),
                const JanFieldLabel('Note (optional)'),
                TextField(
                  controller: _noteController,
                  maxLines: 2,
                  decoration: const InputDecoration(hintText: 'Add a note for the audit trail'),
                ),
                const SizedBox(height: JanSpace.md),
                FilledButton.icon(
                  onPressed: _submitting ? null : () => _submit('VERIFIED', category: _overrideCategory),
                  icon: _submitting
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2.2, color: JanColors.white),
                        )
                      : const Icon(Icons.check_rounded),
                  label: Text(_overrideCategory == c.category ? 'Accept AI Classification' : 'Verify with Override'),
                ),
                const Divider(height: JanSpace.xxl),
                const JanFieldLabel('Rejection reason (if rejecting)'),
                DropdownButtonFormField<String>(
                  initialValue: _rejectionReasonCode,
                  isExpanded: true,
                  decoration: const InputDecoration(hintText: 'Select a reason'),
                  items: _rejectionReasons
                      .map((r) => DropdownMenuItem(value: r, child: Text(r.replaceAll('_', ' '))))
                      .toList(),
                  onChanged: (v) => setState(() => _rejectionReasonCode = v),
                ),
                const SizedBox(height: JanSpace.sm),
                OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: JanColors.error,
                    side: const BorderSide(color: JanColors.error),
                  ),
                  onPressed: _submitting || _rejectionReasonCode == null
                      ? null
                      : () => _submit('REJECTED', rejectionReasonCode: _rejectionReasonCode),
                  icon: const Icon(Icons.close_rounded),
                  label: const Text('Reject'),
                ),
                const SizedBox(height: JanSpace.xs),
                TextButton.icon(
                  onPressed: _submitting ? null : () => _submit('DUPLICATE'),
                  icon: const Icon(Icons.content_copy_rounded),
                  label: const Text('Mark as Duplicate (needs a parent complaint - use full form)'),
                ),
              ],
            ),
          );

          return LayoutBuilder(builder: (context, constraints) {
            const padding = EdgeInsets.fromLTRB(JanSpace.md, JanSpace.md, JanSpace.md, JanSpace.xxl);
            if (constraints.maxWidth >= 860) {
              return SingleChildScrollView(
                padding: padding,
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(flex: 3, child: evidence),
                    const SizedBox(width: JanSpace.xl),
                    Expanded(flex: 2, child: decision),
                  ],
                ),
              );
            }
            return ListView(
              padding: padding,
              children: [evidence, const SizedBox(height: JanSpace.md), decision],
            );
          });
        },
      ),
    );
  }

  Widget _aiPredictionCard(ComplaintDetail c) {
    final ai = c.aiClassification;
    return JanCard(
      gradient: const LinearGradient(colors: [JanColors.infoLight, JanColors.white]),
      borderColor: JanColors.primaryLight,
      elevated: false,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(children: [
            Icon(Icons.smart_toy_outlined, size: 20, color: JanColors.primary),
            SizedBox(width: 6),
            Text('AI Prediction', style: TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
          ]),
          const SizedBox(height: JanSpace.xs),
          Text('Predicted category: ${c.category.replaceAll('_', ' ')}'),
          if (ai?.confidence != null) ...[
            const SizedBox(height: 4),
            Text('Confidence: ${ai!.confidence!.toStringAsFixed(0)}%'),
            const SizedBox(height: 6),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: (ai.confidence! / 100).clamp(0.0, 1.0),
                minHeight: 8,
                backgroundColor: JanColors.divider,
                semanticsLabel: 'AI confidence',
                semanticsValue: '${ai.confidence!.toStringAsFixed(0)}%',
              ),
            ),
          ],
          if (ai?.duplicateFlagged == true) ...[
            const SizedBox(height: JanSpace.xs),
            const Row(children: [
              Icon(Icons.copy_all_rounded, size: 16, color: JanColors.amberDark),
              SizedBox(width: 4),
              Expanded(
                child: Text('Flagged as a possible duplicate',
                    style: TextStyle(color: JanColors.amberDark, fontWeight: FontWeight.w700)),
              ),
            ]),
          ],
          if (ai == null) const Text('AI classification unavailable - manual review required.'),
        ],
      ),
    );
  }
}
