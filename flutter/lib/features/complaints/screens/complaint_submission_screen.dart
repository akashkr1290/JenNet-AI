import 'dart:io';

import '../../../core/widgets/error_text.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/api/api_exception.dart';
import '../../wards/models/ward.dart';
import '../../wards/wards_api.dart';
import '../pending_submission_sync.dart';
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
  File? _photo;
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
    // Gap-backlog Patch 45: restore the picked photo too, if the file still exists.
    final photoPath = draft['photoPath'] as String?;
    if (photoPath != null && _photo == null && File(photoPath).existsSync()) {
      setState(() => _photo = File(photoPath));
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
    final picked = await ImagePicker().pickImage(source: source, imageQuality: 85);
    if (picked != null) {
      setState(() => _photo = File(picked.path));
      _saveDraft();
    }
  }

  Future<void> _submit() async {
    if (_photo == null) {
      setState(() => _error = 'A photo is required.');
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
        photo: _photo!,
        description: _descriptionController.text.trim(),
        latitude: latitude,
        longitude: longitude,
        wardId: _manualFallback ? _selectedWard?.wardId : null,
        locationSource: locationSource,
      );
      await ComplaintDraftService.instance.clear();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Complaint ${complaint.referenceNumber} submitted.')),
      );
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => ComplaintDetailScreen(complaintId: complaint.complaintId)),
      );
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      // Gap-backlog Patch 45: no server response at all (offline/timeout) -
      // queue the full submission for automatic upload when back online.
      await PendingSubmissionSync.instance.queue(
        photoPath: _photo!.path,
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

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Report an Issue', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 16),
          _PhotoPicker(photo: _photo, onPick: _pickPhoto),
          const SizedBox(height: 16),
          TextField(
            controller: _descriptionController,
            maxLength: 500,
            maxLines: 4,
            decoration: const InputDecoration(
              labelText: 'Description (optional)',
              border: OutlineInputBorder(),
              hintText: 'What\'s the issue? Any details that would help.',
            ),
          ),
          const SizedBox(height: 8),
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
          const SizedBox(height: 16),
          if (_error != null) ...[
            ErrorText(_error!),
            const SizedBox(height: 16),
          ],
          FilledButton(
            onPressed: _submitting ? null : _submit,
            child: _submitting
                ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Submit Complaint'),
          ),
        ],
      ),
    );
  }
}

class _PhotoPicker extends StatelessWidget {
  final File? photo;
  final void Function(ImageSource) onPick;
  const _PhotoPicker({required this.photo, required this.onPick});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (photo != null)
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Image.file(photo!, height: 220, fit: BoxFit.cover, semanticLabel: 'Selected complaint photo'),
          )
        else
          Container(
            height: 160,
            decoration: BoxDecoration(
              color: Colors.grey.shade200,
              borderRadius: BorderRadius.circular(8),
            ),
            alignment: Alignment.center,
            child: const Text('No photo selected'),
          ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: Semantics(
                label: 'Take photo with camera',
                button: true,
                child: OutlinedButton.icon(
                  onPressed: () => onPick(ImageSource.camera),
                  icon: const Icon(Icons.camera_alt_outlined),
                  label: const Text('Camera'),
                ),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Semantics(
                label: 'Choose photo from gallery',
                button: true,
                child: OutlinedButton.icon(
                  onPressed: () => onPick(ImageSource.gallery),
                  icon: const Icon(Icons.photo_library_outlined),
                  label: const Text('Gallery'),
                ),
              ),
            ),
          ],
        ),
      ],
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
      return const Row(children: [
        SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2)),
        SizedBox(width: 8),
        Text('Getting your location...'),
      ]);
    }
    if (position == null) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Location not available', style: TextStyle(color: Colors.red)),
        const SizedBox(height: 4),
        Row(children: [
          TextButton(onPressed: onRetry, child: const Text('Retry GPS')),
          TextButton(onPressed: onUseManual, child: const Text('Enter location manually')),
        ]),
      ]);
    }
    return Row(children: [
      const Icon(Icons.location_on_outlined, size: 18, color: Colors.green),
      const SizedBox(width: 4),
      Expanded(
        child: Text(
          '${position!.latitude.toStringAsFixed(6)}, ${position!.longitude.toStringAsFixed(6)}',
        ),
      ),
      TextButton(onPressed: onRetry, child: const Text('Refresh')),
      TextButton(onPressed: onUseManual, child: const Text('Enter manually instead')),
    ]);
  }
}

/// Gap-backlog Patch 9 (Sep 2026 audit): manual location entry used when
/// live GPS is unavailable/denied. Submits with
/// LocationSource.MANUAL_PIN - still a real lat/lng (backend requirement,
/// see LocationService's Javadoc), just typed instead of device-sensed.
/// Ward selection is optional and only helps staff-side routing; it is
/// never required to submit.
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
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.amber.shade50,
        border: Border.all(color: Colors.amber.shade200),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'GPS is unavailable. Choosing your ward below is enough to submit. '
            'If you know your approximate coordinates you can add them too.',
          ),
          const SizedBox(height: 8),
          if (loadingWards)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: LinearProgressIndicator(),
            )
          else if (wards != null && wards!.isNotEmpty)
            DropdownButtonFormField<Ward>(
              value: selectedWard,
              decoration: const InputDecoration(
                labelText: 'Your ward (required if coordinates are left empty)',
                border: OutlineInputBorder(),
              ),
              items: wards!
                  .map((w) => DropdownMenuItem(value: w, child: Text(w.name)))
                  .toList(),
              onChanged: onWardChanged,
            ),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
              child: TextField(
                controller: latController,
                keyboardType: const TextInputType.numberWithOptions(signed: true, decimal: true),
                decoration: const InputDecoration(labelText: 'Latitude', border: OutlineInputBorder()),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: lngController,
                keyboardType: const TextInputType.numberWithOptions(signed: true, decimal: true),
                decoration: const InputDecoration(labelText: 'Longitude', border: OutlineInputBorder()),
              ),
            ),
          ]),
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(onPressed: onTryGpsAgain, child: const Text('Try GPS again')),
          ),
        ],
      ),
    );
  }
}
