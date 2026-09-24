import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../home_router.dart';
import '../auth_api.dart';

/// SRS 27.1 "multi-factor authentication for Admin and Super Admin
/// roles" - second step of login, reached from LoginScreen when
/// POST /auth/login returns `mfaRequired: true`. Phase 17: before this
/// phase, LoginScreen's AuthApi.login() threw a StateError on exactly
/// this response shape, meaning no ADMIN or SUPER_ADMIN account could
/// sign in through the Flutter app at all - see AuthApi.login's doc
/// comment.
///
/// No "Resend OTP" action on this screen: ResendOtpRequest needs a
/// mobileNumber, but this screen only has the short-lived [mfaToken]
/// (by design - AuthController.login's Javadoc: "no tokens issued yet").
/// LoginScreen's `identifier` field accepts either a mobile number or an
/// email, so it cannot be relied on here as a mobile number either.
/// Left as a known limitation (PROJECT_INTEGRATION.md Section 6) rather
/// than guessed at - an Admin/Super Admin whose OTP expires simply
/// returns to LoginScreen and logs in again, which issues a fresh
/// mfaToken and OTP.
class MfaVerificationScreen extends StatefulWidget {
  final String mfaToken;

  const MfaVerificationScreen({super.key, required this.mfaToken});

  @override
  State<MfaVerificationScreen> createState() => _MfaVerificationScreenState();
}

class _MfaVerificationScreenState extends State<MfaVerificationScreen> {
  final _otpController = TextEditingController();
  bool _loading = false;
  String? _error;

  Future<void> _submit() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await AuthApi.instance.verifyMfa(mfaToken: widget.mfaToken, otpCode: _otpController.text.trim());
      if (!mounted) return;
      final home = await resolveHomeScreen();
      if (!mounted) return;
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => home),
        (route) => false,
      );
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      setState(() => _error = 'Verification failed: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
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
      appBar: AppBar(title: const Text('Two-Factor Verification')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Enter the 6-digit verification code sent to your registered contact.',
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
              Text(_error!, style: const TextStyle(color: Colors.red)),
            ],
            const SizedBox(height: 8),
            FilledButton(
              onPressed: _loading ? null : _submit,
              child: _loading
                  ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Verify'),
            ),
          ],
        ),
      ),
    );
  }
}
