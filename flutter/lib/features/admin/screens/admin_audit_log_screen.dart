import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
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

  @override
  void dispose() {
    _entityTypeController.dispose();
    _actionTypeController.dispose();
    super.dispose();
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
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, 0),
          child: JanCard(
            padding: const EdgeInsets.all(JanSpace.sm),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _entityTypeController,
                    decoration: const InputDecoration(
                      hintText: 'Entity type (e.g. USER)',
                      isDense: true,
                      prefixIcon: Icon(Icons.category_outlined, size: 20),
                    ),
                    onSubmitted: (_) => _refresh(),
                  ),
                ),
                const SizedBox(width: JanSpace.xs),
                Expanded(
                  child: TextField(
                    controller: _actionTypeController,
                    decoration: const InputDecoration(
                      hintText: 'Action type',
                      isDense: true,
                      prefixIcon: Icon(Icons.bolt_outlined, size: 20),
                    ),
                    onSubmitted: (_) => _refresh(),
                  ),
                ),
                const SizedBox(width: JanSpace.xs),
                IconButton.filled(
                  onPressed: _refresh,
                  icon: const Icon(Icons.filter_alt_outlined),
                  tooltip: 'Apply filters',
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: JanSpace.xs),
        Expanded(
          child: FutureBuilder<List<AuditLogEntry>>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const JanSkeletonList(semanticLabel: 'Loading audit logs');
              }
              if (snapshot.hasError || !snapshot.hasData) {
                return JanErrorState.fromError(snapshot.error, fallback: 'Could not load audit logs.', onRetry: _refresh);
              }
              final entries = snapshot.data!;
              if (entries.isEmpty) {
                return const JanEmptyState(
                  icon: Icons.manage_search_rounded,
                  title: 'No entries',
                  message: 'No audit log entries match these filters.',
                  color: JanColors.primary,
                );
              }
              return ListView.builder(
                padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
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
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.xs),
      child: JanCard(
        padding: const EdgeInsets.all(JanSpace.sm),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: const BoxDecoration(color: JanColors.infoLight, borderRadius: JanRadius.smAll),
              child: const Icon(Icons.history_rounded, size: 20, color: JanColors.primary),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(entry.actionType,
                      style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 14, color: JanColors.navy)),
                  const SizedBox(height: 2),
                  Text('${entry.entityType} #${entry.entityId} · by ${entry.actorName}',
                      style: const TextStyle(fontSize: 12.5, color: JanColors.slate)),
                  if (entry.createdAt != null)
                    Text(DateFormat('dd MMM yyyy, hh:mm a').format(entry.createdAt!),
                        style: const TextStyle(fontSize: 12, color: JanColors.muted)),
                  if (entry.details != null && entry.details!.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(JanSpace.xs),
                      decoration: const BoxDecoration(color: JanColors.surfaceAlt, borderRadius: JanRadius.smAll),
                      child: Text(entry.details!,
                          style: const TextStyle(fontSize: 12, fontFamily: 'monospace', color: JanColors.ink)),
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
}
