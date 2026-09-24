import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import '../../../core/l10n/app_strings.dart';
import '../widgets/detection_overlay_image.dart';
import '../widgets/status_badge.dart';

/// Full detail + status-history timeline (SRS 15.1 tracking; status_history
/// is exactly ComplaintService.toResponse's statusHistory list).
///
/// Extended by the Sep 2026 gap-backlog audit with: real photo rendering +
/// zoom (Patch 29/42, using ComplaintImage.viewUrl - Patch 6's presigned
/// URLs), AI explainability (Patch 30/43) with a friendly failure message
/// (Patch 31/44) instead of raw status codes, budget approval display
/// (Patch 15), citizen resolution rating (Patch 11/13), and appeal-after-
/// rejection (Patch 12/14).
class ComplaintDetailScreen extends StatefulWidget {
  final int complaintId;
  const ComplaintDetailScreen({super.key, required this.complaintId});

  @override
  State<ComplaintDetailScreen> createState() => _ComplaintDetailScreenState();
}

class _ComplaintDetailScreenState extends State<ComplaintDetailScreen> {
  late Future<ComplaintDetail> _future;
  bool _reopening = false;
  bool _confirming = false;
  bool _submittingRating = false;
  bool _submittingAppeal = false;
  int? _selectedRatingStars;
  final _ratingCommentController = TextEditingController();
  final _appealReasonController = TextEditingController();
  Map<String, dynamic>? _existingRating;
  bool _ratingLoaded = false;

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.getDetail(widget.complaintId);
  }

  @override
  void dispose() {
    _ratingCommentController.dispose();
    _appealReasonController.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = ComplaintsApi.instance.getDetail(widget.complaintId);
      _ratingLoaded = false;
    });
    await _future;
  }

  // Gap-backlog Patch 41: citizen confirms the fix (RESOLVED -> CLOSED).
  Future<void> _confirmResolution() async {
    setState(() => _confirming = true);
    try {
      await ComplaintsApi.instance.confirmResolution(widget.complaintId);
      await _refresh();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Thank you - complaint closed.')));
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _confirming = false);
    }
  }

  Future<void> _reopen() async {
    // Gap-backlog Patch 41: ask why it is not resolved; recorded in the status history.
    final reasonController = TextEditingController();
    final proceed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Issue not resolved?'),
        content: TextField(
          controller: reasonController,
          maxLength: 200,
          maxLines: 3,
          decoration: const InputDecoration(labelText: 'What is still wrong? (optional)'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Reopen')),
        ],
      ),
    );
    final reason = reasonController.text.trim();
    reasonController.dispose();
    if (proceed != true) return;
    setState(() => _reopening = true);
    try {
      await ComplaintsApi.instance.reopen(widget.complaintId, reason: reason);
      await _refresh();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Complaint reopened.')),
      );
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _reopening = false);
    }
  }

  Future<void> _loadExistingRating() async {
    if (_ratingLoaded) return;
    _ratingLoaded = true;
    try {
      final rating = await ComplaintsApi.instance.getRating(widget.complaintId);
      if (mounted) setState(() => _existingRating = rating);
    } catch (_) {
      // Non-fatal - the rating form still renders, just without a
      // pre-filled existing value.
    }
  }

  Future<void> _submitRating() async {
    if (_selectedRatingStars == null) return;
    setState(() => _submittingRating = true);
    try {
      await ComplaintsApi.instance.rate(
        widget.complaintId,
        rating: _selectedRatingStars!,
        comment: _ratingCommentController.text.trim(),
      );
      if (!mounted) return;
      setState(() => _existingRating = {
            'rating': _selectedRatingStars,
            'comment': _ratingCommentController.text.trim(),
          });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Thanks for rating this resolution.')),
      );
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _submittingRating = false);
    }
  }

  Future<void> _submitAppeal() async {
    final reason = _appealReasonController.text.trim();
    if (reason.length < 10) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please explain why you are appealing (at least 10 characters).')),
      );
      return;
    }
    setState(() => _submittingAppeal = true);
    try {
      await ComplaintsApi.instance.appeal(widget.complaintId, reason: reason);
      if (!mounted) return;
      _appealReasonController.clear();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Your appeal has been submitted for review.')),
      );
      await _refresh();
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _submittingAppeal = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Complaint Details')),
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
          final canReopen = c.status == ComplaintStatus.resolved || c.status == ComplaintStatus.closed;
          final canRate = c.status == ComplaintStatus.resolved || c.status == ComplaintStatus.closed;
          final canAppeal = c.status == ComplaintStatus.rejected;
          if (canRate) _loadExistingRating();

          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(c.referenceNumber, style: Theme.of(context).textTheme.titleLarge),
                    StatusBadge(status: c.status),
                  ],
                ),
                const SizedBox(height: 4),
                Text(c.category.replaceAll('_', ' '), style: Theme.of(context).textTheme.bodySmall),
                if (c.isReopened) _flagChip('Reopened', Colors.orange),
                if (c.isEscalated) _flagChip('Escalated', Colors.deepOrange),
                const SizedBox(height: 12),
                if (c.description != null && c.description!.isNotEmpty) ...[
                  Text('Description', style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 4),
                  Text(c.description!),
                  const SizedBox(height: 16),
                ],
                if (c.rejectionReasonCode != null) ...[
                  Text('Rejection reason', style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 4),
                  Text(c.rejectionReasonCode!),
                  const SizedBox(height: 16),
                ],
                _aiExplainabilitySection(c),
                _budgetSection(c),
                Text('Photos (${c.images.length})', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 8),
                _photoGallery(c),
                const SizedBox(height: 20),
                Text('Status Timeline', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 8),
                ...c.statusHistory.map(_timelineTile),
                if (c.status == ComplaintStatus.resolved) ...[
                  const SizedBox(height: 20),
                  Text('Is the issue fixed?', style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 8),
                  FilledButton.icon(
                    onPressed: _confirming ? null : _confirmResolution,
                    icon: const Icon(Icons.check_circle_outline),
                    label: Text(AppStrings.of('action_confirm_resolution')),
                  ),
                ],
                if (canReopen) ...[
                  const SizedBox(height: 20),
                  OutlinedButton(
                    onPressed: _reopening ? null : _reopen,
                    child: _reopening
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : Text(AppStrings.of('action_reopen_not_resolved')),
                  ),
                ],
                if (canRate) _ratingSection(),
                if (canAppeal) _appealSection(),
              ],
            ),
          );
        },
      ),
    );
  }

  // Gap-backlog Patch 29/42 (Sep 2026 audit): real photo rendering with
  // tap-to-zoom, using ComplaintImage.viewUrl (Patch 6's presigned URLs).
  // InteractiveViewer is built into Flutter - no new dependency needed for
  // pinch/double-tap zoom.
  Widget _photoGallery(ComplaintDetail c) {
    if (c.images.isEmpty) {
      return const Text('No photos');
    }
    return SizedBox(
      height: 140,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: c.images.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, i) {
          final image = c.images[i];
          return Semantics(
            label: '${image.imageType} photo, tap to view full screen',
            button: image.viewUrl != null,
            image: true,
            child: Semantics(
            // item 14: a tappable thumbnail is announced as a button with its action
            button: true,
            label: 'Open photo ${i + 1} full screen',
            child: GestureDetector(
            onTap: image.viewUrl != null
                ? () => _openFullScreenPhoto(context, c.images, i, c.aiClassification?.detections ?? const [])
                : null,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Stack(
                children: [
                  if (image.viewUrl != null)
                    Image.network(
                      image.viewUrl!,
                      semanticLabel: '${image.imageType == 'AFTER' ? 'After-resolution' : 'Complaint'} photo ${i + 1}',
                      width: 140,
                      height: 140,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => _photoPlaceholder(image),
                      loadingBuilder: (context, child, progress) =>
                          progress == null ? child : const Center(child: CircularProgressIndicator()),
                    )
                  else
                    _photoPlaceholder(image),
                  Positioned(
                    left: 4,
                    bottom: 4,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.black.withOpacity(0.6),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(image.imageType, style: const TextStyle(color: Colors.white, fontSize: 10)),
                    ),
                  ),
                ],
              ),
            ),
          )));
        },
      ),
    );
  }

  Widget _photoPlaceholder(ComplaintImage image) {
    return Container(
      width: 140,
      height: 140,
      color: Colors.grey.shade300,
      alignment: Alignment.center,
      child: const Icon(Icons.image_not_supported_outlined),
    );
  }

  void _openFullScreenPhoto(BuildContext context, List<ComplaintImage> images, int initialIndex,
      [List<DetectedBox> detections = const []]) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => _FullScreenPhotoViewer(images: images, initialIndex: initialIndex, detections: detections),
    ));
  }

  // Gap-backlog Patch 30/43 (Sep 2026 audit): AI explainability. Gap-backlog
  // Patch 31/44: a friendly message instead of a raw status/error code when
  // the model was unavailable.
  Widget _aiExplainabilitySection(ComplaintDetail c) {
    final ai = c.aiClassification;
    if (ai == null) {
      return const SizedBox.shrink();
    }
    String friendlyStatus;
    Color color;
    IconData icon;
    switch (ai.aiStatus) {
      case 'AUTO_CLASSIFIED':
        friendlyStatus = 'Automatically classified by AI';
        color = Colors.green;
        icon = Icons.smart_toy_outlined;
        break;
      case 'MANUAL_REVIEW_REQUIRED':
        friendlyStatus = 'AI confidence is low - a human will verify this';
        color = Colors.orange;
        icon = Icons.visibility_outlined;
        break;
      default:
        // MODEL_UNAVAILABLE - Gap-backlog Patch 31/44's own example wording.
        friendlyStatus = 'We could not analyze this photo automatically - '
            'it has been submitted for manual verification.';
        color = Colors.blueGrey;
        icon = Icons.info_outline;
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: color.withOpacity(0.08),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withOpacity(0.3)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color, size: 20),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(friendlyStatus, style: const TextStyle(fontWeight: FontWeight.w600)),
                  if (ai.confidence != null)
                    Text('Confidence: ${ai.confidence!.toStringAsFixed(0)}%',
                        style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
                  if (ai.duplicateFlagged)
                    Text('Flagged as a possible duplicate of an existing complaint',
                        style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // Gap-backlog Patch 15 (Sep 2026 audit): budget approval display. Read-only
  // here - the [Approve]/[Reject] controls belong on the officer/department-
  // head screens where the acting role is actually authorized to use them
  // (ComplaintController.approveBudget is DEPARTMENT_HEAD/ADMIN/SUPER_ADMIN-
  // only), not on the citizen-facing detail screen.
  Widget _budgetSection(ComplaintDetail c) {
    final budget = c.budget;
    if (budget == null) {
      return const SizedBox.shrink();
    }
    final formatter = NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 0);
    final range = (budget.estimatedCostMin != null && budget.estimatedCostMax != null)
        ? '${formatter.format(budget.estimatedCostMin)} - ${formatter.format(budget.estimatedCostMax)}'
        : 'Not yet estimated';
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Estimated Budget', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 4),
          Text(range),
          if (budget.estimatedResolutionDays != null)
            Text('Estimated resolution: ${budget.estimatedResolutionDays} day(s)',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
          if (budget.approvalRequired)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                budget.approved
                    ? 'Budget approved${budget.approvedByName != null ? ' by ${budget.approvedByName}' : ''}'
                    : 'Awaiting budget approval (exceeds the standard threshold)',
                style: TextStyle(
                  fontSize: 12,
                  color: budget.approved ? Colors.green.shade700 : Colors.orange.shade800,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
        ],
      ),
    );
  }

  // Gap-backlog Patch 11/13 (Sep 2026 audit): citizen resolution rating.
  Widget _ratingSection() {
    if (_existingRating != null) {
      final stars = _existingRating!['rating'] as int? ?? 0;
      return Padding(
        padding: const EdgeInsets.only(top: 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Your rating', style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 4),
            Row(children: List.generate(5, (i) => Icon(
                  i < stars ? Icons.star : Icons.star_border,
                  color: Colors.amber,
                  size: 20,
                ))),
            if ((_existingRating!['comment'] as String?)?.isNotEmpty == true)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(_existingRating!['comment'] as String),
              ),
          ],
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('How was the resolution?', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          Row(
            children: List.generate(5, (i) {
              final starValue = i + 1;
              final selected = _selectedRatingStars != null && starValue <= _selectedRatingStars!;
              return Semantics(
                label: '$starValue star${starValue > 1 ? 's' : ''}',
                button: true,
                selected: selected,
                child: IconButton(
                  icon: Icon(selected ? Icons.star : Icons.star_border, color: Colors.amber),
                  tooltip: '$starValue star${starValue == 1 ? '' : 's'}', // item 14: named for screen readers
                  onPressed: () => setState(() => _selectedRatingStars = starValue),
                ),
              );
            }),
          ),
          if (_selectedRatingStars != null) ...[
            TextField(
              controller: _ratingCommentController,
              maxLines: 2,
              maxLength: 500,
              decoration: const InputDecoration(
                labelText: 'Optional comment',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            FilledButton(
              onPressed: _submittingRating ? null : _submitRating,
              child: _submittingRating
                  ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Submit Rating'),
            ),
          ],
        ],
      ),
    );
  }

  // Gap-backlog Patch 12/14 (Sep 2026 audit): appeal after rejection.
  Widget _appealSection() {
    return Padding(
      padding: const EdgeInsets.only(top: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Disagree with this decision?', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          TextField(
            controller: _appealReasonController,
            maxLines: 3,
            maxLength: 1000,
            decoration: const InputDecoration(
              labelText: 'Why should this complaint be reconsidered?',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: _submittingAppeal ? null : _submitAppeal,
            child: _submittingAppeal
                ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Appeal Rejection'),
          ),
        ],
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
                Text(
                  entry.newStatus.replaceAll('_', ' '),
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
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
}

/// Gap-backlog Patch 29/42 (Sep 2026 audit): full-screen photo viewer with
/// pinch/double-tap zoom (InteractiveViewer, built into Flutter) and
/// swipe-between-photos (PageView) - "Before/After Comparison" is
/// achieved by swiping between a BEFORE and AFTER image in the same
/// gallery, both already labelled by their imageType chip.
class _FullScreenPhotoViewer extends StatelessWidget {
  final List<ComplaintImage> images;
  final int initialIndex;
  final List<DetectedBox> detections;
  const _FullScreenPhotoViewer({required this.images, required this.initialIndex, this.detections = const []});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(backgroundColor: Colors.black, iconTheme: const IconThemeData(color: Colors.white)),
      body: PageView.builder(
        controller: PageController(initialPage: initialIndex),
        itemCount: images.length,
        itemBuilder: (context, i) {
          final image = images[i];
          if (image.viewUrl == null) {
            return const Center(child: Icon(Icons.image_not_supported_outlined, color: Colors.white54, size: 48));
          }
          return InteractiveViewer(
            minScale: 1,
            maxScale: 4,
            child: Center(
              // Gap-backlog Patch 42: AI boxes drawn on the photo that was classified.
              child: (image.imageType == 'BEFORE' &&
                      detections.isNotEmpty &&
                      i == images.indexWhere((x) => x.imageType == 'BEFORE'))
                  ? DetectionOverlayImage(url: image.viewUrl!, boxes: detections)
                  : Image.network(
                image.viewUrl!,
                semanticLabel: 'Complaint photo, full screen',
                errorBuilder: (_, __, ___) =>
                    const Icon(Icons.broken_image_outlined, color: Colors.white54, size: 48),
              ),
            ),
          );
        },
      ),
    );
  }
}
