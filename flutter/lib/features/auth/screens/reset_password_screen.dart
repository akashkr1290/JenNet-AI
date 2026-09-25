import 'package:flutter/material.dart';

import '../../../core/widgets/error_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_form_widgets.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../widgets/auth_layout.dart';
import '../../../core/api/api_exception.dart';
import '../auth_api.dart';
import 'login_screen.dart';

/// SRS 15.2 business rule "password reset via OTP" - Phase 17. Mobile
/// number arrives pre-filled from ForgotPasswordScreen (editable, in case
/// the citizen navigated here directly or made a typo). Matches
/// ResetPasswordRequest exactly: mobileNumber, otpCode, newPassword.
class ResetPasswordScreen extends StatefulWidget {
  final String mobileNumber;

  const ResetPasswordScreen({super.key, required this.mobileNumber});

  @override
  State<ResetPasswordScreen> createState() => _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends State<ResetPasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _mobileController;
  final _otpController = TextEditingController();
  final _newPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  bool _loading = false;
  String? _error;

  static final _passwordPattern =
      RegExp(r'^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^a-zA-Z0-9]).{8,}$');

  @override
  void initState() {
    super.initState();
    _mobileController = TextEditingController(text: widget.mobileNumber);
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await AuthApi.instance.resetPassword(
        mobileNumber: _mobileController.text.trim(),
        otpCode: _otpController.text.trim(),
        newPassword: _newPasswordController.text,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Password reset. Please sign in.')));
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const LoginScreen()),
        (route) => false,
      );
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      setState(() => _error = 'Password reset failed: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  void dispose() {
    _mobileController.dispose();
    _otpController.dispose();
    _newPasswordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  // UI redesign; validators and the reset call above are unchanged.
  @override
  Widget build(BuildContext context) {
    return JanAuthLayout(
      showBackButton: true,
      child: Form(
        key: _formKey,
        autovalidateMode: AutovalidateMode.onUserInteraction,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const JanAuthHeader(
              title: 'Reset password',
              subtitle: 'Enter the code we sent by SMS and choose a new password.',
            ),
            const JanFieldLabel('Mobile Number'),
            TextFormField(
              controller: _mobileController,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(prefixIcon: Icon(Icons.phone_iphone_rounded), prefixText: '+91  '),
              validator: (v) =>
                  (v == null || !RegExp(r'^[6-9]\d{9}$').hasMatch(v.trim())) ? 'Enter a valid mobile number' : null,
            ),
            const SizedBox(height: JanSpace.md),
            const JanFieldLabel('Reset Code'),
            TextFormField(
              controller: _otpController,
              keyboardType: TextInputType.number,
              maxLength: 6,
              autofillHints: const [AutofillHints.oneTimeCode],
              decoration: const InputDecoration(
                hintText: '6-digit code',
                prefixIcon: Icon(Icons.pin_outlined),
                counterText: '',
              ),
              validator: (v) => (v == null || !RegExp(r'^\d{6}$').hasMatch(v)) ? 'Enter the 6-digit code' : null,
            ),
            const SizedBox(height: JanSpace.md),
            const JanFieldLabel('New Password'),
            TextFormField(
              controller: _newPasswordController,
              obscureText: true,
              autofillHints: const [AutofillHints.newPassword],
              decoration: const InputDecoration(prefixIcon: Icon(Icons.lock_outline_rounded)),
              validator: (v) => (v == null || !_passwordPattern.hasMatch(v))
                  ? 'At least 8 characters, with upper, lower, a digit, and a special character'
                  : null,
            ),
            JanPasswordStrength(controller: _newPasswordController),
            const SizedBox(height: JanSpace.md),
            const JanFieldLabel('Confirm New Password'),
            TextFormField(
              controller: _confirmPasswordController,
              obscureText: true,
              decoration: const InputDecoration(prefixIcon: Icon(Icons.lock_reset_rounded)),
              validator: (v) => (v != _newPasswordController.text) ? 'Passwords do not match' : null,
            ),
            const SizedBox(height: JanSpace.xl),
            if (_error != null) ...[
              ErrorText(_error!),
              const SizedBox(height: JanSpace.md),
            ],
            FilledButton(
              onPressed: _loading ? null : _submit,
              child: _loading ? const JanButtonSpinner() : const Text('Reset Password'),
            ),
          ],
        ),
      ),
    );
  }
}
