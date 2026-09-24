import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../department/department_api.dart';
import '../admin_api.dart';
import '../models/admin_routing_rule.dart';

/// SRS 17.4 "Admin — Routing Rule Form" / 16.3 Routing screen. Rule
/// *creation* has existed since Phase 11 (RoutingRuleController) with no
/// Flutter caller until now; "View Change History" and "Deactivate" are
/// new this phase (RoutingRuleService's class Javadoc explains why
/// deactivate is the closest available analog to the screen's own
/// "Revert to Default" action). Shows only active rules by default, with
/// a toggle to the full history view.
class AdminRoutingRulesScreen extends StatefulWidget {
  const AdminRoutingRulesScreen({super.key});

  @override
  State<AdminRoutingRulesScreen> createState() => _AdminRoutingRulesScreenState();
}

class _AdminRoutingRulesScreenState extends State<AdminRoutingRulesScreen> {
  late Future<List<AdminRoutingRule>> _future;
  bool _showHistory = false;

  @override
  void initState() {
    super.initState();
    _future = AdminApi.instance.listActiveRoutingRules();
  }

  void _refresh() {
    setState(() {
      _future = _showHistory ? AdminApi.instance.routingRuleHistory() : AdminApi.instance.listActiveRoutingRules();
    });
  }

  void _showError(Object e) {
    final message = e is ApiException ? e.message : 'Something went wrong.';
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _deactivate(AdminRoutingRule rule) async {
    try {
      await AdminApi.instance.deactivateRoutingRule(rule.routingRuleId);
      _refresh();
    } catch (e) {
      _showError(e);
    }
  }

  Future<void> _openAddRuleDialog() async {
    final created = await showDialog<AdminRoutingRule>(
      context: context,
      builder: (_) => const _AddRoutingRuleDialog(),
    );
    if (created != null) _refresh();
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
                child: SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('View Change History'),
                  value: _showHistory,
                  onChanged: (v) {
                    setState(() => _showHistory = v);
                    _refresh();
                  },
                ),
              ),
              IconButton(onPressed: _openAddRuleDialog, icon: const Icon(Icons.add_circle_outline), tooltip: 'Add Rule'),
            ],
          ),
        ),
        Expanded(
          child: FutureBuilder<List<AdminRoutingRule>>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snapshot.hasError || !snapshot.hasData) {
                final message = snapshot.error is ApiException
                    ? (snapshot.error as ApiException).message
                    : 'Could not load routing rules.';
                return Center(child: Text(message, textAlign: TextAlign.center));
              }
              final rules = snapshot.data!;
              if (rules.isEmpty) {
                return const Center(child: Text('No routing rules configured yet.'));
              }
              return ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: rules.length,
                itemBuilder: (context, index) => _ruleCard(rules[index]),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _ruleCard(AdminRoutingRule rule) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(rule.issueCategory, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: (rule.isActive ? Colors.green : Colors.grey).withOpacity(0.12),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    rule.isActive ? 'ACTIVE' : 'INACTIVE',
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: rule.isActive ? Colors.green.shade700 : Colors.grey.shade700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text('${rule.departmentName} · SLA ${rule.slaHours}h · effective ${rule.effectiveFrom}',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
            Text('AI confidence ${rule.aiConfidenceThreshold}% · duplicate similarity ${rule.duplicateSimilarityThreshold}%',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
            if (rule.isActive) ...[
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: OutlinedButton(onPressed: () => _deactivate(rule), child: const Text('Deactivate')),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// SRS 17.4 "Admin — Routing Rule Form". Threshold fields are optional -
/// left blank, the backend applies the Admin-configured platform default
/// (RoutingRuleService#create, Phase 14) or the literal Table 10 default
/// if no override exists.
class _AddRoutingRuleDialog extends StatefulWidget {
  const _AddRoutingRuleDialog();

  @override
  State<_AddRoutingRuleDialog> createState() => _AddRoutingRuleDialogState();
}

class _AddRoutingRuleDialogState extends State<_AddRoutingRuleDialog> {
  final _formKey = GlobalKey<FormState>();
  final _slaHoursController = TextEditingController();
  final _aiThresholdController = TextEditingController();
  final _duplicateThresholdController = TextEditingController();
  String _category = issueCategories.first;
  int? _departmentId;
  List<DepartmentOption> _departments = [];
  bool _submitting = false;
  bool _loadingDepartments = true;

  @override
  void initState() {
    super.initState();
    DepartmentApi.instance.list().then((depts) {
      if (mounted) {
        setState(() {
          _departments = depts;
          _departmentId = depts.isNotEmpty ? depts.first.departmentId : null;
          _loadingDepartments = false;
        });
      }
    }).catchError((_) {
      if (mounted) setState(() => _loadingDepartments = false);
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate() || _departmentId == null) return;
    setState(() => _submitting = true);
    try {
      final created = await AdminApi.instance.createRoutingRule(
        issueCategory: _category,
        departmentId: _departmentId!,
        slaHours: int.parse(_slaHoursController.text.trim()),
        aiConfidenceThreshold: _aiThresholdController.text.trim().isEmpty ? null : _aiThresholdController.text.trim(),
        duplicateSimilarityThreshold:
            _duplicateThresholdController.text.trim().isEmpty ? null : _duplicateThresholdController.text.trim(),
      );
      if (mounted) Navigator.of(context).pop(created);
    } catch (e) {
      if (mounted) {
        final message = e is ApiException ? e.message : 'Could not create the routing rule.';
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add Routing Rule'),
      content: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                value: _category,
                decoration: const InputDecoration(labelText: 'Issue category'),
                items: issueCategories.map((c) => DropdownMenuItem(value: c, child: Text(c))).toList(),
                onChanged: (v) => setState(() => _category = v!),
              ),
              const SizedBox(height: 8),
              _loadingDepartments
                  ? const Padding(padding: EdgeInsets.symmetric(vertical: 8), child: LinearProgressIndicator())
                  : DropdownButtonFormField<int?>(
                      value: _departmentId,
                      decoration: const InputDecoration(labelText: 'Department'),
                      items: _departments
                          .map((d) => DropdownMenuItem<int?>(value: d.departmentId, child: Text(d.name)))
                          .toList(),
                      onChanged: (v) => setState(() => _departmentId = v),
                      validator: (v) => v == null ? 'Required' : null,
                    ),
              TextFormField(
                controller: _slaHoursController,
                decoration: const InputDecoration(labelText: 'SLA hours'),
                keyboardType: TextInputType.number,
                validator: (v) => (v == null || int.tryParse(v.trim()) == null) ? 'Enter a whole number' : null,
              ),
              TextFormField(
                controller: _aiThresholdController,
                decoration: const InputDecoration(labelText: 'AI confidence threshold % (optional)'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              TextFormField(
                controller: _duplicateThresholdController,
                decoration: const InputDecoration(labelText: 'Duplicate similarity threshold % (optional)'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _submitting ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          child: _submitting
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Create'),
        ),
      ],
    );
  }
}
