import 'dart:async';

import '../../../core/widgets/error_text.dart';
import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../auth_api.dart';
import 'login_screen.dart';

/// SRS Screen 16.1 OTP input + "Resend OTP" link, split out as its own
/// screen (Phase 17) rather than inlined into RegisterScreen since
/// POST /auth/verify-otp and POST /auth/resend-otp are both standalone
/// endpoints a user may need to return to (e.g. app closed between
/// registering and verifying). [purpose] is only used for the resend
/// call (verify-otp itself has no purpose parameter - see AuthApi's doc
/// comment); currently only reached with purpose=REGISTRATION from
/// RegisterScreen, but kept general rather than hard-coded so a future
/// caller doesn't have to fork this screen.
class OtpVerificationScreen extends StatefulWidget {
  final String mobileNumber;
  final String purpose;

  const OtpVerificationScreen({super.key, required this.mobileNumber, required this.purpose});

  @override
  State<OtpVerificationScreen> createState() => _OtpVerificationScreenState();
}

class _OtpVerificationScreenState extends State<OtpVerificationScreen> {
  final _otpController = TextEditingController();
  bool _verifying = false;
  bool _resending = false;
  String? _error;
  String? _info;

  Future<void> _verify() async {
    setState(() {
      _verifying = true;
      _error = null;
      _info = null;
    });
    try {
      await AuthApi.instance.verifyOtp(mobileNumber: widget.mobileNumber, otpCode: _otpController.text.trim());
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Mobile number verified. Please sign in.')));
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const LoginScreen()),
        (route) => false,
      );
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      setState(() => _error = 'Verification failed: $e');
    } finally {
      if (mounted) setState(() => _verifying = false);
    }
  }

  Future<void> _resend() async {
    setState(() {
      _resending = true;
      _error = null;
      _info = null;
    });
    try {
      await AuthApi.instance.resendOtp(mobileNumber: widget.mobileNumber, purpose: widget.purpose);
      if (mounted) setState(() => _info = 'A new OTP has been sent.');
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      setState(() => _error = 'Could not resend OTP: $e');
    } finally {
      if (mounted) setState(() => _resending = false);
    }
  }

  @override
  void dispose() {
    _otpController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Verify Mobile Number')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Enter the 6-digit code sent to ${widget.mobileNumber}.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _otpController,
              decoration: const InputDecoration(labelText: 'OTP Code', border: OutlineInputBorder()),
              keyboardType: TextInputType.number,
              maxLength: 6,
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              ErrorText(_error!),
            ],
            if (_info != null) ...[
              const SizedBox(height: 8),
              Text(_info!, style: const TextStyle(color: Colors.green)),
            ],
            const SizedBox(height: 8),
            FilledButton(
              onPressed: _verifying ? null : _verify,
              child: _verifying
                  ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Verify'),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: _resending ? null : _resend,
              child: _resending
                  ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Resend OTP'),
            ),
          ],
        ),
      ),
    );
  }
}
