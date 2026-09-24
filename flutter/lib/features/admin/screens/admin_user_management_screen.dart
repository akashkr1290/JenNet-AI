import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../department/department_api.dart';
import '../admin_api.dart';
import '../models/admin_user.dart';

/// SRS 16.3 "User & Role Management" screen: list/search/filter staff,
/// create a staff account, change role, activate/suspend, reset
/// password, revoke sessions. See AdminUserService's Javadoc for the
/// SUPER_ADMIN-only-for-ADMIN-accounts rule this screen surfaces but
/// does not itself enforce (the backend is the real gate; a 403 here
/// just becomes a snackbar, same convention as every other screen).
class AdminUserManagementScreen extends StatefulWidget {
  const AdminUserManagementScreen({super.key});

  @override
  State<AdminUserManagementScreen> createState() => _AdminUserManagementScreenState();
}

class _AdminUserManagementScreenState extends State<AdminUserManagementScreen> {
  late Future<List<AdminUser>> _future;
  final _searchController = TextEditingController();
  String? _roleFilter;
  String? _statusFilter;

  @override
  void initState() {
    super.initState();
    _future = AdminApi.instance.listUsers();
  }

  void _refresh() {
    setState(() {
      _future = AdminApi.instance.listUsers(
        role: _roleFilter,
        status: _statusFilter,
        search: _searchController.text.trim().isEmpty ? null : _searchController.text.trim(),
      );
    });
  }

  void _showError(Object e) {
    final message = e is ApiException ? e.message : 'Something went wrong.';
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _openAddUserDialog() async {
    final created = await showDialog<AdminCreateUserResult>(
      context: context,
      builder: (_) => const _AddUserDialog(),
    );
    if (created == null) return;
    _refresh();
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (_) => _TemporaryPasswordDialog(result: created),
    );
  }

  Future<void> _changeRole(AdminUser user) async {
    final newRole = await showDialog<String>(
      context: context,
      builder: (_) => _RoleChoiceDialog(currentRole: user.role),
    );
    if (newRole == null || newRole == user.role) return;
    try {
      await AdminApi.instance.updateRole(user.userId, newRole);
      _refresh();
    } catch (e) {
      _showError(e);
    }
  }

  Future<void> _toggleStatus(AdminUser user) async {
    final newStatus = user.status == 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
    try {
      await AdminApi.instance.updateStatus(user.userId, newStatus);
      _refresh();
    } catch (e) {
      _showError(e);
    }
  }

  Future<void> _resetPassword(AdminUser user) async {
    try {
      await AdminApi.instance.resetPassword(user.userId);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Password reset OTP sent to the user\'s registered mobile number.')),
        );
      }
    } catch (e) {
      _showError(e);
    }
  }

  Future<void> _revokeSessions(AdminUser user) async {
    try {
      await AdminApi.instance.revokeSessions(user.userId);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('All active sessions revoked for this user.')),
        );
      }
    } catch (e) {
      _showError(e);
    }
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
                  controller: _searchController,
                  decoration: const InputDecoration(
                    hintText: 'Search by name or mobile number',
                    prefixIcon: Icon(Icons.search),
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  onSubmitted: (_) => _refresh(),
                ),
              ),
              const SizedBox(width: 8),
              IconButton(onPressed: _refresh, icon: const Icon(Icons.filter_alt_outlined), tooltip: 'Apply filters'),
              IconButton(onPressed: _openAddUserDialog, icon: const Icon(Icons.person_add_alt_1), tooltip: 'Add User'),
            ],
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: FutureBuilder<List<AdminUser>>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snapshot.hasError || !snapshot.hasData) {
                final message = snapshot.error is ApiException
                    ? (snapshot.error as ApiException).message
                    : 'Could not load users.';
                return Center(child: Text(message, textAlign: TextAlign.center));
              }
              final users = snapshot.data!;
              if (users.isEmpty) {
                return const Center(child: Text('No staff accounts match these filters.'));
              }
              return ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: users.length,
                itemBuilder: (context, index) => _userCard(users[index]),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _userCard(AdminUser user) {
    final isSuspended = user.status == 'SUSPENDED';
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
                  child: Text(user.fullName, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: (isSuspended ? Colors.red : Colors.green).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    user.status,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: isSuspended ? Colors.red.shade700 : Colors.green.shade700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text('${user.role} · ${user.mobileNumber}', style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                OutlinedButton(onPressed: () => _changeRole(user), child: const Text('Change Role')),
                OutlinedButton(
                  onPressed: () => _toggleStatus(user),
                  child: Text(isSuspended ? 'Activate' : 'Suspend'),
                ),
                OutlinedButton(onPressed: () => _resetPassword(user), child: const Text('Reset Password')),
                OutlinedButton(onPressed: () => _revokeSessions(user), child: const Text('Revoke Sessions')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _RoleChoiceDialog extends StatelessWidget {
  final String currentRole;
  const _RoleChoiceDialog({required this.currentRole});

  @override
  Widget build(BuildContext context) {
    return SimpleDialog(
      title: const Text('Change Role'),
      children: manageableStaffRoles
          .map((r) => SimpleDialogOption(
                onPressed: () => Navigator.of(context).pop(r),
                child: Row(
                  children: [
                    if (r == currentRole) const Icon(Icons.check, size: 16),
                    if (r == currentRole) const SizedBox(width: 6),
                    Text(r),
                  ],
                ),
              ))
          .toList(),
    );
  }
}

/// SRS 16.3 "Add User" form. Role options always include every entry of
/// [manageableStaffRoles]; a non-SUPER_ADMIN caller selecting ADMIN just
/// gets a 403 from AdminUserService#requireManageableRole - not filtered
/// out client-side, since the signed-in user's own role isn't otherwise
/// needed by this screen and adding a Session lookup purely to hide one
/// dropdown entry isn't worth the extra round trip (the error message
/// clearly explains why, matching every other screen's error-surfacing
/// convention).
class _AddUserDialog extends StatefulWidget {
  const _AddUserDialog();

  @override
  State<_AddUserDialog> createState() => _AddUserDialogState();
}

class _AddUserDialogState extends State<_AddUserDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _mobileController = TextEditingController();
  final _emailController = TextEditingController();
  String _role = manageableStaffRoles.first;
  int? _departmentId;
  List<DepartmentOption> _departments = [];
  bool _submitting = false;
  bool _loadingDepartments = true;

  @override
  void initState() {
    super.initState();
    DepartmentApi.instance.list().then((depts) {
      if (mounted) setState(() { _departments = depts; _loadingDepartments = false; });
    }).catchError((_) {
      if (mounted) setState(() => _loadingDepartments = false);
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _submitting = true);
    try {
      final result = await AdminApi.instance.createUser(
        fullName: _nameController.text.trim(),
        mobileNumber: _mobileController.text.trim(),
        email: _emailController.text.trim(),
        role: _role,
        departmentId: _departmentId,
      );
      if (mounted) Navigator.of(context).pop(result);
    } catch (e) {
      if (mounted) {
        final message = e is ApiException ? e.message : 'Could not create the account.';
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add User'),
      content: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _nameController,
                decoration: const InputDecoration(labelText: 'Full name'),
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              TextFormField(
                controller: _mobileController,
                decoration: const InputDecoration(labelText: 'Mobile number'),
                keyboardType: TextInputType.phone,
                validator: (v) =>
                    (v == null || !RegExp(r'^[6-9]\d{9}$').hasMatch(v.trim())) ? 'Enter a valid 10-digit number' : null,
              ),
              TextFormField(
                controller: _emailController,
                decoration: const InputDecoration(labelText: 'Email (optional)'),
                keyboardType: TextInputType.emailAddress,
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                value: _role,
                decoration: const InputDecoration(labelText: 'Role'),
                items: manageableStaffRoles.map((r) => DropdownMenuItem(value: r, child: Text(r))).toList(),
                onChanged: (v) => setState(() => _role = v!),
              ),
              const SizedBox(height: 8),
              _loadingDepartments
                  ? const Padding(
                      padding: EdgeInsets.symmetric(vertical: 8),
                      child: LinearProgressIndicator(),
                    )
                  : DropdownButtonFormField<int?>(
                      value: _departmentId,
                      decoration: const InputDecoration(labelText: 'Department (optional)'),
                      items: [
                        const DropdownMenuItem<int?>(value: null, child: Text('None')),
                        ..._departments.map((d) => DropdownMenuItem<int?>(value: d.departmentId, child: Text(d.name))),
                      ],
                      onChanged: (v) => setState(() => _departmentId = v),
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

/// See AdminUserService's Javadoc's "TEMPORARY PASSWORD DESIGN": shown
/// exactly once, right after creation, with an explicit warning that it
/// cannot be retrieved again.
class _TemporaryPasswordDialog extends StatelessWidget {
  final AdminCreateUserResult result;
  const _TemporaryPasswordDialog({required this.result});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Account Created'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${result.user.fullName} (${result.user.role}) has been created.'),
          const SizedBox(height: 12),
          const Text(
            'Temporary password (shown once - convey it to the user directly, it cannot be retrieved again):',
            style: TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 6),
          SelectableText(
            result.temporaryPassword,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 16),
          ),
        ],
      ),
      actions: [
        FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Done')),
      ],
    );
  }
}
