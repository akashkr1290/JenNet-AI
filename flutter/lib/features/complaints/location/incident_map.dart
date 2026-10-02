import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../../../core/theme/jan_tokens.dart';
import 'incident_location.dart';

/// OpenStreetMap tiles (free; tile usage policy: identify the app, show the
/// attribution, no heavy/bulk use - fine for a pilot's interactive use).
TileLayer osmTileLayer() => TileLayer(
      urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      userAgentPackageName: 'com.jannetai.app',
      maxNativeZoom: 19,
    );

/// Required "© OpenStreetMap contributors" attribution.
Widget osmAttribution() => SimpleAttributionWidget(source: const Text('OpenStreetMap contributors'));

LatLng toLatLng(GeoPoint p) => LatLng(p.latitude, p.longitude);

/// The red "problem location" pin. Its tip is the bottom-centre of the icon.
class ProblemPin extends StatelessWidget {
  final double size;
  const ProblemPin({super.key, this.size = 44});

  @override
  Widget build(BuildContext context) =>
      Icon(Icons.location_on, size: size, color: JanColors.error, semanticLabel: 'Problem location');
}

/// Small marker for the automatically detected (photo) location, shown to
/// staff and on the map when the citizen moved the pin away from it.
class DetectedDot extends StatelessWidget {
  const DetectedDot({super.key});

  @override
  Widget build(BuildContext context) => Container(
        decoration: BoxDecoration(
          color: JanColors.white,
          shape: BoxShape.circle,
          border: Border.all(color: JanColors.navy, width: 2),
        ),
        alignment: Alignment.center,
        child: const Icon(Icons.photo_camera, size: 13, color: JanColors.navy, semanticLabel: 'Photo location'),
      );
}

/// Read-only map snippet of an incident location (submission summary,
/// complaint detail). [detected] adds the photo-location marker when it is
/// a different point.
class IncidentMapPreview extends StatelessWidget {
  final GeoPoint point;
  final GeoPoint? detected;
  final double height;
  final double zoom;
  final String semanticLabel;

  const IncidentMapPreview({
    super.key,
    required this.point,
    this.detected,
    this.height = 170,
    this.zoom = 16,
    this.semanticLabel = 'Map showing the problem location',
  });

  @override
  Widget build(BuildContext context) {
    final showDetected = detected != null && distanceMeters(detected!, point) > 5;
    return Semantics(
      label: semanticLabel,
      container: true,
      child: ClipRRect(
        borderRadius: JanRadius.mdAll,
        child: SizedBox(
          height: height,
          child: FlutterMap(
            key: ValueKey('${point.latitude},${point.longitude},${detected?.latitude},${detected?.longitude}'),
            options: MapOptions(
              initialCenter: toLatLng(point),
              initialZoom: zoom,
              interactionOptions: const InteractionOptions(flags: InteractiveFlag.none),
            ),
            children: [
              osmTileLayer(),
              MarkerLayer(markers: [
                if (showDetected)
                  Marker(point: toLatLng(detected!), width: 24, height: 24, child: const DetectedDot()),
                Marker(
                  point: toLatLng(point),
                  width: 44,
                  height: 44,
                  alignment: Alignment.topCenter,
                  child: const ProblemPin(),
                ),
              ]),
              osmAttribution(),
            ],
          ),
        ),
      ),
    );
  }
}
