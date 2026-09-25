import 'dart:async';

import '../../../core/widgets/error_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_form_widgets.dart';
import '../../../core/widgets/jan_states.dart';
import '../widgets/auth_layout.dart';
import 'register_screen.dart';
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

  /// Audit GAP-004: 'SMS' (default) or 'EMAIL' - where the code was sent.
  final String channel;

  /// Shown instead of the phone number when [channel] is 'EMAIL'.
  final String? email;

  const OtpVerificationScreen({
    super.key,
    required this.mobileNumber,
    required this.purpose,
    this.channel = 'SMS',
    this.email,
  });

  bool get _byEmail => channel == 'EMAIL';

  @override
  State<OtpVerificationScreen> createState() => _OtpVerificationScreenState();
}

class _OtpVerificationScreenState extends State<OtpVerificationScreen> {
  final _otpController = TextEditingController();
  bool _verifying = false;
  bool _resending = false;
  String? _error;
  String? _info;

  // UI redesign: short resend cooldown (reference "Resend in 0:45"), started
  // when this screen opens (a code was just sent) and after each successful
  // resend. Purely client-side pacing; the backend's own rate limits apply.
  static const _resendCooldownSeconds = 30;
  Timer? _cooldownTimer;
  int _cooldown = 0;

  @override
  void initState() {
    super.initState();
    _cooldown = _resendCooldownSeconds; // first build already reflects it
    _armCooldownTimer();
  }

  void _startCooldown() {
    setState(() => _cooldown = _resendCooldownSeconds);
    _armCooldownTimer();
  }

  void _armCooldownTimer() {
    _cooldownTimer?.cancel();
    _cooldownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() => _cooldown = _cooldown > 0 ? _cooldown - 1 : 0);
      if (_cooldown == 0) timer.cancel();
    });
  }

  Future<void> _verify() async {
    setState(() {
      _verifying = true;
      _error = null;
      _info = null;
    });
    try {
      await AuthApi.instance.verifyOtp(mobileNumber: widget.mobileNumber, otpCode: _otpController.text.trim());
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(widget._byEmail
              ? 'Email address verified. Please sign in.'
              : 'Mobile number verified. Please sign in.')));
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
      await AuthApi.instance
          .resendOtp(mobileNumber: widget.mobileNumber, purpose: widget.purpose, channel: widget.channel);
      if (mounted) setState(() => _info = 'A new OTP has been sent.');
      if (mounted) _startCooldown();
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
    _cooldownTimer?.cancel();
    _otpController.dispose();
    super.dispose();
  }

  // UI redesign: reference "Verify your number". Verification/resend calls
  // above are unchanged; the six boxes render one real TextField.
  @override
  Widget build(BuildContext context) {
    final canResend = !_resending && _cooldown == 0;
    final mm = (_cooldown ~/ 60).toString();
    final ss = (_cooldown % 60).toString().padLeft(2, '0');
    return JanAuthLayout(
      showBackButton: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          JanAuthHeader(
            title: widget._byEmail ? 'Verify your email' : 'Verify your number',
            subtitle: widget._byEmail
                ? 'Enter the 6-digit code sent to ${widget.email ?? 'your email address'}.'
                : 'Enter the 6-digit code sent to +91 ${widget.mobileNumber}.',
          ),
          JanOtpInput(controller: _otpController, hasError: _error != null),
          const SizedBox(height: JanSpace.md),
          if (_error != null) ...[
            ErrorText(_error!),
            const SizedBox(height: JanSpace.sm),
          ],
          if (_info != null) ...[
            JanBanner(message: _info!, tone: JanBannerTone.success),
            const SizedBox(height: JanSpace.sm),
          ],
          const SizedBox(height: JanSpace.sm),
          Semantics(
            liveRegion: _cooldown == 0,
            child: Text(
              _cooldown > 0 ? 'Resend available in $mm:$ss' : "Didn't get the code?",
              textAlign: TextAlign.center,
              style: const TextStyle(color: JanColors.slate, fontSize: 15),
            ),
          ),
          Wrap(
            alignment: WrapAlignment.center,
            children: [
              TextButton(
                onPressed: canResend ? _resend : null,
                child: _resending
                    ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Resend OTP'),
              ),
              if (widget.purpose == 'REGISTRATION')
                TextButton(
                  onPressed: () => Navigator.of(context).pushReplacement(
                    MaterialPageRoute(builder: (_) => const RegisterScreen()),
                  ),
                  child: Text(widget._byEmail ? 'Change details' : 'Change number'),
                ),
            ],
          ),
          const SizedBox(height: JanSpace.lg),
          FilledButton.icon(
            onPressed: _verifying ? null : _verify,
            icon: _verifying ? const JanButtonSpinner() : const Icon(Icons.verified_user_outlined),
            label: const Text('Verify'),
          ),
        ],
      ),
    );
  }
}
