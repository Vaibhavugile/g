import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/lead_service.dart';
import '../models/lead.dart';
import 'lead_list_screen.dart';

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

  @override
  void initState() {
    super.initState();
    _loadTenantAndLeads();
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
