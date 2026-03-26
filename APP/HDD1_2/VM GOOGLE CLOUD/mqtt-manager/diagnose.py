#!/usr/bin/env python3
"""
Diagnostic Script for HDD Monitor Dashboard
Checks Firestore connection and data availability
"""

import os
import sys
from modules.firestore_client import FirestoreClient

def diagnose_firestore():
    """Diagnose Firestore connection and data"""
    print("=" * 60)
    print("HDD Monitor Dashboard - Diagnostic Tool")
    print("=" * 60)
    print()

    # Check service account file
    service_account_path = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS',
                                          '/home/pqsolutionsperu/vm-service-key.json')

    print(f"1. Checking service account file...")
    print(f"   Path: {service_account_path}")

    if not os.path.exists(service_account_path):
        print(f"   ❌ ERROR: Service account file not found!")
        print(f"   Solution: Set GOOGLE_APPLICATION_CREDENTIALS environment variable")
        return False
    else:
        print(f"   ✓ Service account file exists")

    print()

    # Try to initialize Firestore client
    print(f"2. Initializing Firestore client...")
    try:
        firestore_client = FirestoreClient(service_account_path)
        print(f"   ✓ Firestore client initialized successfully")
    except Exception as e:
        print(f"   ❌ ERROR: Failed to initialize Firestore client")
        print(f"   Error: {e}")
        return False

    print()

    # Check ESP32 devices
    print(f"3. Checking ESP32 devices...")
    try:
        devices = firestore_client.get_all_esp32_devices()
        print(f"   ✓ Found {len(devices)} ESP32 devices")
        if devices:
            print(f"   Sample devices:")
            for device in devices[:3]:
                status = device.get('status', 'unknown')
                print(f"     - {device.get('id')}: {status}")
    except Exception as e:
        print(f"   ❌ ERROR: Failed to get ESP32 devices")
        print(f"   Error: {e}")

    print()

    # Check clients
    print(f"4. Checking clients...")
    try:
        clients = firestore_client.get_all_clients()
        print(f"   ✓ Found {len(clients)} clients")
        if clients:
            print(f"   Sample clients:")
            for client in clients[:3]:
                print(f"     - {client.get('id')}: {client.get('name', 'N/A')}")
    except Exception as e:
        print(f"   ❌ ERROR: Failed to get clients")
        print(f"   Error: {e}")

    print()

    # Check dashboard metrics
    print(f"5. Checking dashboard metrics...")
    try:
        metrics = firestore_client.get_dashboard_metrics()
        print(f"   ✓ Dashboard metrics retrieved successfully")
        print(f"   Metrics:")
        print(f"     - ESP32 Online: {metrics.get('esp32_online')}/{metrics.get('esp32_total')}")
        print(f"     - Panels OK: {metrics.get('panels_ok')}/{metrics.get('panels_total')}")
        print(f"     - Events Today: {metrics.get('events_today')}")
        print(f"     - MQTT Status: {metrics.get('mqtt_status')}")
    except Exception as e:
        print(f"   ❌ ERROR: Failed to get dashboard metrics")
        print(f"   Error: {e}")

    print()
    print("=" * 60)
    print("Diagnosis complete!")
    print("=" * 60)

    return True

if __name__ == '__main__':
    try:
        success = diagnose_firestore()
        sys.exit(0 if success else 1)
    except KeyboardInterrupt:
        print("\n\nDiagnosis interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\n\n❌ Unexpected error: {e}")
        sys.exit(1)
