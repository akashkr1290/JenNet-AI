import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../users/user_api.dart';
import '../../wards/models/ward.dart';
import '../../wards/wards_api.dart';
import 'area_search.dart';
import 'device_position.dart';
import 'incident_location.dart';
import 'incident_map.dart';
import 'location_policy.dart';

/// "Where is the problem?" - the mandatory incident-location step (V33).
///
/// The citizen searches an area, moves/zooms the map (or taps it) so the fixed
/// red pin sits on the problem, and confirms. The map opens on the automatic
/// location when there is one (capture GPS / photo EXIF). "You are here" only
/// moves the map - the citizen still decides where the pin goes - because
/// where the citizen is now is not necessarily where the problem is.
///
/// Pops with the confirmed [GeoPoint], or null when cancelled.
class IncidentLocationPickerScreen extends StatefulWidget {
  /// Where the map opens (automatic proposal or the previous confirmation).
  final GeoPoint? initial;

  /// The automatic (photo) location, shown as a small marker for reference.
  final GeoPoint? detected;

  /// Optional note shown above the map (e.g. imprecise GPS).
  final String? note;

  const IncidentLocationPickerScreen({super.key, this.initial, this.detected, this.note});

  @override
  State<IncidentLocationPickerScreen> createState() => _IncidentLocationPickerScreenState();
}

class _IncidentLocationPickerScreenState extends State<IncidentLocationPickerScreen> {
  // India overview, used only when nothing better is known.
  static const _country = LatLng(22.5, 79.0);
  static const _countryZoom = 4.5;

  final _map = MapController();
  final _searchController = TextEditingController();
  final _areaSearch = AreaSearch();

  late LatLng _center;
  late double _zoom;
  bool _mapReady = false;
  List<Ward> _wards = const [];
  List<AreaResult>? _results;
  bool _searching = false;
  GeoPoint? _youAreHere;
  bool _locating = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    final start = widget.initial;
    _center = start != null ? toLatLng(start) : _country;
    _zoom = start != null ? 17 : _countryZoom;
    _loadWards();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadWards() async {
    List<Ward> wards = const [];
    try {
      wards = await WardsApi.instance.listActiveWards();
    } catch (_) {
      // Search still works through OpenStreetMap.
    }
    if (!mounted) return;
    setState(() => _wards = wards);
    if (widget.initial != null) return;
    // No automatic location: open on the citizen's own ward (their profile),
    // else the centre of all wards - never on the device's position.
    LatLng? start;
    try {
      final me = await UserApi.instance.me();
      final ward = wards.where((w) => w.wardId == me.wardId && w.hasCentre).firstOrNull;
      if (ward != null) start = LatLng(ward.centreLatitude!, ward.centreLongitude!);
    } catch (_) {}
    final withCentre = wards.where((w) => w.hasCentre).toList();
    if (start == null && withCentre.isNotEmpty) {
      start = LatLng(
        withCentre.map((w) => w.centreLatitude!).reduce((a, b) => a + b) / withCentre.length,
        withCentre.map((w) => w.centreLongitude!).reduce((a, b) => a + b) / withCentre.length,
      );
    }
    if (start != null && mounted && _zoom == _countryZoom) _moveTo(start, 14);
  }

  void _moveTo(LatLng point, double zoom) {
    setState(() {
      _center = point;
      _zoom = zoom;
    });
    if (_mapReady) _map.move(point, zoom);
  }

  Future<void> _runSearch() async {
    final query = _searchController.text.trim();
    if (query.isEmpty || _searching) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _searching = true;
      _results = null;
    });
    final results = await _areaSearch.search(
      query,
      wards: _wards,
      near: _zoom > _countryZoom ? GeoPoint(_center.latitude, _center.longitude) : null,
    );
    if (!mounted) return;
    setState(() {
      _searching = false;
      _results = results;
    });
  }

  void _pickResult(AreaResult r) {
    setState(() => _results = null);
    _moveTo(toLatLng(r.point), r.zoom);
  }

  Future<void> _showWhereIAm() async {
    if (_locating) return;
    setState(() {
      _locating = true;
      _message = null;
    });
    final result = await DevicePosition.read(timeout: const Duration(seconds: 15));
    if (!mounted) return;
    setState(() {
      _locating = false;
      if (result.ok) {
        _youAreHere = result.point;
        _message = 'The map now shows where you are. If the problem is somewhere else, '
            'move the map so the red pin is on the problem.';
      } else {
        _message = '${DevicePosition.explain(result.failure!)} You can search for the area instead.';
      }
    });
    if (result.ok) _moveTo(toLatLng(result.point!), math.max(_zoom, LocationPolicy.pinZoom));
  }

  void _zoomBy(double delta) {
    final zoom = (_zoom + delta).clamp(3.0, 19.0);
    _moveTo(_center, zoom);
  }

  void _confirm() {
    if (_zoom < LocationPolicy.minConfirmZoom) {
      _moveTo(_center, LocationPolicy.pinZoom);
      setState(() => _message = 'Zoomed in - check that the red pin is exactly on the problem, then confirm.');
      return;
    }
    Navigator.of(context).pop(GeoPoint(
      double.parse(_center.latitude.toStringAsFixed(6)),
      double.parse(_center.longitude.toStringAsFixed(6)),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final zoomedIn = _zoom >= LocationPolicy.minConfirmZoom;
    return Scaffold(
      appBar: AppBar(title: const Text('Where is the problem?')),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.md, 0),
              child: JanBanner(
                tone: widget.note != null ? JanBannerTone.warning : JanBannerTone.info,
                icon: Icons.place_outlined,
                message: widget.note ??
                    'This is where the problem is - not necessarily where you are now.',
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.md, JanSpace.xs),
              child: TextField(
                controller: _searchController,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => _runSearch(),
                decoration: InputDecoration(
                  labelText: 'Search an area, street or ward',
                  prefixIcon: const Icon(Icons.search),
                  suffixIcon: _searching
                      ? const Padding(
                          padding: EdgeInsets.all(14),
                          child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                        )
                      : IconButton(
                          tooltip: 'Search',
                          icon: const Icon(Icons.arrow_forward_rounded),
                          onPressed: _runSearch,
                        ),
                ),
              ),
            ),
            if (_results != null)
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 220),
                child: _results!.isEmpty
                    ? const Padding(
                        padding: EdgeInsets.symmetric(horizontal: JanSpace.md, vertical: JanSpace.xs),
                        child: Text('No places found. Try another name, or move the map by hand.',
                            style: TextStyle(color: JanColors.muted)),
                      )
                    : ListView(
                        shrinkWrap: true,
                        children: [
                          for (final r in _results!)
                            ListTile(
                              dense: true,
                              leading: const Icon(Icons.place_outlined),
                              title: Text(r.label, maxLines: 2, overflow: TextOverflow.ellipsis),
                              onTap: () => _pickResult(r),
                            ),
                        ],
                      ),
              ),
            Expanded(
              child: Stack(
                children: [
                  FlutterMap(
                    mapController: _map,
                    options: MapOptions(
                      initialCenter: _center,
                      initialZoom: _zoom,
                      minZoom: 3,
                      maxZoom: 19,
                      interactionOptions: const InteractionOptions(
                        flags: InteractiveFlag.all & ~InteractiveFlag.rotate,
                      ),
                      onMapReady: () {
                        _mapReady = true;
                        _map.move(_center, _zoom);
                      },
                      onTap: (_, point) => _moveTo(point, math.max(_zoom, LocationPolicy.pinZoom)),
                      onPositionChanged: (camera, _) {
                        if (camera.center != _center || camera.zoom != _zoom) {
                          setState(() {
                            _center = camera.center;
                            _zoom = camera.zoom;
                          });
                        }
                      },
                    ),
                    children: [
                      osmTileLayer(),
                      MarkerLayer(markers: [
                        if (widget.detected != null)
                          Marker(point: toLatLng(widget.detected!), width: 24, height: 24, child: const DetectedDot()),
                        if (_youAreHere != null)
                          Marker(
                            point: toLatLng(_youAreHere!),
                            width: 22,
                            height: 22,
                            child: Container(
                              decoration: BoxDecoration(
                                color: JanColors.primary,
                                shape: BoxShape.circle,
                                border: Border.all(color: JanColors.white, width: 3),
                              ),
                            ),
                          ),
                      ]),
                      osmAttribution(),
                    ],
                  ),
                  // The fixed pin: the point under its tip is the incident location.
                  const IgnorePointer(
                    child: Center(
                      child: Padding(
                        padding: EdgeInsets.only(bottom: 72),
                        child: SizedBox(
                          height: 72,
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              _PinLabel(),
                              ProblemPin(size: 44),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                  Positioned(
                    right: JanSpace.sm,
                    top: JanSpace.sm,
                    child: Column(
                      children: [
                        _MapButton(icon: Icons.add, tooltip: 'Zoom in', onPressed: () => _zoomBy(1)),
                        const SizedBox(height: JanSpace.xs),
                        _MapButton(icon: Icons.remove, tooltip: 'Zoom out', onPressed: () => _zoomBy(-1)),
                      ],
                    ),
                  ),
                  Positioned(
                    left: JanSpace.sm,
                    bottom: JanSpace.xl,
                    child: FilledButton.tonalIcon(
                      onPressed: _locating ? null : _showWhereIAm,
                      icon: _locating
                          ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.my_location_rounded, size: 18),
                      label: const Text('You are here'),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.md, JanSpace.md),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Semantics(
                    liveRegion: true,
                    child: Text(
                      _message ??
                          (zoomedIn
                              ? 'Move the map (or tap it) so the red pin is on the problem.'
                              : 'Search for the area or zoom in, then put the red pin on the problem.'),
                      style: const TextStyle(color: JanColors.muted),
                    ),
                  ),
                  const SizedBox(height: JanSpace.sm),
                  FilledButton.icon(
                    onPressed: _confirm,
                    icon: Icon(zoomedIn ? Icons.check_rounded : Icons.zoom_in_rounded),
                    label: Text(zoomedIn ? 'Confirm Location' : 'Zoom in to place the pin'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PinLabel extends StatelessWidget {
  const _PinLabel();

  @override
  Widget build(BuildContext context) => Container(
        height: 28,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: JanColors.navy,
          borderRadius: JanRadius.smAll,
          boxShadow: JanShadows.card,
        ),
        child: const Text('Problem location',
            style: TextStyle(color: JanColors.white, fontWeight: FontWeight.w700, fontSize: 12.5)),
      );
}

class _MapButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;
  const _MapButton({required this.icon, required this.tooltip, required this.onPressed});

  @override
  Widget build(BuildContext context) => Material(
        color: JanColors.white,
        shape: const CircleBorder(),
        elevation: 2,
        child: IconButton(tooltip: tooltip, icon: Icon(icon), onPressed: onPressed),
      );
}
