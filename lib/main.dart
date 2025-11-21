
// UPDATED main.dart with improved auth logging & tenant sync diagnostics.

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/services.dart';

import 'call_event_handler.dart';
import 'screens/home_screen.dart';
import 'screens/login_screen.dart';
import 'services/auth_service.dart';

const MethodChannel _nativeChannel =
    MethodChannel('com.example.call_leads_app/native');

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize Firebase before constructing anything that might use it.
  try {
    await Firebase.initializeApp();
    print('✅ Firebase.initializeApp() completed.');
  } catch (e, st) {
    print('❌ Firebase.initializeApp() failed: $e\n$st');
  }

  // Pre-warm SharedPreferences and log current tenant for quick verification.
  try {
    final prefs = await SharedPreferences.getInstance();
    final tenant = prefs.getString('tenantId') ?? '<not-set>';
    print('📣 Preloaded SharedPreferences tenantId=$tenant');
  } catch (e) {
    print('⚠️ Could not read SharedPreferences on startup: $e');
  }

  runApp(MyApp());
}

class MyApp extends StatefulWidget {
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with WidgetsBindingObserver {
  final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();
  late final CallEventHandler _callHandler;
  StreamSubscription<User?>? _authSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _callHandler = CallEventHandler(navigatorKey: navigatorKey);

    // Monitor Firebase auth state
    _authSub =
        FirebaseAuth.instance.authStateChanges().listen((User? user) async {
      print("🔐 authStateChanges() => uid=${user?.uid}, email=${user?.email}");

      if (user != null) {
        // user logged in
        print("➡️ User signed in: ${user.uid}");

        // ensure tenant synced locally + to native
        try {
          await _ensureTenantSyncedForUser(user.uid);
        } catch (e) {
          print("⚠️ Tenant sync error: $e");
        }

        // start call handler
        try {
          Future.microtask(() => _callHandler.startListening());
        } catch (e) {
          print("❌ Failed to start CallEventHandler: $e");
        }
      } else {
        print("⬅️ User signed out.");
        try {
          _callHandler.stopListening();
        } catch (e) {
          print("❌ Failed to stop CallEventHandler: $e");
        }
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _authSub?.cancel();
    _callHandler.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    print("📱 Lifecycle state changed: $state");

    if (state == AppLifecycleState.resumed) {
      if (FirebaseAuth.instance.currentUser != null) {
        print("🔄 Resumed — reattaching CallEventHandler.");
        _callHandler.startListening();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Call Leads',
      navigatorKey: navigatorKey,
      theme: ThemeData(primarySwatch: Colors.blue),
      home: StreamBuilder<User?>(
        stream: FirebaseAuth.instance.authStateChanges(),
        builder: (context, snapshot) {
          final user = snapshot.data;

          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Scaffold(body: Center(child: CircularProgressIndicator()));
          }

          if (user == null) {
            print("🧭 Navigating to LoginScreen");
            return LoginScreen();
          } else {
            print("🧭 Navigating to HomeScreen for uid=${user.uid}");
            return HomeScreen();
          }
        },
      ),
    );
  }

  /// Ensure tenantId is present in SharedPreferences & native preferences.
  Future<void> _ensureTenantSyncedForUser(String uid) async {
    print("🔍 Checking tenant sync for uid=$uid");

    final prefs = await SharedPreferences.getInstance();
    final existing = prefs.getString('tenantId');

    if (existing != null && existing.trim().isNotEmpty) {
      print("🔁 tenantId already in prefs → $existing");
      return;
    }

    print("🌐 Fetching profile from Firestore...");
    final profile = await AuthService().fetchUserProfile(uid);

    if (profile == null) {
      print("⚠️ No userProfiles/$uid doc found — cannot sync tenant.");
      return;
    }

    final tenant = (profile["tenantId"] as String?)?.trim();

    if (tenant == null || tenant.isEmpty) {
      print("ℹ️ userProfiles/$uid has NO tenantId assigned.");
      return;
    }

    // store to prefs
    await prefs.setString("tenantId", tenant);
    print("✅ Stored tenantId in SharedPreferences → $tenant");

    // store to native
    try {
      await _nativeChannel.invokeMethod("setTenantId", {"tenantId": tenant});
      print("✅ Synced tenantId to native layer → $tenant");
    } catch (e) {
      print("⚠️ Failed to sync tenantId to native prefs: $e");
    }
  }
}
