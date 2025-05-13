@echo off

REM Definir la ruta donde quieres crear tu proyecto Flutter
set PROJECT_PATH=E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\flutter_app_hdd_monitor
set PROJECT_NAME=flutter_app_hdd_monitor

REM Cambiar al directorio donde deseas crear tu proyecto Flutter
cd /d %PROJECT_PATH%

REM Crear un nuevo proyecto Flutter
flutter create %PROJECT_NAME%

REM Cambiar al directorio del proyecto recién creado
cd %PROJECT_PATH%\%PROJECT_NAME%

REM Instalar paquetes específicos de Flutter
flutter pub add flutter_bloc  # Instalar el paquete flutter_bloc para manejo de estados
flutter pub add provider      # Instalar el paquete provider para manejo de estados
flutter pub add mqtt_client   # Instalar el paquete mqtt_client para comunicación MQTT

REM Configurar Firebase
flutter pub get  # Obtener las dependencias de Flutter

REM Ejecutar el comando de configuración de Firebase (asegúrate de tener las credenciales y ajustar según tus necesidades)
flutter pub run firebase_core:firebase_core:configure --no-analytics true

REM Crear una estructura básica de carpetas para tu aplicación
mkdir lib\components
mkdir lib\services
mkdir lib\screens
mkdir lib\routes

REM Crear archivos principales dentro de las carpetas
echo // Archivo para manejar la autenticación > lib\services\auth_service.dart
echo // Archivo para manejar Firebase > lib\services\firebase_service.dart
echo // Archivo para manejar MQTT > lib\services\mqtt_service.dart
echo // Archivo para las rutas de la aplicación > lib\routes\app_routes.dart
echo // Pantalla de inicio de sesión > lib\screens\login_screen.dart
echo // Pantalla principal de la aplicación > lib\screens\home_screen.dart

REM Abrir el proyecto en Visual Studio Code
code .

echo Proyecto configurado exitosamente. ¡Listo para empezar a desarrollar!
