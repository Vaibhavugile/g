import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/services.dart';

import '../services/lead_service.dart';
import '../models/lead.dart';
import 'lead_list_screen.dart';

const MethodChannel _native = MethodChannel('com.example.call_leads_app/native');

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final LeadService _leadService = LeadService.instance;

  bool _loading = true;
  List<Lead> _leads = [];
  String _tenantId = '';
  bool _recordingEnabled = false;
  bool _roleRequestBusy = false;

  @override
  void initState() {
    super.initState();
    _loadTenantAndLeads();
    _loadRecordingPref();
  }

  Future<void> _loadRecordingPref() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _recordingEnabled = prefs.getBool('recording_enabled') ?? false;
    });
  }

  Future<void> _setRecordingPref(bool enabled) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('recording_enabled', enabled);
    setState(() => _recordingEnabled = enabled);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(enabled ? 'Recording enabled' : 'Recording disabled')),
    );
  }

  Future<void> _loadTenantAndLeads() async {
    setState(() => _loading = true);

    // Load tenantId
    final prefs = await SharedPreferences.getInstance();
    _tenantId = prefs.getString('tenantId') ?? '';

    print("🏷 Loaded tenantId in HomeScreen: $_tenantId");

    // Load leads (LeadService already isolates by deterministic ID + tenant)
    await _leadService.loadLeads();
    _leads = List<Lead>.from(_leadService.getAll());

    setState(() => _loading = false);
  }

  Future<void> _requestDialerRoleFromNative() async {
    setState(() => _roleRequestBusy = true);
    try {
      final ok = await _native.invokeMethod<bool>('requestDialerRole');
      if (ok == true) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('System dialog opened — choose the app as Phone app.')),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Request failed (check logs).')),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error requesting dialer role: $e')),
      );
    } finally {
      setState(() => _roleRequestBusy = false);
    }
  }

  Widget _statCard(String title, int value, Color color) {
    return Card(
      elevation: 3,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Container(
        padding: const EdgeInsets.all(20),
        width: 150,
        child: Column(
          children: [
            Text(title,
                style: TextStyle(
                    fontSize: 16, fontWeight: FontWeight.w600, color: color)),
            const SizedBox(height: 10),
            Text(
              value.toString(),
              style: TextStyle(
                  fontSize: 28, fontWeight: FontWeight.bold, color: color),
            ),
          ],
        ),
      ),
    );
  }

  Widget _tenantBadge() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 14),
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: Colors.blue.shade50,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.blueAccent, width: 1),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.business, size: 18, color: Colors.blueAccent),
          const SizedBox(width: 6),
          Text(
            _tenantId.isNotEmpty ? "Tenant: $_tenantId" : "No tenant assigned",
            style: const TextStyle(
              fontSize: 14,
              color: Colors.blueAccent,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  Widget _controlsCard() {
    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Integration',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),

            // Recording toggle
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('Auto-record calls'),
                Switch(
                  value: _recordingEnabled,
                  onChanged: (v) => _setRecordingPref(v),
                ),
              ],
            ),

            const SizedBox(height: 6),

            // Dialer role button
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                icon: _roleRequestBusy
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.phone),
                label: const Text('Set as Default Phone App'),
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
                onPressed: _roleRequestBusy ? null : _requestDialerRoleFromNative,
              ),
            ),

            const SizedBox(height: 6),

            const Text(
              'If the dialog does not appear, open Settings → Apps → Default apps → Phone app and select this app.',
              style: TextStyle(fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Call Leads CRM"),
        backgroundColor: Colors.blueAccent,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _loadTenantAndLeads,
              child: SingleChildScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _tenantBadge(),

                    const Text("Dashboard",
                        style: TextStyle(
                            fontSize: 22, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 10),

                    Wrap(
                      spacing: 12,
                      children: [
                        _statCard("Total Leads", _leads.length, Colors.blue),
                        _statCard(
                            "Follow Up",
                            _leads.where((e) => e.status == "Follow Up").length,
                            Colors.orange),
                        _statCard(
                            "Interested",
                            _leads.where((e) => e.status == "Interested").length,
                            Colors.green),
                      ],
                    ),

                    const SizedBox(height: 20),

                    _controlsCard(),

                    const SizedBox(height: 20),

                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                            padding: const EdgeInsets.all(16),
                            backgroundColor: Colors.blueAccent),
                        onPressed: () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                                builder: (_) => const LeadListScreen()),
                          );
                        },
                        child: const Text(
                          "View All Leads",
                          style: TextStyle(fontSize: 18),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}
