@echo off

REM Crea la carpeta principal del proyecto
mkdir app-hdd-monitor
cd app-hdd-monitor

REM Crea la carpeta "backend" y sus subcarpetas y archivos
mkdir backend
cd backend
mkdir src
cd src
mkdir login
cd login
echo. > login.py
cd ..
mkdir dashboard
cd dashboard
echo. > dashboard.py
cd ..
mkdir notifications
cd notifications
echo. > notifications.py
cd ..
echo. > main.py
cd ../..

REM Crea la carpeta "frontend" y sus subcarpetas y archivos
mkdir frontend
cd frontend
mkdir lib
cd lib
mkdir screens
cd screens
mkdir login
cd login
echo. > login.dart
cd ..
mkdir dashboard
cd dashboard
echo. > dashboard.dart
cd ..
mkdir notifications
cd notifications
echo. > notifications.dart
cd ..
echo. > main.dart
cd ..
mkdir assets

REM Crea la carpeta "tests" y sus subcarpetas y archivos
mkdir tests
cd tests
mkdir backend
cd backend
mkdir src
cd src
mkdir login
cd login
echo. > test_login.py
cd ..
mkdir dashboard
cd dashboard
echo. > test_dashboard.py
cd ..
mkdir notifications
cd notifications
echo. > test_notifications.py
cd ../..
mkdir frontend
cd frontend
mkdir lib
cd lib
mkdir screens
cd screens
mkdir login
cd login
echo. > test_login.dart
cd ..
mkdir dashboard
cd dashboard
echo. > test_dashboard.dart
cd ..
mkdir notifications
cd notifications
echo. > test_notifications.dart
cd ../..

REM Crea el archivo pubspec.yaml
echo name: app_hdd_monitor > pubspec.yaml
echo description: Aplicación de monitoreo de relés de paneles de incendios >> pubspec.yaml
echo version: 1.0.0 >> pubspec.yaml
echo environment: >> pubspec.yaml
echo   sdk: ">=2.12.0 <3.0.0" >> pubspec.yaml
echo dependencies: >> pubspec.yaml
echo   flutter: >> pubspec.yaml
echo     sdk: flutter >> pubspec.yaml
echo   # Aquí puedes agregar las dependencias adicionales que necesites >> pubspec.yaml
echo dev_dependencies: >> pubspec.yaml
echo   flutter_test: >> pubspec.yaml
echo     sdk: flutter >> pubspec.yaml

REM Finalización del script
echo Estructura de carpetas y archivos creada exitosamente.
pause