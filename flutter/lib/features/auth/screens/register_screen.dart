import '../../settings/screens/privacy_policy_screen.dart';
import '../../../core/widgets/error_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_form_widgets.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../widgets/auth_layout.dart';
import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../auth_api.dart';
import 'otp_verification_screen.dart';

/// SRS Screen 16.1 "Registration / Login" (registration half) - Phase 17.
/// Public/unauthenticated screen (SecurityConfig permits `/auth/**`
/// without a token). Fields match RegisterRequest exactly: Full Name,
/// Mobile Number, Email (optional), Password. Ward/Area is the one field
/// SRS 16.1 lists that this screen deliberately does NOT collect -
/// WardController (`GET /api/v1/wards`) requires authentication
/// (SecurityConfig: `.requestMatchers("/api/v1/wards/**").authenticated()`,
/// a Phase 5 decision, unchanged here), which a not-yet-registered citizen
/// structurally cannot have. RegisterRequest.wardId is already nullable
/// for exactly this reason, so registration proceeds with wardId=null
/// rather than inventing a public wards endpoint or a free-text ward
/// field this schema has no matching lookup for. No screen anywhere in
/// this app currently lets a citizen set their ward after registration
/// either (PersonalSettingsScreen has no ward field) - documented as a
/// known limitation in PROJECT_INTEGRATION.md Section 6, not silently
/// worked around.
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

  static final _mobilePattern = RegExp(r'^[6-9]\d{9}$');
  static final _passwordPattern =
      RegExp(r'^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^a-zA-Z0-9]).{8,}$');

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
