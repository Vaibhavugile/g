// lib/main.dart
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';

import 'call_event_handler.dart';
import 'screens/home_screen.dart'; // your app's entry screen; adjust if different

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize Firebase before constructing anything that might use it.
  try {
    await Firebase.initializeApp();
    print('✅ Firebase.initializeApp() completed.');
  } catch (e, st) {
    print('❌ Firebase.initializeApp() failed: $e\n$st');
    // Continue startup anyway — services that require Firebase will log errors.
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

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Construct the call handler after Firebase init (main awaited it).
    _callHandler = CallEventHandler(navigatorKey: navigatorKey);
    _callHandler.startListening();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _callHandler.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-attach the event listener when app resumes (helps with some race cases)
    if (state == AppLifecycleState.resumed) {
      _callHandler.startListening();
    } else if (state == AppLifecycleState.paused) {
      // optional: you can stop listening when paused to reduce work
      // _callHandler.stopListening();
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Call Leads',
      navigatorKey: navigatorKey,
      theme: ThemeData(primarySwatch: Colors.blue),
      home: HomeScreen(),
    );
  }
}
