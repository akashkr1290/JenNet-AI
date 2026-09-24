import '../../settings/screens/privacy_policy_screen.dart';
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Create Account'), actions: [
        // Gap-backlog Patch 48: policies readable before an account exists.
        TextButton(
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PrivacyPolicyScreen())),
          child: const Text('Privacy'),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const TermsOfUseScreen())),
          child: const Text('Terms'),
        ),
      ]),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _fullNameController,
                decoration: const InputDecoration(labelText: 'Full Name', border: OutlineInputBorder()),
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Full name is required' : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _mobileController,
                decoration: const InputDecoration(
                  labelText: 'Mobile Number',
                  border: OutlineInputBorder(),
                  hintText: '10-digit number',
                ),
                keyboardType: TextInputType.phone,
                validator: (v) => (v == null || !_mobilePattern.hasMatch(v.trim()))
                    ? 'Enter a valid 10-digit mobile number'
                    : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _emailController,
                decoration: const InputDecoration(
                  labelText: 'Email (optional)',
                  border: OutlineInputBorder(),
                ),
                keyboardType: TextInputType.emailAddress,
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return null;
                  return v.contains('@') ? null : 'Enter a valid email address';
                },
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _passwordController,
                decoration: const InputDecoration(labelText: 'Password', border: OutlineInputBorder()),
                obscureText: true,
                validator: (v) => (v == null || !_passwordPattern.hasMatch(v))
                    ? 'At least 8 characters, with upper, lower, a digit, and a special character'
                    : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _confirmPasswordController,
                decoration: const InputDecoration(labelText: 'Confirm Password', border: OutlineInputBorder()),
                obscureText: true,
                validator: (v) => (v != _passwordController.text) ? 'Passwords do not match' : null,
              ),
              const SizedBox(height: 24),
              if (_error != null) ...[
                Text(_error!, style: const TextStyle(color: Colors.red)),
                const SizedBox(height: 16),
              ],
              FilledButton(
                onPressed: _loading ? null : _submit,
                child: _loading
                    ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Register'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
