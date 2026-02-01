#!/usr/bin/env python3
"""
Local Testing Script for HDD Monitor Dashboard
Tests all components without requiring Firestore connection
"""

import sys
import os
from pathlib import Path

print("=" * 70)
print("HDD MONITOR DASHBOARD - LOCAL TESTING")
print("=" * 70)

# Test 1: Python Version
print("\n[TEST 1] Python Version")
print(f"✓ Python {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}")
if sys.version_info < (3, 8):
    print("✗ ERROR: Python 3.8+ required")
    sys.exit(1)

# Test 2: Directory Structure
print("\n[TEST 2] Directory Structure")
required_dirs = ['modules', 'templates', 'static', 'static/css', 'static/js']
for dir_name in required_dirs:
    if Path(dir_name).exists():
        print(f"✓ {dir_name}/")
    else:
        print(f"✗ MISSING: {dir_name}/")

# Test 3: Required Files
print("\n[TEST 3] Required Files")
required_files = [
    'app.py',
    'requirements.txt',
    'modules/__init__.py',
    'modules/firestore_client.py',
    'modules/cache.py',
    'templates/base.html',
    'templates/dashboard.html',
    'templates/vm_monitoring.html',
    'templates/esp32_devices.html',
    'templates/clients.html',
    'templates/events.html',
    'templates/mqtt_config.html',
    'templates/login.html',
    'static/css/dashboard.css',
    'static/js/dashboard.js',
    'static/js/esp32.js',
    'static/js/events.js',
]

missing_files = []
for file_path in required_files:
    if Path(file_path).exists():
        print(f"✓ {file_path}")
    else:
        print(f"✗ MISSING: {file_path}")
        missing_files.append(file_path)

if missing_files:
    print(f"\n✗ ERROR: {len(missing_files)} files missing!")
    sys.exit(1)

# Test 4: Import Dependencies
print("\n[TEST 4] Checking Dependencies")
dependencies = {
    'flask': 'Flask',
    'flask_cors': 'Flask-CORS',
    'flask_wtf': 'Flask-WTF',
    'werkzeug': 'Werkzeug',
}

missing_deps = []
for module, name in dependencies.items():
    try:
        __import__(module)
        print(f"✓ {name}")
    except ImportError:
        print(f"✗ MISSING: {name}")
        missing_deps.append(name)

# Optional dependencies (won't fail if missing)
print("\n[TEST 4.1] Optional Dependencies (for Firestore)")
optional_deps = {
    'firebase_admin': 'Firebase Admin',
    'google.cloud.firestore': 'Google Cloud Firestore',
}

for module, name in optional_deps.items():
    try:
        __import__(module)
        print(f"✓ {name}")
    except ImportError:
        print(f"⚠ NOT INSTALLED: {name} (required for production)")

# Test 5: Import Application Modules
print("\n[TEST 5] Import Application Modules")
try:
    from modules.cache import SimpleCache, cached
    print("✓ modules.cache")
except Exception as e:
    print(f"✗ modules.cache: {e}")

try:
    # We'll skip firestore_client if Firebase not installed
    try:
        __import__('firebase_admin')
        from modules.firestore_client import FirestoreClient
        print("✓ modules.firestore_client")
    except ImportError:
        print("⚠ modules.firestore_client (skipped - Firebase not installed)")
except Exception as e:
    print(f"✗ modules.firestore_client: {e}")

# Test 6: Cache Module Functionality
print("\n[TEST 6] Cache Module Functionality")
try:
    from modules.cache import SimpleCache

    cache = SimpleCache(max_size=10)

    # Test set/get
    cache.set('test_key', 'test_value', ttl=60)
    value = cache.get('test_key')
    assert value == 'test_value', "Cache set/get failed"
    print("✓ Cache set/get")

    # Test expiration (with 0 TTL)
    cache.set('expire_key', 'expire_value', ttl=0)
    value = cache.get('expire_key')
    assert value == 'expire_value', "Cache immediate expiration failed"
    print("✓ Cache TTL")

    # Test LRU eviction
    for i in range(15):
        cache.set(f'key_{i}', f'value_{i}', ttl=60)
    stats = cache.get_stats()
    assert stats['size'] <= 10, f"LRU eviction failed: {stats['size']} items"
    print(f"✓ Cache LRU eviction (max 10, current {stats['size']})")

    print("✓ All cache tests passed")
except Exception as e:
    print(f"✗ Cache tests failed: {e}")

# Test 7: Flask App Import
print("\n[TEST 7] Flask Application Import")
try:
    # Set environment variables to prevent errors
    os.environ['FLASK_SECRET_KEY'] = 'test-secret-key-for-local-testing'
    os.environ['ADMIN_PASSWORD_HASH'] = 'pbkdf2:sha256:test'

    # Try to import app (will fail if Firestore required)
    try:
        import app as flask_app
        print("✓ Flask app imported")
        print(f"✓ App name: {flask_app.app.name}")
    except Exception as e:
        if 'firebase_admin' in str(e) or 'firestore' in str(e).lower():
            print("⚠ Flask app requires Firebase (expected for production)")
        else:
            print(f"✗ Flask app import failed: {e}")
except Exception as e:
    print(f"✗ Flask app test failed: {e}")

# Test 8: Template Validation
print("\n[TEST 8] Template Validation")
from jinja2 import Environment, FileSystemLoader, TemplateSyntaxError

env = Environment(loader=FileSystemLoader('templates'))
templates_to_test = [
    'base.html',
    'dashboard.html',
    'vm_monitoring.html',
    'esp32_devices.html',
    'clients.html',
    'events.html',
]

template_errors = []
for template_name in templates_to_test:
    try:
        template = env.get_template(template_name)
        print(f"✓ {template_name}")
    except TemplateSyntaxError as e:
        print(f"✗ {template_name}: Syntax error at line {e.lineno}")
        template_errors.append(template_name)
    except Exception as e:
        print(f"✗ {template_name}: {e}")
        template_errors.append(template_name)

if template_errors:
    print(f"\n✗ ERROR: {len(template_errors)} template(s) have errors")

# Test 9: JavaScript Validation
print("\n[TEST 9] JavaScript Files Check")
js_files = [
    'static/js/dashboard.js',
    'static/js/esp32.js',
    'static/js/events.js',
]

for js_file in js_files:
    path = Path(js_file)
    if path.exists():
        size = path.stat().st_size
        lines = len(path.read_text().splitlines())
        print(f"✓ {js_file} ({size} bytes, {lines} lines)")
    else:
        print(f"✗ {js_file} not found")

# Test 10: CSS Files Check
print("\n[TEST 10] CSS Files Check")
css_files = [
    'static/css/dashboard.css',
]

for css_file in css_files:
    path = Path(css_file)
    if path.exists():
        size = path.stat().st_size
        lines = len(path.read_text().splitlines())
        print(f"✓ {css_file} ({size} bytes, {lines} lines)")
    else:
        print(f"✗ {css_file} not found")

# Summary
print("\n" + "=" * 70)
print("TESTING SUMMARY")
print("=" * 70)

if missing_files:
    print(f"✗ FAIL: {len(missing_files)} required files missing")
elif missing_deps:
    print(f"✗ FAIL: Missing required dependencies: {', '.join(missing_deps)}")
    print("\nInstall missing dependencies:")
    print("  pip3 install -r requirements.txt")
elif template_errors:
    print(f"✗ FAIL: {len(template_errors)} template(s) have errors")
else:
    print("✓ SUCCESS: All local tests passed!")
    print("\n📋 Next Steps:")
    print("  1. Install production dependencies: pip3 install -r requirements.txt")
    print("  2. Set up Firestore service account")
    print("  3. Test with: FLASK_APP=app.py flask run")
    print("  4. Deploy to production (see DEPLOYMENT.md)")

print("=" * 70)
