import '../../settings/screens/privacy_policy_screen.dart';
import '../../../core/widgets/error_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_form_widgets.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../widgets/auth_layout.dart';
import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../wards/models/ward.dart';
import '../../wards/wards_api.dart';
import '../auth_api.dart';
import 'otp_verification_screen.dart';

/// SRS Screen 16.1 "Registration / Login" (registration half) - Phase 17.
/// Public/unauthenticated screen (SecurityConfig permits `/auth/**`
/// without a token). Fields match RegisterRequest: Full Name, Mobile
/// Number, Email (optional), Ward/Area, Password.
///
/// Post-UI gap fix: SRS 16.1's "Ward/Area (dropdown)" is now collected.
/// The Phase 17 blocker (GET /api/v1/wards needs a JWT) was removed by
/// Gap-backlog Patch 8's public GET /api/v1/public/wards
/// (PublicWardController), which this screen now loads. The ward is
/// required whenever the list loads; if it cannot be loaded (e.g. the
/// server is unreachable) the citizen can retry, or register without one
/// (RegisterRequest.wardId is nullable) and set it later from My Profile.
class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});

  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  final _formKey = GlobalKey<FormState>();
  final _fullNameController = TextEditingController();
  final _mobileController = TextEditingController();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  bool _loading = false;
  bool _obscurePassword = true;
  bool _obscureConfirm = true;
  String? _error;

  // Ward/Area picker (SRS 16.1), loaded from the public ward endpoint.
  List<Ward>? _wards;
  bool _loadingWards = false;
  String? _wardsError;
  int? _selectedWardId;

  static final _mobilePattern = RegExp(r'^[6-9]\d{9}$');
  static final _passwordPattern =
      RegExp(r'^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^a-zA-Z0-9]).{8,}$');

  @override
  void initState() {
    super.initState();
    _loadWards();
  }

  Future<void> _loadWards() async {
    setState(() {
      _loadingWards = true;
      _wardsError = null;
    });
    try {
      final wards = await WardsApi.instance.listPublicWards();
      if (mounted) setState(() => _wards = wards);
    } catch (e) {
      if (mounted) {
        setState(() => _wardsError = e is ApiException ? e.message : 'Could not load the ward list.');
      }
    } finally {
      if (mounted) setState(() => _loadingWards = false);
    }
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final mobile = _mobileController.text.trim();
    try {
      await AuthApi.instance.register(
        fullName: _fullNameController.text.trim(),
        mobileNumber: mobile,
        email: _emailController.text.trim(),
        password: _passwordController.text,
        wardId: _selectedWardId,
      );
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => OtpVerificationScreen(mobileNumber: mobile, purpose: 'REGISTRATION'),
        ),
      );
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      setState(() => _error = 'Registration failed: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  void dispose() {
    _fullNameController.dispose();
    _mobileController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  void _openPrivacy() =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PrivacyPolicyScreen()));

  void _openTerms() =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => const TermsOfUseScreen()));

  Widget _wardField() {
    if (_loadingWards) {
      return Semantics(
        liveRegion: true,
        child: const InputDecorator(
          decoration: InputDecoration(prefixIcon: Icon(Icons.location_city_outlined)),
          child: Row(
            children: [
              SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: JanSpace.sm),
              Text('Loading wards...'),
            ],
          ),
        ),
      );
    }
    if (_wardsError != null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ErrorText('$_wardsError You can retry, or continue and set your ward later in My Profile.'),
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
    if (wards.isEmpty) {
      return const Text(
        'No wards are configured yet. You can set your ward later in My Profile.',
        style: TextStyle(color: JanColors.muted),
      );
    }
    return DropdownButtonFormField<int>(
      initialValue: _selectedWardId,
      isExpanded: true,
      decoration: const InputDecoration(
        hintText: 'Select your ward',
        prefixIcon: Icon(Icons.location_city_outlined),
      ),
      items: [
        for (final w in wards)
          DropdownMenuItem<int>(
            value: w.wardId,
            child: Text(w.code != null && w.code!.isNotEmpty ? '${w.name} (${w.code})' : w.name,
                overflow: TextOverflow.ellipsis),
          ),
      ],
      onChanged: _loading ? null : (v) => setState(() => _selectedWardId = v),
      validator: (v) => v == null ? 'Select your ward' : null,
    );
  }

  // UI redesign: reference "Create your account". Validation rules, the API
  // call and the OTP hand-off above are unchanged.
  @override
  Widget build(BuildContext context) {
    return JanAuthLayout(
      showBackButton: true,
      child: Form(
        key: _formKey,
        autovalidateMode: AutovalidateMode.onUserInteraction,
        child: AutofillGroup(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const JanAuthHeader(
                title: 'Create your account',
                subtitle: 'Join JanNet AI to report and track civic issues in your city.',
              ),
              const JanFieldLabel('Full Name'),
              TextFormField(
                controller: _fullNameController,
                textInputAction: TextInputAction.next,
                textCapitalization: TextCapitalization.words,
                autofillHints: const [AutofillHints.name],
                decoration: const InputDecoration(
                  hintText: 'Your full name',
                  prefixIcon: Icon(Icons.badge_outlined),
                ),
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Full name is required' : null,
              ),
              const SizedBox(height: JanSpace.md),
              const JanFieldLabel('Mobile Number'),
              TextFormField(
                controller: _mobileController,
                keyboardType: TextInputType.phone,
                textInputAction: TextInputAction.next,
                autofillHints: const [AutofillHints.telephoneNumberNational],
                decoration: const InputDecoration(
                  hintText: '10-digit number',
                  prefixIcon: Icon(Icons.phone_iphone_rounded),
                  prefixText: '+91  ',
                  helperText: 'A verification code will be sent to this number by SMS.',
                ),
                validator: (v) => (v == null || !_mobilePattern.hasMatch(v.trim()))
                    ? 'Enter a valid 10-digit mobile number'
                    : null,
              ),
              const SizedBox(height: JanSpace.md),
              const JanFieldLabel('Email Address (optional)'),
              TextFormField(
                controller: _emailController,
                keyboardType: TextInputType.emailAddress,
                textInputAction: TextInputAction.next,
                autofillHints: const [AutofillHints.email],
                decoration: const InputDecoration(
                  hintText: 'name@example.com',
                  prefixIcon: Icon(Icons.alternate_email_rounded),
                ),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return null;
                  return v.contains('@') ? null : 'Enter a valid email address';
                },
              ),
              const SizedBox(height: JanSpace.md),
              const JanFieldLabel('Ward / Area'),
              _wardField(),
              const SizedBox(height: JanSpace.md),
              const JanFieldLabel('Create Password'),
              TextFormField(
                controller: _passwordController,
                obscureText: _obscurePassword,
                textInputAction: TextInputAction.next,
                autofillHints: const [AutofillHints.newPassword],
                decoration: InputDecoration(
                  hintText: 'At least 8 characters',
                  prefixIcon: const Icon(Icons.lock_outline_rounded),
                  suffixIcon: IconButton(
                    tooltip: _obscurePassword ? 'Show password' : 'Hide password',
                    icon: Icon(_obscurePassword ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                    onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                  ),
                ),
                validator: (v) => (v == null || !_passwordPattern.hasMatch(v))
                    ? 'At least 8 characters, with upper, lower, a digit, and a special character'
                    : null,
              ),
              JanPasswordStrength(controller: _passwordController),
              const SizedBox(height: JanSpace.md),
              const JanFieldLabel('Confirm Password'),
              TextFormField(
                controller: _confirmPasswordController,
                obscureText: _obscureConfirm,
                textInputAction: TextInputAction.done,
                decoration: InputDecoration(
                  hintText: 'Re-enter your password',
                  prefixIcon: const Icon(Icons.lock_reset_rounded),
                  suffixIcon: IconButton(
                    tooltip: _obscureConfirm ? 'Show password' : 'Hide password',
                    icon: Icon(_obscureConfirm ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                    onPressed: () => setState(() => _obscureConfirm = !_obscureConfirm),
                  ),
                ),
                validator: (v) => (v != _passwordController.text) ? 'Passwords do not match' : null,
              ),
              const SizedBox(height: JanSpace.xl),
              if (_error != null) ...[
                ErrorText(_error!),
                const SizedBox(height: JanSpace.md),
              ],
              FilledButton(
                onPressed: _loading ? null : _submit,
                child: _loading ? const JanButtonSpinner() : const Text('Create Account'),
              ),
              const SizedBox(height: JanSpace.sm),
              Wrap(
                alignment: WrapAlignment.center,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  const Text('By creating an account you agree to the', style: TextStyle(color: JanColors.muted, fontSize: 13)),
                  TextButton(onPressed: _openTerms, child: const Text('Terms of Use')),
                  const Text('and', style: TextStyle(color: JanColors.muted, fontSize: 13)),
                  TextButton(onPressed: _openPrivacy, child: const Text('Privacy Policy')),
                ],
              ),
              TextButton(
                onPressed: _loading ? null : () => Navigator.of(context).maybePop(),
                child: const Text.rich(
                  TextSpan(
                    text: 'Already have an account? ',
                    style: TextStyle(color: JanColors.slate, fontWeight: FontWeight.w500),
                    children: [
                      TextSpan(
                        text: 'Sign In',
                        style: TextStyle(color: JanColors.primary, fontWeight: FontWeight.w800, decoration: TextDecoration.underline),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
