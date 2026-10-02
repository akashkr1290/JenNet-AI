import 'dart:convert';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:http/http.dart' as http;

import '../../wards/models/ward.dart';
import 'incident_location.dart';

/// One "Search an area" result on the incident-location map.
class AreaResult {
  final String label;
  final GeoPoint point;

  /// Suggested zoom: a ward or locality is shown wider than a street.
  final double zoom;

  const AreaResult({required this.label, required this.point, this.zoom = 16});
}

/// Area search for the incident-location map: the municipality's own wards
/// first, then OpenStreetMap's free Nominatim geocoder (no key, no cost).
///
/// Nominatim usage policy: at most one request per second, no autocomplete,
/// attribution, identify the application. The app searches only when the
/// citizen presses Search, and sends an identifying User-Agent on Android
/// (browsers send their own User-Agent and Referer). A failed or unreachable
/// geocoder never blocks the citizen - they can still move the map by hand.
class AreaSearch {
  AreaSearch({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;
  DateTime _last = DateTime.fromMillisecondsSinceEpoch(0);

  static final _endpoint = Uri.parse('https://nominatim.openstreetmap.org/search');

  /// Wards whose name or code contains [query] and that have a centre.
  static List<AreaResult> matchWards(String query, List<Ward> wards) {
    final q = query.trim().toLowerCase();
    if (q.isEmpty) return const [];
    return wards
        .where((w) => w.hasCentre && (w.name.toLowerCase().contains(q) || (w.code ?? '').toLowerCase() == q))
        .take(5)
        .map((w) => AreaResult(label: w.name, point: GeoPoint(w.centreLatitude!, w.centreLongitude!), zoom: 14))
        .toList();
  }

  /// Results for [query]; [near] biases (does not restrict) to the current map area.
  Future<List<AreaResult>> search(String query, {List<Ward> wards = const [], GeoPoint? near}) async {
    final results = [...matchWards(query, wards)];
    final q = query.trim();
    if (q.length < 3) return results;
    final wait = const Duration(milliseconds: 1100) - DateTime.now().difference(_last);
    if (!wait.isNegative) await Future<void>.delayed(wait);
    _last = DateTime.now();
    final params = <String, String>{
      'q': q,
      'format': 'jsonv2',
      'limit': '5',
      'countrycodes': 'in',
      'accept-language': 'en',
    };
    if (near != null) {
      params['viewbox'] = '${near.longitude - 0.3},${near.latitude + 0.3},${near.longitude + 0.3},${near.latitude - 0.3}';
    }
    try {
      final response = await _client.get(
        _endpoint.replace(queryParameters: params),
        headers: kIsWeb ? const {} : const {'User-Agent': 'JanNetAI/0.1 (civic complaint app)'},
      ).timeout(const Duration(seconds: 10));
      if (response.statusCode != 200) return results;
      final list = jsonDecode(utf8.decode(response.bodyBytes));
      if (list is! List) return results;
      for (final item in list.whereType<Map<String, dynamic>>()) {
        final lat = double.tryParse('${item['lat']}');
        final lng = double.tryParse('${item['lon']}');
        final name = item['display_name'] as String?;
        if (lat == null || lng == null || name == null) continue;
        final type = '${item['addresstype'] ?? item['type'] ?? ''}';
        final wide = const {'city', 'town', 'state_district', 'county', 'district', 'state', 'municipality'}.contains(type);
        results.add(AreaResult(label: name, point: GeoPoint(lat, lng), zoom: wide ? 13 : 16));
      }
    } catch (_) {
      // Offline / blocked: ward matches (if any) are still returned.
    }
    return results;
  }
}
