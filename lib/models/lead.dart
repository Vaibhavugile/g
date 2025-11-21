import 'dart:math';
import 'package:cloud_firestore/cloud_firestore.dart';

/// Normalize phone to digits-only for internal canonical form.
String normalizePhone(String? raw) {
  if (raw == null) return '';
  return raw.replaceAll(RegExp(r'\D'), '');
}

/// Robust timestamp parser used by fromMap()
DateTime parseTs(Object? v) {
  if (v == null) return DateTime.fromMillisecondsSinceEpoch(0);
  if (v is int) return DateTime.fromMillisecondsSinceEpoch(v);
  if (v is Timestamp) return v.toDate();
  if (v is String) {
    final maybeInt = int.tryParse(v);
    if (maybeInt != null) return DateTime.fromMillisecondsSinceEpoch(maybeInt);
    return DateTime.tryParse(v) ?? DateTime.fromMillisecondsSinceEpoch(0);
  }
  return DateTime.fromMillisecondsSinceEpoch(0);
}

// Add this to lead.dart (near other model classes)

class LatestCall {
  final String? callId;
  final String? phoneNumber;
  final String? direction; // "inbound" / "outbound"
  final String? finalOutcome; // "ended" / "missed" / "rejected" etc.
  final int? durationInSeconds;
  final DateTime? createdAt;
  final DateTime? finalizedAt;

  LatestCall({
    this.callId,
    this.phoneNumber,
    this.direction,
    this.finalOutcome,
    this.durationInSeconds,
    this.createdAt,
    this.finalizedAt,
  });

  factory LatestCall.fromDoc(QueryDocumentSnapshot doc) {
    final data = doc.data() as Map<String, dynamic>;
    DateTime? _ts(Object? v) {
      if (v == null) return null;
      if (v is Timestamp) return v.toDate();
      if (v is DateTime) return v;
      try {
        return DateTime.parse(v.toString());
      } catch (_) {
        return null;
      }
    }

    return LatestCall(
      callId: doc.id,
      phoneNumber: (data['phoneNumber'] as String?)?.trim(),
      direction: (data['direction'] as String?)?.toLowerCase(),
      finalOutcome: (data['finalOutcome'] as String?)?.toLowerCase(),
      durationInSeconds: data['durationInSeconds'] is num ? (data['durationInSeconds'] as num).toInt() : null,
      createdAt: _ts(data['createdAt']),
      finalizedAt: _ts(data['finalizedAt']),
    );
  }
}

/// Entry in call history.
class CallHistoryEntry {
  final String direction; // inbound / outbound
  final String outcome; // answered / missed / rejected / ended / ringing / started / outgoing_start
  final DateTime timestamp;
  final String note;
  int? durationInSeconds;

  CallHistoryEntry({
    required this.direction,
    required this.outcome,
    required this.timestamp,
    this.note = '',
    this.durationInSeconds,
  });

  /// Intermediate means non-terminal (we may later replace with ended)
  bool get isIntermediate {
    final o = outcome.toLowerCase();
    return o == 'ringing' || o == 'started' || o == 'outgoing_start' || o == 'answered';
  }

  factory CallHistoryEntry.fromMap(Map<String, dynamic> map) {
    // parse timestamp: support int (ms), String (ISO or ms), and Firestore Timestamp
    final raw = map['timestamp'];
    DateTime ts;
    if (raw == null) {
      ts = DateTime.fromMillisecondsSinceEpoch(0);
    } else if (raw is int) {
      ts = DateTime.fromMillisecondsSinceEpoch(raw);
    } else if (raw is String) {
      // try parse as integer ms first, then ISO
      final maybeInt = int.tryParse(raw);
      if (maybeInt != null) {
        ts = DateTime.fromMillisecondsSinceEpoch(maybeInt);
      } else {
        ts = DateTime.tryParse(raw) ?? DateTime.fromMillisecondsSinceEpoch(0);
      }
    } else if (raw is Timestamp) {
      ts = raw.toDate();
    } else {
      // unknown type
      ts = DateTime.fromMillisecondsSinceEpoch(0);
    }

    int? dur;
    final durRaw = map['durationInSeconds'];
    if (durRaw is int) dur = durRaw;
    else if (durRaw is String) dur = int.tryParse(durRaw);

    return CallHistoryEntry(
      direction: (map['direction'] ?? 'unknown').toString(),
      outcome: (map['outcome'] ?? 'unknown').toString(),
      timestamp: ts,
      note: (map['note'] ?? '').toString(),
      durationInSeconds: dur,
    );
  }

  Map<String, dynamic> toMap() => {
        'direction': direction,
        'outcome': outcome,
        'timestamp': timestamp.millisecondsSinceEpoch,
        'note': note,
        'durationInSeconds': durationInSeconds,
      };
}

/// Simple note attached to a lead.
class LeadNote {
  final String text;
  final DateTime timestamp;

  LeadNote({required this.text, required this.timestamp});

  factory LeadNote.fromMap(Map<String, dynamic> map) {
    final raw = map['timestamp'];
    DateTime ts;
    if (raw is int) {
      ts = DateTime.fromMillisecondsSinceEpoch(raw);
    } else if (raw is String) {
      ts = DateTime.tryParse(raw) ?? DateTime.fromMillisecondsSinceEpoch(0);
    } else if (raw is Timestamp) {
      ts = (raw as Timestamp).toDate();
    } else {
      ts = DateTime.fromMillisecondsSinceEpoch(0);
    }
    return LeadNote(text: (map['text'] ?? '').toString(), timestamp: ts);
  }

  Map<String, dynamic> toMap() => {
        'text': text,
        'timestamp': timestamp.millisecondsSinceEpoch,
      };
}

/// Lead model stored in Firestore.
class Lead {
  final String id;
  final String name;
  final String phoneNumber; // normalized digits-only
  final String tenantId; // NEW: tenant id for multitenant separation
  final String status;
  final String lastCallOutcome;
  final DateTime lastInteraction;
  final DateTime lastUpdated;
  final List<LeadNote> notes;
  final List<CallHistoryEntry> callHistory;
  final bool needsManualReview;
  final String? address;
  final String? requirements;
  final DateTime? nextFollowUp;
  final DateTime? eventDate;

  Lead({
    required this.id,
    required this.name,
    required this.phoneNumber,
    required this.tenantId,
    required this.status,
    this.lastCallOutcome = 'none',
    required this.lastInteraction,
    required this.lastUpdated,
    this.notes = const [],
    this.callHistory = const [],
    this.needsManualReview = false,
    this.address,
    this.requirements,
    this.nextFollowUp,
    this.eventDate,
  });

  static String generateId() =>
      DateTime.now().millisecondsSinceEpoch.toString() + Random().nextInt(1000).toString();

  factory Lead.newLead(String rawPhone, {String tenantId = 'default_tenant'}) {
    final phone = normalizePhone(rawPhone);
    final now = DateTime.now();
    return Lead(
      id: generateId(),
      name: '',
      phoneNumber: phone,
      tenantId: tenantId,
      status: 'new',
      lastCallOutcome: 'none',
      lastInteraction: now,
      lastUpdated: now,
      notes: [],
      callHistory: [],
      needsManualReview: false,
      address: null,
      requirements: null,
      nextFollowUp: null,
      eventDate: null,
    );
  }

  Lead copyWith({
    String? id,
    String? name,
    String? phoneNumber,
    String? tenantId,
    String? status,
    String? lastCallOutcome,
    DateTime? lastInteraction,
    DateTime? lastUpdated,
    List<LeadNote>? notes,
    List<CallHistoryEntry>? callHistory,
    bool? needsManualReview,
    String? address,
    String? requirements,
    DateTime? nextFollowUp,
    DateTime? eventDate,
  }) {
    return Lead(
      id: id ?? this.id,
      name: name ?? this.name,
      phoneNumber: phoneNumber != null ? normalizePhone(phoneNumber) : this.phoneNumber,
      tenantId: tenantId ?? this.tenantId,
      status: status ?? this.status,
      lastCallOutcome: lastCallOutcome ?? this.lastCallOutcome,
      lastInteraction: lastInteraction ?? this.lastInteraction,
      lastUpdated: lastUpdated ?? this.lastUpdated,
      notes: notes ?? this.notes,
      callHistory: callHistory ?? this.callHistory,
      needsManualReview: needsManualReview ?? this.needsManualReview,
      address: address ?? this.address,
      requirements: requirements ?? this.requirements,
      nextFollowUp: nextFollowUp ?? this.nextFollowUp,
      eventDate: eventDate ?? this.eventDate,
    );
  }

  factory Lead.fromMap(Map<String, dynamic> map) {
    // parse lastInteraction
    final lastInteractionRaw = map['lastInteraction'] ?? map['last_interaction'] ?? map['lastSeenAt'];
    DateTime lastInteraction;
    if (lastInteractionRaw is int) {
      lastInteraction = DateTime.fromMillisecondsSinceEpoch(lastInteractionRaw);
    } else if (lastInteractionRaw is String) {
      lastInteraction = DateTime.tryParse(lastInteractionRaw) ?? DateTime.fromMillisecondsSinceEpoch(0);
    } else if (lastInteractionRaw is Timestamp) {
      lastInteraction = (lastInteractionRaw as Timestamp).toDate();
    } else {
      lastInteraction = DateTime.fromMillisecondsSinceEpoch(0);
    }

    // parse lastUpdated
    final lastUpdatedRaw = map['lastUpdated'] ?? map['last_updated'];
    DateTime lastUpdated;
    if (lastUpdatedRaw is int) {
      lastUpdated = DateTime.fromMillisecondsSinceEpoch(lastUpdatedRaw);
    } else if (lastUpdatedRaw is String) {
      lastUpdated = DateTime.tryParse(lastUpdatedRaw) ?? DateTime.fromMillisecondsSinceEpoch(0);
    } else if (lastUpdatedRaw is Timestamp) {
      lastUpdated = (lastUpdatedRaw as Timestamp).toDate();
    } else {
      lastUpdated = DateTime.fromMillisecondsSinceEpoch(0);
    }

    final notesList = (map['notes'] as List<dynamic>?) ?? [];
    final callsList = (map['callHistory'] as List<dynamic>?) ?? [];

    final history = callsList
        .map((e) {
          try {
            return CallHistoryEntry.fromMap(Map<String, dynamic>.from(e));
          } catch (_) {
            return null;
          }
        })
        .whereType<CallHistoryEntry>()
        .toList()
      ..sort((a, b) => a.timestamp.compareTo(b.timestamp)); // oldest -> newest

    // Tenant handling
    final tenant = (map['tenantId'] ?? map['tenant'] ?? 'default_tenant').toString();

    return Lead(
      id: (map['id'] ?? map['docId'] ?? generateId()).toString(),
      name: (map['name'] ?? '').toString(),
      phoneNumber: normalizePhone((map['phoneNumber'] ?? '').toString()),
      tenantId: tenant,
      status: (map['status'] ?? 'new').toString(),
      lastCallOutcome: (map['lastCallOutcome'] ?? 'none').toString(),
      lastInteraction: lastInteraction,
      lastUpdated: lastUpdated,
      notes: notesList.map((e) {
        try {
          return LeadNote.fromMap(Map<String, dynamic>.from(e));
        } catch (_) {
          return LeadNote(text: '', timestamp: DateTime.fromMillisecondsSinceEpoch(0));
        }
      }).toList(),
      callHistory: history,
      needsManualReview: (map['needsManualReview'] as bool?) ?? false,
      // NEW fields
      address: (map['address'] as String?) ?? null,
      requirements: (map['requirements'] as String?) ?? null,
      nextFollowUp: map['nextFollowUp'] != null ? parseTs(map['nextFollowUp']) : null,
      eventDate: map['eventDate'] != null ? parseTs(map['eventDate']) : null,
    );
  }

  Map<String, dynamic> toMap() => {
        'id': id,
        'name': name,
        'phoneNumber': phoneNumber,
        'tenantId': tenantId,
        'status': status,
        'lastCallOutcome': lastCallOutcome,
        'lastInteraction': lastInteraction.millisecondsSinceEpoch,
        'lastUpdated': lastUpdated.millisecondsSinceEpoch,
        'notes': notes.map((e) => e.toMap()).toList(),
        'callHistory': callHistory.map((e) => e.toMap()).toList(),
        'needsManualReview': needsManualReview,
        'address': address,
        'requirements': requirements,
        'nextFollowUp': nextFollowUp != null ? nextFollowUp!.millisecondsSinceEpoch : null,
        'eventDate': eventDate != null ? eventDate!.millisecondsSinceEpoch : null,
      };
}
