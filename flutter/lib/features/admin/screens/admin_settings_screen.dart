import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../admin_api.dart';
import '../models/platform_setting.dart';

/// SRS 16.3 Admin Settings screen / 15.15 "Admin-level default
/// thresholds". Each row shows the current value (override if one
/// exists, else the recommended default - see
/// PlatformSetting.displayValue's Javadoc-equivalent comment) with an
/// inline edit affordance; range/type validation happens server-side
/// (PlatformSettingKey#validate) and a rejected value surfaces as a
/// snackbar, same convention as every other screen.
class AdminSettingsScreen extends StatefulWidget {
  const AdminSettingsScreen({super.key});

  @override
  State<AdminSettingsScreen> createState() => _AdminSettingsScreenState();
}

class _AdminSettingsScreenState extends State<AdminSettingsScreen> {
  late Future<List<PlatformSetting>> _future;

  @override
  void initState() {
    super.initState();
    _future = AdminApi.instance.listSettings();
  }

  void _refresh() {
    setState(() => _future = AdminApi.instance.listSettings());
  }

  void _showError(Object e) {
    final message = e is ApiException ? e.message : 'Something went wrong.';
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _editSetting(PlatformSetting setting) async {
    final controller = TextEditingController(text: setting.displayValue);
    final newValue = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(platformSettingLabels[setting.key] ?? setting.key),
        content: TextField(
          controller: controller,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Value',
            helperText: 'Recommended default: ${setting.recommendedDefault}',
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (newValue == null || newValue.isEmpty) return;
    try {
      await AdminApi.instance.updateSetting(setting.key, newValue);
      _refresh();
    } catch (e) {
      _showError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: () async {
        _refresh();
        await _future;
      },
      child: FutureBuilder<List<PlatformSetting>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            final message =
                snapshot.error is ApiException ? (snapshot.error as ApiException).message : 'Could not load settings.';
            return ListView(
              children: [
                const SizedBox(height: 120),
                Center(child: Text(message, textAlign: TextAlign.center)),
              ],
            );
          }
          final settings = snapshot.data!;
          return ListView.builder(
            padding: const EdgeInsets.all(16),
            itemCount: settings.length,
            itemBuilder: (context, index) => _settingCard(settings[index]),
          );
        },
      ),
    );
  }

  Widget _settingCard(PlatformSetting setting) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        title: Text(platformSettingLabels[setting.key] ?? setting.key),
        subtitle: Text(
          setting.overridden
              ? 'Current: ${setting.displayValue}${setting.updatedByName != null ? " (set by ${setting.updatedByName})" : ""}'
              : 'Using recommended default: ${setting.displayValue}',
          style: TextStyle(color: setting.overridden ? Colors.indigo.shade700 : Colors.grey.shade600),
        ),
        trailing: OutlinedButton(onPressed: () => _editSetting(setting), child: const Text('Edit')),
      ),
    );
  }
}
