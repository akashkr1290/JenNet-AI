import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/validators.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
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

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
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
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, 0),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _searchController,
                  textInputAction: TextInputAction.search,
                  decoration: const InputDecoration(
                    hintText: 'Search by name or mobile number',
                    prefixIcon: Icon(Icons.search_rounded),
                    isDense: true,
                  ),
                  onSubmitted: (_) => _refresh(),
                ),
              ),
              const SizedBox(width: JanSpace.xs),
              IconButton.filledTonal(
                onPressed: _refresh,
                icon: const Icon(Icons.filter_alt_outlined),
                tooltip: 'Apply filters',
              ),
              const SizedBox(width: JanSpace.xxs),
              IconButton.filled(
                onPressed: _openAddUserDialog,
                icon: const Icon(Icons.person_add_alt_1_rounded),
                tooltip: 'Add User',
              ),
            ],
          ),
        ),
        const SizedBox(height: JanSpace.xs),
        Expanded(
          child: FutureBuilder<List<AdminUser>>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const JanSkeletonList(semanticLabel: 'Loading users');
              }
              if (snapshot.hasError || !snapshot.hasData) {
                return JanErrorState.fromError(snapshot.error, fallback: 'Could not load users.', onRetry: _refresh);
              }
              final users = snapshot.data!;
              if (users.isEmpty) {
                return JanEmptyState(
                  icon: Icons.group_off_outlined,
                  title: 'No users found',
                  message: 'No staff accounts match these filters.',
                  actionLabel: 'Add User',
                  onAction: _openAddUserDialog,
                  color: JanColors.primary,
                );
              }
              return LayoutBuilder(builder: (context, constraints) {
                final columns = constraints.maxWidth >= 1000 ? 2 : 1;
                if (columns == 1) {
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
                    itemCount: users.length,
                    itemBuilder: (context, index) => _userCard(users[index]),
                  );
                }
                final width = ((constraints.maxWidth - JanSpace.md * 3) / 2).floorToDouble();
                return SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
                  child: Wrap(
                    spacing: JanSpace.md,
                    children: [for (final u in users) SizedBox(width: width, child: _userCard(u))],
                  ),
                );
              });
            },
          ),
        ),
      ],
    );
  }

  Widget _userCard(AdminUser user) {
    final isSuspended = user.status == 'SUSPENDED';
    final initials = user.fullName
        .trim()
        .split(RegExp(r'\s+'))
        .where((p) => p.isNotEmpty)
        .take(2)
        .map((p) => p[0].toUpperCase())
        .join();
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.sm),
      child: JanCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 20,
                  backgroundColor: JanColors.primaryLight,
                  child: Text(initials.isEmpty ? '?' : initials,
                      style: const TextStyle(color: JanColors.navy, fontWeight: FontWeight.w800)),
                ),
                const SizedBox(width: JanSpace.sm),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(user.fullName,
                          style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15, color: JanColors.navy)),
                      Text('${user.role} · ${user.mobileNumber}',
                          style: const TextStyle(fontSize: 12.5, color: JanColors.slate)),
                    ],
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: isSuspended ? JanColors.errorLight : JanColors.tealLight,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        isSuspended ? Icons.block_rounded : Icons.check_circle_rounded,
                        size: 14,
                        color: isSuspended ? JanColors.error : JanColors.teal,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        user.status,
                        style: TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w700,
                          color: isSuspended ? JanColors.error : JanColors.teal,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: JanSpace.sm),
            Wrap(
              spacing: JanSpace.xs,
              runSpacing: JanSpace.xs,
              children: [
                OutlinedButton(onPressed: () => _changeRole(user), child: const Text('Change Role')),
                OutlinedButton(
                  onPressed: () => _toggleStatus(user),
                  style: isSuspended
                      ? null
                      : OutlinedButton.styleFrom(
                          foregroundColor: JanColors.error,
                          side: const BorderSide(color: JanColors.error),
                        ),
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
                    if (r == currentRole) const Icon(Icons.check_rounded, size: 18, color: JanColors.teal),
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
      if (mounted) {
        setState(() {
          _departments = depts;
          _loadingDepartments = false;
        });
      }
    }).catchError((_) {
      if (mounted) setState(() => _loadingDepartments = false);
    });
  }

  @override
  void dispose() {
    _nameController.dispose();
    _mobileController.dispose();
    _emailController.dispose();
    super.dispose();
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
      icon: const Icon(Icons.person_add_alt_1_rounded, color: JanColors.primary),
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
                validator: validatePersonName, // audit GAP-054 (SRS 17.1)
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
                initialValue: _role,
                isExpanded: true,
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
                      initialValue: _departmentId,
                      isExpanded: true,
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
              ? const SizedBox(
                  height: 16,
                  width: 16,
                  child: CircularProgressIndicator(strokeWidth: 2, color: JanColors.white),
                )
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
      icon: const Icon(Icons.check_circle_rounded, color: JanColors.teal, size: 40),
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
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(JanSpace.sm),
            decoration: BoxDecoration(
              color: JanColors.amberLight,
              borderRadius: JanRadius.smAll,
              border: Border.all(color: JanColors.amber),
            ),
            child: SelectableText(
              result.temporaryPassword,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 16, fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
      actions: [
        FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Done')),
      ],
    );
  }
}
