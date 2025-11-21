// lib/call_event_handler.dart
// Full updated file with MethodChannel to allow MainActivity -> Flutter "open lead" requests.

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models/lead.dart';
import 'services/lead_service.dart';
import 'screens/lead_form_screen.dart';

class CallEventHandler {
  final GlobalKey<NavigatorState> navigatorKey;

  // EventChannel for native -> flutter streaming of call events
  static const EventChannel _eventChannel =
      EventChannel("com.example.call_leads_app/callEvents");

  // MethodChannel for native -> flutter one-off commands (open lead by phone)
  static const MethodChannel _openLeadChannel =
      MethodChannel('com.example.call_leads_app/openLead');

  // Use the singleton instance
  final LeadService _leadService = LeadService.instance;

  StreamSubscription? _subscription;

  /// Prevents multiple screens from opening
  bool _screenOpen = false;

  /// State tracker for call deduplication (simple single-call guard)
  String? _currentlyProcessingCall;

  /// In-memory session buffer keyed by phone (or '__no_number__' when unknown).
  final Map<String, _CallSessionBuffer> _sessions = {};

  /// Deduplication window (ms) - ignore identical events within this timeframe
  static const int _dedupeWindowMs = 800;

  /// Auto-finalize timeout (ms) - if no terminal event arrives, finalize session
  /// after this many milliseconds.
  static const int _autoFinalizeMs = 8000;

  CallEventHandler({required this.navigatorKey});

  // --------------------------------------------------------------------------
  // START LISTENING & CLEANUP
  // --------------------------------------------------------------------------
  void startListening() {
    print("📞 [CALL HANDLER] START LISTENING");

    // Setup MethodChannel handler so native can request opening a lead by phone.
    _openLeadChannel.setMethodCallHandler((call) async {
      try {
        if (call.method == 'openLeadByPhone') {
          final dynamic args = call.arguments;
          String? phone;
          if (args is Map) {
            phone = args['phone'] as String?;
          } else if (args is String) {
            phone = args;
          }

          if (phone != null && phone.isNotEmpty) {
            // Debug: log tenant so we can validate tenant flow end-to-end
            final tenant = await _getTenantId();
            print('📣 openLeadByPhone invoked for phone=$phone tenant=$tenant');

            // Find or create the lead and open UI
            final Lead lead = await _leadService.findOrCreateLead(phone: phone);
            _openLeadUI(lead);
          } else {
            print('⚠️ openLeadByPhone called with empty phone: $args');
          }
        }
      } catch (e, st) {
        print('❌ Error in openLeadByPhone handler: $e\n$st');
      }
    });

    _subscription = _eventChannel
        .receiveBroadcastStream()
        .listen(
      (event) {
        try {
          final Map<String, dynamic> typedEvent = Map<String, dynamic>.from(event as Map);
          _processCallEvent(typedEvent);
        } catch (e, st) {
          print('❌ Received non-map event or parsing error: $e\n$st');
        }
      },
      onError: (error) {
        print("❌ STREAM ERROR: $error");
      },
      onDone: () {
        print("✅ STREAM DONE");
      },
    );
  }

  void stopListening() {
    _subscription?.cancel();
    _subscription = null;
    print("🛑 [CALL HANDLER] STOP LISTENING");
  }

  void dispose() {
    stopListening();
    // Clear MethodChannel handler
    _openLeadChannel.setMethodCallHandler(null);
    for (final s in _sessions.values) s.dispose();
    _sessions.clear();
  }

  // --------------------------------------------------------------------------
  // EVENT PROCESSOR
  // --------------------------------------------------------------------------
  void _processCallEvent(Map<String, dynamic> event) {
    try {
      print('📞 RAW EVENT → $event');

      final phoneNumber = (event['phoneNumber'] as String?)?.trim();
      final outcome = (event['outcome'] as String?)?.trim();
      final direction = (event['direction'] as String?)?.trim();

      // timestamp and duration may be provided by native call-log fallback
      final timestampMs = (event['timestamp'] is int) ? event['timestamp'] as int : DateTime.now().millisecondsSinceEpoch;
      final duration = event['durationInSeconds'] as int?;

      if (outcome == null || direction == null) {
        print("! Ignoring invalid call event (missing outcome/direction).");
        return;
      }

      // use synthetic key when number is unknown
      final key = (phoneNumber == null || phoneNumber.isEmpty) ? '__no_number__' : phoneNumber;

      // create session buffer if missing (pass auto finalize timeout)
      final buf = _sessions.putIfAbsent(key, () => _CallSessionBuffer(key, autoFinalizeMs: _autoFinalizeMs));

      // Deduplicate same-outcome too quickly
      if (buf.lastEventType == outcome && (timestampMs - (buf.lastEventTs ?? 0)).abs() < _dedupeWindowMs) {
        print("! DEDUPLICATED: Skipping duplicate event for $key ($outcome).");
        // If authoritative duration arrives with duplicate, update last event duration
        if (duration != null) {
          buf.updateLastEventWithDuration(duration, timestampMs);
          // If we previously finalized and now got duration, perform an authoritative update
          if (buf.finalized && !buf.reportedAuthoritativeUpdate) {
            buf.reportedAuthoritativeUpdate = true;
            _applyAuthoritativeUpdate(key, phoneNumber, duration, timestampMs);
          }
        }
        // If synthetic -> real phone migration
        if (key == '__no_number__' && phoneNumber != null && phoneNumber.isNotEmpty) {
          _migrateSessionKey('__no_number__', phoneNumber);
        }
        return;
      }

      // Record event in buffer
      buf.addEvent(_CallEvent(
        type: outcome,
        timestampMs: timestampMs,
        durationSeconds: duration,
        direction: direction,
        phoneNumber: phoneNumber,
      ));

      // If synthetic key and now have real number, migrate buffer
      if (key == '__no_number__' && phoneNumber != null && phoneNumber.isNotEmpty) {
        _migrateSessionKey('__no_number__', phoneNumber);
      }

      // If this event carries authoritative duration -> finalize now using duration
      if (duration != null) {
        print('ℹ️ Received authoritative duration for $key: $duration sec — finalizing using call-log data.');
        // If already finalized earlier, treat as authoritative update
        if (buf.finalized) {
          if (!buf.reportedAuthoritativeUpdate) {
            buf.reportedAuthoritativeUpdate = true;
            _applyAuthoritativeUpdate(key, phoneNumber, duration, timestampMs);
          }
        } else {
          _finalizeSessionWithDuration(key, phoneNumber, duration, timestampMs);
        }
        return;
      }

      // Intermediate vs terminal
      if (_isIntermediate(outcome)) {
        _handleIntermediateEvent(buf, phoneNumber, direction, outcome, timestampMs, duration);
      } else if (_isTerminal(outcome)) {
        _handleTerminalEvent(buf, phoneNumber, direction, outcome, timestampMs, duration);
      } else {
        _handleIntermediateEvent(buf, phoneNumber, direction, outcome, timestampMs, duration);
      }
    } catch (e, st) {
      print("❌ _processCallEvent error: $e\n$st");
    }
  }

  bool _isIntermediate(String outcome) {
    final o = outcome.toLowerCase();
    return o == 'ringing' || o == 'started' || o == 'outgoing_start' || o == 'answered';
  }

  bool _isTerminal(String outcome) {
    final o = outcome.toLowerCase();
    return o == 'ended' || o == 'missed' || o == 'rejected';
  }

  // --------------------------------------------------------------------------
  // INTERMEDIATE EVENT HANDLING (ringing/started/answered/outgoing_start)
  // --------------------------------------------------------------------------
  void _handleIntermediateEvent(
    _CallSessionBuffer buf,
    String? phoneNumber,
    String direction,
    String outcome,
    int timestampMs,
    int? duration,
  ) async {
    try {
      final DateTime timestamp = DateTime.fromMillisecondsSinceEpoch(timestampMs);

      // Avoid re-saving same intermediate outcome many times:
      if (buf.lastSavedOutcome == outcome && (timestampMs - (buf.lastSavedTs ?? 0)).abs() < _dedupeWindowMs) {
        print("! Skipping saving duplicate intermediate outcome '$outcome' for ${buf.key}.");
      } else {
        // call LeadService.addCallEvent to append/update lead
        final Lead lead = await _leadService.addCallEvent(
          phone: phoneNumber ?? '',
          direction: direction,
          outcome: outcome,
          timestamp: timestamp,
          durationInSeconds: duration,
        );

        // mark last saved to avoid duplicates
        buf.lastSavedOutcome = outcome;
        buf.lastSavedTs = timestampMs;

        // If intermediate event should open UI
        if (outcome == 'ringing' || outcome == 'answered') {
          if (_currentlyProcessingCall != phoneNumber) {
            _currentlyProcessingCall = phoneNumber;
            print('📞 New call. Opening UI with lead ID: ${lead.id}');
            _openLeadUI(lead);
          } else {
            print('! SCREEN OPEN DEDUP prevented for $phoneNumber');
          }
        }
      }

      // reset or set auto-finalize timer on any intermediate event
      buf.scheduleAutoFinalize(() {
        print('⌛ Auto-finalize triggered for session ${buf.key} (no terminal event).');
        _autoFinalizeSession(buf.key);
      });
    } catch (e, st) {
      print("❌ Error handling intermediate event: $e\n$st");
    }
  }

  // --------------------------------------------------------------------------
  // TERMINAL EVENT HANDLING (ended/missed/rejected)
  // --------------------------------------------------------------------------
  void _handleTerminalEvent(
    _CallSessionBuffer buf,
    String? phoneNumber,
    String direction,
    String outcome,
    int timestampMs,
    int? duration,
  ) async {
    try {
      final DateTime timestamp = DateTime.fromMillisecondsSinceEpoch(timestampMs);
      final String phoneToUse = (phoneNumber != null && phoneNumber.isNotEmpty) ? phoneNumber : '';

      // Build consolidated list and call addFinalCallEvent once
      await _commitFinalFromBuffer(buf, phoneToUse, outcome, timestampMs, duration);

      // Clear currently processing marker if matches
      if (_currentlyProcessingCall == phoneNumber) _currentlyProcessingCall = null;

      // finalize buffer and cleanup shortly
      buf.markFinalized();
      buf.cancelAutoFinalize();
      Future.delayed(const Duration(milliseconds: 600), () {
        _sessions.remove(buf.key)?.dispose();
      });
    } catch (e, st) {
      print("❌ Error handling terminal event: $e\n$st");
    }
  }

  // ------------------------------------------------------------
  // FINALIZATION helpers: create consolidated events and save once
  // ------------------------------------------------------------
  Future<void> _commitFinalFromBuffer(_CallSessionBuffer buf, String phone, String finalOutcome, int timestampMs, int? durationFromEvent) async {
    try {
      // Consolidate events: sort by timestamp, merge duplicates, prefer events with duration
      final consolidated = _consolidateEvents(buf.events);

      // Choose best timestamp/duration for final save:
      int? chosenDuration;
      int chosenTimestamp = timestampMs;
      for (final e in consolidated.reversed) {
        if (e.durationSeconds != null) {
          chosenDuration = e.durationSeconds;
          chosenTimestamp = e.timestampMs;
          break;
        }
      }
      if (chosenDuration == null && consolidated.isNotEmpty) {
        chosenTimestamp = consolidated.last.timestampMs;
      }

      // If we have an explicit durationFromEvent, prefer it
      if (durationFromEvent != null) {
        chosenDuration = durationFromEvent;
        chosenTimestamp = timestampMs;
      }

      final DateTime finalTs = DateTime.fromMillisecondsSinceEpoch(chosenTimestamp);

      // Debug: log tenant when finalizing
      final tenant = await _getTenantId();
      print('📣 Finalizing call for phone=$phone tenant=$tenant outcome=$finalOutcome duration=$chosenDuration ts=$chosenTimestamp');

      // Call leadService.addFinalCallEvent ONCE with chosen data
      final Lead? updatedLead = await _leadService.addFinalCallEvent(
        phone: phone,
        direction: consolidated.isNotEmpty ? consolidated.last.direction ?? 'unknown' : 'unknown',
        outcome: finalOutcome,
        timestamp: finalTs,
        durationInSeconds: chosenDuration,
      );

      print('✅ Finalized call for $phone outcome=$finalOutcome dur=$chosenDuration ts=$chosenTimestamp savedLead=${updatedLead?.id}');

      // If updatedLead requires manual review, open UI
      if (updatedLead != null && updatedLead.needsManualReview) {
        _openLeadUI(updatedLead);
      }
    } catch (e, st) {
      print('❌ _commitFinalFromBuffer error: $e\n$st');
    }
  }

  // When authoritative duration arrives *after* we already finalized (rare), update final record.
  Future<void> _applyAuthoritativeUpdate(String key, String? phoneNumber, int durationSec, int timestampMs) async {
    try {
      final buf = _sessions[key];
      final phone = (phoneNumber != null && phoneNumber.isNotEmpty) ? phoneNumber : (buf?.key == '__no_number__' ? '' : buf?.key ?? '');

      final DateTime ts = DateTime.fromMillisecondsSinceEpoch(timestampMs);

      final Lead? updatedLead = await _leadService.addFinalCallEvent(
        phone: phone,
        direction: buf != null && buf.events.isNotEmpty ? buf.events.last.direction : 'unknown',
        outcome: 'ended',
        timestamp: ts,
        durationInSeconds: durationSec,
      );

      print('✅ Applied authoritative update for $phone dur=$durationSec updatedLead=${updatedLead?.id}');

      if (updatedLead != null && updatedLead.needsManualReview) _openLeadUI(updatedLead);
    } catch (e, st) {
      print('❌ _applyAuthoritativeUpdate error: $e\n$st');
    }
  }

  // consolidate buffer events: sort by ts, dedupe consecutive duplicates, prefer duration where present
  List<_CallEvent> _consolidateEvents(List<_CallEvent> events) {
    if (events.isEmpty) return [];

    final copy = List<_CallEvent>.from(events);
    copy.sort((a, b) => a.timestampMs.compareTo(b.timestampMs));

    final List<_CallEvent> out = [];
    for (final e in copy) {
      if (out.isEmpty) {
        out.add(_cloneEvent(e));
        continue;
      }
      final last = out.last;
      // if same type and timestamp extremely close, keep the one that has duration or later timestamp
      if (last.type == e.type) {
        if (last.durationSeconds == null && e.durationSeconds != null) {
          out[out.length - 1] = _cloneEvent(e);
        } else if (e.timestampMs > last.timestampMs + 50) {
          // slightly different → append
          out.add(_cloneEvent(e));
        } else {
          // otherwise ignore duplicate near-duplicate
        }
      } else {
        out.add(_cloneEvent(e));
      }
    }
    return out;
  }

  _CallEvent _cloneEvent(_CallEvent e) {
    return _CallEvent(
      type: e.type,
      timestampMs: e.timestampMs,
      durationSeconds: e.durationSeconds,
      direction: e.direction,
      phoneNumber: e.phoneNumber,
    );
  }

  // finalize when authoritative duration arrives
  void _finalizeSessionWithDuration(String key, String? phoneNumber, int durationSec, int timestampMs) {
    final buf = _sessions[key];
    if (buf == null) {
      print('! No buffer found for finalizeWithDuration: $key');
      return;
    }
    // commit using the buffer's consolidated events
    _commitFinalFromBuffer(buf, (phoneNumber != null && phoneNumber.isNotEmpty) ? phoneNumber : (key == '__no_number__' ? '' : key), 'ended', timestampMs, durationSec);
    // mark finalized & cleanup
    buf.markFinalized();
    buf.cancelAutoFinalize();
    Future.delayed(const Duration(milliseconds: 400), () {
      _sessions.remove(key)?.dispose();
    });
  }

  // Called when auto-finalize timer fires (no terminal event)
  void _autoFinalizeSession(String key) async {
    final buf = _sessions[key];
    if (buf == null) return;

    final consolidated = _consolidateEvents(buf.events);
    final last = consolidated.isNotEmpty ? consolidated.last : null;
    final phone = (last?.phoneNumber != null && last!.phoneNumber!.isNotEmpty) ? last.phoneNumber! : (key == '__no_number__' ? '' : key);

    final DateTime ts = DateTime.fromMillisecondsSinceEpoch(last?.timestampMs ?? DateTime.now().millisecondsSinceEpoch);
    final String direction = last?.direction ?? 'unknown';
    final String outcome = last?.type ?? 'ended';
    final int? duration = consolidated.reversed.firstWhere((e) => e.durationSeconds != null, orElse: () => _CallEvent.empty()).durationSeconds;

    try {
      // Debug: log tenant on auto-finalize
      final tenant = await _getTenantId();
      print('⌛ Auto-finalize for phone=$phone tenant=$tenant outcome=$outcome duration=$duration');

      final Lead? updatedLead = await _leadService.addFinalCallEvent(
        phone: phone,
        direction: direction,
        outcome: outcome == 'ended' ? 'ended' : outcome,
        timestamp: ts,
        durationInSeconds: duration,
      );

      if (updatedLead != null && updatedLead.needsManualReview) {
        _openLeadUI(updatedLead);
      }
    } catch (e, st) {
      print('❌ Error auto-finalizing session $key: $e\n$st');
    } finally {
      _sessions.remove(key)?.dispose();
    }
  }

  // ---------------------------------------------------------------------------
  // OPEN THE SCREEN SAFELY
  // ---------------------------------------------------------------------------
  void _openLeadUI(Lead lead) {
    if (_screenOpen) {
      print("⚠️ SCREEN ALREADY OPEN — skipping");
      return;
    }

    final ctx = navigatorKey.currentState?.overlay?.context;
    if (ctx == null) {
      print("❌ NO CONTEXT — delaying open");
      Future.delayed(const Duration(milliseconds: 300), () {
        _openLeadUI(lead);
      });
      return;
    }

    _screenOpen = true;
    print("📞 OPENING UI FOR ${lead.phoneNumber} (leadId=${lead.id})");

    navigatorKey.currentState!.push(
      MaterialPageRoute(
        settings: const RouteSettings(name: '/lead-form'),
        fullscreenDialog: true,
        builder: (_) => LeadFormScreen(
          lead: lead,
          autoOpenedFromCall: true,
        ),
      ),
    ).then((_) {
      Future.delayed(const Duration(milliseconds: 250), () {
        _screenOpen = false;
      });
    });
  }

  // ---------------------------------------------------------------------------
  // HELPERS
  // ---------------------------------------------------------------------------
  void _migrateSessionKey(String oldKey, String newKey) {
    if (!_sessions.containsKey(oldKey)) return;
    final old = _sessions.remove(oldKey)!;
    final target = _sessions.putIfAbsent(newKey, () => _CallSessionBuffer(newKey, autoFinalizeMs: _autoFinalizeMs));
    target.absorb(old);
    old.dispose();
    print('ℹ️ Migrated session buffer $oldKey → $newKey');
  }

  Future<String> _getTenantId() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final t = prefs.getString('tenantId');
      if (t != null && t.isNotEmpty) return t;
    } catch (_) {}
    return 'default_tenant';
  }
}

// ---------------------------------------------------------------------------
// Internal session buffer & event types
// ---------------------------------------------------------------------------

class _CallSessionBuffer {
  final String key; // phone number or '__no_number__'
  final List<_CallEvent> events = [];
  int? lastEventTs;
  String? lastEventType;
  bool finalized = false;
  bool reportedAuthoritativeUpdate = false; // ensure we only apply authoritative update once
  Timer? _expirationTimer;
  Timer? _autoFinalizeTimer;
  final int autoFinalizeMs;

  // last saved intermediate outcome (to avoid re-saving same outcome repeatedly)
  String? lastSavedOutcome;
  int? lastSavedTs;

  _CallSessionBuffer(this.key, {this.autoFinalizeMs = 8000});

  void addEvent(_CallEvent e) {
    if (events.isNotEmpty) {
      final last = events.last;
      // avoid exact duplicate events in a row
      if (last.type == e.type && last.timestampMs == e.timestampMs && last.durationSeconds == e.durationSeconds) {
        return;
      }
    }
    events.add(e);
    lastEventTs = e.timestampMs;
    lastEventType = e.type;

    // reset a general expiration timer (cleanup stale sessions)
    _expirationTimer?.cancel();
    _expirationTimer = Timer(const Duration(seconds: 60), () {
      dispose();
    });
  }

  void updateLastEventWithDuration(int duration, int timestampMs) {
    if (events.isEmpty) return;
    final last = events.last;
    last.durationSeconds = duration;
    last.timestampMs = timestampMs;
    lastEventTs = timestampMs;
  }

  void markFinalized() {
    finalized = true;
    cancelAutoFinalize();
  }

  void absorb(_CallSessionBuffer other) {
    for (final e in other.events) addEvent(e);
  }

  void dispose() {
    _expirationTimer?.cancel();
    _autoFinalizeTimer?.cancel();
    _expirationTimer = null;
    _autoFinalizeTimer = null;
  }

  void scheduleAutoFinalize(void Function() callback) {
    _autoFinalizeTimer?.cancel();
    _autoFinalizeTimer = Timer(Duration(milliseconds: autoFinalizeMs), () {
      callback();
    });
  }

  void cancelAutoFinalize() {
    _autoFinalizeTimer?.cancel();
    _autoFinalizeTimer = null;
  }
}

class _CallEvent {
  final String type;
  int timestampMs;
  int? durationSeconds;
  final String direction;
  final String? phoneNumber;

  _CallEvent({
    required this.type,
    required this.timestampMs,
    required this.direction,
    this.durationSeconds,
    this.phoneNumber,
  });

  static _CallEvent empty() => _CallEvent(type: 'empty', timestampMs: 0, direction: 'unknown');
}
