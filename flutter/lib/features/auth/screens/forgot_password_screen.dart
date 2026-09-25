import 'package:flutter/material.dart';

import '../../../core/widgets/error_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../widgets/auth_layout.dart';
import '../../../core/api/api_exception.dart';
import '../auth_api.dart';
import 'reset_password_screen.dart';

/// SRS Screen 16.1 "Forgot Password" button - Phase 17. Mobile-number-only
/// (matches ForgotPasswordRequest exactly; the backend's response is
/// deliberately generic and never confirms/denies whether the account
/// exists - see AuthApi.forgotPassword's doc comment), so this screen
/// always proceeds to ResetPasswordScreen after submitting.
class ForgotPasswordScreen extends StatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  State<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends State<ForgotPasswordScreen> {
  final _mobileController = TextEditingController();
  bool _loading = false;
  String? _error;

  static final _mobilePattern = RegExp(r'^[6-9]\d{9}$');

  Future<void> _submit() async {
    final mobile = _mobileController.text.trim();
    if (!_mobilePattern.hasMatch(mobile)) {
      setState(() => _error = 'Enter a valid 10-digit mobile number');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await AuthApi.instance.forgotPassword(mobileNumber: mobile);
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => ResetPasswordScreen(mobileNumber: mobile)),
      );
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      setState(() => _error = 'Could not send OTP: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  void dispose() {
    _mobileController.dispose();
    super.dispose();
  }

  // UI redesign; the reset-code request above is unchanged.
  @override
  Widget build(BuildContext context) {
    return JanAuthLayout(
      showBackButton: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const JanAuthHeader(
            title: 'Forgot password?',
            subtitle: 'Enter your registered mobile number and we will send you a reset code.',
          ),
          const JanFieldLabel('Mobile Number'),
          TextField(
            controller: _mobileController,
            keyboardType: TextInputType.phone,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) {
              if (!_loading) _submit();
            },
            decoration: const InputDecoration(
              hintText: '10-digit number',
              prefixIcon: Icon(Icons.phone_iphone_rounded),
              prefixText: '+91  ',
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: JanSpace.md),
            ErrorText(_error!),
          ],
          const SizedBox(height: JanSpace.xl),
          FilledButton(
            onPressed: _loading ? null : _submit,
            child: _loading ? const JanButtonSpinner() : const Text('Send Reset Code'),
          ),
          const SizedBox(height: JanSpace.sm),
          TextButton(
            onPressed: () => Navigator.of(context).maybePop(),
            child: const Text('Back to Sign In'),
          ),
        ],
      ),
    );
  }
}
