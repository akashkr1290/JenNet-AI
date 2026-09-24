import 'package:flutter/material.dart';

import '../../../core/api/api_exception.dart';
import '../../home_router.dart';
import '../auth_api.dart';
import 'forgot_password_screen.dart';
import 'mfa_verification_screen.dart';
import 'register_screen.dart';

/// SRS Screen 16.1 "Registration / Login" (login half) + SRS 27.1 MFA
/// routing. One shared form for every role, matching AuthController's own
/// single `/auth/login` endpoint for all five roles.
///
/// Phase 6 shipped this as deliberately minimal (mobile/email + password
/// only, no Register/Forgot-Password links, and an outright StateError if
/// the backend responded with `mfaRequired` - i.e. Admin/Super Admin
/// could not sign in through the app at all). Phase 17 closes that: this
/// screen now links to RegisterScreen and ForgotPasswordScreen (both new
/// this phase) and routes an `mfaRequired` response to
/// MfaVerificationScreen (also new this phase) instead of throwing.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _identifierController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _loading = false;
  String? _error;

  Future<void> _submit() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await AuthApi.instance.login(
        identifier: _identifierController.text.trim(),
        password: _passwordController.text,
      );
      if (!mounted) return;

      if (result.mfaRequired) {
        Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => MfaVerificationScreen(mfaToken: result.mfaToken!)),
        );
        return;
      }

      final home = await resolveHomeScreen();
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => home),
      );
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (e) {
      setState(() => _error = 'Login failed: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('JANNet AI — Sign In')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _identifierController,
              decoration: const InputDecoration(
                labelText: 'Mobile number or email',
                border: OutlineInputBorder(),
              ),
              keyboardType: TextInputType.emailAddress,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _passwordController,
              decoration: const InputDecoration(
                labelText: 'Password',
                border: OutlineInputBorder(),
              ),
              obscureText: true,
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const ForgotPasswordScreen()),
                ),
                child: const Text('Forgot Password?'),
              ),
            ),
            if (_error != null) ...[
              Text(_error!, style: const TextStyle(color: Colors.red)),
              const SizedBox(height: 16),
            ],
            FilledButton(
              onPressed: _loading ? null : _submit,
              child: _loading
                  ? const SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Sign In'),
            ),
            const SizedBox(height: 16),
            TextButton(
              onPressed: _loading
                  ? null
                  : () => Navigator.of(context).push(
                        MaterialPageRoute(builder: (_) => const RegisterScreen()),
                      ),
              child: const Text("Don't have an account? Register"),
            ),
          ],
        ),
      ),
    );
  }
}
