// lib/screens/login_screen.dart
import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';

import '../services/auth_service.dart';

class LoginScreen extends StatefulWidget {
  final VoidCallback? onSignedIn;

  const LoginScreen({Key? key, this.onSignedIn}) : super(key: key);

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final TextEditingController _emailCtl = TextEditingController();
  final TextEditingController _passCtl = TextEditingController();
  final TextEditingController _tenantCtl = TextEditingController();

  final AuthService _auth = AuthService();

  bool _isSignUpMode = false;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _emailCtl.dispose();
    _passCtl.dispose();
    _tenantCtl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      // In case Firebase wasn't initialized earlier (safe-guard)
      try {
        await Firebase.initializeApp();
      } catch (_) {}

      if (_isSignUpMode) {
        // Pass tenantIdForNewUser only if user entered one
        final tenantProvided =
            _tenantCtl.text.trim().isNotEmpty ? _tenantCtl.text.trim() : null;

        try {
          await _auth.signUpWithEmail(
            email: _emailCtl.text.trim(),
            password: _passCtl.text,
            displayName: null,
            tenantIdForNewUser: tenantProvided,
          );
          // authStateChanges() in main.dart should navigate to Home; optional callback:
          if (widget.onSignedIn != null) widget.onSignedIn!();
        } catch (e) {
          // surface the error to the user
          setState(() => _error = e.toString());
        }
      } else {
        try {
          await _auth.signInWithEmail(
            email: _emailCtl.text.trim(),
            password: _passCtl.text,
          );
          if (widget.onSignedIn != null) widget.onSignedIn!();
        } catch (e) {
          setState(() => _error = e.toString());
        }
      }
    } catch (e) {
      setState(() {
        _error = e.toString();
      });
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  String? _validateEmail(String? s) {
    if (s == null || s.trim().isEmpty) return 'Please enter email';
    final email = s.trim();
    if (!RegExp(r"^[^@]+@[^@]+\.[^@]+").hasMatch(email)) return 'Invalid email';
    return null;
  }

  String? _validatePassword(String? s) {
    if (s == null || s.isEmpty) return 'Please enter password';
    if (s.length < 6) return 'Min 6 characters';
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final primary = Theme.of(context).colorScheme.primary;

    return Scaffold(
      appBar: AppBar(
        title: Text(_isSignUpMode ? 'Sign Up' : 'Sign In'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  if (_error != null) ...[
                    Text(_error!, style: const TextStyle(color: Colors.red)),
                    const SizedBox(height: 8),
                  ],
                  Form(
                    key: _formKey,
                    child: Column(
                      children: [
                        TextFormField(
                          controller: _emailCtl,
                          decoration: const InputDecoration(
                            labelText: 'Email',
                            prefixIcon: Icon(Icons.email),
                          ),
                          validator: _validateEmail,
                          keyboardType: TextInputType.emailAddress,
                          autofillHints: const [AutofillHints.email],
                        ),
                        const SizedBox(height: 8),
                        TextFormField(
                          controller: _passCtl,
                          decoration: const InputDecoration(
                            labelText: 'Password',
                            prefixIcon: Icon(Icons.lock),
                          ),
                          validator: _validatePassword,
                          obscureText: true,
                          autofillHints: const [AutofillHints.password],
                        ),
                        const SizedBox(height: 8),
                        // Optional tenant input shown only for sign-up
                        if (_isSignUpMode) ...[
                          TextFormField(
                            controller: _tenantCtl,
                            decoration: const InputDecoration(
                              labelText: 'Tenant ID (optional)',
                              hintText: 'Enter tenant id if you have one',
                              prefixIcon: Icon(Icons.apartment),
                            ),
                          ),
                          const SizedBox(height: 8),
                        ],
                        const SizedBox(height: 12),
                        SizedBox(
                          width: double.infinity,
                          child: ElevatedButton(
                            onPressed: _loading ? null : _submit,
                            child: _loading
                                ? const SizedBox(
                                    height: 16,
                                    width: 16,
                                    child: CircularProgressIndicator(strokeWidth: 2),
                                  )
                                : Text(_isSignUpMode ? 'Create account' : 'Sign in'),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text(_isSignUpMode
                                ? 'Already have an account?'
                                : "Don't have an account?"),
                            TextButton(
                              onPressed: _loading
                                  ? null
                                  : () {
                                      setState(() {
                                        _isSignUpMode = !_isSignUpMode;
                                        _error = null;
                                      });
                                    },
                              child: Text(_isSignUpMode ? 'Sign in' : 'Create account'),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        TextButton(
                          onPressed: _loading
                              ? null
                              : () async {
                                  // optional: password reset flow
                                  final email = _emailCtl.text.trim();
                                  if (email.isEmpty) {
                                    setState(() {
                                      _error = 'Enter email to reset password';
                                    });
                                    return;
                                  }
                                  try {
                                    await _auth.sendPasswordReset(email: email);
                                    setState(() {
                                      _error = 'Password reset email sent';
                                    });
                                  } catch (e) {
                                    setState(() {
                                      _error = 'Failed to send reset: $e';
                                    });
                                  }
                                },
                          child: const Text('Forgot password?'),
                        ),
                      ],
                    ),
                  ),
                ]),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
