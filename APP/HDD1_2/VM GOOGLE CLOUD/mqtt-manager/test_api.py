#!/usr/bin/env python3
"""
API Test Script - Test all endpoints from localhost
"""

import requests
import json

BASE_URL = "http://127.0.0.1:5000"

def test_endpoint(method, endpoint, description, data=None):
    """Test an API endpoint"""
    print(f"\n{'='*60}")
    print(f"Testing: {description}")
    print(f"Endpoint: {method} {endpoint}")
    print('='*60)

    try:
        if method == "GET":
            response = requests.get(f"{BASE_URL}{endpoint}", timeout=5)
        elif method == "POST":
            response = requests.post(f"{BASE_URL}{endpoint}", json=data, timeout=5)

        print(f"Status Code: {response.status_code}")
        print(f"Response:")

        try:
            json_data = response.json()
            print(json.dumps(json_data, indent=2))
        except:
            print(response.text[:500])

        return response.status_code == 200

    except Exception as e:
        print(f"❌ Error: {e}")
        return False

def main():
    print("="*60)
    print("HDD Monitor API Test Suite")
    print("="*60)

    tests = [
        ("GET", "/api/health", "Health Check"),
        ("GET", "/api/mqtt/status", "MQTT Broker Status"),
        ("GET", "/api/mqtt/users", "Get MQTT Users"),
        ("GET", "/api/dashboard/metrics", "Dashboard Metrics"),
        ("GET", "/api/esp32/devices", "Get ESP32 Devices"),
        ("GET", "/api/clients", "Get Clients"),
        ("GET", "/api/esp32/health/status", "ESP32 Health Status"),
    ]

    results = []

    for method, endpoint, description in tests:
        success = test_endpoint(method, endpoint, description)
        results.append((description, success))

    # Summary
    print(f"\n{'='*60}")
    print("Test Summary")
    print('='*60)

    passed = sum(1 for _, success in results if success)
    total = len(results)

    for description, success in results:
        status = "✅ PASS" if success else "❌ FAIL"
        print(f"{status} - {description}")

    print(f"\nResults: {passed}/{total} tests passed")
    print('='*60)

if __name__ == '__main__':
    main()
