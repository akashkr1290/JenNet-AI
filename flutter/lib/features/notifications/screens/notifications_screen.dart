import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../models/app_notification.dart';
import '../notification_api.dart';

/// SRS 20.5 `GET /api/v1/notifications` (Phase 15, Notification Module).
/// Read-only list, most recent first (server-sorted) - no mark-as-read
/// affordance, matching the backend: the `notifications` table
/// (V11__create_notifications.sql) and NotificationResponse have no
/// "read" flag to toggle (the SRS's 20.5 API table gives this endpoint no
/// other verb), so this deliberately doesn't fabricate one.
///
/// UI redesign: reference "Notifications" screen - Today / Earlier groups,
/// icon tiles per channel, delivery failures called out with text (not
/// colour alone), and the shared loading / empty / error states.
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  late Future<List<AppNotification>> _future;

  @override
  void initState() {
    super.initState();
    _future = NotificationApi.instance.list();
  }

  void _refresh() {
    setState(() => _future = NotificationApi.instance.list());
  }

  IconData _channelIcon(String channel) {
    switch (channel) {
      case 'EMAIL':
        return Icons.email_outlined;
      case 'SMS':
        return Icons.sms_outlined;
      case 'PUSH':
        return Icons.notifications_active_outlined;
      case 'IN_APP':
      default:
        return Icons.notifications_outlined;
    }
  }

  String _channelLabel(String channel) {
    switch (channel) {
      case 'EMAIL':
        return 'Email';
      case 'SMS':
        return 'SMS';
      case 'PUSH':
        return 'Push';
      case 'IN_APP':
      default:
        return 'In-app';
    }
  }

  static bool _isToday(DateTime? dt) {
    if (dt == null) return false;
    final local = dt.toLocal();
    final now = DateTime.now();
    return local.year == now.year && local.month == now.month && local.day == now.day;
  }

  @override
  Widget build(BuildContext context) {
    return JanPage(
      title: 'Notifications',
      maxWidth: 760,
      body: RefreshIndicator(
        onRefresh: () async {
          _refresh();
          await _future;
        },
        child: FutureBuilder<List<AppNotification>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const JanSkeletonList(semanticLabel: 'Loading notifications');
            }
            if (snapshot.hasError || !snapshot.hasData) {
              return JanErrorState.fromError(
                snapshot.error,
                fallback: 'Could not load notifications.',
                onRetry: _refresh,
              );
            }
            final notifications = snapshot.data!;
            if (notifications.isEmpty) {
              return const JanEmptyState(
                icon: Icons.notifications_none_rounded,
                title: 'No notifications yet',
                message: "Updates about your complaints will appear here.",
                color: JanColors.primary,
              );
            }
            final today = notifications.where((n) => _isToday(n.createdAt)).toList();
            final earlier = notifications.where((n) => !_isToday(n.createdAt)).toList();
            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
              children: [
                if (today.isNotEmpty) ...[
                  const JanSectionHeader(title: 'Today'),
                  for (final n in today) ...[_notificationTile(n), const SizedBox(height: JanSpace.sm)],
                ],
                if (earlier.isNotEmpty) ...[
                  const JanSectionHeader(title: 'Earlier'),
                  for (final n in earlier) ...[_notificationTile(n), const SizedBox(height: JanSpace.sm)],
                ],
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _notificationTile(AppNotification notification) {
    final failed = notification.deliveryStatus == 'FAILED';
    final color = failed ? JanColors.error : JanColors.primary;
    final tint = failed ? JanColors.errorLight : JanColors.infoLight;
    final meta = [
      _channelLabel(notification.channel),
      if (notification.complaintReferenceNumber != null) notification.complaintReferenceNumber!,
      if (notification.createdAt != null) _formatTimestamp(notification.createdAt!),
    ].join(' · ');
    return Semantics(
      container: true,
      label: '${failed ? 'Delivery failed. ' : ''}${notification.message}. $meta',
      excludeSemantics: true,
      child: JanCard(
        padding: const EdgeInsets.all(JanSpace.sm),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(color: tint, borderRadius: JanRadius.mdAll),
              child: Icon(_channelIcon(notification.channel), color: color),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    notification.message,
                    style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.ink, height: 1.35),
                  ),
                  const SizedBox(height: 4),
                  Text(meta, style: const TextStyle(fontSize: 12.5, color: JanColors.muted)),
                  if (failed) ...[
                    const SizedBox(height: 6),
                    const Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.error_outline, size: 16, color: JanColors.error),
                        SizedBox(width: 4),
                        Text(
                          'Delivery failed',
                          style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700, color: JanColors.error),
                        ),
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatTimestamp(DateTime dt) {
    final local = dt.toLocal();
    return _isToday(dt) ? DateFormat('hh:mm a').format(local) : DateFormat('dd MMM yyyy, hh:mm a').format(local);
  }
}
