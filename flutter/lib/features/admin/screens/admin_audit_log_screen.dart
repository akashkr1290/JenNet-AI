import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/api/api_exception.dart';
import '../admin_api.dart';
import '../models/audit_log_entry.dart';

/// SRS 15.11 "audit log review" / 16.3 Admin Audit Log screen. Read-only
/// (AdminAuditLogController has no mutating endpoint at all - see its
/// Javadoc). Filterable by entity type and action type; no date-range
/// picker this phase (kept to the two most useful filters for a first
/// pass - the backend already supports from/to, so a future phase can
/// add the picker without any backend change).
class AdminAuditLogScreen extends StatefulWidget {
  const AdminAuditLogScreen({super.key});

  @override
  State<AdminAuditLogScreen> createState() => _AdminAuditLogScreenState();
}

class _AdminAuditLogScreenState extends State<AdminAuditLogScreen> {
  late Future<List<AuditLogEntry>> _future;
  final _entityTypeController = TextEditingController();
  final _actionTypeController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _future = AdminApi.instance.listAuditLogs();
  }

  void _refresh() {
    setState(() {
      _future = AdminApi.instance.listAuditLogs(
        entityType: _entityTypeController.text.trim().isEmpty ? null : _entityTypeController.text.trim().toUpperCase(),
        actionType: _actionTypeController.text.trim().isEmpty ? null : _actionTypeController.text.trim().toUpperCase(),
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _entityTypeController,
                  decoration: const InputDecoration(
                    hintText: 'Entity type (e.g. USER)',
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  onSubmitted: (_) => _refresh(),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: _actionTypeController,
                  decoration: const InputDecoration(
                    hintText: 'Action type',
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  onSubmitted: (_) => _refresh(),
                ),
              ),
              const SizedBox(width: 8),
              IconButton(onPressed: _refresh, icon: const Icon(Icons.filter_alt_outlined), tooltip: 'Apply filters'),
            ],
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: FutureBuilder<List<AuditLogEntry>>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snapshot.hasError || !snapshot.hasData) {
                final message = snapshot.error is ApiException
                    ? (snapshot.error as ApiException).message
                    : 'Could not load audit logs.';
                return Center(child: Text(message, textAlign: TextAlign.center));
              }
              final entries = snapshot.data!;
              if (entries.isEmpty) {
                return const Center(child: Text('No audit log entries match these filters.'));
              }
              return ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: entries.length,
                itemBuilder: (context, index) => _entryCard(entries[index]),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _entryCard(AuditLogEntry entry) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(entry.actionType, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
            const SizedBox(height: 4),
            Text('${entry.entityType} #${entry.entityId} · by ${entry.actorName}',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
            if (entry.createdAt != null)
              Text(DateFormat('dd MMM yyyy, hh:mm a').format(entry.createdAt!),
                  style: TextStyle(fontSize: 11, color: Colors.grey.shade500)),
            if (entry.details != null && entry.details!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(entry.details!, style: const TextStyle(fontSize: 12, fontFamily: 'monospace')),
            ],
          ],
        ),
      ),
    );
  }
}
