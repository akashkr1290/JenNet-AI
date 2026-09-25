import 'dart:async';

import 'package:flutter/material.dart';

import 'api/api_client.dart';
import 'api/api_exception.dart';
import 'widgets/jan_states.dart';

/// Audit GAP-037 (SRS 15.11): maintenance mode and the platform announcement,
/// from the public GET /api/v1/public/platform-status.
class PlatformStatus {
  final bool maintenanceMode;
  final String? maintenanceMessage;
  final int retryAfterSeconds;
  final String? announcement;

  const PlatformStatus({
    required this.maintenanceMode,
    this.maintenanceMessage,
    this.retryAfterSeconds = 1800,
    this.announcement,
  });

  static const none = PlatformStatus(maintenanceMode: false);

  factory PlatformStatus.fromJson(Map<String, dynamic> json) => PlatformStatus(
        maintenanceMode: (json['maintenanceMode'] as bool?) ?? false,
        maintenanceMessage: json['maintenanceMessage'] as String?,
        retryAfterSeconds: (json['retryAfterSeconds'] as num?)?.toInt() ?? 1800,
        announcement: json['announcement'] as String?,
      );

  bool get hasBanner => maintenanceMode || (announcement != null && announcement!.trim().isNotEmpty);
}

/// True for the backend's 503 answer to a write during maintenance
/// (MaintenanceModeFilter: status 503, error "MAINTENANCE").
bool isMaintenanceRefusal(Object error) =>
    error is ApiException && error.status == 503 && error.error == 'MAINTENANCE';

/// Polls the public status every [interval] while the app runs and exposes it
/// as a [ValueNotifier]; a failed poll keeps the last known value.
class PlatformStatusService {
  PlatformStatusService._();
  static final PlatformStatusService instance = PlatformStatusService._();

  static const interval = Duration(minutes: 5);
  final ValueNotifier<PlatformStatus> status = ValueNotifier(PlatformStatus.none);
  Timer? _timer;

  void start() {
    if (_timer != null) return;
    refresh();
    _timer = Timer.periodic(interval, (_) => refresh());
  }

  Future<void> refresh() async {
    try {
      final json = await ApiClient.instance.get('/public/platform-status') as Map<String, dynamic>;
      status.value = PlatformStatus.fromJson(json);
    } catch (_) {
      // offline or server unreachable: keep the last known status
    }
  }
}

/// Banner shown at the top of every signed-in shell and the sign-in screens.
class PlatformStatusBanner extends StatefulWidget {
  const PlatformStatusBanner({super.key});

  @override
  State<PlatformStatusBanner> createState() => _PlatformStatusBannerState();
}

class _PlatformStatusBannerState extends State<PlatformStatusBanner> {
  @override
  void initState() {
    super.initState();
    PlatformStatusService.instance.start();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<PlatformStatus>(
      valueListenable: PlatformStatusService.instance.status,
      builder: (context, s, _) {
        if (!s.hasBanner) return const SizedBox.shrink();
        return Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (s.maintenanceMode)
                JanBanner(
                  tone: JanBannerTone.warning,
                  icon: Icons.build_circle_outlined,
                  message: '${s.maintenanceMessage ?? 'Scheduled maintenance is in progress.'} '
                      'You can still view information; new submissions are saved and sent automatically afterwards.',
                ),
              if (s.maintenanceMode && s.announcement != null) const SizedBox(height: 6),
              if (s.announcement != null && s.announcement!.trim().isNotEmpty)
                JanBanner(tone: JanBannerTone.info, icon: Icons.campaign_outlined, message: s.announcement!),
            ],
          ),
        );
      },
    );
  }
}
