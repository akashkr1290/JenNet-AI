import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../complaints_api.dart';
import '../models/complaint.dart';
import '../models/complaint_status.dart';
import '../../../core/l10n/app_strings.dart';
import '../widgets/detection_overlay_image.dart';
import '../widgets/category_visuals.dart';
import '../widgets/status_badge.dart';
import '../widgets/status_timeline.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';

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

  // UI redesign: reference "Complaint Detail" - navy header with reference
  // ID + status, hero photo, info card, connected status timeline, AI card,
  // budget, gallery and the citizen actions. All actions call the unchanged
  // methods above.
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Complaint Details'),
        backgroundColor: JanColors.navy,
        foregroundColor: JanColors.white,
        iconTheme: const IconThemeData(color: JanColors.white),
        titleTextStyle: const TextStyle(color: JanColors.white, fontSize: 20, fontWeight: FontWeight.w700),
      ),
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
              onRetry: _refresh,
            );
          }
          final c = snapshot.data!;
          final canReopen = c.status == ComplaintStatus.resolved || c.status == ComplaintStatus.closed;
          final canRate = c.status == ComplaintStatus.resolved || c.status == ComplaintStatus.closed;
          final canAppeal = c.status == ComplaintStatus.rejected;
          if (canRate) _loadExistingRating();

          final details = <Widget>[
            _heroPhoto(c),
            const SizedBox(height: JanSpace.md),
            _infoCard(c),
            _aiExplainabilitySection(c),
            _budgetSection(c),
          ];
          final progress = <Widget>[
            const JanSectionHeader(title: 'Status Timeline'),
            StatusTimeline(history: c.statusHistory),
            JanSectionHeader(title: 'Photos (${c.images.length})'),
            _photoGallery(c),
            if (c.status == ComplaintStatus.resolved) _confirmCard(),
            if (canReopen) ...[
              const SizedBox(height: JanSpace.md),
              FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: JanColors.navy),
                onPressed: _reopening ? null : _reopen,
                icon: _reopening
                    ? const SizedBox(
                        height: 18,
                        width: 18,
                        child: CircularProgressIndicator(strokeWidth: 2, color: JanColors.white),
                      )
                    : const Icon(Icons.restart_alt_rounded),
                label: Text(AppStrings.of('action_reopen_not_resolved')),
              ),
            ],
            if (canRate) _ratingSection(),
            if (canAppeal) _appealSection(),
          ];

          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.zero,
              children: [
                _header(c),
                ResponsiveCenter(
                  maxWidth: 1100,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.md, JanSpace.md, JanSpace.xxl),
                    child: LayoutBuilder(builder: (context, constraints) {
                      if (constraints.maxWidth >= 860) {
                        return Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: details)),
                            const SizedBox(width: JanSpace.xl),
                            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: progress)),
                          ],
                        );
                      }
                      return Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [...details, ...progress],
                      );
                    }),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _header(ComplaintDetail c) {
    return Container(
      padding: const EdgeInsets.fromLTRB(JanSpace.md, 0, JanSpace.md, JanSpace.lg),
      decoration: const BoxDecoration(
        color: JanColors.navy,
        borderRadius: BorderRadius.vertical(bottom: Radius.circular(JanRadius.xl)),
      ),
      child: Center(
        child: Wrap(
          alignment: WrapAlignment.center,
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: JanSpace.xs,
          runSpacing: JanSpace.xs,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(color: JanColors.white, borderRadius: BorderRadius.circular(14)),
              child: Text(
                'Complaint ID: ${c.referenceNumber}',
                style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.navy),
              ),
            ),
            StatusBadge(status: c.status),
          ],
        ),
      ),
    );
  }

  ComplaintImage? _primaryImage(ComplaintDetail c) {
    if (c.images.isEmpty) return null;
    return c.images.firstWhere((i) => i.imageType == 'BEFORE', orElse: () => c.images.first);
  }

  Widget _heroPhoto(ComplaintDetail c) {
    final image = _primaryImage(c);
    final created = c.createdAt;
    final submitted = created != null ? DateFormat('dd MMM yyyy, hh:mm a').format(created.toLocal()) : null;
    if (image == null || image.viewUrl == null) {
      return JanCard(
        child: Row(
          children: [
            CategoryTile(category: c.category, size: 72),
            const SizedBox(width: JanSpace.md),
            Expanded(
              child: Text(
                submitted != null ? 'Submitted $submitted\nNo photo available' : 'No photo available',
                style: const TextStyle(color: JanColors.muted, height: 1.4),
              ),
            ),
          ],
        ),
      );
    }
    final index = c.images.indexOf(image);
    return Semantics(
      button: true,
      label: 'Complaint photo. Open full screen',
      excludeSemantics: true,
      child: JanCard(
        padding: EdgeInsets.zero,
        onTap: () => _openFullScreenPhoto(context, c.images, index, c.aiClassification?.detections ?? const []),
        child: Stack(
          children: [
            AspectRatio(
              aspectRatio: 16 / 10,
              child: Image.network(
                image.viewUrl!,
                fit: BoxFit.cover,
                excludeFromSemantics: true,
                errorBuilder: (_, __, ___) => _photoPlaceholder(image),
                loadingBuilder: (context, child, progress) =>
                    progress == null ? child : const Center(child: CircularProgressIndicator()),
              ),
            ),
            if (submitted != null)
              Positioned(
                left: 0,
                right: 0,
                top: 0,
                child: Container(
                  padding: const EdgeInsets.fromLTRB(12, 10, 12, 18),
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [Color(0xAA0B1320), Color(0x000B1320)],
                    ),
                  ),
                  child: Text(
                    'Submitted: $submitted',
                    style: const TextStyle(color: JanColors.white, fontWeight: FontWeight.w700),
                  ),
                ),
              ),
            const Positioned(
              right: 10,
              bottom: 10,
              child: CircleAvatar(
                radius: 18,
                backgroundColor: Color(0xB3203A5F),
                child: Icon(Icons.zoom_out_map_rounded, color: JanColors.white, size: 20),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _infoRow(IconData icon, String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.sm),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: JanColors.navy),
          const SizedBox(width: JanSpace.sm),
          Expanded(
            child: Text.rich(
              TextSpan(children: [
                TextSpan(text: '$label: ', style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                TextSpan(text: value),
              ]),
              style: const TextStyle(height: 1.45, fontSize: 15),
            ),
          ),
        ],
      ),
    );
  }

  String? _locationText(ComplaintLocation? l) {
    if (l == null) return null;
    if (l.formattedAddress != null && l.formattedAddress!.isNotEmpty) return l.formattedAddress;
    if (l.wardName != null && l.wardName!.isNotEmpty) return l.wardName;
    if (l.latitude != null && l.longitude != null) {
      return '${l.latitude!.toStringAsFixed(5)}, ${l.longitude!.toStringAsFixed(5)}';
    }
    return null;
  }

  Widget _infoCard(ComplaintDetail c) {
    final category = CategoryVisual.of(c.category);
    final location = _locationText(c.location);
    return JanCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _infoRow(category.icon, 'Category', category.label),
          if (c.description != null && c.description!.isNotEmpty) _infoRow(Icons.notes_rounded, 'Description', c.description!),
          if (location != null) _infoRow(Icons.location_on_outlined, 'Location', location),
          if (c.corroborationCount > 0)
            _infoRow(Icons.groups_outlined, 'Also reported by', '${c.corroborationCount} other citizen(s)'),
          if (c.isReopened || c.isEscalated)
            Wrap(
              spacing: JanSpace.xs,
              children: [
                if (c.isReopened) _flagChip('Reopened', Icons.restart_alt_rounded, JanColors.primary),
                if (c.isEscalated) _flagChip('Escalated', Icons.priority_high_rounded, const Color(0xFF9A4E0E)),
              ],
            ),
          if (c.rejectionReasonCode != null) ...[
            const SizedBox(height: JanSpace.xs),
            JanBanner(tone: JanBannerTone.error, message: 'Rejection reason: ${c.rejectionReasonCode!}'),
          ],
        ],
      ),
    );
  }

  Widget _flagChip(String label, IconData icon, Color color) {
    return Chip(
      avatar: Icon(icon, size: 16, color: color),
      label: Text(label, style: TextStyle(fontSize: 12, color: color, fontWeight: FontWeight.w700)),
      backgroundColor: color.withValues(alpha: 0.10),
      side: BorderSide(color: color.withValues(alpha: 0.35)),
      visualDensity: VisualDensity.compact,
    );
  }

  // Gap-backlog Patch 29/42: photos with tap-to-zoom (ComplaintImage.viewUrl).
  Widget _photoGallery(ComplaintDetail c) {
    if (c.images.isEmpty) {
      return const JanCard(child: Text('No photos', style: TextStyle(color: JanColors.muted)));
    }
    return SizedBox(
      height: 128,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: c.images.length,
        separatorBuilder: (_, __) => const SizedBox(width: JanSpace.xs),
        itemBuilder: (context, i) {
          final image = c.images[i];
          final kind = image.imageType == 'AFTER' ? 'After-resolution' : 'Complaint';
          return Semantics(
            button: image.viewUrl != null,
            label: '$kind photo ${i + 1}. Open full screen',
            excludeSemantics: true,
            child: GestureDetector(
              onTap: image.viewUrl != null
                  ? () => _openFullScreenPhoto(context, c.images, i, c.aiClassification?.detections ?? const [])
                  : null,
              child: ClipRRect(
                borderRadius: JanRadius.mdAll,
                child: Stack(
                  children: [
                    if (image.viewUrl != null)
                      Image.network(
                        image.viewUrl!,
                        excludeFromSemantics: true,
                        width: 128,
                        height: 128,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => _photoPlaceholder(image),
                        loadingBuilder: (context, child, progress) =>
                            progress == null ? child : const Center(child: CircularProgressIndicator()),
                      )
                    else
                      _photoPlaceholder(image),
                    Positioned(
                      left: 6,
                      bottom: 6,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                        decoration: BoxDecoration(
                          color: image.imageType == 'AFTER' ? JanColors.teal : JanColors.navy,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text(
                          image.imageType,
                          style: const TextStyle(color: JanColors.white, fontSize: 10.5, fontWeight: FontWeight.w700),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _photoPlaceholder(ComplaintImage image) {
    return Container(
      width: 128,
      height: 128,
      color: JanColors.skeleton,
      alignment: Alignment.center,
      child: const Icon(Icons.image_not_supported_outlined, color: JanColors.muted),
    );
  }

  void _openFullScreenPhoto(BuildContext context, List<ComplaintImage> images, int initialIndex,
      [List<DetectedBox> detections = const []]) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => _FullScreenPhotoViewer(images: images, initialIndex: initialIndex, detections: detections),
    ));
  }

  // Gap-backlog Patch 30/43: AI explainability; Patch 31/44: friendly
  // message instead of a raw status code when the model was unavailable.
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
        color = JanColors.teal;
        icon = Icons.smart_toy_outlined;
        break;
      case 'MANUAL_REVIEW_REQUIRED':
        friendlyStatus = 'AI confidence is low - a human will verify this';
        color = JanColors.amberDark;
        icon = Icons.visibility_outlined;
        break;
      default:
        friendlyStatus = 'We could not analyze this photo automatically - '
            'it has been submitted for manual verification.';
        color = JanColors.slate;
        icon = Icons.info_outline;
    }
    final confidence = ai.confidence;
    return Padding(
      padding: const EdgeInsets.only(top: JanSpace.md),
      child: JanCard(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFE2F4F2), Color(0xFFF2F8FD)],
        ),
        borderColor: const Color(0xFFBFE3DF),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(icon, color: color, size: 22),
              const SizedBox(width: JanSpace.xs),
              const Text(
                'AI ANALYSIS',
                style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 0.9, color: JanColors.navy, fontSize: 13.5),
              ),
            ]),
            const SizedBox(height: JanSpace.xs),
            Text(friendlyStatus, style: TextStyle(fontWeight: FontWeight.w700, color: color, height: 1.4)),
            if (confidence != null) ...[
              const SizedBox(height: JanSpace.sm),
              Row(children: [
                const Text('AI Confidence: ', style: TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                Text('${confidence.toStringAsFixed(0)}%', style: const TextStyle(fontWeight: FontWeight.w600)),
              ]),
              const SizedBox(height: 6),
              ClipRRect(
                borderRadius: BorderRadius.circular(6),
                child: LinearProgressIndicator(
                  value: (confidence / 100).clamp(0.0, 1.0),
                  minHeight: 8,
                  color: color,
                  backgroundColor: JanColors.white,
                ),
              ),
            ],
            if (ai.duplicateFlagged) ...[
              const SizedBox(height: JanSpace.sm),
              const Text(
                'Flagged as a possible duplicate of an existing complaint',
                style: TextStyle(fontSize: 13, color: JanColors.slate),
              ),
            ],
          ],
        ),
      ),
    );
  }

  // Gap-backlog Patch 15: budget approval display. Read-only here - approve /
  // reject controls live on the authorized staff screens.
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
      padding: const EdgeInsets.only(top: JanSpace.md),
      child: JanCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _infoRow(Icons.account_balance_wallet_outlined, 'Estimated Budget', range),
            if (budget.estimatedResolutionDays != null)
              _infoRow(Icons.event_available_outlined, 'Estimated resolution', '${budget.estimatedResolutionDays} day(s)'),
            if (budget.approvalRequired)
              JanBanner(
                tone: budget.approved ? JanBannerTone.success : JanBannerTone.warning,
                message: budget.approved
                    ? 'Budget approved${budget.approvedByName != null ? ' by ${budget.approvedByName}' : ''}'
                    : 'Awaiting budget approval (exceeds the standard threshold)',
              ),
          ],
        ),
      ),
    );
  }

  Widget _confirmCard() {
    return Padding(
      padding: const EdgeInsets.only(top: JanSpace.lg),
      child: JanCard(
        borderColor: JanColors.tealBrand,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Is the issue fixed?', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16, color: JanColors.navy)),
            const SizedBox(height: 4),
            const Text(
              'Confirming closes the complaint. If it is not fixed, reopen it below with a reason.',
              style: TextStyle(color: JanColors.muted, height: 1.4),
            ),
            const SizedBox(height: JanSpace.md),
            FilledButton.icon(
              style: FilledButton.styleFrom(backgroundColor: JanColors.teal),
              onPressed: _confirming ? null : _confirmResolution,
              icon: const Icon(Icons.check_circle_outline),
              label: Text(AppStrings.of('action_confirm_resolution')),
            ),
          ],
        ),
      ),
    );
  }

  // Gap-backlog Patch 11/13: citizen resolution rating.
  Widget _ratingSection() {
    if (_existingRating != null) {
      final stars = _existingRating!['rating'] as int? ?? 0;
      return Padding(
        padding: const EdgeInsets.only(top: JanSpace.lg),
        child: JanCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Your rating', style: TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
              const SizedBox(height: 4),
              Semantics(
                label: '$stars out of 5 stars',
                excludeSemantics: true,
                child: Row(
                  children: List.generate(
                    5,
                    (i) => Icon(i < stars ? Icons.star_rounded : Icons.star_border_rounded, color: JanColors.amber, size: 26),
                  ),
                ),
              ),
              if ((_existingRating!['comment'] as String?)?.isNotEmpty == true)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(_existingRating!['comment'] as String),
                ),
            ],
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: JanSpace.lg),
      child: JanCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('How was the resolution?', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16, color: JanColors.navy)),
            const SizedBox(height: JanSpace.xs),
            Row(
              children: List.generate(5, (i) {
                final starValue = i + 1;
                final selected = _selectedRatingStars != null && starValue <= _selectedRatingStars!;
                return Semantics(
                  label: '$starValue star${starValue > 1 ? 's' : ''}',
                  button: true,
                  selected: selected,
                  child: IconButton(
                    icon: Icon(selected ? Icons.star_rounded : Icons.star_border_rounded, color: JanColors.amber, size: 32),
                    tooltip: '$starValue star${starValue == 1 ? '' : 's'}', // item 14: named for screen readers
                    onPressed: () => setState(() => _selectedRatingStars = starValue),
                  ),
                );
              }),
            ),
            if (_selectedRatingStars != null) ...[
              const SizedBox(height: JanSpace.xs),
              TextField(
                controller: _ratingCommentController,
                maxLines: 2,
                maxLength: 500,
                decoration: const InputDecoration(labelText: 'Optional comment'),
              ),
              const SizedBox(height: JanSpace.xs),
              FilledButton(
                onPressed: _submittingRating ? null : _submitRating,
                child: _submittingRating
                    ? const SizedBox(
                        height: 18,
                        width: 18,
                        child: CircularProgressIndicator(strokeWidth: 2, color: JanColors.white),
                      )
                    : const Text('Submit Rating'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  // Gap-backlog Patch 12/14: appeal after rejection.
  Widget _appealSection() {
    return Padding(
      padding: const EdgeInsets.only(top: JanSpace.lg),
      child: JanCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Disagree with this decision?', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16, color: JanColors.navy)),
            const SizedBox(height: JanSpace.sm),
            TextField(
              controller: _appealReasonController,
              maxLines: 3,
              maxLength: 1000,
              decoration: const InputDecoration(labelText: 'Why should this complaint be reconsidered?', alignLabelWithHint: true),
            ),
            const SizedBox(height: JanSpace.xs),
            OutlinedButton.icon(
              onPressed: _submittingAppeal ? null : _submitAppeal,
              icon: _submittingAppeal
                  ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.gavel_rounded),
              label: const Text('Appeal Rejection'),
            ),
          ],
        ),
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
