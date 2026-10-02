import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
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
import '../location/device_position.dart';
import '../location/incident_location.dart';
import '../location/incident_location_picker_screen.dart';
import '../location/incident_map.dart';
import '../location/location_policy.dart';
import '../location/photo_intake.dart';
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
///
/// V33 - INCIDENT LOCATION != SUBMISSION LOCATION. The location step asks
/// "Where is the problem?": the app proposes the location from the photo
/// (GPS read right after an in-app camera capture, else the photo's EXIF GPS)
/// and the citizen confirms it on an OpenStreetMap map - one tap when it is
/// right - or places the pin by hand. The phone's position when this screen
/// opens or when Submit is pressed is never used as the incident location.
/// "Save & Report Later" keeps the photo, its location and its time together.
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
  bool _submitting = false;
  bool _savingForLater = false;
  String? _error;

  // V33 incident location.
  IncidentLocation _location = IncidentLocation.none;
  String? _locationNote;
  bool _stillThere = false;
  bool _savedForLater = false;

  // Ward fallback - only when the map cannot be used (server stores an
  // approximate ward location, LocationSource.WARD_FALLBACK).
  bool _wardMode = false;
  List<Ward>? _wards;
  bool _loadingWards = false;

  @override
  void initState() {
    super.initState();
    _restoreDraftIfAny();
    // Gap-backlog Patch 32/45 (Sep 2026 audit): auto-save on every edit.
    _descriptionController.addListener(_saveDraft);
  }

  Future<void> _restoreDraftIfAny() async {
    final draft = await ComplaintDraftService.instance.load();
    if (draft == null || draft.isEmpty || !mounted) return;
    setState(() {
      if (_photo == null && draft.photo != null && draft.photo!.validationError == null) {
        _photo = draft.photo;
        _location = draft.location;
        _stillThere = draft.stillThere;
      }
      _savedForLater = draft.savedForLater;
    });
    final description = draft.description;
    if (description != null && description.isNotEmpty && _descriptionController.text.isEmpty) {
      _descriptionController.text = description;
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(draft.savedForLater
            ? 'Your saved report is ready to finish. The problem location from the photo is kept.'
            : 'Restored your unsaved draft from earlier.'),
      ),
    );
  }

  void _saveDraft() {
    ComplaintDraftService.instance.save(
      description: _descriptionController.text,
      location: _location,
      stillThere: _stillThere,
      savedForLater: _savedForLater,
    );
  }

  @override
  void dispose() {
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _pickPhoto(ImageSource source) async {
    if (_pickingPhoto) return;
    setState(() => _pickingPhoto = true);
    try {
      final intake = await PhotoIntakeService.takeOrPick(source);
      if (intake == null) return;
      final problem = intake.photo.validationError;
      if (!mounted) return;
      if (problem != null) {
        // Keep any previously selected (valid) photo; just explain the rejection.
        setState(() => _photoError = problem);
        return;
      }
      var location = intake.location;
      if (location.detected == null && _location.isConfirmed) {
        // A replacement photo without a location keeps the place already confirmed.
        location = IncidentLocation(
          photoTakenAt: location.photoTakenAt,
          confirmed: _location.confirmed,
          fallbackWardId: _location.fallbackWardId,
          fallbackWardName: _location.fallbackWardName,
        );
      }
      setState(() {
        _photo = intake.photo;
        _photoError = null;
        _location = location;
        _locationNote = location.isConfirmed ? null : intake.note;
        _stillThere = false;
        _wardMode = false;
        if (_error == 'A photo is required.') _error = null;
      });
      _saveDraft();
      await ComplaintDraftService.instance.savePhoto(intake.photo);
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
      // The photo's own location and time go with it; a place the citizen
      // already confirmed stays.
      _location = _location.isConfirmed
          ? IncidentLocation(
              confirmed: _location.confirmed,
              fallbackWardId: _location.fallbackWardId,
              fallbackWardName: _location.fallbackWardName,
            )
          : IncidentLocation.none;
      _locationNote = null;
      _stillThere = false;
    });
    _saveDraft();
    ComplaintDraftService.instance.savePhoto(null);
  }

  // ---- Incident location ----

  /// One-tap confirmation of the automatic location.
  void _confirmDetected() {
    final detected = _location.detected;
    if (detected == null) return;
    setState(() {
      _location = _location.confirm(detected.point);
      _locationNote = null;
      if (_error != null && _error!.startsWith('Please confirm')) _error = null;
    });
    _saveDraft();
  }

  Future<void> _openMap() async {
    final detected = _location.detected;
    final note = detected != null && detected.isLowAccuracy && !_location.isConfirmed
        ? 'Your phone could only find an approximate location for this photo. '
            'Please move the map so the red pin is exactly on the problem.'
        : null;
    final point = await Navigator.of(context).push<GeoPoint>(
      MaterialPageRoute(
        builder: (_) => IncidentLocationPickerScreen(
          initial: _location.proposedPoint,
          detected: detected?.point,
          note: note,
        ),
      ),
    );
    if (point == null || !mounted) return;
    setState(() {
      _location = _location.confirm(point);
      _locationNote = null;
      _wardMode = false;
      if (_error != null && _error!.startsWith('Please confirm')) _error = null;
    });
    _saveDraft();
  }

  Future<void> _useWardInstead() async {
    setState(() => _wardMode = true);
    if (_wards != null) return;
    setState(() => _loadingWards = true);
    try {
      final wards = await WardsApi.instance.listActiveWards();
      if (mounted) setState(() => _wards = wards);
    } catch (_) {
      if (mounted) setState(() => _wards = const []);
    } finally {
      if (mounted) setState(() => _loadingWards = false);
    }
  }

  void _chooseWard(Ward? ward) {
    if (ward == null) return;
    setState(() {
      _location = _location.useWard(ward.wardId, ward.name);
      _locationNote = null;
      if (_error != null && _error!.startsWith('Please confirm')) _error = null;
    });
    _saveDraft();
  }

  void _changeLocation() {
    setState(() {
      _location = _location.unconfirmed();
      _wardMode = false;
    });
    _saveDraft();
    _openMap();
  }

  // ---- Save & Report Later ----

  Future<bool> _keepForLater() async {
    _savedForLater = true;
    await ComplaintDraftService.instance.save(
      description: _descriptionController.text,
      location: _location,
      stillThere: _stillThere,
      savedForLater: true,
    );
    return ComplaintDraftService.instance.savePhoto(_photo);
  }

  Future<void> _saveForLater() async {
    if (_photo == null) {
      setState(() => _error = 'Add a photo first - it is saved together with where it was taken.');
      return;
    }
    setState(() {
      _savingForLater = true;
      _error = null;
    });
    final photoKept = await _keepForLater();
    if (!mounted) return;
    setState(() => _savingForLater = false);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(photoKept
          ? 'Saved on this device. Open "Report an Issue" within '
              '${LocationPolicy.veryOldPhotoDays} days to finish it - the photo and the place it was taken are kept.'
          : 'Your details are saved, but this photo could not be stored on this device. '
              'Keep this screen open or add the photo again later.'),
    ));
  }

  // ---- Submit ----

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
    if (!_location.isConfirmed) {
      setState(() => _error = 'Please confirm where the problem is on the map.');
      return;
    }
    if (_location.isVeryOld(DateTime.now()) && !_stillThere) {
      setState(() => _error = 'This photo is more than ${LocationPolicy.veryOldPhotoDays} days old. '
          'Please tick "The problem is still there" (or take a new photo).');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });

    // Optional, privacy-preserving: only the DISTANCE between the problem and
    // where the citizen is now - and only if location permission was already
    // given (no prompt). The citizen's own position is never sent.
    double? submissionDistance;
    final confirmedPoint = _location.confirmed;
    if (confirmedPoint != null) {
      final here = await DevicePosition.ifAlreadyAllowed();
      if (here.ok) submissionDistance = distanceMeters(here.point!, confirmedPoint);
    }
    final locationFields = _location.toApiFields(submissionDistanceMeters: submissionDistance);
    final description = _descriptionController.text.trim();

    try {
      final complaint = await ComplaintsApi.instance.submit(
        photo: photo.upload,
        description: description,
        locationFields: locationFields,
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
      setState(() {
        _photo = null;
        _location = IncidentLocation.none;
        _locationNote = null;
        _stillThere = false;
        _savedForLater = false;
        _wardMode = false;
      });
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
      // submission is kept and sent later, like an offline one.
      if (isMaintenanceRefusal(e)) {
        final queued = await _queueOrKeep(photo, description, locationFields);
        if (mounted) {
          setState(() => _error = queued
              ? '${e.message} Your complaint is saved and will be sent automatically when maintenance ends.'
              : '${e.message} Your report is saved on this device - press Submit again when maintenance ends.');
        }
        return;
      }
      if (mounted) setState(() => _error = e.message);
    } catch (e) {
      // Gap-backlog Patch 45: no server response at all (offline/timeout).
      final queued = await _queueOrKeep(photo, description, locationFields);
      if (!mounted) return;
      setState(() => _error = queued
          ? 'No connection right now. Your complaint is saved and will upload automatically when you are back online.'
          : 'Could not reach the server. Your report (photo and problem location) is saved on this device - '
              'press Submit again when you are back online.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// Android: queue for automatic upload (the confirmed incident location is
  /// sent unchanged later). Web (no durable file path): keep the full draft.
  Future<bool> _queueOrKeep(PickedPhoto photo, String description, Map<String, String> locationFields) async {
    final photoPath = photo.path;
    if (photoPath != null && (await PickedPhoto.fromSavedPath(photoPath)) != null) {
      await PendingSubmissionSync.instance.queue(
        photoPath: photoPath,
        description: description,
        locationFields: locationFields,
      );
      PendingSubmissionSync.instance.start();
      return true;
    }
    await _keepForLater();
    return false;
  }

  // UI redesign: reference "Report an Issue" step layout (Photo, Description,
  // Location, Submit). The page heading is rendered by the citizen shell.
  @override
  Widget build(BuildContext context) {
    final busy = _submitting || _savingForLater;
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
        const _StepHeader(number: 3, title: 'Where is the problem?'),
        _IncidentLocationStep(
          location: _location,
          note: _locationNote,
          hasPhoto: _photo != null,
          stillThere: _stillThere,
          onStillThereChanged: (v) {
            setState(() => _stillThere = v);
            _saveDraft();
          },
          onConfirmDetected: _confirmDetected,
          onOpenMap: _openMap,
          onChange: _changeLocation,
          wardMode: _wardMode,
          wards: _wards,
          loadingWards: _loadingWards,
          onUseWard: _useWardInstead,
          onWardChosen: _chooseWard,
          onBackToMap: () => setState(() => _wardMode = false),
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
          onPressed: busy ? null : _submit,
          icon: _submitting
              ? const SizedBox(
                  height: 20,
                  width: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.4, color: JanColors.white),
                )
              : const Icon(Icons.send_rounded),
          label: Text(_submitting ? 'Submitting...' : 'Report Now'),
        ),
        const SizedBox(height: JanSpace.xs),
        OutlinedButton.icon(
          onPressed: busy || _photo == null ? null : _saveForLater,
          icon: const Icon(Icons.bookmark_add_outlined),
          label: Text(_savingForLater ? 'Saving...' : 'Save & Report Later'),
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

/// V33: "Where is the problem?" - the mandatory incident-location
/// confirmation. Citizens see a map and plain words only; sources, accuracy
/// and flags are for staff.
class _IncidentLocationStep extends StatelessWidget {
  final IncidentLocation location;
  final String? note;
  final bool hasPhoto;
  final bool stillThere;
  final ValueChanged<bool> onStillThereChanged;
  final VoidCallback onConfirmDetected;
  final VoidCallback onOpenMap;
  final VoidCallback onChange;
  final bool wardMode;
  final List<Ward>? wards;
  final bool loadingWards;
  final VoidCallback onUseWard;
  final ValueChanged<Ward?> onWardChosen;
  final VoidCallback onBackToMap;

  const _IncidentLocationStep({
    required this.location,
    required this.note,
    required this.hasPhoto,
    required this.stillThere,
    required this.onStillThereChanged,
    required this.onConfirmDetected,
    required this.onOpenMap,
    required this.onChange,
    required this.wardMode,
    required this.wards,
    required this.loadingWards,
    required this.onUseWard,
    required this.onWardChosen,
    required this.onBackToMap,
  });

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final age = location.photoAgeDays(now);
    final children = <Widget>[];

    if (location.isStale(now)) {
      children.add(JanBanner(
        tone: JanBannerTone.warning,
        icon: Icons.history_rounded,
        message: 'This photo was taken $age days ago. If the problem is still there you can still report it.',
      ));
      if (location.isVeryOld(now)) {
        children.add(CheckboxListTile(
          value: stillThere,
          onChanged: (v) => onStillThereChanged(v ?? false),
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          title: const Text('The problem is still there'),
        ));
      }
      children.add(const SizedBox(height: JanSpace.sm));
    }

    if (location.confirmed != null) {
      children.addAll([
        IncidentMapPreview(point: location.confirmed!, detected: location.detected?.point),
        const SizedBox(height: JanSpace.xs),
        _ConfirmedRow(text: 'Problem location confirmed', onChange: onChange),
      ]);
    } else if (location.fallbackWardId != null) {
      children.addAll([
        JanCard(
          child: Row(children: [
            const Icon(Icons.map_outlined, color: JanColors.navy),
            const SizedBox(width: JanSpace.xs),
            Expanded(
              child: Text('Approximate location: ${location.fallbackWardName ?? 'your ward'}',
                  style: const TextStyle(fontWeight: FontWeight.w600, color: JanColors.slate)),
            ),
          ]),
        ),
        const SizedBox(height: JanSpace.xs),
        _ConfirmedRow(text: 'Ward chosen', onChange: onChange, changeLabel: 'Use the map instead'),
      ]);
    } else if (wardMode) {
      children.add(_WardFallback(
        wards: wards,
        loading: loadingWards,
        onChosen: onWardChosen,
        onBackToMap: onBackToMap,
      ));
    } else if (location.detected != null) {
      final detected = location.detected!;
      children.addAll([
        IncidentMapPreview(point: detected.point),
        const SizedBox(height: JanSpace.xs),
        if (detected.isLowAccuracy) ...[
          const JanBanner(
            tone: JanBannerTone.warning,
            icon: Icons.gps_not_fixed_rounded,
            message: 'Your phone could only find an approximate location. Please check the pin on the map.',
          ),
          const SizedBox(height: JanSpace.xs),
          FilledButton.tonalIcon(
            onPressed: onOpenMap,
            icon: const Icon(Icons.edit_location_alt_outlined),
            label: const Text('Check on map'),
          ),
        ] else ...[
          Text(
            detected.source == DetectedSource.captureGps
                ? 'This is where you took the photo. Is the problem here?'
                : 'This is where the photo was taken. Is the problem here?',
            style: const TextStyle(color: JanColors.slate, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: JanSpace.xs),
          FilledButton.tonalIcon(
            onPressed: onConfirmDetected,
            icon: const Icon(Icons.check_rounded),
            label: const Text('Confirm Location'),
          ),
          TextButton.icon(
            onPressed: onOpenMap,
            icon: const Icon(Icons.edit_location_alt_outlined, size: 18),
            label: const Text('Change on map'),
          ),
        ],
      ]);
    } else {
      children.addAll([
        JanCard(
          borderColor: note != null ? JanColors.amber : null,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                const Icon(Icons.place_outlined, color: JanColors.navy),
                const SizedBox(width: JanSpace.xs),
                Expanded(
                  child: Text(
                    note ??
                        (hasPhoto
                            ? 'Show us where the problem is on the map.'
                            : 'Add a photo first - if it was taken with your camera we can usually find the place for you.'),
                    style: const TextStyle(color: JanColors.slate, fontWeight: FontWeight.w600),
                  ),
                ),
              ]),
              const SizedBox(height: JanSpace.sm),
              FilledButton.tonalIcon(
                onPressed: onOpenMap,
                icon: const Icon(Icons.map_outlined),
                label: const Text('Choose on map'),
              ),
            ],
          ),
        ),
      ]);
    }

    if (!location.isConfirmed && !wardMode) {
      children.add(Align(
        alignment: Alignment.centerLeft,
        child: TextButton(
          onPressed: onUseWard,
          child: const Text('Map not working? Choose your ward instead'),
        ),
      ));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
  }
}

class _ConfirmedRow extends StatelessWidget {
  final String text;
  final VoidCallback onChange;
  final String changeLabel;
  const _ConfirmedRow({required this.text, required this.onChange, this.changeLabel = 'Change'});

  @override
  Widget build(BuildContext context) => Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: JanSpace.xs,
        children: [
          const Icon(Icons.check_circle_rounded, size: 20, color: JanColors.teal),
          Text(text, style: const TextStyle(color: JanColors.teal, fontWeight: FontWeight.w700)),
          TextButton.icon(
            onPressed: onChange,
            icon: const Icon(Icons.edit_location_alt_outlined, size: 18),
            label: Text(changeLabel),
          ),
        ],
      );
}

/// Ward fallback when the map cannot be used: the server stores an
/// approximate ward location (WARD_FALLBACK).
class _WardFallback extends StatelessWidget {
  final List<Ward>? wards;
  final bool loading;
  final ValueChanged<Ward?> onChosen;
  final VoidCallback onBackToMap;

  const _WardFallback({required this.wards, required this.loading, required this.onChosen, required this.onBackToMap});

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
            message: 'Choose the ward where the problem is. Officers will see an approximate location, '
                'so please describe the exact spot in the description.',
          ),
          const SizedBox(height: JanSpace.md),
          if (loading)
            const Padding(padding: EdgeInsets.symmetric(vertical: 8), child: LinearProgressIndicator())
          else if (wards != null && wards!.isNotEmpty)
            DropdownButtonFormField<Ward>(
              isExpanded: true,
              decoration: const InputDecoration(
                labelText: 'Ward where the problem is',
                prefixIcon: Icon(Icons.map_outlined),
              ),
              items: wards!.map((w) => DropdownMenuItem(value: w, child: Text(w.name))).toList(),
              onChanged: onChosen,
            )
          else
            const Text('The ward list could not be loaded. Please try the map again.',
                style: TextStyle(color: JanColors.muted)),
          const SizedBox(height: JanSpace.xs),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: onBackToMap,
              icon: const Icon(Icons.map_outlined, size: 18),
              label: const Text('Back to the map'),
            ),
          ),
        ],
      ),
    );
  }
}
