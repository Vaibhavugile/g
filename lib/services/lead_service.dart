// lib/services/lead_service.dart
import 'dart:async';
import 'package:cloud_firestore/cloud_firestore.dart';
import '../models/lead.dart';

/// Robust LeadService with:
///  - consistent phone normalization (uses normalizePhone from models/lead.dart)
///  - per-phone concurrency serialization (prevents duplicate creates)
///  - idempotent saves (skips no-op Firestore writes)
class LeadService {
  // Singleton for shared cache/locks across app
  static final LeadService instance = LeadService._internal();
  factory LeadService() => instance;
  LeadService._internal();

  final FirebaseFirestore _db = FirebaseFirestore.instance;
  final CollectionReference<Map<String, dynamic>> _leadsCollection =
      FirebaseFirestore.instance.collection('leads');

  final List<Lead> _cached = [];

  /// per-normalized-phone pending operation to avoid race-created duplicates
  final Map<String, Completer<Lead>> _pendingFindOrCreates = {};

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------
  /// Normalizes phone to digits only via shared helper (keeps leading country if present).
  String _normalize(String? number) {
    return normalizePhone(number);
  }

  bool _mapsShallowEqual(Map<String, dynamic> a, Map<String, dynamic> b) {
    if (a.length != b.length) return false;
    for (final k in a.keys) {
      if (!b.containsKey(k)) return false;
      final av = a[k], bv = b[k];
      if (av is List && bv is List) {
        if (av.length != bv.length) return false;
        if (av.toString() != bv.toString()) return false;
      } else {
        if (av != bv) return false;
      }
    }
    return true;
  }

  // -------------------------------------------------------------------------
  // LOAD / GET
  // -------------------------------------------------------------------------
  Future<void> loadLeads() async {
    try {
      final snapshot = await _leadsCollection.get();
      _cached.clear();
      _cached.addAll(snapshot.docs.map((d) {
        final map = d.data();
        final lead = Lead.fromMap(Map<String, dynamic>.from(map));
        return lead.copyWith(id: d.id);
      }).toList());
      print("✅ [FIRESTORE] Loaded ${_cached.length} leads from /leads.");
    } catch (e) {
      print("❌ [FIRESTORE] Error loading leads: $e");
      _cached.clear();
    }
  }

  List<Lead> getAll() => List.unmodifiable(_cached);

  Lead? _findInCacheByNormalized(String normalized) {
    try {
      return _cached.firstWhere((l) => _normalize(l.phoneNumber) == normalized);
    } catch (e) {
      return null;
    }
  }

  Future<Lead?> getLead({required String leadId}) async {
    // cache-first
    try {
      final fromCache = _cached.firstWhere((l) => l.id == leadId);
      return fromCache;
    } catch (_) {
      // not in cache, fall through
    }

    try {
      final doc = await _leadsCollection.doc(leadId).get();
      if (doc.exists && doc.data() != null) {
        final lead = Lead.fromMap(Map<String, dynamic>.from(doc.data()!)).copyWith(id: doc.id);
        // update cache
        _cached.removeWhere((l) => l.id == lead.id);
        _cached.add(lead);
        return lead;
      }
      return null;
    } catch (e) {
      print("❌ [FIRESTORE] Error fetching lead $leadId: $e");
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // Idempotent storage helper
  // -------------------------------------------------------------------------
  Future<void> _saveLeadToStorage(Lead lead) async {
    try {
      final index = _cached.indexWhere((l) => l.id == lead.id);

      if (index != -1) {
        final existing = _cached[index];
        if (_mapsShallowEqual(existing.toMap(), lead.toMap())) {
          // nothing changed — skip
          print("ℹ️ [FIRESTORE] No changes for lead ${lead.id} — skipping write.");
          // still refresh in-memory
          _cached[index] = lead;
          return;
        }
      }

      await _leadsCollection.doc(lead.id).set(lead.toMap());
      print("✅ [FIRESTORE] Saved lead ${lead.id} to /leads.");

      if (index == -1) {
        _cached.add(lead);
      } else {
        _cached[index] = lead;
      }
    } catch (e) {
      print("❌ [FIRESTORE] Error saving lead: $e");
    }
  }

  Future<void> saveLead(Lead lead) async {
    final cleared = lead.copyWith(needsManualReview: false);
    await _saveLeadToStorage(cleared);
  }

  // -------------------------------------------------------------------------
  // CREATE / FIND (serialized per-normalized phone to avoid duplicates)
  // -------------------------------------------------------------------------
  Future<Lead> createLead(String phone) async {
    final normalized = _normalize(phone);
    // Defensive: if normalized empty, still create (some callers want placeholder leads),
    // but we serialize so multiple calls won't create duplicates.
    return await _findOrCreateByNormalized(normalized, phoneFallback: phone);
  }

  /// The central method: find in cache -> Firestore query -> create.
  /// Concurrent callers for same normalized key will await the same future.
  Future<Lead> findOrCreateLead({
    required String phone,
    String finalOutcome = 'none',
  }) async {
    final normalized = _normalize(phone);
    return await _findOrCreateByNormalized(normalized, finalOutcome: finalOutcome, phoneFallback: phone);
  }

  Future<Lead> _findOrCreateByNormalized(String normalized, {String? finalOutcome, String? phoneFallback}) async {
    // 1) check cache
    final cached = _findInCacheByNormalized(normalized);
    if (cached != null) {
      // update lastCallOutcome if requested
      final maybeUpdated = (finalOutcome != null && finalOutcome.isNotEmpty)
          ? cached.copyWith(lastCallOutcome: finalOutcome, lastUpdated: DateTime.now())
          : cached;
      if (maybeUpdated != cached) {
        await _saveLeadToStorage(maybeUpdated);
        return maybeUpdated;
      }
      return cached;
    }

    // 2) if there's a pending operation for the key, await it
    if (_pendingFindOrCreates.containsKey(normalized)) {
      try {
        final existingFuture = _pendingFindOrCreates[normalized]!;
        return await existingFuture.future;
      } catch (e) {
        // If the pending future failed, fall through to attempt again
      }
    }

    // 3) create a pending completer so other callers wait
    final completer = Completer<Lead>();
    _pendingFindOrCreates[normalized] = completer;

    try {
      // Re-check Firestore for existing doc with the same normalized phone (race-safe)
      try {
        final querySnapshot = await _leadsCollection
            .where('phoneNumber', isEqualTo: normalized)
            .limit(1)
            .get();

        if (querySnapshot.docs.isNotEmpty) {
          final doc = querySnapshot.docs.first;
          var lead = Lead.fromMap(Map<String, dynamic>.from(doc.data())).copyWith(id: doc.id);
          // update cache
          _cached.removeWhere((l) => l.id == lead.id);
          _cached.add(lead);

          // optionally update call outcome
          final maybeUpdated = (finalOutcome != null && finalOutcome.isNotEmpty)
              ? lead.copyWith(lastCallOutcome: finalOutcome, lastUpdated: DateTime.now())
              : lead;
          if (maybeUpdated != lead) {
            await _saveLeadToStorage(maybeUpdated);
            completer.complete(maybeUpdated);
            _pendingFindOrCreates.remove(normalized);
            return maybeUpdated;
          } else {
            completer.complete(lead);
            _pendingFindOrCreates.remove(normalized);
            return lead;
          }
        }
      } catch (e) {
        print("❌ [FIRESTORE] Query error while searching by phone: $e");
        // continue to create new lead as fallback
      }

      // Not found -> create a new lead
      final phoneToStore = normalized; // store normalized phone in lead.phoneNumber
      final newLead = Lead.newLead(phoneToStore).copyWith(lastCallOutcome: finalOutcome ?? 'none', lastUpdated: DateTime.now());

      await _saveLeadToStorage(newLead);

      completer.complete(newLead);
      _pendingFindOrCreates.remove(normalized);
      return newLead;
    } catch (e, st) {
      // Ensure completer completes with error and cleanup
      if (!completer.isCompleted) completer.completeError(e, st);
      _pendingFindOrCreates.remove(normalized);
      rethrow;
    }
  }

  // -------------------------------------------------------------------------
  // addCallEvent & addFinalCallEvent (unchanged semantics but using the
  // normalized / serialized findOrCreate flow)
  // -------------------------------------------------------------------------
  Future<Lead> addCallEvent({
    required String phone,
    required String direction,
    required String outcome,
    required DateTime timestamp,
    int? durationInSeconds,
  }) async {
    // ensure we use normalized & serial find-or-create
    final lead = await findOrCreateLead(phone: phone);

    final entry = CallHistoryEntry(
      direction: direction,
      outcome: outcome,
      timestamp: timestamp,
      durationInSeconds: durationInSeconds,
    );

    // dedupe near-duplicate last entry
    final currentHist = List<CallHistoryEntry>.from(lead.callHistory);
    final last = currentHist.isNotEmpty ? currentHist.last : null;
    if (last != null) {
      final sameType = last.outcome == entry.outcome;
      final sameDir = last.direction == entry.direction;
      final sameDur = last.durationInSeconds == entry.durationInSeconds;
      final dt = (entry.timestamp.difference(last.timestamp).inMilliseconds).abs();
      if (sameType && sameDir && sameDur && dt < 2000) {
        final updated = lead.copyWith(lastUpdated: DateTime.now(), lastInteraction: DateTime.now());
        await _saveLeadToStorage(updated);
        return updated;
      }
    }

    final updated = lead.copyWith(
      callHistory: [...lead.callHistory, entry],
      lastUpdated: DateTime.now(),
      lastCallOutcome: lead.lastCallOutcome,
      needsManualReview: lead.needsManualReview,
    );
    await _saveLeadToStorage(updated);
    return updated;
  }

  Future<Lead?> addFinalCallEvent({
    required String phone,
    required String direction,
    required String outcome,
    required DateTime timestamp,
    int? durationInSeconds,
  }) async {
    final lead = await findOrCreateLead(phone: phone, finalOutcome: outcome);

    bool needsReview = lead.needsManualReview;
    if (outcome == 'missed' || outcome == 'rejected') needsReview = true;

    final entry = CallHistoryEntry(
      direction: direction,
      outcome: outcome,
      timestamp: timestamp,
      durationInSeconds: durationInSeconds,
    );

    final hist = List<CallHistoryEntry>.from(lead.callHistory);
    final lastIndex = hist.isNotEmpty ? hist.length - 1 : -1;

    if (lastIndex >= 0) {
      final last = hist[lastIndex];
      final sameType = last.outcome == entry.outcome;
      final sameDir = last.direction == entry.direction;
      final dt = (timestamp.difference(last.timestamp).inMilliseconds).abs();

      if ((sameType && sameDir && dt < 5000) ||
          (last.durationInSeconds == null && entry.durationInSeconds != null && dt < 30000)) {
        hist[lastIndex] = entry;
      } else {
        hist.add(entry);
      }
    } else {
      hist.add(entry);
    }

    final updated = lead.copyWith(
      callHistory: hist,
      lastCallOutcome: outcome,
      lastInteraction: DateTime.now(),
      lastUpdated: DateTime.now(),
      needsManualReview: needsReview,
    );

    await _saveLeadToStorage(updated);
    return updated;
  }

  // -------------------------------------------------------------------------
  // User actions
  // -------------------------------------------------------------------------
  Future<void> markLeadForReview(String leadId, bool isNeeded) async {
    final lead = await getLead(leadId: leadId);
    if (lead == null) {
      print("❌ Lead not found for review update.");
      return;
    }
    final updated = lead.copyWith(needsManualReview: isNeeded, lastUpdated: DateTime.now());
    await _saveLeadToStorage(updated);
  }

  Future<void> addNote({required Lead lead, required String note}) async {
    if (lead.id.isEmpty) throw Exception('Lead must have a valid ID to add a note.');

    final latest = await getLead(leadId: lead.id);
    if (latest == null) throw Exception("Lead not found for note update.");

    final updated = latest.copyWith(
      notes: [...latest.notes, LeadNote(timestamp: DateTime.now(), text: note)],
      lastUpdated: DateTime.now(),
      lastInteraction: DateTime.now(),
      needsManualReview: false,
    );
    await _saveLeadToStorage(updated);
  }

  Future<Lead> updateLead({
    required String id,
    String? name,
    String? status,
    String? phoneNumber,
  }) async {
    final existing = await getLead(leadId: id);
    if (existing == null) throw Exception("Lead not found for update.");

    final updated = existing.copyWith(
      name: name ?? existing.name,
      status: status ?? existing.status,
      phoneNumber: phoneNumber ?? existing.phoneNumber,
      lastUpdated: DateTime.now(),
      lastInteraction: DateTime.now(),
      needsManualReview: false,
    );
    await _saveLeadToStorage(updated);
    return updated;
  }

  Future<void> deleteLead(String id) async {
    try {
      await _leadsCollection.doc(id).delete();
      _cached.removeWhere((l) => l.id == id);
      print("✅ [FIRESTORE] Deleted lead $id from /leads.");
    } catch (e) {
      print("❌ [FIRESTORE] Error deleting lead: $e");
    }
  }
}
