import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
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
            return const JanLoadingView(message: 'Loading platform settings...');
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return JanErrorState.fromError(snapshot.error, fallback: 'Could not load settings.', onRetry: _refresh);
          }
          final settings = snapshot.data!;
          if (settings.isEmpty) {
            return const JanEmptyState(
              icon: Icons.tune_rounded,
              title: 'No settings',
              message: 'No platform settings are available.',
            );
          }
          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
            itemCount: settings.length,
            itemBuilder: (context, index) => _settingCard(settings[index]),
          );
        },
      ),
    );
  }

  Widget _settingCard(PlatformSetting setting) {
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.sm),
      child: JanCard(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.sm, JanSpace.sm),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: setting.overridden ? JanColors.infoLight : JanColors.surfaceAlt,
                borderRadius: JanRadius.smAll,
              ),
              child: Icon(
                setting.overridden ? Icons.edit_note_rounded : Icons.settings_suggest_outlined,
                color: setting.overridden ? JanColors.primary : JanColors.muted,
              ),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(platformSettingLabels[setting.key] ?? setting.key,
                      style: const TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
                  const SizedBox(height: 2),
                  Text(
                    setting.overridden
                        ? 'Current: ${setting.displayValue}${setting.updatedByName != null ? " (set by ${setting.updatedByName})" : ""}'
                        : 'Using recommended default: ${setting.displayValue}',
                    style: TextStyle(
                      fontSize: 13,
                      color: setting.overridden ? JanColors.primary : JanColors.muted,
                      fontWeight: setting.overridden ? FontWeight.w600 : FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: JanSpace.xs),
            OutlinedButton(onPressed: () => _editSetting(setting), child: const Text('Edit')),
          ],
        ),
      ),
    );
  }
}
