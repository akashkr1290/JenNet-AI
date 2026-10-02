import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/jan_tokens.dart';
import '../models/complaint.dart';
import 'incident_location.dart';
import 'incident_map.dart';

/// Staff view of a complaint's incident location (V33): the map with the
/// confirmed pin (and the automatically detected point when the citizen moved
/// it), how the location was obtained, and the review flags. Flags are
/// warnings for staff judgement - the complaint is never blocked by them.
class LocationCheckCard extends StatelessWidget {
  final ComplaintLocation location;
  final DateTime? reportedAt;

  const LocationCheckCard({super.key, required this.location, this.reportedAt});

  static String sourceLabel(String? source) => switch (source) {
        'CAPTURE_GPS' => 'GPS when the photo was taken in the app',
        'EXIF' => 'Location saved in the photo',
        'MANUAL_PIN' => 'Pin placed on the map by the citizen',
        'WARD_FALLBACK' => 'Ward only (approximate point)',
        'DEVICE_GPS' => 'Phone GPS at submission (older app - may not be the problem location)',
        _ => 'Unknown',
      };

  static String flagLabel(String flag) => switch (flag) {
        'STALE_PHOTO' => 'Old photo',
        'LOW_ACCURACY' => 'Imprecise GPS',
        'PIN_MOVED_FAR' => 'Pin moved from photo location',
        'NO_PHOTO_LOCATION' => 'No location in photo',
        'OUT_OF_JURISDICTION' => 'Outside municipal area',
        _ => flag,
      };

  static String distanceLabel(double meters) =>
      meters >= 1000 ? '${(meters / 1000).toStringAsFixed(1)} km' : '${meters.round()} m';

  @override
  Widget build(BuildContext context) {
    final l = location;
    final rows = <(String, String)>[
      ('How it was found', sourceLabel(l.source)),
      ('Confirmed by citizen', l.confirmedByCitizen ? 'Yes, on the map' : 'No (older app)'),
      if (l.accuracyMeters != null) ('GPS accuracy', '± ${distanceLabel(l.accuracyMeters!)}'),
      if (l.capturedAt != null) ('Photo taken', _photoTaken(l.capturedAt!)),
      if (l.hasPoint && l.hasDetected)
        (
          'Photo location vs. pin',
          '${distanceLabel(distanceMeters(GeoPoint(l.detectedLatitude!, l.detectedLongitude!), GeoPoint(l.latitude!, l.longitude!)))} apart'
        ),
      if (l.submissionDistanceMeters != null)
        ('Reported from', '${distanceLabel(l.submissionDistanceMeters!)} away from the problem'),
      if (l.hasPoint) ('Coordinates', '${l.latitude!.toStringAsFixed(6)}, ${l.longitude!.toStringAsFixed(6)}'),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (l.hasPoint && !l.isApproximate)
          IncidentMapPreview(
            point: GeoPoint(l.latitude!, l.longitude!),
            detected: l.hasDetected ? GeoPoint(l.detectedLatitude!, l.detectedLongitude!) : null,
            height: 190,
            semanticLabel: 'Map of the problem location',
          ),
        if (l.flags.isNotEmpty) ...[
          const SizedBox(height: JanSpace.xs),
          Wrap(
            spacing: JanSpace.xs,
            runSpacing: 4,
            children: [
              for (final f in l.flags)
                Chip(
                  avatar: const Icon(Icons.flag_outlined, size: 16, color: JanColors.amberDark),
                  label: Text(flagLabel(f)),
                  backgroundColor: JanColors.amberLight,
                  side: BorderSide.none,
                  visualDensity: VisualDensity.compact,
                ),
            ],
          ),
        ],
        const SizedBox(height: JanSpace.xs),
        for (final (label, value) in rows)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 2),
            child: Text.rich(
              TextSpan(children: [
                TextSpan(text: '$label: ', style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.slate)),
                TextSpan(text: value),
              ]),
              style: const TextStyle(fontSize: 13.5, color: JanColors.ink),
            ),
          ),
        if (l.hasPoint && l.hasDetected)
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Text('Red pin: problem location confirmed by the citizen. Camera dot: location from the photo.',
                style: TextStyle(fontSize: 12.5, color: JanColors.muted)),
          ),
      ],
    );
  }

  String _photoTaken(DateTime takenAt) {
    final text = DateFormat('d MMM y, h:mm a').format(takenAt.toLocal());
    final reference = reportedAt ?? DateTime.now();
    final days = reference.difference(takenAt).inDays;
    return days >= 1 ? '$text ($days day${days == 1 ? '' : 's'} before reporting)' : text;
  }
}
