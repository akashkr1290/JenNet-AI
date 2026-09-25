import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/platform_status.dart';
import '../../../core/widgets/error_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_illustrations.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../../wards/models/ward.dart';
import '../../wards/wards_api.dart';
import '../pending_submission_sync.dart';
import '../picked_photo.dart';
import '../complaint_draft_service.dart';
import '../complaints_api.dart';
import 'complaint_detail_screen.dart';

/// SRS 15.1/17.2 (Complaint Submission Form): photo + description +
/// location. Category/severity are intentionally NOT fields here - both
/// are assigned later (AI Analysis Module, Phase 7, or the Phase 6 manual
/// Verification Team override), matching ComplaintCategory's own Javadoc
/// ("AI issue categories") and the SRS form spec, which doesn't list
/// category as citizen-entered.
class ComplaintSubmissionScreen extends StatefulWidget {
  const ComplaintSubmissionScreen({super.key});

  @override
  State<ComplaintSubmissionScreen> createState() => _ComplaintSubmissionScreenState();
}

class _ComplaintSubmissionScreenState extends State<ComplaintSubmissionScreen> {
  final _descriptionController = TextEditingController();
  // Post-UI gap fix: bytes + content type (PickedPhoto) instead of a
  // dart:io File, so picking, preview and upload also work on Flutter Web.
  PickedPhoto? _photo;
  bool _pickingPhoto = false;
  String? _photoError;
  Position? _position;
  bool _locating = false;
  bool _submitting = false;
  String? _error;

  // Gap-backlog Patch 9 (Sep 2026 audit): GPS fallback. The backend has
  // always supported a manual, coordinate-bearing location
  // (LocationSource.MANUAL_PIN - see LocationService's Javadoc); this
  // screen previously just never offered it and hard-blocked submission
  // instead. No map-picker package is available in pubspec.yaml
  // (geolocator only), so the fallback is a ward picker + manually-typed
  // approximate coordinates rather than a tap-to-pin map - still produces
  // the real lat/lng the backend requires, honestly labelled as
  // approximate.
  bool _manualFallback = false;
  List<Ward>? _wards;
  bool _loadingWards = false;
  Ward? _selectedWard;
  final _manualLatController = TextEditingController();
  final _manualLngController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _captureLocation();
    _restoreDraftIfAny();
    // Gap-backlog Patch 32/45 (Sep 2026 audit): auto-save on every edit -
    // see ComplaintDraftService's Javadoc-equivalent for exactly what is
    // and isn't persisted.
    _descriptionController.addListener(_saveDraft);
  }

  Future<void> _restoreDraftIfAny() async {
    final draft = await ComplaintDraftService.instance.load();
    if (draft == null || !mounted) return;
    // Gap-backlog Patch 45: restore the picked photo too, if the file still
    // exists (Android only - Web has no durable file path to restore from).
    final restored = await PickedPhoto.fromSavedPath(draft['photoPath'] as String?);
    if (!mounted) return;
    if (restored != null && _photo == null && restored.validationError == null) {
      setState(() => _photo = restored);
    }
    final description = draft['description'] as String?;
    if (description != null && description.isNotEmpty) {
      _descriptionController.text = description;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Restored your unsaved draft from earlier.')),
      );
    }
  }

  void _saveDraft() {
    ComplaintDraftService.instance.save(
      description: _descriptionController.text,
      latitude: _position?.latitude,
      longitude: _position?.longitude,
      photoPath: _photo?.path,
    );
  }

  @override
  void dispose() {
    _descriptionController.dispose();
    _manualLatController.dispose();
    _manualLngController.dispose();
    super.dispose();
  }

  Future<void> _captureLocation() async {
    setState(() {
      _locating = true;
      _error = null;
    });
    try {
      final serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        throw StateError('Location services are turned off.');
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) {
        throw StateError('Location permission was not granted.');
      }
      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.high,
      );
      setState(() {
        _position = position;
        _manualFallback = false;
      });
    } catch (e) {
      // SRS 15.5 Exceptions: submission without live GPS still needs a
      // real location, so this now offers the manual fallback instead of
      // just surfacing a blocking error.
      setState(() {
        _error = e is StateError ? e.message : 'Could not get your location: $e';
      });
      await _enableManualFallback();
    } finally {
      if (mounted) setState(() => _locating = false);
    }
  }

  Future<void> _enableManualFallback() async {
    setState(() => _manualFallback = true);
    if (_wards != null) return;
    setState(() => _loadingWards = true);
    try {
      final wards = await WardsApi.instance.listActiveWards();
      if (mounted) setState(() => _wards = wards);
    } catch (_) {
      // Ward list is a convenience (helps staff route the complaint) -
      // manual lat/lng entry alone is still enough to submit, so a failed
      // ward fetch here is not itself a blocking error.
    } finally {
      if (mounted) setState(() => _loadingWards = false);
    }
  }

  Future<void> _pickPhoto(ImageSource source) async {
    if (_pickingPhoto) return;
    setState(() => _pickingPhoto = true);
    try {
      // Android: camera / gallery. Web: the browser's file chooser (with the
      // camera offered by mobile browsers for ImageSource.camera).
      // Audit GAP-009: a full-resolution 12 MP photo made AI analysis exceed
      // its time limit. 1600 px on the longer side keeps ample detail (the AI
      // model works at 640 px) and stays above the 480p minimum (SRS 17.2).
      final picked = await ImagePicker().pickImage(
        source: source,
        imageQuality: 85,
        maxWidth: 1600,
        maxHeight: 1600,
      );
      if (picked == null) return;
      final photo = await PickedPhoto.fromXFile(picked);
      final problem = photo.validationError;
      if (!mounted) return;
      if (problem != null) {
        // Keep any previously selected (valid) photo; just explain the rejection.
        setState(() => _photoError = problem);
        return;
      }
      setState(() {
        _photo = photo;
        _photoError = null;
        if (_error == 'A photo is required.') _error = null;
      });
      _saveDraft();
    } catch (e) {
      if (mounted) {
        setState(() => _photoError = source == ImageSource.camera
            ? 'Could not open the camera. Check the camera permission, or choose a photo instead.'
            : 'Could not open the selected photo. Please try another one.');
      }
    } finally {
      if (mounted) setState(() => _pickingPhoto = false);
    }
  }

  // UI redesign: remove the selected photo (the draft is re-saved without it).
  void _removePhoto() {
    setState(() {
      _photo = null;
      _photoError = null;
    });
    _saveDraft();
  }

  Future<void> _submit() async {
    if (_photo == null) {
      setState(() => _error = 'A photo is required.');
      return;
    }
    final photo = _photo!;
    final photoProblem = photo.validationError;
    if (photoProblem != null) {
      setState(() => _error = photoProblem);
      return;
    }

    double? latitude = _position?.latitude;
    double? longitude = _position?.longitude;
    if (_manualFallback) {
      // Remaining-gaps item 3: choosing a ward is enough when GPS is unavailable -
      // coordinates are optional; without them the server stores an approximate
      // ward location (WARD_FALLBACK).
      final latText = _manualLatController.text.trim();
      final lngText = _manualLngController.text.trim();
      if (latText.isEmpty && lngText.isEmpty) {
        latitude = null;
        longitude = null;
        if (_selectedWard == null) {
          setState(() => _error = 'Choose your ward (or enter approximate coordinates) to submit without GPS.');
          return;
        }
      } else {
        latitude = double.tryParse(latText);
        longitude = double.tryParse(lngText);
        if (latitude == null || longitude == null) {
          setState(() => _error = 'Enter both coordinates as numbers, or leave both empty and choose your ward.');
          return;
        }
      }
    }
    if (!_manualFallback && (latitude == null || longitude == null)) {
      setState(() => _error = 'Location is required. Please retry location capture.');
      return;
    }

    final locationSource = !_manualFallback
        ? 'DEVICE_GPS'
        : (latitude == null ? 'WARD_FALLBACK' : 'MANUAL_PIN');
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final complaint = await ComplaintsApi.instance.submit(
        photo: photo.upload,
        description: _descriptionController.text.trim(),
        latitude: latitude,
        longitude: longitude,
        wardId: _manualFallback ? _selectedWard?.wardId : null,
        locationSource: locationSource,
      );
      // UI redesign: reset the form for the next report (without re-saving an
      // empty draft), then show the reference success dialog. "Track Status"
      // opens the detail page ON TOP of the citizen shell - the previous
      // pushReplacement replaced the whole shell, so Back left the app.
      _descriptionController.removeListener(_saveDraft);
      _descriptionController.clear();
      _descriptionController.addListener(_saveDraft);
      await ComplaintDraftService.instance.clear();
      if (!mounted) return;
      setState(() => _photo = null);
      await showJanSuccessDialog(
        context,
        title: 'Report Submitted',
        message: 'Complaint ${complaint.referenceNumber} has been lodged and is queued for review. '
            'You can track its progress at any time.',
        primaryLabel: 'Track Status',
        onPrimary: () {
          if (!mounted) return;
          Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ComplaintDetailScreen(complaintId: complaint.complaintId)),
          );
        },
        secondaryLabel: 'Done',
      );
    } on ApiException catch (e) {
      // Audit GAP-037 (SRS 15.1 Exceptions): during a maintenance window the
      // submission is queued and retried automatically, like an offline one.
      if (isMaintenanceRefusal(e) && photo.path != null) {
        await PendingSubmissionSync.instance.queue(
          photoPath: photo.path!,
          description: _descriptionController.text.trim(),
          latitude: latitude,
          longitude: longitude,
          wardId: _manualFallback ? _selectedWard?.wardId : null,
          locationSource: locationSource,
        );
        PendingSubmissionSync.instance.start();
        if (mounted) {
          setState(() => _error = '${e.message} Your complaint is saved and will be sent automatically '
              'when maintenance ends.');
        }
        return;
      }
      setState(() => _error = e.message);
    } catch (e) {
      // Gap-backlog Patch 45: no server response at all (offline/timeout) -
      // queue the full submission for automatic upload when back online.
      // The queue stores the photo's file path, which only exists on
      // Android; on Web the photo stays selected and the citizen retries.
      final photoPath = photo.path;
      if (photoPath == null) {
        if (mounted) {
          setState(() => _error = 'Could not reach the server. Check your connection and press Submit again - '
              'your photo and details are still here.');
        }
        return;
      }
      await PendingSubmissionSync.instance.queue(
        photoPath: photoPath,
        description: _descriptionController.text.trim(),
        latitude: latitude,
        longitude: longitude,
        wardId: _manualFallback ? _selectedWard?.wardId : null,
        locationSource: locationSource,
      );
      PendingSubmissionSync.instance.start();
      if (!mounted) return;
      setState(() => _error = 'No connection right now. Your complaint is saved and will upload '
          'automatically when you are back online.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  // UI redesign: reference "Report an Issue" step layout (Photo, Description,
  // Location, Submit). The page heading is rendered by the citizen shell.
  @override
  Widget build(BuildContext context) {
    final photoStep = _PhotoStep(
      photo: _photo,
      uploading: _submitting,
      picking: _pickingPhoto,
      error: _photoError,
      onPick: _pickPhoto,
      onRemove: _removePhoto,
    );
    const aiNote = JanBanner(
      tone: JanBannerTone.info,
      icon: Icons.auto_awesome_outlined,
      message: 'After you submit, JanNet AI analyses the photo to identify the issue '
          'and route it to the right department - no need to pick a category.',
    );
    final descriptionStep = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 2, title: 'Description'),
        TextField(
          controller: _descriptionController,
          maxLength: 500,
          maxLines: 4,
          textCapitalization: TextCapitalization.sentences,
          decoration: const InputDecoration(
            labelText: 'Description (optional)',
            alignLabelWithHint: true,
            hintText: 'What\'s the issue? Any details that would help.',
          ),
        ),
      ],
    );
    final locationStep = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 3, title: 'Location'),
        if (!_manualFallback)
          _LocationStatus(
            locating: _locating,
            position: _position,
            onRetry: _captureLocation,
            onUseManual: _enableManualFallback,
          )
        else
          _ManualLocationPicker(
            wards: _wards,
            loadingWards: _loadingWards,
            selectedWard: _selectedWard,
            onWardChanged: (w) => setState(() => _selectedWard = w),
            latController: _manualLatController,
            lngController: _manualLngController,
            onTryGpsAgain: _captureLocation,
          ),
      ],
    );
    final submitStep = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 4, title: 'Submit'),
        if (_error != null) ...[
          ErrorText(_error!),
          const SizedBox(height: JanSpace.md),
        ],
        FilledButton.icon(
          onPressed: _submitting ? null : _submit,
          icon: _submitting
              ? const SizedBox(
                  height: 20,
                  width: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.4, color: JanColors.white),
                )
              : const Icon(Icons.send_rounded),
          label: Text(_submitting ? 'Submitting...' : 'Submit Complaint'),
        ),
      ],
    );

    return LayoutBuilder(builder: (context, constraints) {
      final wide = constraints.maxWidth >= 860;
      return SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
        child: wide
            ? Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [photoStep, const SizedBox(height: JanSpace.md), aiNote],
                    ),
                  ),
                  const SizedBox(width: JanSpace.xl),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [descriptionStep, locationStep, const SizedBox(height: JanSpace.sm), submitStep],
                    ),
                  ),
                ],
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  photoStep,
                  const SizedBox(height: JanSpace.md),
                  aiNote,
                  descriptionStep,
                  locationStep,
                  const SizedBox(height: JanSpace.sm),
                  submitStep,
                ],
              ),
      );
    });
  }
}

class _StepHeader extends StatelessWidget {
  final int number;
  final String title;
  const _StepHeader({required this.number, required this.title});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: JanSpace.lg, bottom: JanSpace.sm),
      child: Semantics(
        header: true,
        label: 'Step $number: $title',
        excludeSemantics: true,
        child: Row(
          children: [
            Container(
              width: 26,
              height: 26,
              alignment: Alignment.center,
              decoration: const BoxDecoration(color: JanColors.navy, shape: BoxShape.circle),
              child: Text('$number', style: const TextStyle(color: JanColors.white, fontWeight: FontWeight.w800, fontSize: 13)),
            ),
            const SizedBox(width: JanSpace.xs),
            Text(
              title.toUpperCase(),
              style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w800, letterSpacing: 0.9, color: JanColors.navy),
            ),
          ],
        ),
      ),
    );
  }
}

class _PhotoStep extends StatelessWidget {
  final PickedPhoto? photo;
  final bool uploading;
  final bool picking;
  final String? error;
  final void Function(ImageSource) onPick;
  final VoidCallback onRemove;

  const _PhotoStep({
    required this.photo,
    required this.uploading,
    required this.picking,
    required this.error,
    required this.onPick,
    required this.onRemove,
  });

  static String _size(int bytes) =>
      bytes >= 1024 * 1024 ? '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB' : '${(bytes / 1024).ceil()} KB';

  @override
  Widget build(BuildContext context) {
    // Post-UI gap fix: Web now has a real photo flow. image_picker's web
    // implementation opens the browser file chooser; for the camera source
    // it adds the HTML `capture` hint, so phone browsers open the camera and
    // desktop browsers fall back to the file chooser. Android keeps the
    // native camera and gallery.
    final cameraLabel = kIsWeb ? 'Use Camera' : 'Camera';
    final galleryLabel = kIsWeb ? 'Choose Photo' : 'Gallery';
    final busy = uploading || picking;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 1, title: 'Photo'),
        if (photo == null)
          JanCard(
            padding: const EdgeInsets.all(JanSpace.lg),
            child: Column(
              children: [
                const JanStateIllustration(icon: Icons.photo_camera_outlined, color: JanColors.primary, size: 112),
                const SizedBox(height: JanSpace.sm),
                const Text(
                  'Add a photo of the issue',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: JanColors.navy),
                ),
                const SizedBox(height: 4),
                Text(
                  kIsWeb
                      ? 'Choose a JPEG, PNG or WEBP photo (up to 10 MB) from this device.'
                      : 'A clear photo helps the AI and officers understand the problem.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: JanColors.muted),
                ),
                const SizedBox(height: JanSpace.md),
                if (picking)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: JanSpace.xs),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                        SizedBox(width: JanSpace.sm),
                        Text('Opening photo...', style: TextStyle(fontWeight: FontWeight.w600)),
                      ],
                    ),
                  )
                else
                  Wrap(
                    alignment: WrapAlignment.center,
                    spacing: JanSpace.sm,
                    runSpacing: JanSpace.sm,
                    children: [
                      // Web: the file chooser is the primary action.
                      if (kIsWeb)
                        _pickButton(galleryLabel, Icons.upload_file_rounded, ImageSource.gallery, primary: true,
                            semantic: 'Choose photo from this device'),
                      _pickButton(cameraLabel, Icons.camera_alt_outlined, ImageSource.camera, primary: !kIsWeb,
                          semantic: 'Take photo with camera'),
                      if (!kIsWeb)
                        _pickButton(galleryLabel, Icons.photo_library_outlined, ImageSource.gallery,
                            primary: false, semantic: 'Choose photo from gallery'),
                    ],
                  ),
              ],
            ),
          )
        else
          JanCard(
            padding: const EdgeInsets.all(JanSpace.xs),
            borderColor: JanColors.tealBrand,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ClipRRect(
                  borderRadius: JanRadius.mdAll,
                  child: Stack(
                    children: [
                      // Image.memory renders the same bytes that will be
                      // uploaded, on every platform.
                      Image.memory(
                        photo!.upload.bytes,
                        height: 230,
                        width: double.infinity,
                        fit: BoxFit.cover,
                        gaplessPlayback: true,
                        semanticLabel: 'Selected complaint photo',
                        errorBuilder: (_, __, ___) => Container(
                          height: 230,
                          color: JanColors.surfaceAlt,
                          alignment: Alignment.center,
                          child: const Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.broken_image_outlined, size: 40, color: JanColors.muted),
                              SizedBox(height: 6),
                              Text('Preview not available for this photo', style: TextStyle(color: JanColors.muted)),
                            ],
                          ),
                        ),
                      ),
                      if (uploading)
                        Positioned.fill(
                          child: Container(
                            color: const Color(0x99203A5F),
                            alignment: Alignment.center,
                            child: const Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                CircularProgressIndicator(color: JanColors.white),
                                SizedBox(height: JanSpace.sm),
                                Text('Uploading...', style: TextStyle(color: JanColors.white, fontWeight: FontWeight.w700)),
                              ],
                            ),
                          ),
                        )
                      else
                        Positioned(
                          top: 8,
                          right: 8,
                          child: Material(
                            color: const Color(0xB3203A5F),
                            shape: const CircleBorder(),
                            child: IconButton(
                              tooltip: 'Remove photo',
                              color: JanColors.white,
                              icon: const Icon(Icons.close_rounded),
                              onPressed: picking ? null : onRemove,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(JanSpace.xs, JanSpace.xs, JanSpace.xs, 2),
                  child: Wrap(
                    spacing: JanSpace.xs,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      const Icon(Icons.check_circle_rounded, color: JanColors.teal, size: 18),
                      Text(
                        'Photo added · ${_size(photo!.upload.bytes.length)}',
                        style: const TextStyle(color: JanColors.teal, fontWeight: FontWeight.w700),
                      ),
                      TextButton.icon(
                        onPressed: busy ? null : () => onPick(ImageSource.camera),
                        icon: const Icon(Icons.camera_alt_outlined, size: 18),
                        label: const Text('Retake'),
                      ),
                      TextButton.icon(
                        onPressed: busy ? null : () => onPick(ImageSource.gallery),
                        icon: Icon(kIsWeb ? Icons.upload_file_rounded : Icons.photo_library_outlined, size: 18),
                        label: const Text('Replace'),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        if (error != null) ...[
          const SizedBox(height: JanSpace.sm),
          ErrorText(error!),
        ],
      ],
    );
  }

  Widget _pickButton(String label, IconData icon, ImageSource source, {required bool primary, required String semantic}) {
    final onPressed = uploading ? null : () => onPick(source);
    return Semantics(
      label: semantic,
      button: true,
      excludeSemantics: true,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 140),
        child: primary
            ? FilledButton.icon(onPressed: onPressed, icon: Icon(icon), label: Text(label))
            : OutlinedButton.icon(onPressed: onPressed, icon: Icon(icon), label: Text(label)),
      ),
    );
  }
}

class _LocationStatus extends StatelessWidget {
  final bool locating;
  final Position? position;
  final VoidCallback onRetry;
  final VoidCallback onUseManual;
  const _LocationStatus({
    required this.locating,
    required this.position,
    required this.onRetry,
    required this.onUseManual,
  });

  @override
  Widget build(BuildContext context) {
    if (locating) {
      return JanCard(
        child: Semantics(
          liveRegion: true,
          child: const Row(children: [
            SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)),
            SizedBox(width: JanSpace.sm),
            Text('Getting your location...', style: TextStyle(fontWeight: FontWeight.w600)),
          ]),
        ),
      );
    }
    if (position == null) {
      return JanCard(
        borderColor: JanColors.amber,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Row(children: [
            Icon(Icons.location_off_outlined, color: JanColors.amberDark),
            SizedBox(width: JanSpace.xs),
            Text('Location not available', style: TextStyle(color: JanColors.amberDark, fontWeight: FontWeight.w700)),
          ]),
          const SizedBox(height: 4),
          Wrap(spacing: JanSpace.xs, children: [
            TextButton.icon(onPressed: onRetry, icon: const Icon(Icons.my_location_rounded, size: 18), label: const Text('Retry GPS')),
            TextButton.icon(onPressed: onUseManual, icon: const Icon(Icons.edit_location_alt_outlined, size: 18), label: const Text('Enter location manually')),
          ]),
        ]),
      );
    }
    return JanCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.check_circle_rounded, size: 20, color: JanColors.teal),
            const SizedBox(width: 6),
            const Text('GPS detected', style: TextStyle(color: JanColors.teal, fontWeight: FontWeight.w700)),
            const Spacer(),
            IconButton(onPressed: onRetry, icon: const Icon(Icons.refresh_rounded), tooltip: 'Refresh location'),
          ]),
          Row(children: [
            const Icon(Icons.location_on_outlined, size: 18, color: JanColors.muted),
            const SizedBox(width: 4),
            Expanded(
              child: Text(
                '${position!.latitude.toStringAsFixed(6)}, ${position!.longitude.toStringAsFixed(6)}',
                style: const TextStyle(fontWeight: FontWeight.w600, color: JanColors.slate),
              ),
            ),
          ]),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(onPressed: onUseManual, child: const Text('Enter manually instead')),
          ),
        ],
      ),
    );
  }
}

/// Gap-backlog Patch 9 (Sep 2026 audit): manual location entry used when
/// live GPS is unavailable/denied. Choosing a ward alone is enough (the
/// server stores an approximate ward location); approximate coordinates can
/// be added (LocationSource.MANUAL_PIN).
class _ManualLocationPicker extends StatelessWidget {
  final List<Ward>? wards;
  final bool loadingWards;
  final Ward? selectedWard;
  final ValueChanged<Ward?> onWardChanged;
  final TextEditingController latController;
  final TextEditingController lngController;
  final VoidCallback onTryGpsAgain;

  const _ManualLocationPicker({
    required this.wards,
    required this.loadingWards,
    required this.selectedWard,
    required this.onWardChanged,
    required this.latController,
    required this.lngController,
    required this.onTryGpsAgain,
  });

  @override
  Widget build(BuildContext context) {
    return JanCard(
      borderColor: JanColors.amber,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const JanBanner(
            tone: JanBannerTone.warning,
            icon: Icons.location_searching_rounded,
            // Audit GAP-031: without coordinates the server first uses the GPS
            // position the camera stored in the photo (EXIF), then the ward.
            message: 'GPS is unavailable. Choosing your ward below is enough to submit. '
                'If the photo was taken with location tagging on, its position is used instead. '
                'If you know your approximate coordinates you can add them too.',
          ),
          const SizedBox(height: JanSpace.md),
          if (loadingWards)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: LinearProgressIndicator(),
            )
          else if (wards != null && wards!.isNotEmpty)
            DropdownButtonFormField<Ward>(
              initialValue: selectedWard,
              isExpanded: true,
              decoration: const InputDecoration(
                labelText: 'Your ward (required if coordinates are left empty)',
                prefixIcon: Icon(Icons.map_outlined),
              ),
              items: wards!
                  .map((w) => DropdownMenuItem(value: w, child: Text(w.name)))
                  .toList(),
              onChanged: onWardChanged,
            ),
          const SizedBox(height: JanSpace.sm),
          Row(children: [
            Expanded(
              child: TextField(
                controller: latController,
                keyboardType: const TextInputType.numberWithOptions(signed: true, decimal: true),
                decoration: const InputDecoration(labelText: 'Latitude'),
              ),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: TextField(
                controller: lngController,
                keyboardType: const TextInputType.numberWithOptions(signed: true, decimal: true),
                decoration: const InputDecoration(labelText: 'Longitude'),
              ),
            ),
          ]),
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: onTryGpsAgain,
              icon: const Icon(Icons.my_location_rounded, size: 18),
              label: const Text('Try GPS again'),
            ),
          ),
        ],
      ),
    );
  }
}
