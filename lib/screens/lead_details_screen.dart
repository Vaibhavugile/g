import 'package:flutter/material.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import '../models/lead.dart';
import '../services/lead_service.dart';

class LeadDetailsScreen extends StatefulWidget {
  final Lead lead;

  const LeadDetailsScreen({
    super.key,
    required this.lead,
  });

  @override
  State<LeadDetailsScreen> createState() => _LeadDetailsScreenState();
}

class LatestCall {
  final String id;
  final String? direction;
  final int? durationInSeconds;
  final DateTime? createdAt;

  LatestCall({
    required this.id,
    this.direction,
    this.durationInSeconds,
    this.createdAt,
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
        final maybeInt = int.tryParse(v.toString());
        if (maybeInt != null) return DateTime.fromMillisecondsSinceEpoch(maybeInt);
        return null;
      }
    }

    return LatestCall(
      id: doc.id,
      direction: (data['direction'] as String?)?.toLowerCase(),
      durationInSeconds:
          data['durationInSeconds'] is num ? (data['durationInSeconds'] as num).toInt() : null,
      createdAt: _toDate(data['createdAt']),
    );
  }
}

class _LeadDetailsScreenState extends State<LeadDetailsScreen> {
  final LeadService _service = LeadService.instance;

  late Lead _lead;

  // Controllers
  late TextEditingController _phoneController;
  late TextEditingController _nameController;
  late TextEditingController _noteController;

  // NEW controllers for editable fields
  late TextEditingController _addressController;
  late TextEditingController _requirementsController;
  DateTime? _nextFollowUp;
  DateTime? _eventDate;

  bool _saving = false;

  final List<String> _statusOptions = [
    "new",
    "in progress",
    "follow up",
    "interested",
    "not interested",
    "closed",
  ];

  // Latest up to 5 calls for this lead
  List<LatestCall> _latestCalls = [];
  bool _loadingLatestCalls = false;

  @override
  void initState() {
    super.initState();
    _lead = widget.lead;

    _phoneController = TextEditingController(text: _lead.phoneNumber);
    _nameController = TextEditingController(text: _lead.name);
    _noteController = TextEditingController();

    // initialize new controllers/date fields
    _addressController = TextEditingController(text: _lead.address ?? '');
    _requirementsController = TextEditingController(text: _lead.requirements ?? '');
    _nextFollowUp = _lead.nextFollowUp;
    _eventDate = _lead.eventDate;

    _loadLatestCalls();
  }

  @override
  void didUpdateWidget(covariant LeadDetailsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.lead.id != oldWidget.lead.id) {
      setState(() {
        _lead = widget.lead;
        _phoneController.text = _lead.phoneNumber;
        _nameController.text = _lead.name;
        _addressController.text = _lead.address ?? '';
        _requirementsController.text = _lead.requirements ?? '';
        _nextFollowUp = _lead.nextFollowUp;
        _eventDate = _lead.eventDate;
      });
      _loadLatestCalls();
    }
  }

  @override
  void dispose() {
    _phoneController.dispose();
    _nameController.dispose();
    _noteController.dispose();
    _addressController.dispose();
    _requirementsController.dispose();
    super.dispose();
  }

  String _formatDuration(int seconds) {
    if (seconds < 60) return '${seconds}s';
    final minutes = (seconds ~/ 60);
    final secs = (seconds % 60).toString().padLeft(2, '0');
    return '${minutes}m ${secs}s';
  }

  String _formatDate(DateTime dt) {
    final d = dt.toLocal();
    return "${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}  "
        "${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}";
  }

  Future<void> _saveAll() async {
    setState(() => _saving = true);
    try {
      final updated = _lead.copyWith(
        name: _nameController.text.trim(),
        // phone is read-only here (modify pattern if you want editable phone)
        address: _addressController.text.trim().isEmpty ? null : _addressController.text.trim(),
        requirements:
            _requirementsController.text.trim().isEmpty ? null : _requirementsController.text.trim(),
        nextFollowUp: _nextFollowUp,
        eventDate: _eventDate,
        lastUpdated: DateTime.now(),
        lastInteraction: DateTime.now(),
      );

      final saved = await _service.saveLead(updated);

      setState(() {
        _lead = saved;
        _nameController.text = _lead.name;
        _phoneController.text = _lead.phoneNumber;
        _addressController.text = _lead.address ?? '';
        _requirementsController.text = _lead.requirements ?? '';
        _nextFollowUp = _lead.nextFollowUp;
        _eventDate = _lead.eventDate;
      });

      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Saved')));
    } catch (e) {
      // show error
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Save failed: $e')));
    } finally {
      setState(() => _saving = false);
    }
  }

  Future<void> _saveStatus(String newStatus) async {
    final updated = _lead.copyWith(
      status: newStatus,
      lastInteraction: DateTime.now(),
      lastUpdated: DateTime.now(),
    );

    final saved = await _service.saveLead(updated);

    setState(() {
      _lead = saved;
      _nameController.text = _lead.name;
      _phoneController.text = _lead.phoneNumber;
    });
  }

  Future<void> _addNote() async {
    if (_noteController.text.isEmpty) return;

    final String note = _noteController.text.trim();
    _noteController.clear();

    try {
      await _service.addNote(lead: _lead, note: note);

      final updatedLead = await _service.getLead(leadId: _lead.id);

      setState(() {
        if (updatedLead != null) {
          _lead = updatedLead;
          _nameController.text = _lead.name;
          _phoneController.text = _lead.phoneNumber;
          _addressController.text = _lead.address ?? '';
          _requirementsController.text = _lead.requirements ?? '';
          _nextFollowUp = _lead.nextFollowUp;
          _eventDate = _lead.eventDate;
        }
      });

      // refresh latest calls after adding a note
      await _loadLatestCalls();
    } catch (e) {
      print('❌ Error adding note: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to add note: $e')),
      );
    }
  }

  Widget _sectionTitle(String text) {
    return Padding(
      padding: const EdgeInsets.only(top: 20, bottom: 10),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 20,
          fontWeight: FontWeight.w700,
          color: Colors.blueGrey.shade900,
        ),
      ),
    );
  }

  Widget _headerCard() {
    final bool needsReview = _lead.needsManualReview;
    final String callOutcome = _lead.lastCallOutcome.toUpperCase();

    Color outcomeColor;
    if (callOutcome == 'MISSED') {
      outcomeColor = Colors.red.shade700;
    } else if (callOutcome == 'ANSWERED') {
      outcomeColor = Colors.green.shade700;
    } else {
      outcomeColor = Colors.blueGrey;
    }

    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 28,
                  backgroundColor: Colors.blue.shade100,
                  child: Icon(Icons.phone_android, size: 28, color: Colors.blue.shade700),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    _lead.phoneNumber,
                    style: const TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ],
            ),
            if (_lead.lastCallOutcome != 'none') ...[
              const SizedBox(height: 10),
              Text(
                'Last Call Status: ${_lead.lastCallOutcome.toUpperCase()}',
                style: TextStyle(
                  fontSize: 16,
                  color: _lead.lastCallOutcome == 'missed' ? Colors.red.shade700 : Colors.green.shade700,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
            // display small summary of address/requirements/dates
            if ((_lead.address ?? '').isNotEmpty) ...[
              const SizedBox(height: 12),
              Text('Address: ${_lead.address}', style: const TextStyle(fontSize: 13)),
            ],
            if ((_lead.requirements ?? '').isNotEmpty) ...[
              const SizedBox(height: 8),
              Text('Requirements: ${_lead.requirements}', style: const TextStyle(fontSize: 13)),
            ],
            if (_lead.nextFollowUp != null || _lead.eventDate != null) ...[
              const SizedBox(height: 10),
              Row(
                children: [
                  if (_lead.nextFollowUp != null)
                    Chip(
                      label: Text('Follow: ${_formatDate(_lead.nextFollowUp!)}'),
                    ),
                  const SizedBox(width: 8),
                  if (_lead.eventDate != null)
                    Chip(
                      label: Text('Event: ${_formatDate(_lead.eventDate!)}'),
                    ),
                ],
              )
            ]
          ],
        ),
      ),
    );
  }

  // -------------------------
  // Load latest up to 5 calls
  // -------------------------
  Future<void> _loadLatestCalls() async {
    setState(() {
      _loadingLatestCalls = true;
    });

    try {
      if (_lead.id.isEmpty) {
        setState(() {
          _latestCalls = [];
          _loadingLatestCalls = false;
        });
        return;
      }

      final q = await FirebaseFirestore.instance
          .collection('leads')
          .doc(_lead.id)
          .collection('calls')
          .orderBy('createdAt', descending: true)
          .limit(5)
          .get();

      final list = q.docs.map((d) => LatestCall.fromDoc(d)).toList();
      setState(() {
        _latestCalls = list;
      });
    } catch (e, st) {
      print('Error loading latest calls for lead ${_lead.id}: $e\n$st');
      setState(() {
        _latestCalls = [];
      });
    } finally {
      setState(() {
        _loadingLatestCalls = false;
      });
    }
  }

  String? _timeAgo(DateTime? dt) {
    if (dt == null) return null;
    final diff = DateTime.now().difference(dt);
    if (diff.inSeconds < 60) return '${diff.inSeconds}s';
    if (diff.inMinutes < 60) return '${diff.inMinutes}m';
    if (diff.inHours < 24) return '${diff.inHours}h';
    return '${diff.inDays}d';
  }

  // Replaces the old callHistory UI — shows latest up to 5 calls from calls subcollection
  Widget _callHistorySection() {
    if (_loadingLatestCalls) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 8.0),
        child: SizedBox(height: 28, child: Center(child: CircularProgressIndicator(strokeWidth: 2))),
      );
    }

    if (_latestCalls.isEmpty) {
      return const Text("No call history yet.");
    }

    return Column(
      children: _latestCalls.map((call) {
        final icon = call.direction == "inbound" ? Icons.call_received : Icons.call_made;
        final color = call.direction == "inbound" ? Colors.blue.shade700 : Colors.purple.shade700;
        final durationText = call.durationInSeconds != null ? ' (${_formatDuration(call.durationInSeconds!)})' : '';
        final when = _timeAgo(call.createdAt) ?? _formatDate(call.createdAt ?? DateTime.now());

        return Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          child: ListTile(
            leading: Icon(icon, color: color),
            title: Text("${call.direction?.toUpperCase() ?? 'UNKNOWN'}$durationText",
                style: TextStyle(fontWeight: FontWeight.w600, color: color)),
            subtitle: Text(when),
            trailing: IconButton(
              icon: const Icon(Icons.chevron_right),
              onPressed: () async {
                // Optional: open call doc, currently refreshes the list
                await _loadLatestCalls();
              },
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _notesSection() {
    if (_lead.notes.isEmpty) {
      return const Text("No notes yet");
    }

    return Column(
      children: _lead.notes.reversed.map((note) {
        return Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          child: ListTile(
            leading: const Icon(Icons.note),
            title: Text(note.text),
            subtitle: Text(_formatDate(note.timestamp)),
          ),
        );
      }).toList(),
    );
  }

  // ------------------------------
  // Date/time pickers helpers UI
  // ------------------------------
  Future<void> _pickDateTime({required bool forNextFollowUp}) async {
    final now = DateTime.now();
    final initial = forNextFollowUp ? (_nextFollowUp ?? now) : (_eventDate ?? now);
    final pickedDate = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(now.year - 5),
      lastDate: DateTime(now.year + 5),
    );
    if (pickedDate == null) return;

    final pickedTime = await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(initial));
    final combined = DateTime(pickedDate.year, pickedDate.month, pickedDate.day, pickedTime?.hour ?? 0, pickedTime?.minute ?? 0);

    setState(() {
      if (forNextFollowUp) _nextFollowUp = combined;
      else _eventDate = combined;
    });
  }

  Widget _dateRow({required String label, DateTime? value, required VoidCallback onTap}) {
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 12),
        decoration: BoxDecoration(
          color: Colors.grey.shade100,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.grey.shade300),
        ),
        child: Row(
          children: [
            Icon(label.contains('Follow') ? Icons.event : Icons.event_available, color: Colors.grey.shade700),
            const SizedBox(width: 12),
            Text(value != null ? _formatDate(value) : label, style: const TextStyle(fontSize: 14)),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Lead Details"),
        backgroundColor: Colors.blueAccent,
        foregroundColor: Colors.white,
        actions: [
          IconButton(
            icon: _saving ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2)) : const Icon(Icons.save),
            onPressed: _saving ? null : _saveAll,
            tooltip: 'Save All',
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _headerCard(),

            _sectionTitle("Phone Number"),
            TextField(
              controller: _phoneController,
              readOnly: true,
              decoration: InputDecoration(
                filled: true,
                fillColor: Colors.grey.shade200,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
            ),

            _sectionTitle("Name"),
            TextField(
              controller: _nameController,
              decoration: InputDecoration(
                filled: true,
                fillColor: Colors.grey.shade200,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onEditingComplete: _saveAll,
            ),

            _sectionTitle("Status"),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              decoration: BoxDecoration(
                color: Colors.grey.shade100,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.blueGrey.shade200),
              ),
              child: DropdownButton<String>(
                value: _lead.status,
                isExpanded: true,
                underline: const SizedBox(),
                items: _statusOptions.map((s) {
                  return DropdownMenuItem(value: s, child: Text(s));
                }).toList(),
                onChanged: (val) async {
                  if (val == null) return;
                  await _saveStatus(val);
                },
              ),
            ),

            // NEW: Address (editable)
            _sectionTitle("Address"),
            TextField(
              controller: _addressController,
              maxLines: 2,
              decoration: InputDecoration(
                hintText: "Address (optional)",
                filled: true,
                fillColor: Colors.grey.shade100,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onEditingComplete: _saveAll,
            ),

            // NEW: Requirements (editable)
            _sectionTitle("Requirements"),
            TextField(
              controller: _requirementsController,
              maxLines: 3,
              decoration: InputDecoration(
                hintText: "Lead requirements (what they need)",
                filled: true,
                fillColor: Colors.grey.shade100,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onEditingComplete: _saveAll,
            ),

            // NEW: Next follow-up
            _sectionTitle("Next Follow-up"),
            _dateRow(label: 'Set next follow-up date', value: _nextFollowUp, onTap: () => _pickDateTime(forNextFollowUp: true)),
            const SizedBox(height: 12),

            // NEW: Event date
            _sectionTitle("Event Date"),
            _dateRow(label: 'Set event date', value: _eventDate, onTap: () => _pickDateTime(forNextFollowUp: false)),

            _sectionTitle("Call History"),
            _callHistorySection(),

            _sectionTitle("Notes"),
            _notesSection(),

            const SizedBox(height: 12),

            TextField(
              controller: _noteController,
              minLines: 1,
              maxLines: 3,
              decoration: InputDecoration(
                hintText: "Write a note...",
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.send),
                  onPressed: _addNote,
                ),
              ),
            ),

            const SizedBox(height: 40),
          ],
        ),
      ),
    );
  }
}
