import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../models/app_notification.dart';
import '../notification_api.dart';

/// SRS 20.5 `GET /api/v1/notifications` (Phase 15, Notification Module).
/// Read-only list, most recent first (server-sorted) - no mark-as-read
/// affordance, matching the backend: the `notifications` table
/// (V11__create_notifications.sql) and NotificationResponse have no
/// "read" flag to toggle (the SRS's 20.5 API table gives this endpoint no
/// other verb), so this deliberately doesn't fabricate one.
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Notifications')),
      body: RefreshIndicator(
        onRefresh: () async {
          _refresh();
          await _future;
        },
        child: FutureBuilder<List<AppNotification>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError || !snapshot.hasData) {
              final message = snapshot.error is ApiException
                  ? (snapshot.error as ApiException).message
                  : 'Could not load notifications.';
              return ListView(
                children: [
                  const SizedBox(height: 120),
                  Center(child: Text(message, textAlign: TextAlign.center)),
                ],
              );
            }
            final notifications = snapshot.data!;
            if (notifications.isEmpty) {
              return ListView(
                children: const [
                  SizedBox(height: 120),
                  Center(child: Text('No notifications yet.')),
                ],
              );
            }
            return ListView.builder(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: notifications.length,
              itemBuilder: (context, index) => _notificationTile(notifications[index]),
            );
          },
        ),
      ),
    );
  }

  Widget _notificationTile(AppNotification notification) {
    final failed = notification.deliveryStatus == 'FAILED';
    return ListTile(
      leading: Icon(_channelIcon(notification.channel), color: failed ? Colors.grey : Colors.indigo),
      title: Text(notification.message),
      subtitle: Text(
        [
          if (notification.complaintReferenceNumber != null) notification.complaintReferenceNumber!,
          if (notification.createdAt != null) _formatTimestamp(notification.createdAt!),
        ].join(' \u00b7 '),
      ),
      trailing: failed
          ? const Tooltip(message: 'Delivery failed', child: Icon(Icons.error_outline, color: Colors.redAccent))
          : null,
    );
  }

  String _formatTimestamp(DateTime dt) {
    final local = dt.toLocal();
    return '${local.year}-${local.month.toString().padLeft(2, '0')}-${local.day.toString().padLeft(2, '0')} '
        '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
  }
}
