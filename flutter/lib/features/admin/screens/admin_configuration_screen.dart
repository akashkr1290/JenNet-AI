import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/platform_status.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../admin_api.dart';
import '../models/admin_configuration.dart';
import '../models/admin_user.dart';

/// Admin "Setup" destination (SRS 15.11 configuration surface):
///  * Departments - create / rename / describe / assign head / activate (audit GAP-020);
///  * Wards - create / edit incl. GeoJSON boundary / activate (audit GAP-020). The
///    server validates the geometry and rejects overlaps with other active wards;
///    the saved boundary is what GPS-to-ward matching uses from the next complaint.
///    There is no map editor (no map package in pubspec.yaml): the boundary is
///    pasted as GeoJSON exported from a GIS tool;
///  * Zones - schools / hospitals / high-traffic roads for priority weighting (audit GAP-033);
///  * Jurisdiction - complaints located outside the configured boundary (audit GAP-031);
///  * Maintenance - maintenance mode and the announcement banner (audit GAP-037).
class AdminConfigurationScreen extends StatelessWidget {
  const AdminConfigurationScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const DefaultTabController(
      length: 5,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: [
              Tab(text: 'Departments'),
              Tab(text: 'Wards'),
              Tab(text: 'Sensitive zones'),
              Tab(text: 'Jurisdiction review'),
              Tab(text: 'Maintenance'),
            ],
          ),
          Expanded(
            child: TabBarView(children: [
              _DepartmentsTab(),
              _WardsTab(),
              _ZonesTab(),
              _JurisdictionTab(),
              _MaintenanceTab(),
            ]),
          ),
        ],
      ),
    );
  }
}

void _showError(BuildContext context, Object e) {
  final message = e is ApiException ? e.message : (e is String ? e : 'Something went wrong.');
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}

/// Generic list tab: loads [load], renders [row] for each item, with an optional add button.
class _ListTab<T> extends StatefulWidget {
  final Future<List<T>> Function() load;
  final Widget Function(BuildContext context, T item, VoidCallback reload) row;
  final String emptyTitle;
  final String emptyMessage;
  final String? addLabel;
  final Future<bool> Function(BuildContext context)? onAdd;
  final Widget? header;

  const _ListTab({
    required this.load,
    required this.row,
    required this.emptyTitle,
    required this.emptyMessage,
    this.addLabel,
    this.onAdd,
    this.header,
  });

  @override
  State<_ListTab<T>> createState() => _ListTabState<T>();
}

class _ListTabState<T> extends State<_ListTab<T>> {
  late Future<List<T>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.load();
  }

  void _reload() => setState(() => _future = widget.load());

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: () async {
        _reload();
        await _future;
      },
      child: FutureBuilder<List<T>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanLoadingView();
          }
          if (snapshot.hasError) {
            return JanErrorState.fromError(snapshot.error, fallback: 'Could not load.', onRetry: _reload);
          }
          final items = snapshot.data ?? [];
          return ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.md, JanSpace.xxl),
            children: [
              if (widget.header != null) ...[widget.header!, const SizedBox(height: JanSpace.sm)],
              if (widget.onAdd != null)
                Align(
                  alignment: Alignment.centerLeft,
                  child: FilledButton.icon(
                    onPressed: () async {
                      if (await widget.onAdd!(context)) _reload();
                    },
                    icon: const Icon(Icons.add_rounded),
                    label: Text(widget.addLabel ?? 'Add'),
                  ),
                ),
              const SizedBox(height: JanSpace.sm),
              if (items.isEmpty)
                JanEmptyState(icon: Icons.inbox_outlined, title: widget.emptyTitle, message: widget.emptyMessage)
              else
                for (final item in items)
                  Padding(
                    padding: const EdgeInsets.only(bottom: JanSpace.sm),
                    child: widget.row(context, item, _reload),
                  ),
            ],
          );
        },
      ),
    );
  }
}

Widget _activeSwitch(BuildContext context, bool active, Future<void> Function(bool) onChanged, VoidCallback reload) {
  return Switch(
    value: active,
    onChanged: (v) async {
      try {
        await onChanged(v);
        reload();
      } catch (e) {
        if (context.mounted) _showError(context, e);
      }
    },
  );
}

// ---------------------------------------------------------------- Departments

class _DepartmentsTab extends StatelessWidget {
  const _DepartmentsTab();

  @override
  Widget build(BuildContext context) {
    return _ListTab<AdminDepartment>(
      load: AdminApi.instance.listDepartments,
      emptyTitle: 'No departments',
      emptyMessage: 'Create the first department.',
      addLabel: 'Add department',
      onAdd: (ctx) => _editDepartment(ctx, null),
      row: (context, d, reload) => JanCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(d.name, style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
          subtitle: Text([
            if (d.description != null) d.description!,
            d.headUserId != null ? 'Head: user #${d.headUserId}' : 'No head assigned',
            if (!d.isActive) 'Inactive',
          ].join('\n')),
          isThreeLine: d.description != null,
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(
              tooltip: 'Edit',
              icon: const Icon(Icons.edit_outlined),
              onPressed: () async {
                if (await _editDepartment(context, d)) reload();
              },
            ),
            _activeSwitch(context, d.isActive, (v) => AdminApi.instance.setDepartmentActive(d.departmentId, v), reload),
          ]),
        ),
      ),
    );
  }

  static Future<bool> _editDepartment(BuildContext context, AdminDepartment? existing) async {
    final name = TextEditingController(text: existing?.name ?? '');
    final description = TextEditingController(text: existing?.description ?? '');
    int? headUserId = existing?.headUserId;
    // Only an ACTIVE department head already belonging to this department can be head (server rule).
    final heads = existing == null
        ? Future.value(<AdminUser>[])
        : AdminApi.instance.listUsers(role: 'DEPARTMENT_HEAD', status: 'ACTIVE', departmentId: existing.departmentId);
    final saved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) => AlertDialog(
          title: Text(existing == null ? 'New department' : 'Edit department'),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(controller: name, maxLength: 100, decoration: const InputDecoration(labelText: 'Name')),
              TextField(
                controller: description,
                maxLength: 300,
                decoration: const InputDecoration(labelText: 'Description (optional)'),
              ),
              if (existing != null)
                FutureBuilder<List<AdminUser>>(
                  future: heads,
                  builder: (context, snap) {
                    final users = snap.data ?? [];
                    final ids = users.map((u) => u.userId).toSet();
                    return DropdownButtonFormField<int?>(
                      value: ids.contains(headUserId) ? headUserId : null,
                      decoration: const InputDecoration(labelText: 'Department head'),
                      items: [
                        const DropdownMenuItem<int?>(value: null, child: Text('None')),
                        for (final u in users) DropdownMenuItem<int?>(value: u.userId, child: Text(u.fullName)),
                      ],
                      onChanged: (v) => setDialogState(() => headUserId = v),
                    );
                  },
                )
              else
                const Text('Assign a head after creating the department and a Department Head account in it.',
                    style: TextStyle(color: JanColors.muted, fontSize: 13)),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
            FilledButton(
              onPressed: () async {
                try {
                  await AdminApi.instance.saveDepartment(
                    departmentId: existing?.departmentId,
                    name: name.text.trim(),
                    description: description.text.trim(),
                    headUserId: existing == null ? null : headUserId,
                  );
                  if (dialogContext.mounted) Navigator.of(dialogContext).pop(true);
                } catch (e) {
                  if (dialogContext.mounted) _showError(dialogContext, e);
                }
              },
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );
    return saved ?? false;
  }
}

// ---------------------------------------------------------------- Wards

class _WardsTab extends StatelessWidget {
  const _WardsTab();

  @override
  Widget build(BuildContext context) {
    return _ListTab<AdminWard>(
      load: AdminApi.instance.listWards,
      emptyTitle: 'No wards',
      emptyMessage: 'Create the first ward.',
      addLabel: 'Add ward',
      onAdd: (ctx) => _editWard(ctx, null),
      header: const JanBanner(
        message: 'Boundaries are GeoJSON Polygon or MultiPolygon with [longitude, latitude] positions, '
            'for example exported from QGIS or geojson.io. Overlapping another active ward is rejected; '
            'sharing a border is fine. A saved boundary is used for GPS-to-ward matching immediately.',
      ),
      row: (context, w, reload) => JanCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(w.name, style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
          subtitle: Text([
            if (w.code != null) 'Code ${w.code}',
            w.hasBoundary ? 'Boundary on record' : 'No boundary - not matched from GPS',
            if (!w.isActive) 'Inactive',
          ].join(' · ')),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(
              tooltip: 'Edit',
              icon: const Icon(Icons.edit_outlined),
              onPressed: () async {
                if (await _editWard(context, w)) reload();
              },
            ),
            _activeSwitch(context, w.isActive, (v) => AdminApi.instance.setWardActive(w.wardId, v), reload),
          ]),
        ),
      ),
    );
  }

  static Future<bool> _editWard(BuildContext context, AdminWard? existing) async {
    final name = TextEditingController(text: existing?.name ?? '');
    final code = TextEditingController(text: existing?.code ?? '');
    final boundary = TextEditingController(text: existing?.boundaryGeojson ?? '');
    final saved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(existing == null ? 'New ward' : 'Edit ward'),
        content: SizedBox(
          width: 520,
          child: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(controller: name, maxLength: 150, decoration: const InputDecoration(labelText: 'Name')),
              TextField(controller: code, maxLength: 30, decoration: const InputDecoration(labelText: 'Code (optional)')),
              TextField(
                controller: boundary,
                minLines: 4,
                maxLines: 10,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                decoration: const InputDecoration(
                  labelText: 'Boundary GeoJSON (optional)',
                  helperText: 'Leave empty for no boundary. Saving replaces the previous boundary.',
                  alignLabelWithHint: true,
                ),
              ),
            ]),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
          FilledButton(
            onPressed: () async {
              try {
                await AdminApi.instance.saveWard(
                  wardId: existing?.wardId,
                  name: name.text.trim(),
                  code: code.text.trim(),
                  boundaryGeojson: boundary.text,
                );
                if (dialogContext.mounted) Navigator.of(dialogContext).pop(true);
              } catch (e) {
                if (dialogContext.mounted) _showError(dialogContext, e);
              }
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
    return saved ?? false;
  }
}

// ---------------------------------------------------------------- Sensitive zones

class _ZonesTab extends StatelessWidget {
  const _ZonesTab();

  @override
  Widget build(BuildContext context) {
    return _ListTab<SensitiveZone>(
      load: AdminApi.instance.listSensitiveZones,
      emptyTitle: 'No sensitive zones',
      emptyMessage: 'Complaints get no school/hospital/road weighting until zones are recorded.',
      addLabel: 'Add zone',
      onAdd: _addZone,
      header: const JanBanner(
        message: 'A complaint inside an active zone raises its AI severity by one level (SRS 15.8). '
            'Record each school, hospital or busy road as a centre point and a radius (10-5000 m).',
      ),
      row: (context, z, reload) => JanCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(z.name, style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
          subtitle: Text('${sensitiveZoneTypeLabels[z.zoneType] ?? z.zoneType} · '
              '${z.latitude.toStringAsFixed(6)}, ${z.longitude.toStringAsFixed(6)} · ${z.radiusMeters} m'
              '${z.isActive ? '' : ' · Inactive'}'),
          trailing: _activeSwitch(
              context, z.isActive, (v) => AdminApi.instance.setSensitiveZoneActive(z.zoneId, v), reload),
        ),
      ),
    );
  }

  static Future<bool> _addZone(BuildContext context) async {
    final name = TextEditingController();
    final lat = TextEditingController();
    final lng = TextEditingController();
    final radius = TextEditingController(text: '200');
    String type = 'SCHOOL';
    final saved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) => AlertDialog(
          title: const Text('New sensitive zone'),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(controller: name, maxLength: 150, decoration: const InputDecoration(labelText: 'Name')),
              DropdownButtonFormField<String>(
                value: type,
                decoration: const InputDecoration(labelText: 'Type'),
                items: sensitiveZoneTypeLabels.entries
                    .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
                    .toList(),
                onChanged: (v) => setDialogState(() => type = v ?? type),
              ),
              TextField(
                controller: lat,
                keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                decoration: const InputDecoration(labelText: 'Latitude'),
              ),
              TextField(
                controller: lng,
                keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                decoration: const InputDecoration(labelText: 'Longitude'),
              ),
              TextField(
                controller: radius,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Radius (metres)'),
              ),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
            FilledButton(
              onPressed: () async {
                final la = double.tryParse(lat.text.trim());
                final lo = double.tryParse(lng.text.trim());
                final r = int.tryParse(radius.text.trim());
                if (la == null || lo == null || r == null) {
                  _showError(dialogContext, 'Enter numbers for latitude, longitude and radius.');
                  return;
                }
                try {
                  await AdminApi.instance.createSensitiveZone(
                      name: name.text.trim(), zoneType: type, latitude: la, longitude: lo, radiusMeters: r);
                  if (dialogContext.mounted) Navigator.of(dialogContext).pop(true);
                } catch (e) {
                  if (dialogContext.mounted) _showError(dialogContext, e);
                }
              },
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );
    return saved ?? false;
  }
}

// ---------------------------------------------------------------- Jurisdiction review

class _JurisdictionTab extends StatelessWidget {
  const _JurisdictionTab();

  @override
  Widget build(BuildContext context) {
    return _ListTab<OutOfJurisdictionItem>(
      load: () => AdminApi.instance.listOutOfJurisdiction(),
      emptyTitle: 'Nothing to review',
      emptyMessage: 'No open complaint is located outside the configured boundary.',
      header: const JanBanner(
        tone: JanBannerTone.warning,
        message: 'These complaints were located outside the configured municipal boundary. Accept one into '
            'a ward if it belongs to the municipality; otherwise reject it through verification with a reason.',
      ),
      row: (context, item, reload) => JanCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(item.referenceNumber, style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
          subtitle: Text('${item.category ?? 'Unclassified'} · ${item.status}\n'
              '${item.latitude.toStringAsFixed(6)}, ${item.longitude.toStringAsFixed(6)}'
              ' (${item.locationSource ?? 'unknown source'})'),
          isThreeLine: true,
          trailing: OutlinedButton(
            onPressed: () async {
              final wards = await AdminApi.instance.listWards();
              if (!context.mounted) return;
              final wardId = await showDialog<int>(
                context: context,
                builder: (dialogContext) => SimpleDialog(
                  title: const Text('Accept into ward'),
                  children: [
                    for (final w in wards.where((w) => w.isActive))
                      SimpleDialogOption(
                        onPressed: () => Navigator.of(dialogContext).pop(w.wardId),
                        child: Text(w.name),
                      ),
                  ],
                ),
              );
              if (wardId == null) return;
              try {
                await AdminApi.instance.acceptIntoWard(item.complaintId, wardId);
                reload();
              } catch (e) {
                if (context.mounted) _showError(context, e);
              }
            },
            child: const Text('Accept'),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- Maintenance

class _MaintenanceTab extends StatefulWidget {
  const _MaintenanceTab();

  @override
  State<_MaintenanceTab> createState() => _MaintenanceTabState();
}

class _MaintenanceTabState extends State<_MaintenanceTab> {
  final _message = TextEditingController();
  final _announcement = TextEditingController();
  final _retry = TextEditingController(text: '30');
  bool _maintenance = false;
  bool _loading = true;
  bool _saving = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final s = await AdminApi.instance.getPlatformStatus();
      _maintenance = s.maintenanceMode;
      _message.text = s.maintenanceMessage ?? '';
      _announcement.text = s.announcement ?? '';
      _retry.text = '${(s.retryAfterSeconds / 60).round()}';
    } catch (e) {
      _error = e;
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await AdminApi.instance.updatePlatformStatus(
        maintenanceMode: _maintenance,
        maintenanceMessage: _message.text.trim(),
        retryAfterMinutes: int.tryParse(_retry.text.trim()),
        announcement: _announcement.text.trim(),
      );
      await PlatformStatusService.instance.refresh();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Platform status saved.')));
      }
    } catch (e) {
      if (mounted) _showError(context, e);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const JanLoadingView();
    if (_error != null) return JanErrorState.fromError(_error, fallback: 'Could not load.', onRetry: _load);
    return ListView(
      padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.md, JanSpace.xxl),
      children: [
        JanCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Maintenance mode', style: TextStyle(fontWeight: FontWeight.w800)),
              subtitle: const Text('Citizens and staff can read but not change anything; complaint submissions '
                  'are saved on the phone and sent automatically afterwards. Administrators are not blocked.'),
              value: _maintenance,
              onChanged: (v) => setState(() => _maintenance = v),
            ),
            TextField(
              controller: _message,
              maxLength: 200,
              decoration: const InputDecoration(labelText: 'Maintenance message (optional)'),
            ),
            TextField(
              controller: _retry,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Ask clients to retry after (minutes, 1-1440)'),
            ),
          ]),
        ),
        const SizedBox(height: JanSpace.md),
        JanCard(
          child: TextField(
            controller: _announcement,
            maxLength: 200,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'Announcement banner (optional)',
              helperText: 'Shown to everyone at the top of the app. Leave empty to remove it.',
            ),
          ),
        ),
        const SizedBox(height: JanSpace.md),
        FilledButton(onPressed: _saving ? null : _save, child: Text(_saving ? 'Saving...' : 'Save')),
      ],
    );
  }
}
