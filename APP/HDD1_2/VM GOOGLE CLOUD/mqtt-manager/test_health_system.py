#!/usr/bin/env python3
"""
Health Monitoring System Verification Script

Tests all components of the ESP32 health monitoring system:
- MQTT broker connectivity
- Firestore access
- Email configuration
- Flask routes
- API endpoints

Run this before deploying to production.
"""

import sys
import os
import json
import time
from datetime import datetime

# Color codes for terminal output
class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    BOLD = '\033[1m'
    END = '\033[0m'

def print_header(text):
    print(f"\n{Colors.BOLD}{Colors.BLUE}{'=' * 60}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.BLUE}{text:^60}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.BLUE}{'=' * 60}{Colors.END}\n")

def print_success(text):
    print(f"{Colors.GREEN}✓ {text}{Colors.END}")

def print_error(text):
    print(f"{Colors.RED}✗ {text}{Colors.END}")

def print_warning(text):
    print(f"{Colors.YELLOW}⚠ {text}{Colors.END}")

def print_info(text):
    print(f"{Colors.BLUE}ℹ {text}{Colors.END}")

def test_imports():
    """Test if all required Python modules are available"""
    print_header("Testing Python Dependencies")

    modules = [
        ('flask', 'Flask web framework'),
        ('paho.mqtt.client', 'MQTT client library'),
        ('firebase_admin', 'Firebase Admin SDK'),
        ('google.cloud.firestore', 'Firestore client'),
    ]

    all_ok = True
    for module_name, description in modules:
        try:
            __import__(module_name)
            print_success(f"{description} ({module_name})")
        except ImportError as e:
            print_error(f"{description} ({module_name}) - MISSING")
            print(f"  Install with: pip3 install {module_name.split('.')[0]}")
            all_ok = False

    return all_ok

def test_firestore_config():
    """Test Firestore service account configuration"""
    print_header("Testing Firestore Configuration")

    service_account_paths = [
        '/home/pqsolutionsperu/vm-service-key.json',
        './vm-service-key.json',
        os.environ.get('GOOGLE_APPLICATION_CREDENTIALS', '')
    ]

    found = False
    for path in service_account_paths:
        if path and os.path.exists(path):
            print_success(f"Service account found: {path}")
            try:
                with open(path, 'r') as f:
                    data = json.load(f)
                    project_id = data.get('project_id', 'unknown')
                    print_info(f"Project ID: {project_id}")
                found = True
                break
            except Exception as e:
                print_error(f"Invalid JSON in {path}: {e}")
                return False

    if not found:
        print_error("Service account JSON not found")
        print("  Set GOOGLE_APPLICATION_CREDENTIALS environment variable")
        return False

    # Test Firestore connection
    try:
        from modules.firestore_client import FirestoreClient
        client = FirestoreClient(path)
        print_success("Firestore client initialized")

        # Try to read a collection
        devices = client.get_all_esp32_devices()
        print_info(f"Found {len(devices)} ESP32 devices in Firestore")
        return True

    except Exception as e:
        print_error(f"Firestore connection failed: {e}")
        return False

def test_email_config():
    """Test email configuration"""
    print_header("Testing Email Configuration")

    config_path = os.environ.get('EMAIL_CONFIG_PATH', './config.email.json')

    if not os.path.exists(config_path):
        print_error(f"Email config not found: {config_path}")
        print("  Copy config.email.example.json to config.email.json")
        return False

    try:
        with open(config_path, 'r') as f:
            config = json.load(f)

        required_fields = ['smtp_server', 'smtp_port', 'smtp_user',
                          'smtp_password', 'from_email', 'to_emails']

        missing = [f for f in required_fields if f not in config]
        if missing:
            print_error(f"Missing required fields: {', '.join(missing)}")
            return False

        print_success(f"Email config valid")
        print_info(f"SMTP Server: {config['smtp_server']}:{config['smtp_port']}")
        print_info(f"From: {config['from_email']}")
        print_info(f"To: {', '.join(config['to_emails'])}")

        # Test email connection (optional)
        try:
            from modules.email_alerter import EmailAlerter, EmailConfig

            email_config = EmailConfig(
                smtp_server=config['smtp_server'],
                smtp_port=config['smtp_port'],
                smtp_user=config['smtp_user'],
                smtp_password=config['smtp_password'],
                from_email=config['from_email'],
                to_emails=config['to_emails'],
                use_tls=config.get('use_tls', True)
            )

            alerter = EmailAlerter(email_config)
            print_success("Email alerter initialized")

            # Ask user if they want to send test email
            response = input("\n  Send test email? (y/n): ").strip().lower()
            if response == 'y':
                try:
                    alerter.send_test_email()
                    print_success("Test email sent! Check your inbox.")
                except Exception as e:
                    print_error(f"Failed to send test email: {e}")
                    return False

        except ImportError:
            print_warning("Email alerter module not found (optional test skipped)")

        return True

    except json.JSONDecodeError as e:
        print_error(f"Invalid JSON in email config: {e}")
        return False
    except Exception as e:
        print_error(f"Email config error: {e}")
        return False

def test_mqtt_broker():
    """Test MQTT broker connectivity"""
    print_header("Testing MQTT Broker")

    try:
        import paho.mqtt.client as mqtt

        connected = [False]
        error_msg = [None]

        def on_connect(client, userdata, flags, rc):
            if rc == 0:
                connected[0] = True
            else:
                error_msg[0] = f"Connection failed with code {rc}"

        client = mqtt.Client(client_id="health_system_test")
        client.on_connect = on_connect

        try:
            client.connect("localhost", 1883, 60)
            client.loop_start()

            # Wait for connection
            timeout = 5
            start = time.time()
            while not connected[0] and time.time() - start < timeout:
                time.sleep(0.1)

            client.loop_stop()
            client.disconnect()

            if connected[0]:
                print_success("MQTT broker reachable at localhost:1883")
                return True
            else:
                print_error(error_msg[0] or "Connection timeout")
                return False

        except Exception as e:
            print_error(f"Cannot connect to MQTT broker: {e}")
            print("  Make sure Mosquitto is running: sudo systemctl status mosquitto")
            return False

    except ImportError:
        print_error("paho-mqtt not installed")
        return False

def test_flask_app():
    """Test Flask application routes"""
    print_header("Testing Flask Application")

    try:
        # Import app
        sys.path.insert(0, os.path.dirname(__file__))
        from app import app

        print_success("Flask app imported successfully")

        # Test routes
        test_routes = [
            ('/dashboard', 'Dashboard'),
            ('/health', 'Health Monitor'),
            ('/api/esp32/health/status', 'Health API'),
        ]

        with app.test_client() as client:
            for route, name in test_routes:
                try:
                    # Note: These will redirect to login if not authenticated
                    response = client.get(route, follow_redirects=False)
                    if response.status_code in [200, 302]:  # 302 = redirect to login
                        print_success(f"{name} route exists: {route}")
                    else:
                        print_error(f"{name} route failed: {route} (Status: {response.status_code})")
                except Exception as e:
                    print_error(f"{name} route error: {e}")

        return True

    except ImportError as e:
        print_error(f"Cannot import Flask app: {e}")
        return False
    except Exception as e:
        print_error(f"Flask app test failed: {e}")
        return False

def test_health_monitor():
    """Test health monitoring system initialization"""
    print_header("Testing Health Monitoring System")

    try:
        from modules.health_system import HealthMonitoringSystem
        from modules.email_alerter import EmailConfig

        # Create dummy config
        email_config = EmailConfig(
            smtp_server="smtp.gmail.com",
            smtp_port=587,
            smtp_user="test@example.com",
            smtp_password="dummy",
            from_email="test@example.com",
            to_emails=["admin@example.com"],
            use_tls=True
        )

        # Initialize (without starting)
        system = HealthMonitoringSystem(
            email_config=email_config,
            mqtt_broker="localhost"
        )

        print_success("HealthMonitoringSystem instantiated")

        monitor = system.get_health_monitor()
        print_success("Health monitor component accessible")

        alerter = system.get_email_alerter()
        print_success("Email alerter component accessible")

        return True

    except ImportError as e:
        print_error(f"Cannot import health system modules: {e}")
        return False
    except Exception as e:
        print_error(f"Health system initialization failed: {e}")
        return False

def main():
    print_header("ESP32 Health Monitoring System - Verification")
    print(f"Test started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

    results = {}

    # Run all tests
    results['Python Dependencies'] = test_imports()
    results['Firestore Configuration'] = test_firestore_config()
    results['Email Configuration'] = test_email_config()
    results['MQTT Broker'] = test_mqtt_broker()
    results['Flask Application'] = test_flask_app()
    results['Health Monitoring System'] = test_health_monitor()

    # Summary
    print_header("Test Summary")

    passed = sum(1 for v in results.values() if v)
    total = len(results)

    for test_name, result in results.items():
        if result:
            print_success(f"{test_name}")
        else:
            print_error(f"{test_name}")

    print(f"\n{Colors.BOLD}Results: {passed}/{total} tests passed{Colors.END}")

    if passed == total:
        print(f"\n{Colors.GREEN}{Colors.BOLD}✓ All tests passed! System ready for deployment.{Colors.END}")
        return 0
    else:
        print(f"\n{Colors.RED}{Colors.BOLD}✗ Some tests failed. Fix issues before deploying.{Colors.END}")
        return 1

if __name__ == '__main__':
    sys.exit(main())
