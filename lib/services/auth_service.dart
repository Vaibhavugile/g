// lib/services/auth_service.dart
import 'dart:async';
import 'package:flutter/services.dart';

import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// AuthService: handles Firebase Auth + userProfiles tenant wiring + native prefs
class AuthService {
  static const MethodChannel _native =
      MethodChannel('com.example.call_leads_app/native');

  final FirebaseAuth _auth = FirebaseAuth.instance;
  final FirebaseFirestore _firestore = FirebaseFirestore.instance;

  // Singleton style
  static final AuthService _instance = AuthService._internal();
  factory AuthService() => _instance;
  AuthService._internal();

  Stream<User?> authStateChanges() => _auth.authStateChanges();

  User? get currentUser => _auth.currentUser;

  /// Sign up with email/password, then create (or merge) a userProfiles doc.
  // Paste this inside your AuthService class, replacing the old signUpWithEmail function.
Future<UserCredential> signUpWithEmail({
  required String email,
  required String password,
  String? displayName,
  String? tenantIdForNewUser,
}) async {
  try {
    final cred = await _auth.createUserWithEmailAndPassword(
      email: email,
      password: password,
    );

    final uid = cred.user!.uid;
    print('🔐 Created Firebase Auth user uid=$uid (email=$email)');

    // Ensure userProfiles doc exists and contains tenantId if provided
    final profileRef = _firestore.collection('userProfiles').doc(uid);
    final now = FieldValue.serverTimestamp();
    final data = <String, dynamic>{
      'email': email,
      'displayName': displayName ?? '',
      'createdAt': now,
      'updatedAt': now,
    };

    if (tenantIdForNewUser != null && tenantIdForNewUser.isNotEmpty) {
      data['tenantId'] = tenantIdForNewUser;
    }

    try {
      await profileRef.set(data, SetOptions(merge: true));
      print('✅ Wrote userProfiles/$uid: ${data.keys.toList()}');
    } catch (e, st) {
      print('❌ Failed to write userProfiles/$uid: $e\n$st');
      rethrow;
    }

    // Fetch tenant and persist locally + native (your existing helper)
    await _postLoginFetchAndStore(uid);

    return cred;
  } catch (e, st) {
    print('❌ signUpWithEmail failed: $e\n$st');
    rethrow;
  }
}
/// Send a password reset email to the provided address.
/// Uses FirebaseAuth.sendPasswordResetEmail under the hood.
Future<void> sendPasswordReset({required String email}) async {
  try {
    await _auth.sendPasswordResetEmail(email: email);
    print('✅ Password reset email sent to $email');
  } catch (e, st) {
    print('❌ sendPasswordReset failed for $email: $e\n$st');
    rethrow;
  }
}


  /// Sign in with email + password
  Future<UserCredential> signInWithEmail({
    required String email,
    required String password,
  }) async {
    final cred = await _auth.signInWithEmailAndPassword(
      email: email,
      password: password,
    );

    final uid = cred.user!.uid;
    await _postLoginFetchAndStore(uid);
    return cred;
  }

  /// Sign out locally and clear tenant from native prefs
  Future<void> signOut() async {
    try {
      await _native.invokeMethod('clearTenantId');
    } catch (_) {
      // ignore if native call fails
    }

    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('tenantId');

    await _auth.signOut();
  }

  /// Fetch userProfiles/{uid}.tenantId and persist it both locally and into native prefs.
  Future<void> _postLoginFetchAndStore(String uid) async {
    try {
      final doc = await _firestore.collection('userProfiles').doc(uid).get();
      String? tenantId;
      if (doc.exists) {
        final d = doc.data();
        if (d != null && d['tenantId'] != null && (d['tenantId'] as String).isNotEmpty) {
          tenantId = d['tenantId'] as String;
        }
      }

      final prefs = await SharedPreferences.getInstance();
      if (tenantId != null) {
        await prefs.setString('tenantId', tenantId);
      } else {
        await prefs.remove('tenantId');
      }

      try {
        if (tenantId != null) {
          await _native.invokeMethod('setTenantId', {'tenantId': tenantId});
        } else {
          await _native.invokeMethod('clearTenantId');
        }
      } catch (e) {
        // ignore native failure
      }
    } catch (e) {
      rethrow;
    }
  }

  /// Utility: get tenantId from local SharedPreferences (fast)
  Future<String?> getLocalTenantId() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('tenantId');
  }

  /// If user wants to change tenant (e.g. admin assigned new tenant), call this to update both
  /// the userProfiles doc and native prefs.
  Future<void> setTenantForCurrentUser(String tenantId) async {
    final user = _auth.currentUser;
    if (user == null) throw Exception('No authenticated user');

    final uid = user.uid;
    await _firestore.collection('userProfiles').doc(uid).set({
      'tenantId': tenantId,
      'updatedAt': FieldValue.serverTimestamp(),
    }, SetOptions(merge: true));

    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('tenantId', tenantId);

    try {
      await _native.invokeMethod('setTenantId', {'tenantId': tenantId});
    } catch (_) {}
  }

  /// Simple helper to lookup a profile document for UI
  Future<Map<String, dynamic>?> fetchUserProfile(String uid) async {
    final doc = await _firestore.collection('userProfiles').doc(uid).get();
    return doc.exists ? (doc.data() as Map<String, dynamic>) : null;
  }
}
