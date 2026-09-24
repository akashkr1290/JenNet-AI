import 'package:flutter/material.dart';

import '../../../core/widgets/error_text.dart';
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Reset Password')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _mobileController,
                decoration: const InputDecoration(labelText: 'Mobile Number', border: OutlineInputBorder()),
                keyboardType: TextInputType.phone,
                validator: (v) =>
                    (v == null || !RegExp(r'^[6-9]\d{9}$').hasMatch(v.trim())) ? 'Enter a valid mobile number' : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _otpController,
                decoration: const InputDecoration(labelText: 'OTP Code', border: OutlineInputBorder()),
                keyboardType: TextInputType.number,
                maxLength: 6,
                validator: (v) => (v == null || !RegExp(r'^\d{6}$').hasMatch(v)) ? 'Enter the 6-digit code' : null,
              ),
              TextFormField(
                controller: _newPasswordController,
                decoration: const InputDecoration(labelText: 'New Password', border: OutlineInputBorder()),
                obscureText: true,
                validator: (v) => (v == null || !_passwordPattern.hasMatch(v))
                    ? 'At least 8 characters, with upper, lower, a digit, and a special character'
                    : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _confirmPasswordController,
                decoration: const InputDecoration(labelText: 'Confirm New Password', border: OutlineInputBorder()),
                obscureText: true,
                validator: (v) => (v != _newPasswordController.text) ? 'Passwords do not match' : null,
              ),
              const SizedBox(height: 24),
              if (_error != null) ...[
                ErrorText(_error!),
                const SizedBox(height: 16),
              ],
              FilledButton(
                onPressed: _loading ? null : _submit,
                child: _loading
                    ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Reset Password'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
