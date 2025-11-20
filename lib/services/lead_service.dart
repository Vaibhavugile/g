import 'dart:async';
import 'dart:convert';                        // ⬅ added
import 'package:crypto/crypto.dart';          // ⬅ added
import 'package:cloud_firestore/cloud_firestore.dart';
import '../models/lead.dart';

/// Robust LeadService with:
///  - consistent phone normalization (uses normalizePhone from models/lead.dart)
///  - per-phone concurrency serialization (prevents duplicate creates)
///  - idempotent saves (skips no-op Firestore writes)
///  - DETERMINISTIC LEAD IDS (Option A) → ensures Flutter + UploadWorker use SAME doc
class LeadService {
  // Singleton
  static final LeadService instance = LeadService._internal();
  factory LeadService() => instance;
  LeadService._internal();

  final FirebaseFirestore _db = FirebaseFirestore.instance;
  final CollectionReference<Map<String, dynamic>> _leadsCollection =
      FirebaseFirestore.instance.collection('leads');

  final List<Lead> _cached = [];

  /// per-normalized-phone pending operation
  final Map<String, Completer<Lead>> _pendingFindOrCreates = {};

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------
  /// Normalizes phone to digits only.
  String _normalize(String? number) => normalizePhone(number);

  /// NEW → make same lead ID as UploadWorker
  /// UploadWorker: leadId = "phone_" + sha1(digits).substring(0,12)
  String _leadIdFromPhone(String digits) {
    final digest = sha1.convert(utf8.encode(digits)).toString();
    return 'phone_${digest.substring(0, 12)}';
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
      print("✅ [FIRESTORE] Loaded ${_cached.length} leads.");
    } catch (e) {
      print("❌ Load leads error: $e");
      _cached.clear();
    }
  }

  List<Lead> getAll() => List.unmodifiable(_cached);

  Lead? _findInCacheByNormalized(String normalized) {
    try {
      return _cached.firstWhere((l) => _normalize(l.phoneNumber) == normalized);
    } catch (_) {
      return null;
    }
  }

  Future<Lead?> getLead({required String leadId}) async {
    try {
      final cached = _cached.firstWhere((l) => l.id == leadId);
      return cached;
    } catch (_) {}

    try {
      final doc = await _leadsCollection.doc(leadId).get();
      if (doc.exists && doc.data() != null) {
        final lead = Lead.fromMap(Map<String, dynamic>.from(doc.data()!))
            .copyWith(id: doc.id);
        _cached.removeWhere((l) => l.id == lead.id);
        _cached.add(lead);
        return lead;
      }
      return null;
    } catch (e) {
      print("❌ Fetch lead error: $e");
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // SAVE (idempotent)
  // -------------------------------------------------------------------------
  Future<void> _saveLeadToStorage(Lead lead) async {
    try {
      final index = _cached.indexWhere((l) => l.id == lead.id);

      if (index != -1) {
        final existing = _cached[index];
        if (_mapsShallowEqual(existing.toMap(), lead.toMap())) {
          print("ℹ️ No changes for lead ${lead.id} — skip write.");
          _cached[index] = lead;
          return;
        }
      }

      await _leadsCollection.doc(lead.id).set(lead.toMap());
      print("✅ [FIRESTORE] Saved lead ${lead.id}");

      if (index == -1) {
        _cached.add(lead);
      } else {
        _cached[index] = lead;
      }
    } catch (e) {
      print("❌ Save lead error: $e");
    }
  }

  Future<void> saveLead(Lead lead) async {
    final cleared = lead.copyWith(needsManualReview: false);
    await _saveLeadToStorage(cleared);
  }

  // -------------------------------------------------------------------------
  // FIND OR CREATE — NOW DETERMINISTIC ID
  // -------------------------------------------------------------------------
  Future<Lead> createLead(String phone) async {
    final normalized = _normalize(phone);
    return await _findOrCreateByNormalized(normalized, phoneFallback: phone);
  }

  Future<Lead> findOrCreateLead({
    required String phone,
    String finalOutcome = 'none',
  }) async {
    final normalized = _normalize(phone);
    return await _findOrCreateByNormalized(
      normalized,
      finalOutcome: finalOutcome,
      phoneFallback: phone,
    );
  }

  Future<Lead> _findOrCreateByNormalized(
    String normalized, {
    String? finalOutcome,
    String? phoneFallback,
  }) async {
    // 1. cache
    final cached = _findInCacheByNormalized(normalized);
    if (cached != null) {
      final maybeUpdated = (finalOutcome != null && finalOutcome.isNotEmpty)
          ? cached.copyWith(
              lastCallOutcome: finalOutcome,
              lastUpdated: DateTime.now(),
            )
          : cached;
      if (maybeUpdated != cached) {
        await _saveLeadToStorage(maybeUpdated);
        return maybeUpdated;
      }
      return cached;
    }

    // 2. pending future
    if (_pendingFindOrCreates.containsKey(normalized)) {
      try {
        return await _pendingFindOrCreates[normalized]!.future;
      } catch (_) {}
    }

    final completer = Completer<Lead>();
    _pendingFindOrCreates[normalized] = completer;

    try {
      // 3. Firestore query (legacy lookup by phoneNumber)
      try {
        final snap = await _leadsCollection
            .where('phoneNumber', isEqualTo: normalized)
            .limit(1)
            .get();
        if (snap.docs.isNotEmpty) {
          final doc = snap.docs.first;
          final lead = Lead.fromMap(Map<String, dynamic>.from(doc.data()))
              .copyWith(id: doc.id);

          _cached.removeWhere((l) => l.id == lead.id);
          _cached.add(lead);

          final maybeUpdated =
              (finalOutcome != null && finalOutcome.isNotEmpty)
                  ? lead.copyWith(
                      lastCallOutcome: finalOutcome,
                      lastUpdated: DateTime.now(),
                    )
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
        print("❌ Phone query error: $e");
      }

      // 4. NEW LEAD using deterministic ID
      final digits = normalized.isEmpty ? _normalize(phoneFallback) : normalized;
      final leadId = _leadIdFromPhone(digits);

      final docRef = _leadsCollection.doc(leadId);
      final doc = await docRef.get();

      Lead newLead;

      if (doc.exists && doc.data() != null) {
        // if exists, load it
        newLead = Lead.fromMap(Map<String, dynamic>.from(doc.data()!))
            .copyWith(id: leadId);
      } else {
        // otherwise create fresh
        newLead = Lead.newLead(digits).copyWith(
          id: leadId,
          lastCallOutcome: finalOutcome ?? 'none',
          lastUpdated: DateTime.now(),
        );
        await docRef.set(newLead.toMap());
        print("🆕 Created deterministic lead $leadId");
      }

      // update cache
      _cached.removeWhere((l) => l.id == newLead.id);
      _cached.add(newLead);

      completer.complete(newLead);
      _pendingFindOrCreates.remove(normalized);
      return newLead;
    } catch (e, st) {
      if (!completer.isCompleted) completer.completeError(e, st);
      _pendingFindOrCreates.remove(normalized);
      rethrow;
    }
  }

  // -------------------------------------------------------------------------
  // addCallEvent
  // -------------------------------------------------------------------------
  Future<Lead> addCallEvent({
    required String phone,
    required String direction,
    required String outcome,
    required DateTime timestamp,
    int? durationInSeconds,
  }) async {
    final lead = await findOrCreateLead(phone: phone);

    final entry = CallHistoryEntry(
      direction: direction,
      outcome: outcome,
      timestamp: timestamp,
      durationInSeconds: durationInSeconds,
    );

    final hist = [...lead.callHistory];
    final last = hist.isNotEmpty ? hist.last : null;

    if (last != null) {
      final dt = (entry.timestamp.difference(last.timestamp)).inMilliseconds.abs();
      final dupe = last.outcome == entry.outcome &&
          last.direction == entry.direction &&
          last.durationInSeconds == entry.durationInSeconds &&
          dt < 2000;

      if (dupe) {
        final updated = lead.copyWith(lastUpdated: DateTime.now());
        await _saveLeadToStorage(updated);
        return updated;
      }
    }

    final updated = lead.copyWith(
      callHistory: [...hist, entry],
      lastUpdated: DateTime.now(),
    );
    await _saveLeadToStorage(updated);
    return updated;
  }

  // -------------------------------------------------------------------------
  // addFinalCallEvent
  // -------------------------------------------------------------------------
  Future<Lead?> addFinalCallEvent({
    required String phone,
    required String direction,
    required String outcome,
    required DateTime timestamp,
    int? durationInSeconds,
  }) async {
    final lead = await findOrCreateLead(
      phone: phone,
      finalOutcome: outcome,
    );

    bool needsReview = lead.needsManualReview;
    if (outcome == 'missed' || outcome == 'rejected') {
      needsReview = true;
    }

    final entry = CallHistoryEntry(
      direction: direction,
      outcome: outcome,
      timestamp: timestamp,
      durationInSeconds: durationInSeconds,
    );

    final hist = [...lead.callHistory];
    final lastIndex = hist.isEmpty ? -1 : hist.length - 1;

    if (lastIndex >= 0) {
      final last = hist[lastIndex];
      final dt = (timestamp.difference(last.timestamp)).inMilliseconds.abs();
      final merge =
          (last.outcome == entry.outcome && last.direction == entry.direction && dt < 5000) ||
              (last.durationInSeconds == null &&
                  entry.durationInSeconds != null &&
                  dt < 30000);

      if (merge) {
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
  // USER ACTIONS
  // -------------------------------------------------------------------------
  Future<void> markLeadForReview(String leadId, bool isNeeded) async {
    final lead = await getLead(leadId: leadId);
    if (lead == null) return;

    final updated = lead.copyWith(
      needsManualReview: isNeeded,
      lastUpdated: DateTime.now(),
    );
    await _saveLeadToStorage(updated);
  }

  Future<void> addNote({required Lead lead, required String note}) async {
    if (lead.id.isEmpty) throw Exception("Lead must have ID");

    final latest = await getLead(leadId: lead.id);
    if (latest == null) throw Exception("Lead not found");

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
    if (existing == null) throw Exception("Lead not found");

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
      print("🗑 Deleted $id");
    } catch (e) {
      print("❌ Delete lead error: $e");
    }
  }
}