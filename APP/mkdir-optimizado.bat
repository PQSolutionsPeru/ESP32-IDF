@echo off

REM Crear la carpeta principal del proyecto
mkdir app-hdd-monitor
cd app-hdd-monitor

REM Crear la estructura de carpetas para "backend"
mkdir backend
cd backend
mkdir src
cd src
mkdir api auth services utils
cd api
echo. > routes.py
cd ..
cd auth
echo. > auth.py
cd ..
cd services
echo. > firebase_service.py
echo. > mqtt_service.py
cd ..
cd utils
echo. > helpers.py
cd ..\..
echo. > main.py
echo. > config.py
cd ..

REM Crear la estructura de carpetas para "frontend"
mkdir frontend
cd frontend
mkdir lib assets
cd lib
echo. > main.dart
mkdir screens components utils services routes
cd screens
mkdir login dashboard notifications
echo. > login\login_screen.dart
echo. > dashboard\dashboard_screen.dart
echo. > notifications\notifications_screen.dart
cd ..\components
echo. > login_form.dart
echo. > dashboard_card.dart
echo. > notification_tile.dart
cd ..\utils
echo. > constants.dart
echo. > helpers.dart
echo. > firebase_options.dart
cd ..\services
echo. > auth_service.dart
echo. > firebase_service.dart
echo. > mqtt_service.dart
cd ..\routes
echo. > app_routes.dart
cd ..\..

REM Crear el archivo pubspec.yaml en la raíz del "frontend"
echo name: app_hdd_monitor > pubspec.yaml
echo description: Aplicación de monitoreo de relés de paneles de incendios >> pubspec.yaml
echo version: 1.0.0+1 >> pubspec.yaml
echo environment: >> pubspec.yaml
echo   sdk: ">=2.12.0 <3.0.0" >> pubspec.yaml
echo dependencies: >> pubspec.yaml
echo   flutter: >> pubspec.yaml
echo     sdk: flutter >> pubspec.yaml
echo   firebase_core: 2.30.0 >> pubspec.yaml
echo   firebase_auth: 4.19.2 >> pubspec.yaml
echo   cloud_firestore: 4.17.0 >> pubspec.yaml
echo   mqtt_client: 10.2.1 >> pubspec.yaml
echo   go_router: 13.2.4 >> pubspec.yaml
echo dev_dependencies: >> pubspec.yaml
echo   flutter_test: >> pubspec.yaml
echo     sdk: flutter >> pubspec.yaml
echo   mockito: ^5.4.4 >> pubspec.yaml

REM Crear la estructura de carpetas para "tests"
cd ..
mkdir tests
cd tests
mkdir backend frontend
cd backend
echo. > test_auth.py
echo. > test_firebase.py
echo. > test_mqtt.py
cd ..\frontend
echo. > test_auth_service.dart
echo. > test_firebase_service.dart
echo. > test_mqtt_service.dart
echo. > test_routes.dart
cd ..\..

REM Finalización del script
echo Estructura de carpetas y archivos para app-hdd-monitor creada exitosamente.
pause