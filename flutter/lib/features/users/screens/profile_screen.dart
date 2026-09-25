import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/error_text.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../../wards/models/ward.dart';
import '../../wards/wards_api.dart';
import '../user_api.dart';

/// Post-UI gap fix: "My Profile" - SRS 15.1 (Citizen Module) lists
/// "registration and profile management" and "reputation score display".
/// The backend has supported both since Phase 4/5 (GET/PUT /api/v1/users/me)
/// but no Flutter screen let a user edit their profile, and the Phase 17
/// docs recorded that nothing let a citizen set their ward after
/// registration. This screen closes that gap with the existing API only.
///
/// Editable: full name, and ward (citizens only). Mobile number and email
/// are shown read-only: PUT /users/me deliberately does not accept them
/// (changing a login identifier needs a re-verification flow the SRS does
/// not define - PROJECT_INTEGRATION.md Section 6, "Profile update excludes
/// mobileNumber/email").
class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  late Future<UserProfile> _future;
  UserProfile? _profile;

  List<Ward>? _wards;
  bool _loadingWards = false;
  String? _wardsError;
  int? _wardId;

  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  Future<UserProfile> _load() async {
    final me = await UserApi.instance.me();
    _profile = me;
    _nameController.text = me.fullName;
    _wardId = me.wardId;
    if (me.role == 'CITIZEN') _loadWards();
    return me;
  }

  void _reload() => setState(() => _future = _load());

  Future<void> _loadWards() async {
    setState(() {
      _loadingWards = true;
      _wardsError = null;
    });
    try {
      final wards = await WardsApi.instance.listActiveWards();
      if (mounted) setState(() => _wards = wards);
    } catch (e) {
      if (mounted) setState(() => _wardsError = e is ApiException ? e.message : 'Could not load the ward list.');
    } finally {
      if (mounted) setState(() => _loadingWards = false);
    }
  }

  Future<void> _save() async {
    final profile = _profile;
    if (profile == null || !_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      // PUT /users/me treats wardId:null as "clear the ward", so anyone who
      // cannot edit the ward here sends their current one back unchanged.
      final wardToSend = (profile.role == 'CITIZEN' && _wards != null) ? _wardId : profile.wardId;
      final updated = await UserApi.instance.updateMe(fullName: _nameController.text.trim(), wardId: wardToSend);
      if (!mounted) return;
      setState(() {
        _profile = updated;
        _wardId = updated.wardId;
      });
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Profile updated.')));
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not save your profile. Please try again.');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  static String _roleLabel(String role) => switch (role) {
        'CITIZEN' => 'Citizen',
        'GOVERNMENT_OFFICER' => 'Government Officer',
        'MAINTENANCE_TEAM' => 'Maintenance Team',
        'VERIFICATION_TEAM' => 'Verification Team',
        'DEPARTMENT_HEAD' => 'Department Head',
        'ADMIN' => 'Administrator',
        'SUPER_ADMIN' => 'Super Administrator',
        _ => role.replaceAll('_', ' '),
      };

  @override
  Widget build(BuildContext context) {
    return JanPage(
      title: 'My Profile',
      maxWidth: 720,
      body: FutureBuilder<UserProfile>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const JanLoadingView(message: 'Loading your profile...');
          }
          if (snapshot.hasError || _profile == null) {
            return JanErrorState.fromError(snapshot.error, fallback: 'Could not load your profile.', onRetry: _reload);
          }
          final p = _profile!;
          final isCitizen = p.role == 'CITIZEN';
          return Form(
            key: _formKey,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.md, JanSpace.md, JanSpace.xxl),
              children: [
                _header(p),
                const JanSectionHeader(title: 'Personal Details'),
                JanCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const JanFieldLabel('Full Name'),
                      TextFormField(
                        controller: _nameController,
                        enabled: !_saving,
                        maxLength: 100,
                        textCapitalization: TextCapitalization.words,
                        decoration: const InputDecoration(prefixIcon: Icon(Icons.badge_outlined), counterText: ''),
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Full name is required' : null,
                      ),
                      if (isCitizen) ...[
                        const SizedBox(height: JanSpace.md),
                        const JanFieldLabel('Ward / Area'),
                        _wardField(),
                      ],
                      const SizedBox(height: JanSpace.md),
                      if (_error != null) ...[
                        ErrorText(_error!),
                        const SizedBox(height: JanSpace.sm),
                      ],
                      FilledButton.icon(
                        onPressed: _saving ? null : _save,
                        icon: _saving
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(strokeWidth: 2.2, color: JanColors.white),
                              )
                            : const Icon(Icons.save_outlined),
                        label: Text(_saving ? 'Saving...' : 'Save Changes'),
                      ),
                    ],
                  ),
                ),
                const JanSectionHeader(title: 'Sign-in Details'),
                JanCard(
                  child: Column(
                    children: [
                      _readOnlyRow(Icons.phone_iphone_rounded, 'Mobile number',
                          p.mobileNumber != null ? '+91 ${p.mobileNumber}' : 'Not set'),
                      const Divider(height: 20),
                      _readOnlyRow(Icons.alternate_email_rounded, 'Email',
                          (p.email != null && p.email!.isNotEmpty) ? p.email! : 'Not set'),
                      const Divider(height: 20),
                      const Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(Icons.info_outline_rounded, size: 18, color: JanColors.muted),
                          SizedBox(width: JanSpace.xs),
                          Expanded(
                            child: Text(
                              'Your mobile number and email are your sign-in identifiers and cannot be changed here.',
                              style: TextStyle(fontSize: 12.5, color: JanColors.muted, height: 1.4),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _header(UserProfile p) {
    final initials = p.fullName
        .trim()
        .split(RegExp(r'\s+'))
        .where((x) => x.isNotEmpty)
        .take(2)
        .map((x) => x[0].toUpperCase())
        .join();
    final isCitizen = p.role == 'CITIZEN';
    return JanCard(
      gradient: const LinearGradient(colors: [JanColors.navy, JanColors.navyDeep]),
      elevated: false,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              CircleAvatar(
                radius: 30,
                backgroundColor: JanColors.white.withValues(alpha: 0.16),
                child: Text(
                  initials.isEmpty ? '?' : initials,
                  style: const TextStyle(color: JanColors.white, fontSize: 22, fontWeight: FontWeight.w800),
                ),
              ),
              const SizedBox(width: JanSpace.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      p.fullName,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: JanColors.white, fontSize: 19, fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      [_roleLabel(p.role), if (p.status != null) p.status!.replaceAll('_', ' ').toLowerCase()].join(' · '),
                      style: const TextStyle(color: Color(0xFFC9D8EA), fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (isCitizen && p.reputationScore != null) ...[
            const SizedBox(height: JanSpace.md),
            Semantics(
              label: 'Reputation score: ${p.reputationScore}',
              excludeSemantics: true,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: JanSpace.sm, vertical: JanSpace.xs),
                decoration: BoxDecoration(
                  color: JanColors.white.withValues(alpha: 0.12),
                  borderRadius: JanRadius.mdAll,
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.workspace_premium_outlined, color: JanColors.amber, size: 20),
                    const SizedBox(width: JanSpace.xs),
                    Text(
                      'Reputation score: ${p.reputationScore}',
                      style: const TextStyle(color: JanColors.white, fontWeight: FontWeight.w700),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _wardField() {
    if (_loadingWards) {
      return const InputDecorator(
        decoration: InputDecoration(prefixIcon: Icon(Icons.location_city_outlined)),
        child: Row(
          children: [
            SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2)),
            SizedBox(width: JanSpace.sm),
            Text('Loading wards...'),
          ],
        ),
      );
    }
    if (_wardsError != null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ErrorText('$_wardsError Your current ward is kept unchanged.'),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: _loadWards,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Retry loading wards'),
            ),
          ),
        ],
      );
    }
    final wards = _wards ?? const <Ward>[];
    // Keep the saved ward selectable even if it is no longer active.
    final knownIds = wards.map((w) => w.wardId).toSet();
    final current = (_wardId != null && !knownIds.contains(_wardId)) ? null : _wardId;
    return DropdownButtonFormField<int?>(
      key: ValueKey<int>(wards.length),
      initialValue: current,
      isExpanded: true,
      decoration: const InputDecoration(prefixIcon: Icon(Icons.location_city_outlined)),
      items: [
        const DropdownMenuItem<int?>(value: null, child: Text('Not set')),
        for (final w in wards)
          DropdownMenuItem<int?>(
            value: w.wardId,
            child: Text(w.code != null && w.code!.isNotEmpty ? '${w.name} (${w.code})' : w.name,
                overflow: TextOverflow.ellipsis),
          ),
      ],
      onChanged: _saving ? null : (v) => setState(() => _wardId = v),
    );
  }

  Widget _readOnlyRow(IconData icon, String label, String value) {
    return Semantics(
      label: '$label: $value',
      excludeSemantics: true,
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: const BoxDecoration(color: JanColors.infoLight, borderRadius: JanRadius.smAll),
            child: Icon(icon, size: 20, color: JanColors.primary),
          ),
          const SizedBox(width: JanSpace.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: const TextStyle(fontSize: 12.5, color: JanColors.muted, fontWeight: FontWeight.w600)),
                Text(value, style: const TextStyle(color: JanColors.ink, fontWeight: FontWeight.w600)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
