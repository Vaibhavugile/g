// lib/screens/lead_list_screen.dart
import 'package:flutter/material.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import '../models/lead.dart';
import '../services/lead_service.dart';
import 'lead_form_screen.dart';
// Note: lead_details_screen.dart is no longer imported as it is deprecated.

// -------------------------------------------------------------------------
// 🔥 PREMIUM COLOR PALETTE (Matched with LeadFormScreen)
// -------------------------------------------------------------------------
const Color _primaryColor = Color(0xFF1A237E); // Deep Indigo
const Color _accentColor = Color(0xFFE6A600); // Gold/Amber
const Color _backgroundColor = Color(0xFFF5F5F5); // Light Gray Background

class LeadListScreen extends StatefulWidget {
  const LeadListScreen({super.key});

  @override
  State<LeadListScreen> createState() => _LeadListScreenState();
}

// Lightweight model for the latest call (kept local here to avoid requiring lead.dart edits)
class LatestCall {
  final String? callId;
  final String? phoneNumber;
  final String? direction; // inbound / outbound
  final String? finalOutcome; // ended / missed / rejected
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

    DateTime? _toDate(Object? v) {
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
      createdAt: _toDate(data['createdAt']),
      finalizedAt: _toDate(data['finalizedAt']),
    );
  }
}

class _LeadListScreenState extends State<LeadListScreen> {
  // Use the singleton instance so cache is shared across app
  final LeadService _service = LeadService.instance;

  // mutable lists used by the UI
  List<Lead> _allLeads = [];
  List<Lead> _filteredLeads = [];
  bool _loading = true;

  final TextEditingController _searchCtrl = TextEditingController();

  // State for the call filter
  String _selectedFilter = 'All';
  final List<String> _filters = [
    'All',
    'Needs Review',
    'Incoming',
    'Outgoing',
    'Answered',
    'Missed',
    'Rejected'
  ];

  // Map leadId -> LatestCall (fetched from calls subcollection)
  final Map<String, LatestCall?> _latestCallByLead = {};
  bool _loadingLatestCalls = false;

  @override
  void initState() {
    super.initState();
    _loadLeads();
    _searchCtrl.addListener(_applySearch);
  }

  @override
  void dispose() {
    _searchCtrl.removeListener(_applySearch);
    _searchCtrl.dispose();
    super.dispose();
  }

  /// Fetch the single most recent call doc for the given lead.
  Future<LatestCall?> fetchLatestCallForLead(String leadId) async {
    try {
      final q = await FirebaseFirestore.instance
          .collection('leads')
          .doc(leadId)
          .collection('calls')
          .orderBy('createdAt', descending: true)
          .limit(1)
          .get();
      if (q.docs.isEmpty) return null;
      return LatestCall.fromDoc(q.docs.first);
    } catch (e, st) {
      // keep UI resilient: log and return null
      // ignore: avoid_print
      print('fetchLatestCallForLead error for $leadId: $e\n$st');
      return null;
    }
  }

  /// Load leads and in parallel fetch latest calls (batched to avoid too many concurrent reads)
  Future<void> _loadLeads() async {
    setState(() => _loading = true);

    await _service.loadLeads();

    // IMPORTANT: _service.getAll() may return an unmodifiable list (service cache).
    // Copy into a mutable list before sorting/modifying.
    final fetched = _service.getAll();
    _allLeads = List<Lead>.from(fetched);

    // Sort -> latest interaction first (mutable list sort is fine now)
    _allLeads.sort((a, b) => b.lastInteraction.compareTo(a.lastInteraction));

    // Clear previous latest map
    _latestCallByLead.clear();
    _loadingLatestCalls = true;

    // Fetch latest calls in batches to limit concurrent reads (batchSize tuned to 10)
    const int batchSize = 10;
    for (var i = 0; i < _allLeads.length; i += batchSize) {
      final batch = _allLeads.skip(i).take(batchSize).toList();
      final futures = batch.map((l) => fetchLatestCallForLead(l.id)).toList();
      final results = await Future.wait(futures);
      for (var j = 0; j < batch.length; j++) {
        _latestCallByLead[batch[j].id] = results[j];
      }
      // update UI incrementally so user sees chips appear
      setState(() {});
    }

    _applySearch(); // Apply search and filter after loading

    setState(() {
      _loading = false;
      _loadingLatestCalls = false;
    });
  }

  void _applySearch() {
    final query = _searchCtrl.text.toLowerCase();

    // 1. Apply text search
    List<Lead> searchFiltered = _allLeads.where((l) {
      return l.name.toLowerCase().contains(query) || l.phoneNumber.contains(query);
    }).toList();

    // 2. Apply call/review filter - use _latestCallByLead if available
    _filteredLeads = searchFiltered.where((l) {
      if (_selectedFilter == 'All') return true;

      // Use the explicit needsManualReview flag
      if (_selectedFilter == 'Needs Review') {
        return l.needsManualReview;
      }

      // Prefer latest-call data
      final LatestCall? latest = _latestCallByLead[l.id];

      // If we don't have latest-call data yet, attempt to fall back to existing fields
      if (latest == null) {
        // If no call history, it can't match call-specific filters
        if (l.callHistory.isEmpty) return false;

        // Fallback to legacy behavior (lastCallOutcome / callHistory.last)
        if (_selectedFilter == 'Answered' && l.lastCallOutcome == 'answered') return true;
        if (_selectedFilter == 'Missed' && l.lastCallOutcome == 'missed') return true;
        if (_selectedFilter == 'Rejected' && l.lastCallOutcome == 'rejected') return true;

        final lastCall = l.callHistory.last;
        if (_selectedFilter == 'Incoming' && lastCall.direction == 'inbound') return true;
        if (_selectedFilter == 'Outgoing' && lastCall.direction == 'outbound') return true;

        return false;
      }

      // Use latest call values
      final outcome = latest.finalOutcome?.toLowerCase();
      final direction = latest.direction?.toLowerCase();

      if (_selectedFilter == 'Answered') return (outcome == 'answered' || outcome == 'ended');
      if (_selectedFilter == 'Missed') return outcome == 'missed';
      if (_selectedFilter == 'Rejected') return outcome == 'rejected';
      if (_selectedFilter == 'Incoming') return direction == 'inbound';
      if (_selectedFilter == 'Outgoing') return direction == 'outbound';

      return false;
    }).toList();

    setState(() {});
  }

  // Method to handle filter change
  void _changeFilter(String filter) {
    setState(() {
      _selectedFilter = filter;
      _applySearch(); // Re-filter the list
    });
  }

  // -----------------------------------------
  // UI: STATUS CHIP (Updated Colors)
  // -----------------------------------------
  Widget _statusChip(String status) {
    Color color;
    switch (status) {
      case "new":
        color = _primaryColor; // Deep Indigo
        break;
      case "in progress":
        color = Colors.orange.shade700;
        break;
      case "follow up":
        color = Colors.teal.shade600;
        break;
      case "interested":
        color = Colors.green.shade600;
        break;
      default:
        color = Colors.blueGrey.shade400;
    }
    return Chip(
      label: Text(
        status.toUpperCase(),
        style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold),
      ),
      backgroundColor: color.withOpacity(0.1),
      labelStyle: TextStyle(color: color),
      visualDensity: VisualDensity.compact,
    );
  }

  // small helper to format relative time
  String? timeAgo(DateTime dt) {
    final diff = DateTime.now().difference(dt);
    if (diff.inSeconds < 60) return '${diff.inSeconds}s';
    if (diff.inMinutes < 60) return '${diff.inMinutes}m';
    if (diff.inHours < 24) return '${diff.inHours}h';
    return '${diff.inDays}d';
  }

  // -----------------------------------------
  // UI: LEAD CARD (Premium UI/UX and Review Highlight)
  // -----------------------------------------
  Widget _leadCard(Lead lead) {
    final bool needsReview = lead.needsManualReview;

    // Get latest call for this lead (if available)
    final latest = _latestCallByLead[lead.id];

    // Decide colors based on latest call (fallback to legacy lead.lastCallOutcome)
    String displayOutcome = 'NONE';
    Color outcomeColor = Colors.grey.shade600;
    Color outcomeBgColor = Colors.grey.shade200;

    if (latest != null && latest.finalOutcome != null) {
      displayOutcome = latest.finalOutcome!.toUpperCase();
      if (latest.finalOutcome == 'missed' || latest.finalOutcome == 'rejected') {
        outcomeColor = Colors.red.shade700;
        outcomeBgColor = Colors.red.withOpacity(0.1);
      } else if (latest.finalOutcome == 'answered' || latest.finalOutcome == 'ended') {
        outcomeColor = Colors.green.shade700;
        outcomeBgColor = Colors.green.withOpacity(0.1);
      }
    } else if (lead.lastCallOutcome != 'none') {
      displayOutcome = lead.lastCallOutcome.toUpperCase();
      if (lead.lastCallOutcome == 'missed' || lead.lastCallOutcome == 'rejected') {
        outcomeColor = Colors.red.shade700;
        outcomeBgColor = Colors.red.withOpacity(0.1);
      } else if (lead.lastCallOutcome == 'answered') {
        outcomeColor = Colors.green.shade700;
        outcomeBgColor = Colors.green.withOpacity(0.1);
      }
    }

    // Extra small chips: direction, duration, time
    final List<Widget> extraChips = [];
    if (latest != null) {
      if (latest.direction != null && latest.direction!.isNotEmpty) {
        extraChips.add(Chip(label: Text(latest.direction!.toUpperCase()), visualDensity: VisualDensity.compact));
      }
      if (latest.durationInSeconds != null) {
        extraChips.add(Chip(label: Text('${latest.durationInSeconds}s'), visualDensity: VisualDensity.compact));
      }
      if (latest.createdAt != null) {
        final when = timeAgo(latest.createdAt!);
        if (when != null) extraChips.add(Chip(label: Text(when), visualDensity: VisualDensity.compact));
      }
    } else if (lead.callHistory.isNotEmpty) {
      final last = lead.callHistory.last;
      extraChips.add(Chip(label: Text(last.direction.toUpperCase()), visualDensity: VisualDensity.compact));
    }

    return Card(
      elevation: needsReview ? 4 : 2, // Higher elevation for review
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        // 🔥 Highlight border if review is needed
        side: needsReview ? const BorderSide(color: _accentColor, width: 2) : BorderSide.none,
      ),
      child: ListTile(
        contentPadding: const EdgeInsets.all(12),
        leading: needsReview ? Icon(Icons.error_outline, color: _accentColor, size: 30) : Icon(Icons.person_pin, color: _primaryColor, size: 30),
        onTap: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => LeadFormScreen(
                lead: lead,
                autoOpenedFromCall: false,
              ),
            ),
          ).then((_) => _loadLeads());
        },
        title: Text(
          lead.name.isEmpty ? "No Name (${lead.phoneNumber})" : lead.name,
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: needsReview ? _accentColor : _primaryColor,
          ),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 4),
            Text(lead.phoneNumber, style: TextStyle(color: Colors.blueGrey.shade600)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8.0, // space between chips
              runSpacing: 4.0, // space between rows of chips
              children: [
                _statusChip(lead.status),
                if (displayOutcome != 'NONE')
                  Chip(
                    label: Text(
                      displayOutcome,
                      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold),
                    ),
                    backgroundColor: outcomeBgColor,
                    labelStyle: TextStyle(color: outcomeColor),
                    visualDensity: VisualDensity.compact,
                  ),
                ...extraChips,
                if (needsReview)
                  Chip(
                    label: const Text(
                      "REVIEW NEEDED",
                      style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold),
                    ),
                    backgroundColor: _accentColor.withOpacity(0.15),
                    labelStyle: const TextStyle(color: _accentColor),
                    visualDensity: VisualDensity.compact,
                  ),
              ],
            ),
          ],
        ),
        trailing: const Icon(Icons.arrow_forward_ios, size: 18, color: _primaryColor),
      ),
    );
  }

  // -----------------------------------------
  // MAIN UI
  // -----------------------------------------
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _backgroundColor,
      appBar: AppBar(
        title: const Text("Lead L"),
        backgroundColor: _primaryColor, // Premium Primary Color
        foregroundColor: Colors.white,
        elevation: 0,
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: _accentColor, // Premium Accent Color
        foregroundColor: _primaryColor, // Text color for the gold button
        child: const Icon(Icons.add),
        onPressed: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              // Opens form for a new, empty lead.
              builder: (_) => LeadFormScreen(
                lead: Lead.newLead(""),
                autoOpenedFromCall: false,
              ),
            ),
          ).then((_) => _loadLeads());
        },
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator(color: _primaryColor))
          : Column(
              children: [
                // -------------------------
                // SEARCH BAR
                // -------------------------
                Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: TextField(
                    controller: _searchCtrl,
                    decoration: InputDecoration(
                      prefixIcon: const Icon(Icons.search, color: _primaryColor),
                      hintText: "Search by name or phone",
                      filled: true,
                      fillColor: Colors.white,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: BorderSide.none,
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: const BorderSide(color: _primaryColor, width: 2),
                      ),
                    ),
                  ),
                ),

                // -------------------------
                // FILTER CHIPS
                // -------------------------
                SizedBox(
                  height: 40,
                  child: ListView.builder(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    itemCount: _filters.length,
                    itemBuilder: (_, i) {
                      final filter = _filters[i];
                      final isSelected = _selectedFilter == filter;
                      return Padding(
                        padding: const EdgeInsets.only(right: 10),
                        child: ActionChip(
                          label: Text(filter),
                          backgroundColor: isSelected ? _primaryColor : Colors.white,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(20),
                            side: BorderSide(color: isSelected ? _primaryColor : Colors.blueGrey.shade200),
                          ),
                          labelStyle: TextStyle(
                            color: isSelected ? Colors.white : _primaryColor,
                            fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                          ),
                          onPressed: () => _changeFilter(filter),
                        ),
                      );
                    },
                  ),
                ),

                const SizedBox(height: 8),

                // -------------------------
                // LIST
                // -------------------------
                Expanded(
                  child: _filteredLeads.isEmpty
                      ? const Center(child: Text("No leads found matching current filters."))
                      : ListView.builder(
                          itemCount: _filteredLeads.length,
                          itemBuilder: (_, i) => _leadCard(_filteredLeads[i]),
                        ),
                ),
              ],
            ),
    );
  }
}
