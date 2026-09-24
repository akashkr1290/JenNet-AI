import 'package:flutter/material.dart';

import '../../../core/api/api_client.dart';
import '../../../core/api/api_exception.dart';

/// Gap-backlog Patch 10/12 (Sep 2026 audit): citizen-visible ward-level
/// complaint density, backed by the new CommunityHeatmapController
/// (GET /api/v1/community/heatmap) - ward name + counts only, never
/// individual complaint or citizen identity (see that controller's
/// Javadoc for the privacy rationale this inherits from
/// WardHeatmapPointResponse).
class CommunityHeatmapScreen extends StatefulWidget {
  final bool embedded;
  const CommunityHeatmapScreen({super.key, this.embedded = false});

  @override
  State<CommunityHeatmapScreen> createState() => _CommunityHeatmapScreenState();
}

class _CommunityHeatmapScreenState extends State<CommunityHeatmapScreen> {
  late Future<List<_WardHeatmapPoint>> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<List<_WardHeatmapPoint>> _load() async {
    final json = await ApiClient.instance.get('/community/heatmap') as List<dynamic>;
    return json.map((e) => _WardHeatmapPoint.fromJson(e as Map<String, dynamic>)).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: widget.embedded ? null : AppBar(title: const Text('Community Heatmap')),
      body: FutureBuilder<List<_WardHeatmapPoint>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            final message = snapshot.error is ApiException
                ? (snapshot.error as ApiException).message
                : 'Could not load the community heatmap.';
            return Center(child: Text(message));
          }
          final points = snapshot.data ?? [];
          if (points.isEmpty) {
            return const Center(child: Text('No complaints reported yet.'));
          }
          final maxCount = points.map((p) => p.complaintCount).reduce((a, b) => a > b ? a : b);
          return ListView.builder(
            padding: const EdgeInsets.all(16),
            itemCount: points.length,
            itemBuilder: (context, i) {
              final p = points[i];
              final intensity = maxCount == 0 ? 0.0 : p.complaintCount / maxCount;
              return Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(p.wardName, style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text('${p.complaintCount} report(s) · ${p.openComplaintCount} open'),
                      ],
                    ),
                    const SizedBox(height: 4),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: intensity,
                        minHeight: 8,
                        backgroundColor: Colors.grey.shade200,
                        color: Color.lerp(Colors.amber, Colors.red, intensity),
                      ),
                    ),
                  ],
                ),
              );
            },
          );
        },
      ),
    );
  }
}

class _WardHeatmapPoint {
  final String wardName;
  final int complaintCount;
  final int openComplaintCount;

  _WardHeatmapPoint({required this.wardName, required this.complaintCount, required this.openComplaintCount});

  factory _WardHeatmapPoint.fromJson(Map<String, dynamic> json) {
    return _WardHeatmapPoint(
      wardName: json['wardName'] as String? ?? 'Unknown ward',
      complaintCount: (json['complaintCount'] as num?)?.toInt() ?? 0,
      openComplaintCount: (json['openComplaintCount'] as num?)?.toInt() ?? 0,
    );
  }
}
