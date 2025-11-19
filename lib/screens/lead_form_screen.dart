import 'package:flutter/material.dart';
import '../models/lead.dart';
import '../services/lead_service.dart';

// -------------------------------------------------------------------------
// 🔥 PREMIUM COLOR PALETTE
// -------------------------------------------------------------------------
const Color _primaryColor = Color(0xFF1A237E); // Deep Indigo
const Color _accentColor = Color(0xFFE6A600); // Gold/Amber

class LeadFormScreen extends StatefulWidget {
  final Lead lead;
  final bool autoOpenedFromCall;

  const LeadFormScreen({
    super.key,
    required this.lead,
    this.autoOpenedFromCall = false,
  });

  @override
  State<LeadFormScreen> createState() => _LeadFormScreenState();
}

class _LeadFormScreenState extends State<LeadFormScreen> {
  final LeadService _service = LeadService.instance;

  late Lead _lead;

  late TextEditingController _nameController;
  late TextEditingController _noteController;
  late TextEditingController _phoneController;

  bool _hasUnsavedNameChanges = false;
  bool _hasUserSavedOrNoted = false;

  final List<String> _statusOptions = [
    "new",
    "in progress",
    "follow up",
    "interested",
    "not interested",
    "closed",
  ];

  @override
  void initState() {
    super.initState();
    _lead = widget.lead;
    _nameController = TextEditingController(text: _lead.name);
    _noteController = TextEditingController();
    _phoneController = TextEditingController(text: _lead.phoneNumber);

    _nameController.addListener(_checkUnsavedChanges);
  }

  @override
  void dispose() {
    if (widget.autoOpenedFromCall && !_hasUserSavedOrNoted && _lead.id.isNotEmpty) {
      print("⚠️ UI closed without save/note. Marking Lead ${_lead.id} for manual review.");
      _service.markLeadForReview(_lead.id, true).catchError((e) {
        print("❌ Error marking lead for review: $e");
      });
    }

    _nameController.removeListener(_checkUnsavedChanges);
    _nameController.dispose();
    _noteController.dispose();
    _phoneController.dispose();
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

  Future<void> _persistLeadIfTransient() async {
    if (_lead.id.isEmpty) {
      print("📝 First save: Persisting new lead for ${_lead.phoneNumber}");
      final persistedLead = await _service.createLead(_lead.phoneNumber);
      final updatedTransientLead = persistedLead.copyWith(
        name: _nameController.text.trim(),
        status: _lead.status,
        callHistory: _lead.callHistory,
        notes: _lead.notes,
        lastCallOutcome: _lead.lastCallOutcome,
        lastInteraction: DateTime.now(),
        lastUpdated: DateTime.now(),
      );
      await _service.saveLead(updatedTransientLead);
      setState(() {
        _lead = updatedTransientLead;
      });
    }
  }

  void _checkUnsavedChanges() {
    final currentName = _nameController.text.trim();
    final hasChanges = currentName != _lead.name;

    if (hasChanges != _hasUnsavedNameChanges) {
      setState(() {
        _hasUnsavedNameChanges = hasChanges;
      });
    }
  }

  Future<void> _saveLead({String? newStatus, String? newName}) async {
    await _persistLeadIfTransient();

    final name = newName ?? _nameController.text.trim();
    final status = newStatus ?? _lead.status;

    if (name == _lead.name && status == _lead.status && newStatus == null && newName == null) {
      return;
    }

    final updated = await _service.updateLead(id: _lead.id, name: name, status: status);

    _hasUserSavedOrNoted = true;

    setState(() {
      _lead = updated;
      _hasUnsavedNameChanges = false;
      _nameController.text = _lead.name;
    });

    _checkUnsavedChanges();
  }

  Future<void> _addNote() async {
    if (_noteController.text.isEmpty) return;
    await _persistLeadIfTransient();

    final String note = _noteController.text.trim();
    _noteController.clear();

    try {
      await _service.addNote(lead: _lead, note: note);

      _hasUserSavedOrNoted = true;

      final updatedLead = await _service.getLead(leadId: _lead.id);

      setState(() {
        if (updatedLead != null) {
          _lead = updatedLead;
          _nameController.text = _lead.name;
          _phoneController.text = _lead.phoneNumber;
        }
      });
    } catch (e) {
      print('❌ Error adding note: $e');
    }
  }

  Widget _sectionTitle(String text) {
    return Padding(
      padding: const EdgeInsets.only(top: 24, bottom: 8),
      child: Text(
        text,
        style: const TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.bold,
          color: _primaryColor,
          letterSpacing: 0.5,
        ),
      ),
    );
  }

  Widget _headerCard() {
    final bool needsReview = _lead.needsManualReview;
    final String callOutcome = _lead.lastCallOutcome.toUpperCase();

    Color outcomeColor;
    if (callOutcome == 'MISSED' || callOutcome == 'REJECTED') {
      outcomeColor = Colors.red.shade700;
    } else if (callOutcome == 'ANSWERED') {
      outcomeColor = Colors.green.shade700;
    } else {
      outcomeColor = Colors.blueGrey;
    }

    return Card(
      elevation: 6,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: needsReview ? const BorderSide(color: _accentColor, width: 3) : BorderSide.none,
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (needsReview)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: _accentColor,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Text(
                  'ACTION REQUIRED',
                  style: TextStyle(
                    color: _primaryColor,
                    fontWeight: FontWeight.bold,
                    fontSize: 12,
                  ),
                ),
              ),
            if (needsReview) const SizedBox(height: 12),
            Row(
              children: [
                CircleAvatar(
                  radius: 28,
                  backgroundColor: _primaryColor,
                  child: const Icon(Icons.perm_phone_msg_outlined, size: 28, color: Colors.white),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    _lead.phoneNumber,
                    style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w900, color: _primaryColor),
                  ),
                ),
              ],
            ),
            if (_lead.lastCallOutcome != 'none') ...[
              const Divider(height: 24),
              Text(
                'Last Call Outcome: ${callOutcome}',
                style: TextStyle(fontSize: 16, color: outcomeColor, fontWeight: FontWeight.w700),
              ),
            ]
          ],
        ),
      ),
    );
  }

  Widget _callHistorySection() {
    if (_lead.callHistory.isEmpty) return const Text("No call history recorded.");

    return Column(
      children: _lead.callHistory.reversed.map((call) {
        final icon = call.direction == "inbound" ? Icons.call_received : Icons.call_made;
        final Color color;
        switch (call.outcome) {
          case 'answered':
            color = Colors.green.shade600;
            break;
          case 'missed':
            color = Colors.red.shade600;
            break;
          case 'rejected':
            color = Colors.orange.shade600;
            break;
          case 'ended':
            color = Colors.blueGrey.shade400;
            break;
          default:
            color = Colors.blue.shade600;
        }

        final durationText = call.durationInSeconds != null ? ' (${_formatDuration(call.durationInSeconds!)})' : '';

        return Padding(
          padding: const EdgeInsets.only(bottom: 8.0),
          child: Container(
            decoration: BoxDecoration(
              color: color.withOpacity(0.08),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: color.withOpacity(0.2)),
            ),
            child: ListTile(
              leading: Icon(icon, color: color),
              title: Text("${call.direction} – ${call.outcome.toUpperCase()}$durationText", style: TextStyle(fontWeight: FontWeight.w600, color: color)),
              subtitle: Text(_formatDate(call.timestamp), style: const TextStyle(fontSize: 12)),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _notesSection() {
    if (_lead.notes.isEmpty) return const Text("No notes yet");

    return Column(
      children: _lead.notes.reversed.map((note) {
        return Card(
          elevation: 1,
          color: Colors.grey.shade50,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: BorderSide(color: Colors.grey.shade300)),
          child: ListTile(
            leading: const Icon(Icons.edit_note, color: _primaryColor),
            title: Text(note.text, style: const TextStyle(fontWeight: FontWeight.w500)),
            subtitle: Text(_formatDate(note.timestamp), style: const TextStyle(fontSize: 12)),
          ),
        );
      }).toList(),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: Text(widget.autoOpenedFromCall ? "Call Lead Review" : "Lead Details"),
        backgroundColor: _primaryColor,
        foregroundColor: Colors.white,
        elevation: 4,
        actions: [
          if (_hasUnsavedNameChanges)
            IconButton(
              icon: const Icon(Icons.save),
              onPressed: () => _saveLead(newName: _nameController.text.trim()),
              tooltip: 'Save Name',
            ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          _headerCard(),
          _sectionTitle("Phone Number"),
          TextField(
            controller: _phoneController,
            readOnly: true,
            style: const TextStyle(fontWeight: FontWeight.bold),
            decoration: InputDecoration(
              filled: true,
              fillColor: Colors.grey.shade100,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
          _sectionTitle("Lead Name"),
          TextField(
            controller: _nameController,
            decoration: InputDecoration(
              hintText: "Enter lead name",
              filled: true,
              fillColor: Colors.grey.shade100,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
            onSubmitted: (val) => _saveLead(newName: val),
            onEditingComplete: () => _saveLead(newName: _nameController.text.trim()),
          ),
          _sectionTitle("Status"),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              color: Colors.grey.shade100,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: _primaryColor.withOpacity(0.3)),
            ),
            child: DropdownButton<String>(
              value: _lead.status,
              isExpanded: true,
              underline: const SizedBox(),
              style: const TextStyle(color: _primaryColor, fontWeight: FontWeight.w600, fontSize: 16),
              items: _statusOptions.map((s) => DropdownMenuItem(value: s, child: Text(s))).toList(),
              onChanged: (val) async {
                if (val == null) return;
                await _saveLead(newStatus: val);
              },
            ),
          ),
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
              hintText: "Write a follow-up note...",
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              suffixIcon: Container(
                margin: const EdgeInsets.all(8),
                decoration: BoxDecoration(color: _accentColor, shape: BoxShape.circle),
                child: IconButton(icon: const Icon(Icons.send, color: Colors.white), onPressed: _addNote, tooltip: 'Add Note'),
              ),
            ),
          ),
          const SizedBox(height: 40),
        ]),
      ),
    );
  }
}
