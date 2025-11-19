import 'package:flutter/material.dart';
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

class _LeadDetailsScreenState extends State<LeadDetailsScreen> {
  final LeadService _service = LeadService.instance;

  late Lead _lead;

  // Controllers are created once and updated when lead changes
  late TextEditingController _phoneController;
  late TextEditingController _nameController;
  late TextEditingController _noteController;

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
    _phoneController = TextEditingController(text: _lead.phoneNumber);
    _nameController = TextEditingController(text: _lead.name);
    _noteController = TextEditingController();
  }

  @override
  void didUpdateWidget(covariant LeadDetailsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    // If widget.lead changed externally, update local state + controllers
    if (widget.lead.id != oldWidget.lead.id) {
      setState(() {
        _lead = widget.lead;
        _phoneController.text = _lead.phoneNumber;
        _nameController.text = _lead.name;
      });
    }
  }

  @override
  void dispose() {
    _phoneController.dispose();
    _nameController.dispose();
    _noteController.dispose();
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

  Future<void> _saveStatus(String newStatus) async {
    final updated = _lead.copyWith(
      status: newStatus,
      lastInteraction: DateTime.now(),
      lastUpdated: DateTime.now(),
    );

    await _service.saveLead(updated);

    setState(() {
      _lead = updated;
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
        }
      });
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
                  child: Icon(Icons.phone_android,
                      size: 28, color: Colors.blue.shade700),
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
            ]
          ],
        ),
      ),
    );
  }

  Widget _callHistorySection() {
    if (_lead.callHistory.isEmpty) {
      return const Text("No call history yet.");
    }

    return Column(
      children: _lead.callHistory.reversed.map((call) {
        final icon = call.direction == "inbound" ? Icons.call_received : Icons.call_made;
        final color = call.outcome == "answered" ? Colors.green :
                      call.outcome == "missed" ? Colors.red :
                      call.outcome == "rejected" ? Colors.orange :
                      Colors.blue;

        final durationText = call.durationInSeconds != null
            ? ' (${_formatDuration(call.durationInSeconds!)})'
            : '';

        return Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          child: ListTile(
            leading: Icon(icon, color: color),
            title: Text("${call.direction} – ${call.outcome.toUpperCase()}$durationText"),
            subtitle: Text(_formatDate(call.timestamp)),
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Lead Details"),
        backgroundColor: Colors.blueAccent,
        foregroundColor: Colors.white,
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
              readOnly: true,
              decoration: InputDecoration(
                filled: true,
                fillColor: Colors.grey.shade200,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
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
