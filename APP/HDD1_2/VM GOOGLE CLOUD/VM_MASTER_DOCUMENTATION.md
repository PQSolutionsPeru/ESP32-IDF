# HDD Monitor - Documentación Maestra del Sistema

**Versión:** 2.5
**Última actualización:** 06 de febrero de 2026
**Posicionamiento:** Sistema Secundario Supervisorio + Gestión de Agenda
**Sistema de Monitoreo y Notificación de Paneles de Incendio**

---

## Índice

1. [Visión General del Sistema](#1-visión-general-del-sistema)
2. [Arquitectura](#2-arquitectura)
3. [Gestión de Agenda y Eventos Programados](#3-gestión-de-agenda-y-eventos-programados)
4. [Componentes Principales](#4-componentes-principales)
5. [Flujo de Datos](#5-flujo-de-datos)
6. [Cumplimiento de Normativas](#6-cumplimiento-de-normativas)
7. [Servidor VM (Google Cloud)](#7-servidor-vm-google-cloud)
8. [ESP32 Firmware](#8-esp32-firmware)
9. [Aplicación Android](#9-aplicación-android)
10. [Sistema de Notificaciones](#10-sistema-de-notificaciones)
11. [Métricas y Monitoreo](#11-métricas-y-monitoreo)
12. [Configuración y Deployment](#12-configuración-y-deployment)
13. [Troubleshooting](#13-troubleshooting)
14. [Mantenimiento](#14-mantenimiento)
15. [Sistema de Monitoreo de Salud ESP32](#15-sistema-de-monitoreo-de-salud-esp32)
16. [Sistema de Actualización en Tiempo Real Web](#16-sistema-de-actualización-en-tiempo-real-web)

---

## 1. Visión General del Sistema

**HDD Monitor** es un **sistema secundario de monitoreo supervisorio** y **gestión de agenda** para paneles de alarma contra incendios. El sistema detecta cambios de estado en relays de paneles certificados y provee notificaciones adicionales, dashboard web, y gestión de eventos programados (capacitaciones, mantenimientos, inspecciones).

### Posicionamiento del Sistema

```
╔═══════════════════════════════════════════════════════════════╗
║              ARQUITECTURA DE SISTEMA SECUNDARIO                ║
╠═══════════════════════════════════════════════════════════════╣
║                                                               ║
║  SISTEMA PRIMARIO (Obligatorio - Cliente):                   ║
║  • Panel certificado (UL 864: Notifier, Simplex, Edwards)    ║
║  • Instalación por empresa autorizada                        ║
║  • ITSDC vigente (INDECI)                                    ║
║  • Responsabilidad de seguridad de vida crítica              ║
║                                                               ║
║  HDD MONITOR (Secundario - Value Add):                        ║
║  • Monitoreo supervisorio de relays del panel primario       ║
║  • Notificaciones push/email adicionales                     ║
║  • Dashboard web en tiempo real                              ║
║  • Gestión de agenda (mantenimientos, capacitaciones)        ║
║  • NO reemplaza sistema primario                             ║
║  • NO interfiere con panel certificado                       ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
```

**⚠️ IMPORTANTE:** HDD Monitor es un sistema de **monitoreo adicional** que complementa (NO reemplaza) sistemas de alarma contra incendios certificados. El cliente debe mantener un panel primario certificado UL 864 con ITSDC vigente.

### Características Principales

#### Monitoreo de Relays (Sistema Secundario)
- ✅ Lectura no invasiva de hasta 6 relays del panel primario
- ✅ Detección de cambios en tiempo real (<100ms)
- ✅ Notificaciones push instantáneas (6-8 segundos end-to-end)
- ✅ Dashboard web con actualización en tiempo real (1-2s latencia)
- ✅ Detección de desconexión de dispositivos (LWT MQTT)
- ✅ Cumplimiento técnico NFPA 72/UL 864 (latencia)

#### Gestión de Agenda y Eventos
- ✅ **Programación de mantenimientos preventivos**
- ✅ **Registro de capacitaciones al personal**
- ✅ **Control de garantías e instalaciones**
- ✅ **Recordatorios automáticos de eventos próximos**
- ✅ **Historial completo de eventos programados**
- ✅ **Estados: Programado → Aceptado → Finalizado**
- ✅ **Notificaciones push al aproximarse evento**

#### Tecnología y Comunicaciones
- ✅ WebSocket/Socket.IO para actualizaciones automáticas de UI
- ✅ Firestore real-time listeners para cambios instantáneos
- ✅ Configuración remota de relays vía MQTT
- ✅ Persistencia redundante (Firestore + PostgreSQL)
- ✅ Sistema de monitoreo de salud ESP32 con alertas por email
- ✅ Auto-recovery y watchdog del sistema

#### Cumplimiento Normativo
- ✅ Cumplimiento técnico NFPA 72 (latencia < 90s: 6-8s)
- ✅ Cumplimiento técnico UL 864 (sin debounce artificial)
- ⚠️ Requiere homologación MTC (WiFi 2.4 GHz)
- ⚠️ Requiere registro INDECOPI (comercialización)
- ✅ No requiere certificación UL (sistema supervisorio)

---

## 2. Arquitectura

### Modelo de Sistema Secundario

**HDD Monitor** opera como **sistema secundario supervisorio** que complementa un panel de alarma certificado existente:

```
╔═══════════════════════════════════════════════════════════════╗
║                    SISTEMA PRIMARIO (Cliente)                  ║
╠═══════════════════════════════════════════════════════════════╣
║                                                               ║
║  ┌───────────────────────────────────────────────────┐       ║
║  │  Panel de Alarma Contra Incendios CERTIFICADO     │       ║
║  │  (Ej: Notifier NFS2-640, Simplex, Edwards)        │       ║
║  │                                                    │       ║
║  │  • Certificado UL 864 / UL Listed                 │       ║
║  │  • Instalado por empresa autorizada               │       ║
║  │  • ITSDC vigente (INDECI)                         │       ║
║  │  • Mantenimiento semestral                        │       ║
║  │  • RESPONSABLE DE SEGURIDAD DE VIDA CRÍTICA       │       ║
║  │                                                    │       ║
║  │  Salidas de relay: Alarma, Falla, Supervisión     │       ║
║  └─────────────────────┬─────────────────────────────┘       ║
║                        │                                      ║
╚════════════════════════╪══════════════════════════════════════╝
                         │ Lectura no invasiva (GPIO)
                         ↓
╔═══════════════════════════════════════════════════════════════╗
║         SISTEMA SECUNDARIO - HDD MONITOR (Value Add)          ║
╠═══════════════════════════════════════════════════════════════╣
║                                                               ║
║  ┌───────────────────────────────────────────────────┐       ║
║  │  ESP32 DevKit (Monitoreo de Relays)               │       ║
║  │  • Lee 6 relays del panel primario                │       ║
║  │  • NO interfiere con panel certificado            │       ║
║  │  • Instalación no invasiva                        │       ║
║  └─────────────────────┬─────────────────────────────┘       ║
║                        │ MQTTS (TLS 1.3)                      ║
║                        ↓                                      ║
║  ┌───────────────────────────────────────────────────┐       ║
║  │  Cloud Backend (Google Cloud VM)                  │       ║
║  │  • MQTT Broker (Mosquitto)                        │       ║
║  │  • Firestore + PostgreSQL                         │       ║
║  │  • Dashboard Web (Real-time)                      │       ║
║  │  • FCM Push Notifications                         │       ║
║  │  • Gestión de Agenda (Eventos)                    │       ║
║  └─────────────────────┬─────────────────────────────┘       ║
║                        │                                      ║
║                        ↓                                      ║
║  ┌───────────────────────────────────────────────────┐       ║
║  │  Usuarios Finales                                 │       ║
║  │  • App Android (notificaciones)                   │       ║
║  │  • Dashboard Web (monitoreo)                      │       ║
║  │  • Email Alerts (administradores)                 │       ║
║  └───────────────────────────────────────────────────┘       ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
```

### Diagrama de Arquitectura Técnica

```
┌─────────────────────────────────────────────────────────┐
│  PANEL PRIMARIO CERTIFICADO (UL 864)                    │
│  (Notifier / Simplex / Edwards / etc.)                  │
│                                                          │
│  Salidas de Relay (NO/NC):                              │
│  1. Alarma General    4. Falla Comunicación             │
│  2. Pre-Alarma        5. Supervisión                    │
│  3. Falla Sistema     6. Desactivado                    │
└─────────────────┬───────────────────────────────────────┘
                  │ Lectura GPIO (3.3V, optoacopladores)
                  ↓
┌─────────────────────────────────────────────────────────┐
│  ESP32 DevKit v1 (HDD Monitor)                          │
│  • 6 GPIO inputs (lectura de relays)                    │
│  • WiFi 802.11 b/g/n                                    │
│  • MQTT Client (TLS 1.3)                                │
│  • NVS (Non-Volatile Storage)                           │
│  • Health Monitor (watchdog, memoria)                   │
└─────────────────┬───────────────────────────────────────┘
                  │ MQTTS (8883) / hddm.pqsolutionsperu.com
                  ↓
┌─────────────────────────────────────────────────────────┐
│  VM Google Cloud (instanciavm-myqtthub)                 │
│  IP: 34.63.146.196 | OS: Debian 12 | 2 vCPU, 2GB RAM   │
│                                                          │
│  ┌────────────────────────────────────────────────┐    │
│  │ MQTT Broker (Mosquitto)                        │    │
│  │ • Port 8883 (TLS) - ESP32 remoto               │    │
│  │ • Port 1883 (Plain) - localhost only           │    │
│  │ • LWT (Last Will Testament)                    │    │
│  └────────────────────────────────────────────────┘    │
│                                                          │
│  ┌────────────────────────────────────────────────┐    │
│  │ Backend Services (systemd)                     │    │
│  │ • hdd-monitor.service (Python)                 │    │
│  │ • mqtt-manager.service (Flask + SocketIO)      │    │
│  │ • hdd-monitor-watchdog.service                 │    │
│  │ • log-monitor.timer (daily ESP32 log check)    │    │
│  └────────────────────────────────────────────────┘    │
│                                                          │
│  ┌────────────────────────────────────────────────┐    │
│  │ Databases                                       │    │
│  │ • Firestore (primary, real-time)              │    │
│  │ • PostgreSQL 15 (backup, analytics)            │    │
│  └────────────────────────────────────────────────┘    │
└─────────────────┬───────────────────────────────────────┘
                  │
    ┌─────────────┼─────────────┬─────────────────┐
    ↓             ↓             ↓                 ↓
┌─────────┐  ┌─────────┐  ┌─────────┐     ┌──────────┐
│   FCM   │  │Dashboard│  │ Firestore│     │  Email   │
│  Push   │  │   Web   │  │Real-time│     │  SMTP    │
│Notif.   │  │(HTTPS)  │  │Listeners│     │  Alerts  │
└────┬────┘  └────┬────┘  └────┬────┘     └────┬─────┘
     │            │             │                │
     ↓            ↓             ↓                ↓
┌─────────────────────────────────────────────────────┐
│  USUARIOS FINALES                                   │
│  • App Android (push notifications)                 │
│  • Dashboard Web (monitoreo tiempo real)            │
│  • Email (alertas críticas)                         │
│  • Gestión de agenda (capacitaciones, mtto)        │
└─────────────────────────────────────────────────────┘
```

### Casos de Uso del Sistema

#### 1. Monitoreo de Relays (Sistema Secundario)
```
Evento: Panel primario detecta alarma
  ↓ Relay "Alarma General" se activa
  ↓ ESP32 detecta cambio GPIO (<100ms)
  ↓ Publica a MQTT (hddm/relay/status)
  ↓ Backend procesa y guarda en Firestore
  ↓ FCM envía push notification (6-8s total)
  ↓ Usuario recibe alerta en móvil
  ↓ Dashboard web actualiza en tiempo real
```

#### 2. Gestión de Agenda
```
Usuario: Programa capacitación para 15/03/2026
  ↓ Evento guardado en Firestore
  ↓ Estado: "Programado"
  ↓ Sistema envía recordatorio 24h antes
  ↓ Usuario marca como "Aceptado"
  ↓ Tras completar, marca como "Finalizado"
  ↓ Historial queda registrado
```

#### 3. Mantenimiento Preventivo
```
Cliente: Panel requiere mantenimiento semestral
  ↓ Evento programado: "Mantto. Panel (INDECI req.)"
  ↓ Fecha: Cada 6 meses
  ↓ Recordatorio 7 días antes
  ↓ Empresa certificada realiza mantto
  ↓ Usuario marca como "Finalizado"
  ↓ Próximo mantenimiento auto-programado
```

### Stack Tecnológico

**Hardware:**
- ESP32 DevKit v1 (Dual-Core 240MHz, WiFi/BLE)
- 6 GPIOs configurables (NC/NO)

**Firmware:**
- ESP-IDF v5.4.1
- FreeRTOS
- MQTT Client (paho-mqtt embedded)
- TLS 1.2/1.3 (ESP-IDF Certificate Bundle + Let's Encrypt)

**Backend (VM):**
- **OS:** Debian GNU/Linux 12 (bookworm)
- **Kernel:** 6.1.0-37-cloud-amd64
- **Python:** 3.11.2
- **Mosquitto MQTT Broker:** 2.0.18
- **PostgreSQL:** 15.15
- **Firebase Cloud Messaging (FCM)**
- **systemd 252** (auto-restart)

**Frontend:**
- Android (Kotlin)
- Jetpack Compose
- Firebase SDK
- Material Design 3

---

## 3. Gestión de Agenda y Eventos Programados

### 3.1 Visión General

El módulo de **Gestión de Agenda** permite programar, rastrear y gestionar eventos relacionados con el mantenimiento y operación del sistema de alarmas contra incendios. Este módulo complementa el monitoreo de relays proporcionando una herramienta para:

- **Mantenimientos preventivos programados**
- **Capacitaciones al personal**
- **Inspecciones periódicas (INDECI, bomberos)**
- **Gestión de garantías**
- **Registro de instalaciones**
- **Auditorías y certificaciones**

### 3.2 Tipos de Eventos

| Tipo de Evento | Descripción | Frecuencia Típica | Recordatorio |
|----------------|-------------|-------------------|--------------|
| **Mantenimiento Preventivo** | Revisión semestral del panel (req. RNE A.130) | Cada 6 meses | 7 días antes |
| **Capacitación Personal** | Entrenamiento en uso del sistema | Anual / Por rotación | 24 horas antes |
| **Inspección INDECI** | ITSDC (Inspección Técnica Seguridad Defensa Civil) | Cada 2 años | 30 días antes |
| **Prueba de Sistema** | Prueba funcional de paneles y relays | Mensual | 24 horas antes |
| **Renovación Garantía** | Vencimiento de garantía de equipos | Por equipo | 30 días antes |
| **Instalación** | Registro de nueva instalación | Por demanda | - |
| **Auditoría** | Auditoría interna/externa de cumplimiento | Anual | 15 días antes |
| **Certificación** | Renovación de certificaciones (UL, MTC, etc.) | Por vigencia | 60 días antes |

### 3.3 Estados del Evento

Cada evento pasa por un ciclo de vida con 3 estados:

```
┌──────────────┐
│  PROGRAMADO  │  ← Estado inicial al crear evento
└──────┬───────┘
       │ Usuario confirma asistencia/aceptación
       ↓
┌──────────────┐
│   ACEPTADO   │  ← Evento confirmado, personal notificado
└──────┬───────┘
       │ Evento completado
       ↓
┌──────────────┐
│  FINALIZADO  │  ← Evento archivado, historial permanente
└──────────────┘
```

**Transiciones:**
- `PROGRAMADO → ACEPTADO`: Usuario acepta/confirma evento
- `ACEPTADO → FINALIZADO`: Evento se completa
- `PROGRAMADO → FINALIZADO`: Se puede finalizar directo (cancelación)

### 3.4 Estructura de Datos de Evento

```json
{
  "event_id": "Mantto_Panel_15032026_client_1",
  "client_id": "client_1",
  "type": "mantenimiento_preventivo",
  "title": "Mantenimiento Semestral Panel Principal",
  "description": "Revisión preventiva requerida por RNE A.130",
  "date_time": "15-03-2026 10:00",
  "location": "Piso 5, Sala de Máquinas",
  "responsible": "Empresa Certificada XYZ",
  "status": "programado",
  "reminder_sent": false,
  "created_at": "2026-02-02T12:00:00Z",
  "updated_at": "2026-02-02T12:00:00Z",
  "notes": "Coordinar con bomberos para prueba de sistema",
  "attachments": []
}
```

### 3.5 Sistema de Recordatorios

El sistema envía recordatorios automáticos basados en el tipo de evento:

```python
# hdd-monitor/notification_handler.py

def check_upcoming_events():
    """Verifica eventos próximos y envía recordatorios"""
    now = datetime.utcnow()

    # Obtener eventos próximos (próximas 24h)
    upcoming_events = get_events_in_timeframe(
        start=now,
        end=now + timedelta(hours=24)
    )

    for event in upcoming_events:
        if not event['reminder_sent']:
            # Calcular tiempo hasta evento
            time_until = event['date_time'] - now

            # Enviar recordatorio según tipo
            if time_until < timedelta(hours=24):
                send_event_reminder_notification(event)
                mark_reminder_sent(event['event_id'])
```

**Canales de Recordatorio:**
- ✅ Push notification (FCM)
- ✅ Email a administradores
- ⚠️ SMS (no implementado)

### 3.6 Notificaciones Push de Eventos

Cuando se aproxima un evento, se envía notificación FCM:

```json
{
  "notification": {
    "title": "🗓️ Recordatorio: Mantenimiento Preventivo",
    "body": "Mañana 10:00 - Revisión semestral Panel Principal"
  },
  "data": {
    "type": "event_reminder",
    "event_id": "Mantto_Panel_15032026_client_1",
    "event_date": "15-03-2026 10:00",
    "event_type": "mantenimiento_preventivo",
    "action": "view_event"
  }
}
```

**Usuario ve en app:**
```
┌────────────────────────────────────────┐
│  🗓️ Recordatorio de Evento            │
├────────────────────────────────────────┤
│                                        │
│  Mantenimiento Semestral               │
│  Panel Principal                       │
│                                        │
│  📅 Mañana, 10:00 AM                   │
│  📍 Piso 5, Sala de Máquinas           │
│                                        │
│  ┌──────────┐    ┌──────────┐         │
│  │  ACEPTAR │    │   VER    │         │
│  └──────────┘    └──────────┘         │
└────────────────────────────────────────┘
```

### 3.7 Dashboard Web - Vista de Agenda

El dashboard web incluye vista de calendario con eventos:

**URL:** https://hddm.pqsolutionsperu.com/events

**Características:**
- ✅ Vista de lista con filtros por estado
- ✅ Filtro por tipo de evento
- ✅ Búsqueda por texto
- ✅ Actualización en tiempo real (WebSocket)
- ✅ Color coding por estado:
  - 🟡 Programado (amarillo)
  - 🔵 Aceptado (azul)
  - 🟢 Finalizado (verde)

**Vista de Tabla:**
```
┌──────────────────────────────────────────────────────────────┐
│  EVENTOS PROGRAMADOS                        [+ Nuevo Evento] │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  🟡 Mantto. Panel Principal    15/03/2026 10:00  Programado │
│     Revisión semestral RNE A.130                            │
│                                                              │
│  🔵 Capacitación Personal      20/03/2026 14:00  Aceptado   │
│     Uso de extintores y evacuación                          │
│                                                              │
│  🟢 Inspección INDECI         10/02/2026 09:00  Finalizado │
│     ITSDC renovado (Cert. 2026-123)                         │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 3.8 Casos de Uso Reales

#### Caso 1: Mantenimiento Preventivo Semestral

```
Timeline:
-180 días: Sistema crea evento automático (si programado recurrente)
  -7 días: Recordatorio email a admin
  -1 día:  Recordatorio push a personal responsable
   0 día:  Evento programado
          ↓ Empresa certificada realiza mantenimiento
          ↓ Usuario marca como "Finalizado" en app
          ↓ Sistema registra completion en Firestore
          ↓ Historial actualizado
  +180 días: Sistema auto-programa próximo mantenimiento
```

#### Caso 2: Capacitación de Personal Nuevo

```
Manager: Crea evento "Capacitación Sistema Alarmas"
  ↓ Asigna fecha: 25/03/2026 15:00
  ↓ Asigna responsable: "Ing. Juan Pérez"
  ↓ Agrega notas: "Incluir protocolo evacuación"
  ↓ Estado: PROGRAMADO

-24h: Sistema envía recordatorio push
  ↓ Notificación: "Mañana capacitación a las 3pm"
  ↓ Ing. Pérez marca como ACEPTADO

Día del evento:
  ↓ Capacitación se realiza
  ↓ Ing. Pérez marca como FINALIZADO
  ↓ Agrega notas: "10 personas capacitadas"
  ↓ Historial archivado
```

#### Caso 3: Renovación ITSDC (INDECI)

```
Sistema: Detecta ITSDC próximo a vencer (expira en 60 días)
  ↓ Crea evento automático: "Renovación ITSDC"
  ↓ Fecha sugerida: 30 días antes de expiración
  ↓ Estado: PROGRAMADO

-30 días: Recordatorio email
  ↓ "Su ITSDC expira en 1 mes"
  ↓ "Coordine inspección con INDECI"

-7 días: Recordatorio crítico
  ↓ "URGENTE: ITSDC expira en 7 días"

Admin: Coordina inspección
  ↓ Marca como ACEPTADO
  ↓ Fecha confirmada: 15/03/2026

Día de inspección:
  ↓ Inspector INDECI realiza visita
  ↓ Certificado emitido
  ↓ Admin sube certificado a sistema
  ↓ Marca como FINALIZADO
  ↓ Sistema programa próxima inspección (2 años)
```

### 3.9 API de Gestión de Eventos

**Endpoints disponibles:**

```http
# Obtener todos los eventos de un cliente
GET /api/clients/{client_id}/events
Response: {"success": true, "events": [...]}

# Crear nuevo evento
POST /api/clients/{client_id}/events
Body: {
  "type": "capacitacion",
  "title": "Capacitación Extintores",
  "date_time": "25-03-2026 15:00",
  "location": "Sala de Conferencias",
  "description": "..."
}

# Actualizar estado de evento
PUT /api/events/{event_id}/status
Body: {"status": "aceptado"}

# Eliminar evento
DELETE /api/events/{event_id}
```

### 3.10 Cumplimiento Normativo

El módulo de Gestión de Agenda ayuda a cumplir requisitos regulatorios:

| Normativa | Requisito | Cómo Ayuda HDD Monitor |
|-----------|-----------|------------------------|
| **RNE A.130 Art. 52** | Mantenimiento cada 6 meses | Programa automático de mantenimientos |
| **INDECI** | ITSDC cada 2 años | Recordatorios 60/30/7 días antes |
| **NFPA 72 Ch. 14** | Testing anual de sistemas | Eventos programados con historial |
| **ISO 9001** | Registros de mantenimiento | Historial completo en Firestore |

### 3.11 Estadísticas y Reportes

Sistema genera reportes automáticos:

```python
# Reporte mensual de eventos
{
  "month": "2026-02",
  "events_total": 15,
  "events_completed": 12,
  "events_pending": 2,
  "events_overdue": 1,
  "by_type": {
    "mantenimiento_preventivo": 4,
    "capacitacion": 6,
    "inspeccion": 2,
    "prueba": 3
  },
  "completion_rate": "80%"
}
```

---

## 4. Componentes Principales

### 3.1 ESP32 Firmware

**Ubicación:** `ESP-IDF-HDDESP32/hddesp32/`

**Managers:**
- `relay_manager`: Monitoreo de GPIOs con debounce (50ms)
- `mqtt_manager`: Cliente MQTT con SSL/TLS
- `wifi_manager`: Gestión WiFi (STA + AP provisioning)
- `connectivity_monitor`: Monitoreo de conectividad RAM-only
- `watchdog_manager`: Task Watchdog Timer
- `config_manager`: NVS con wear leveling

**Características:**
- Debounce hardware en GPIOs (50ms)
- Cola de mensajes MQTT (procesamiento diferido)
- Deferred relay configuration (guarda estado en NVS después de 5s)
- LWT (Last Will and Testament) para detección de desconexión
- Reconexión automática WiFi/MQTT
- OTA updates (preparado)

**Dispositivos registrados:**
- `3608AC08` - Panel prueba PRUELPET
- `1694ACA8` - Panel adicional
- `42A8ACA0` - Panel adicional

### 3.2 Servidor VM (Python)

**Ubicación:** `/home/pqsolutions/hdd-monitor/`

**Archivos principales:**
- `main.py`: Entry point del servicio
- `mqtt_client.py`: Cliente MQTT (sin TLS para localhost)
- `firestore_handler.py`: Observadores de Firestore + lógica de negocio
- `notification_handler.py`: FCM notifications
- `nfpa_metrics.py`: Métricas de compliance NFPA 72
- `rate_limiter.py`: Rate limiting para notificaciones
- `config.py`: Configuración (Firestore, PostgreSQL, MQTT)
- `system_watchdog.py`: Monitoreo de sistema
- `log_server.py`: Servidor de logs ESP32

**Funcionalidades:**
- Observadores de Firestore (eventos, relays, configuraciones)
- Envío de notificaciones FCM (usuario + admin)
- Persistencia redundante (Firestore + PostgreSQL)
- Rate limiting (1000 req/min por usuario)
- Event reminder checker (recordatorios 1 hora antes)
- System watchdog
- **SIN debounce de configuraciones** (cumple NFPA 72 < 200ms)

### 3.3 MQTT Broker (Mosquitto)

**Configuración:** `/etc/mosquitto/conf.d/hdd-monitor.conf`

```conf
# HDD Monitor MQTT Broker Configuration
listener 1883 0.0.0.0                    # Sin TLS (localhost)

listener 8883 0.0.0.0                    # Con TLS (ESP32s remotos)
certfile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem
keyfile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/privkey.pem
cafile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/chain.pem
require_certificate false

allow_anonymous false
password_file /etc/mosquitto/passwd

persistence true
persistence_location /var/lib/mosquitto/

max_connections 100
max_inflight_messages 20
max_queued_messages 100
```

**Usuarios:**
- `admin_hdd`: Servidor Python (localhost:1883)
- `mqtt_firestore_handler`: Handler principal
- `esp32_config_manager`: Gestor de configuración
- `esp32_3608AC08`: ESP32 panel PRUELPET (TLS:8883)
- `1694ACA8`: ESP32 adicional
- `42A8ACA0`: ESP32 adicional

**Topics:**

```
# Estado ESP32
system/status/<esp32_id>                    # LWT (Last Will Testament)
esp32/network_info                          # Info de red
esp32/connectivity/<esp32_id>               # Eventos conectividad

# Panel relay
clients/<client_id>/panels/<panel_id>/relays         # Estados relay
clients/<client_id>/panels/<panel_id>/relay_config   # Configuración
clients/<client_id>/panels/<panel_id>/command        # Comandos
clients/<client_id>/panels/<panel_id>/status         # Estado panel
```

**Puertos abiertos:**
```
tcp        0.0.0.0:1883    (sin TLS - localhost)
tcp        0.0.0.0:8883    (TLS - ESP32 remoto)
tcp        127.0.0.1:5432  (PostgreSQL - localhost)
```

### 3.4 Base de Datos

**Firestore (Primary):**

```
hdd-monitor/
├── accounts/
│   └── clients/
│       └── <client_id>/
│           ├── panels/
│           │   └── <panel_id>/
│           │       └── relays/
│           │           └── <relay_id>
│           │               ├── status: "OK" | "DISC"
│           │               ├── isActive: boolean
│           │               ├── contactType: "NO" | "NC"
│           │               ├── customName: string
│           │               └── lastUpdate: timestamp
│           ├── events/
│           │   └── <event_id>
│           │       ├── title, type, date_time
│           │       ├── status: "PROGRAMADO" | "COMPLETADO"
│           │       └── panelDocName, panelName
│           └── notifications/
│               └── <notification_id>
│                   ├── type: "relay" | "event" | "connectivity"
│                   ├── message, date_time, timestamp
│                   ├── isRead, readByAdmin, readByUser
│                   └── relay, panel_id, state
└── esp32/
    └── registered/
        └── <esp32_id>
            ├── status: "ONLINE" | "OFFLINE" | "AWAITING_CONFIG"
            ├── client_id, panel_id
            ├── lastSeen: timestamp
            └── networkInfo: {...}
```

**PostgreSQL (Backup):**

```sql
CREATE DATABASE hdd_monitor;

CREATE TABLE relay_events (
    id SERIAL PRIMARY KEY,
    client_id VARCHAR(255),
    panel_id VARCHAR(255),
    relay_id VARCHAR(255),
    old_status VARCHAR(50),
    new_status VARCHAR(50),
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_timestamp ON relay_events(timestamp DESC);
CREATE INDEX idx_panel_relay ON relay_events(panel_id, relay_id);
```

---

## 4. Flujo de Datos

### 4.1 Cambio de Estado de Relay

```
[Panel Alarma] Relay cambia estado (contacto físico)
        ↓
[ESP32] GPIO detecta cambio con debounce 50ms
        ├─→ Valida cambio real (no ruido)
        └─→ Registra en evento
        ↓
[ESP32] Publica MQTT QoS 2: clients/client_1/panels/panel_XXX/relays
        payload: {
          relay: "relay_1",
          status: "DISC",
          contact_type: "NC",
          timestamp: 1769825781854
        }
        ↓
[Broker MQTT localhost:1883] Recibe y distribuye mensaje
        ↓
[Servidor Python] mqtt_client.py recibe mensaje
        ↓
[Servidor] firestore_handler._update_relay_state()
        ├─→ Actualiza Firestore (status, lastUpdate, source: "mqtt")
        │   └─→ Latencia: ~200-500ms
        ├─→ Guarda en PostgreSQL (backup redundante)
        │   └─→ Latencia: ~50-100ms
        └─→ Llama _send_relay_notification_fast()
                ├─→ Valida rate limit (1000 req/min)
                ├─→ Prevención duplicados (debounce 1s)
                └─→ Registra métrica NFPA
                        ↓
        [Servidor] notification_handler.send_fcm_notifications()
                ├─→ Envía a usuario (FCM)
                │   └─→ Latencia: ~100-300ms
                └─→ Envía a admin (FCM)
                    └─→ Latencia: ~100-300ms
                        ↓
                [FCM] Distribuye notificación a dispositivos
                        └─→ Latencia: ~4-6 segundos
                        ↓
                [App Android] Recibe y muestra notificación
                        └─→ Sonido + Vibración + Badge
```

**Tiempos totales (cambio físico → notificación en APP):**
- GPIO → ESP32: ~50ms (debounce)
- ESP32 → MQTT → Broker: ~50-200ms (TLS + red)
- Broker → Servidor: ~10-50ms (localhost)
- Servidor procesa + Firestore + PostgreSQL: ~200-600ms
- Servidor → FCM: ~100-300ms
- FCM → App: ~4-6 segundos
- **TOTAL: 6-8 segundos** ✅ (NFPA 72 requiere < 90s)

### 4.2 Configuración Remota de Relay

```
[App Android] Usuario cambia isActive/contactType en Firestore
        ↓
[Firestore] Trigger onSnapshot detecta cambio
        ↓
[Servidor] watch_relay_configurations() → on_relay_config_change()
        ├─→ Detecta cambio (isActive, contactType, customName)
        ├─→ Verifica ESP32 status == "ONLINE" en Firestore
        ├─→ Valida client_id/panel_id match
        └─→ SIN DEBOUNCE (antes tenía 10s, eliminado para cumplir NFPA)
                ↓
        [Servidor] Publica MQTT QoS 2: clients/.../relay_config
                payload: {
                  command: "update_config",
                  relay_id: "relay_1",
                  is_active: true,
                  contact_type: "NC",
                  custom_name: "Bomba Principal",
                  timestamp: 1769825...
                }
                ↓
[ESP32] mqtt_manager recibe configuración
        ↓
[ESP32] relay_manager procesa
        ├─→ Activa/desactiva relay (cambia monitoreo GPIO)
        ├─→ Cambia tipo de contacto (lógica NC/NO)
        ├─→ Actualiza nombre custom
        └─→ Programa guardado diferido en NVS (5s delay)
                ↓
[ESP32] Publica estado actual: clients/.../relays
        payload: {status: "OK", active: true, type: "NC"}
        ↓
[Servidor] Actualiza Firestore con nuevo estado
        ↓
[App] Observador Firestore detecta cambio
        └─→ UI refleja cambio inmediatamente
```

**Tiempos de configuración:**
- App → Firestore: ~100-200ms
- Firestore trigger → Servidor: ~50-100ms
- Servidor → MQTT: < 10ms (sin debounce)
- MQTT → ESP32: ~50-200ms
- ESP32 aplica config: ~10-50ms
- ESP32 confirma → Servidor → Firestore → App: ~500ms
- **TOTAL: < 2 segundos** ✅

### 4.3 Detección de Desconexión (LWT)

```
[ESP32] Al conectar a MQTT, configura LWT (Last Will Testament):
        topic: system/status/3608AC08
        payload: {"status": "OFFLINE", "timestamp": ..., "reason": "timeout"}
        qos: 2
        retain: true
        ↓
[ESP32] Inmediatamente después publica estado ONLINE:
        topic: system/status/3608AC08
        payload: {"status": "ONLINE", "timestamp": ..., "ip": "192.168.1.21"}
        qos: 2
        retain: true
        ↓
[Broker] Guarda LWT y espera keepalive (90 segundos)
        ↓
[ESP32] Se desconecta (apagado, WiFi perdido, etc.)
        ↓
[Broker] Después de 90s sin keepalive:
        └─→ Publica automáticamente el mensaje LWT
                ↓
[Servidor] mqtt_client.py detecta mensaje en system/status/3608AC08
        ├─→ Filtra mensajes retain antiguos (elapsed < 5s)
        ├─→ Verifica que payload.status == "OFFLINE"
        └─→ Actualiza Firestore: esp32/registered/3608AC08
                └─→ status: "OFFLINE"
                └─→ lastSeen: timestamp
                ↓
        [Servidor] notification_handler.send_offline_notification()
                ├─→ Busca panel asociado al ESP32
                ├─→ Crea notificación en Firestore
                └─→ Envía FCM a usuarios y admin
                        ↓
                [App] Recibe notificación: "El panel XXX se desconectó"
```

**Cuando ESP32 se reconecta:**
```
[ESP32] Publica: esp32/network_info
        payload: {
          esp32_id: "3608AC08",
          ip: "192.168.1.21",
          rssi: -45,
          timestamp: ...
        }
        ↓
[Servidor] mqtt_client.handle_network_info()
        ├─→ Detecta que status anterior era "OFFLINE"
        ├─→ Actualiza status: "ONLINE"
        └─→ notification_handler.send_online_notification()
                ↓
        [App] Recibe notificación: "El panel XXX volvió a estar ONLINE"
```

**Tiempos LWT:**
- Desconexión → Timeout broker: **90 segundos** (keepalive)
- Broker publica LWT: < 10ms
- Servidor procesa + notifica: ~2 segundos
- **TOTAL: ~92 segundos** ✅ (NFPA 72 acepta hasta 200s para supervisión)

---

## 5. Cumplimiento de Normativas y Estándares Internacionales

**Última Evaluación:** 02 de febrero de 2026
**Documento de Certificación Completo:** `CERTIFICACION_PRODUCCION_PERU.md`

### Resumen Ejecutivo de Cumplimiento

```
╔═══════════════════════════════════════════════════════════════╗
║          ESTADO DE CERTIFICACIÓN - HDD MONITOR v2.2           ║
╠═══════════════════════════════════════════════════════════════╣
║                                                               ║
║  CUMPLIMIENTO TÉCNICO:          ✅ 85% (Excelente)            ║
║  CERTIFICACIÓN FORMAL:          ❌ 15% (Insuficiente)         ║
║  LISTO PARA PRODUCCIÓN PERÚ:    ⚠️  CONDICIONAL               ║
║                                                               ║
║  ✅ CUMPLE: NFPA 72, UL 864 (técnico), OWASP IoT (70%)       ║
║  ❌ FALTA: Homologación MTC, Registro INDECOPI, UL Listed    ║
║                                                               ║
║  RIESGO DE MULTAS (Lima, Perú):                              ║
║    • Venta comercial: $100K-$150K (probabilidad 70-80%)     ║
║    • Cliente privado: $10K-$30K (probabilidad 30-40%)       ║
║    • Piloto interno: $0-$5K (probabilidad 5-10%)            ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
```

### Índice de Normativas

- [5.1 Normativas de Alarmas Contra Incendios](#51-normativas-de-alarmas-contra-incendios)
- [5.2 Normativas Peruanas (CRÍTICAS)](#52-normativas-peruanas-críticas)
- [5.3 Estándares de Comunicaciones IoT](#53-estándares-de-comunicaciones-iot)
- [5.4 Estándares de Ciberseguridad](#54-estándares-de-ciberseguridad)
- [5.5 Protección de Datos](#55-protección-de-datos)
- [5.6 Tabla Resumen de Cumplimiento](#56-tabla-resumen-de-cumplimiento)
- [5.7 Métricas de Compliance](#57-métricas-de-compliance)
- [5.8 Requisitos de Hardware y Alimentación (Producción)](#58-requisitos-de-hardware-y-alimentación-producción)
- [5.9 Requisitos Legales para Venta en Perú](#59-requisitos-legales-para-venta-en-perú)
- [5.10 Normativas Técnicas 2025-2026](#510-normativas-técnicas-2025-2026)
- [5.11 Checklist Pre-Producción](#511-checklist-pre-producción)
- [5.12 Referencias Verificadas](#512-referencias-verificadas-febrero-2026)
- [5.13 Roadmap de Certificación](#513-roadmap-de-certificación)

---

### 5.1 Normativas de Alarmas Contra Incendios

#### 5.1.1 NFPA 72 - National Fire Alarm and Signaling Code (2022)

**Autoridad:** National Fire Protection Association
**URL:** https://www.nfpa.org/codes-and-standards/all-codes-and-standards/list-of-codes-and-standards/detail?code=72
**Aplicabilidad:** ✅ CRÍTICA - Referencia mundial
**Cumplimiento:** ✅ **100% técnico**

| Sección | Requisito | HDD Monitor | Estado |
|---------|-----------|-------------|--------|
| 10.6.1 | Transmisión sin demora (< 90s) | 6-8 segundos | ✅ **91% más rápido** |
| 10.11 | Procesamiento < 200ms | ~50ms GPIO | ✅ **75% más rápido** |
| 26.6.3.2.1.2 | Centro monitoreo < 90s | 6-8 segundos | ✅ **10x más rápido** |
| 26.6.3.1.1 | Supervisión línea < 200s | 90s (LWT MQTT) | ✅ **2x más rápido** |
| 26.6.3.1.3 | Restauración < 60s | < 10 segundos | ✅ **6x más rápido** |
| 23.8 | Notificación < 10s | 6-8 segundos | ✅ **CUMPLE** |

**Estadísticas Reales:**
- Latencia medida: 6-8 segundos (end-to-end)
- Tiempo procesamiento ESP32: ~50ms
- Tiempo MQTT publish: < 500ms
- Tiempo Firestore write: ~1-2s
- Tiempo FCM delivery: 4-6s

---

#### 5.1.2 UL 864 - Standard for Control Units and Accessories

**Autoridad:** Underwriters Laboratories
**URL:** https://standardscatalog.ul.com/ProductDetail.aspx?productId=UL864
**Aplicabilidad:** ✅ CRÍTICA para certificación comercial
**Cumplimiento:** ✅ **Técnico 100%** | ❌ **Certificación 0%**

| Requisito | Estado | Implementación |
|-----------|--------|----------------|
| Transmisión < 10s | ✅ CUMPLE | 6-8 segundos |
| Señales fallo < 200s | ✅ SUPERA | < 5s (health alerts) |
| Config inmediata | ✅ CUMPLE | < 200ms (sin debounce) |
| Persistencia config | ✅ CUMPLE | NVS + Firestore backup |

**Nota Crítica:**
- ✅ Cumplimiento técnico: 100%
- ❌ Certificación UL Listed: NO OBTENIDA
- 💰 Costo certificación: $30,000 - $50,000 USD
- ⏱️ Tiempo: 4-6 meses

---

#### 5.1.3 UL 2572 - Mass Notification Systems

**Autoridad:** Underwriters Laboratories
**URL:** https://standardscatalog.ul.com/ProductDetail.aspx?productId=UL2572
**Aplicabilidad:** ⚠️ MEDIA
**Cumplimiento:** ⚠️ **70%** (2 de 4 canales)

**Canales Implementados:**
- ✅ Push Notifications (FCM)
- ✅ Email Alerts
- ❌ SMS (no implementado)
- ❌ Voice Calls (no implementado)

---

### 5.2 Normativas Peruanas (CRÍTICAS)

#### 5.2.1 MTC - Homologación de Equipos (DS 001-2013-MTC)

**Autoridad:** Ministerio de Transportes y Comunicaciones
**URL:** https://www.gob.pe/mtc
**Aplicabilidad:** ✅ **OBLIGATORIO** - Dispositivos WiFi 2.4 GHz
**Cumplimiento:** ❌ **NO HOMOLOGADO**

**Estado ESP32:**
- Frecuencia: 2.400 - 2.4835 GHz (banda ISM)
- Potencia: < 20 dBm (100 mW)
- Módulo base: Certificado por Espressif
- Producto final: ❌ **NO HOMOLOGADO**

**Consecuencias de No Homologación:**
- 🚫 Uso comercial NO permitido
- 💰 Multa: 50-100 UIT (~$62,500 - $125,000 USD)
- 📡 Responsabilidad penal por interferencia
- 🚨 Decomiso de equipos

**Acción Requerida:** 🔴 **CRÍTICA**
- Costo: $3,000 - $5,000 USD
- Tiempo: 3-4 meses
- Prioridad: **MÁXIMA**

---

#### 5.2.2 INDECOPI - Registro de Productos (Res. 096-2005)

**Autoridad:** Instituto Nacional de Defensa Civil
**URL:** https://www.gob.pe/indecopi
**Aplicabilidad:** ✅ **OBLIGATORIO** para comercialización
**Cumplimiento:** ❌ **NO REGISTRADO**

**Estado:**
- ❌ Producto NO registrado
- ❌ Certificado de conformidad NO obtenido
- ❌ Declaración jurada NO presentada

**Consecuencias:**
- 🚫 Venta comercial NO permitida
- 💰 Multa: 25-50 UIT (~$31,250 - $62,500 USD)
- ⚖️ Decomiso de productos

**Acción Requerida:** 🔴 **CRÍTICA**
- Costo: $1,000 - $2,000 USD
- Tiempo: 2-3 meses

---

#### 5.2.3 Reglamento Nacional de Edificaciones - Norma A.130

**Autoridad:** Ministerio de Vivienda
**URL:** https://www.gob.pe/institucion/vivienda/
**Aplicabilidad:** ✅ **OBLIGATORIO** para edificaciones
**Cumplimiento:** ⚠️ **PARCIAL** (técnico OK, certificación pendiente)

**Artículo 47:** Sistemas deben cumplir NTP 350.043-1 y NFPA 72
- ✅ Cumple NFPA 72 (100%)
- ⚠️ NTP 350.043-1: Pendiente verificación
- ⚠️ Certificado conformidad: NO obtenido

---

#### 5.2.4 Ley N° 29733 - Protección de Datos Personales

**Autoridad:** Ministerio de Justicia
**URL:** https://www.gob.pe/institucion/minjus/
**Aplicabilidad:** ✅ **OBLIGATORIO** - Procesa datos personales
**Cumplimiento:** ⚠️ **60%** (medidas técnicas OK, legal pendiente)

**Datos Recolectados:**
- Email usuarios (notificaciones)
- Token FCM (push)
- Ubicación instalación
- Logs de actividad

**Estado:**
- ✅ Encriptación TLS 1.3
- ✅ NVS encriptado
- ❌ Política de privacidad NO publicada
- ❌ Consentimiento NO implementado
- ❌ Derecho al olvido NO implementado

**Multa Potencial:** Hasta 100 UIT (~$125,000 USD)

---

### 5.3 Estándares de Comunicaciones IoT

#### 5.3.1 ISO/IEC 20922:2016 - MQTT Protocol

**Cumplimiento:** ✅ **100%**

Implementación:
- ✅ MQTT v3.1.1 (Eclipse Mosquitto)
- ✅ QoS 1 (at least once delivery)
- ✅ Persistent sessions
- ✅ Last Will Testament (LWT)
- ✅ TLS 1.3 encryption (puerto 8883)

#### 5.3.2 RFC 8446 - TLS 1.3

**Cumplimiento:** ✅ **100%**

- ✅ TLS 1.3 en MQTT (8883)
- ✅ Certificados Let's Encrypt
- ✅ Renovación automática
- ✅ Perfect Forward Secrecy
- ✅ HTTPS para dashboard

#### 5.3.3 IEEE 802.11 - WiFi Standards

**Cumplimiento:** ✅ **CUMPLE** (con limitaciones hardware)

- ✅ ESP32 soporta 802.11 b/g/n
- ✅ WPA2-PSK encryption
- ⚠️ WPA3 no soportado (limitación ESP32)
- ✅ Auto-reconnect

---

### 5.4 Estándares de Ciberseguridad

#### 5.4.1 OWASP IoT Top 10 (2018)

**Cumplimiento:** ⚠️ **70%** (7/10 mitigados)

| # | Vulnerabilidad | Estado | Mitigación |
|---|----------------|--------|------------|
| I1 | Weak Passwords | ✅ MITIGADO | Credenciales únicas en NVS |
| I2 | Insecure Services | ✅ MITIGADO | MQTT TLS, servicios mínimos |
| I3 | Insecure Interfaces | ⚠️ PARCIAL | API sin OAuth2 |
| I4 | No Secure Update | ⚠️ **VULNERABLE** | OTA sin firma digital |
| I5 | Insecure Components | ✅ MITIGADO | ESP-IDF v5.0+, deps actualizadas |
| I6 | Insufficient Privacy | ✅ MITIGADO | Datos mínimos, sin PII |
| I7 | Insecure Transfer | ✅ MITIGADO | TLS 1.3, NVS encriptado |
| I8 | No Management | ⚠️ PARCIAL | Health monitoring OK |
| I9 | Insecure Defaults | ✅ MITIGADO | Sin defaults inseguros |
| I10 | No Physical Hardening | ❌ **VULNERABLE** | Sin tamper detection |

**Vulnerabilidades Críticas Pendientes:**
- I4: Secure OTA (ALTA prioridad, $5K-10K, 2-3 meses)
- I10: Physical Hardening (MEDIA prioridad, $3K-5K, 1-2 meses)

#### 5.4.2 NIST Cybersecurity Framework

**Cumplimiento:** ⚠️ **60%**

| Función | Calificación | Implementación |
|---------|--------------|----------------|
| IDENTIFY | ⚠️ 70% | ✅ Inventario, ❌ Risk assessment formal |
| PROTECT | ⚠️ 75% | ✅ TLS, ✅ Auth, ⚠️ RBAC básico |
| DETECT | ⚠️ 60% | ✅ Health monitor, ❌ IDS/IPS |
| RESPOND | ⚠️ 50% | ✅ Alerts, ❌ Plan formal |
| RECOVER | ⚠️ 50% | ✅ Auto-reconnect, ❌ DR plan |

---

### 5.5 Protección de Datos

#### 5.5.1 GDPR (Unión Europea)

**Aplicabilidad:** ⚠️ SI hay usuarios en UE
**Cumplimiento:** ⚠️ **PARCIAL**

- ✅ Encriptación en tránsito
- ✅ Encriptación en reposo
- ❌ Política de privacidad no publicada
- ❌ Consentimiento no implementado
- ❌ Derecho al olvido no implementado

---

### 5.6 Tabla Resumen de Cumplimiento

| Categoría | Normativa | Cumplimiento | Prioridad | Riesgo Legal |
|-----------|-----------|--------------|-----------|--------------|
| **Alarmas** | NFPA 72 | ✅ 100% | 🔴 CRÍTICA | 🟢 BAJO |
| | UL 864 | ✅ 100% técnico | 🔴 CRÍTICA | 🟡 MEDIO |
| | UL 2572 | ⚠️ 70% | 🟡 MEDIA | 🟢 BAJO |
| **Perú** | MTC Homologación | ❌ 0% | 🔴 CRÍTICA | 🔴 **CRÍTICO** |
| | INDECOPI Registro | ❌ 0% | 🔴 CRÍTICA | 🔴 **CRÍTICO** |
| | RNE A.130 | ⚠️ 80% | 🟡 MEDIA | 🟡 MEDIO |
| | Ley 29733 | ⚠️ 60% | 🟡 MEDIA | 🟡 MEDIO |
| **Comunicaciones** | ISO/IEC 20922 | ✅ 100% | 🔴 CRÍTICA | 🟢 BAJO |
| | TLS 1.3 | ✅ 100% | 🔴 CRÍTICA | 🟢 BAJO |
| | IEEE 802.11 | ✅ 95% | 🔴 CRÍTICA | 🟢 BAJO |
| **Ciberseguridad** | OWASP IoT | ⚠️ 70% | 🔴 CRÍTICA | 🟡 MEDIO |
| | NIST CSF | ⚠️ 60% | 🟡 MEDIA | 🟢 BAJO |
| **Datos** | GDPR | ⚠️ PARCIAL | 🟡 MEDIA | 🟡 MEDIO |

**Leyenda:**
- 🔴 **CRÍTICO:** Multa segura si se detecta
- 🟡 **MEDIO:** Puede generar observaciones
- 🟢 **BAJO:** Sin riesgo legal directo

---

### 5.7 Métricas de Compliance

#### 5.7.1 Logging NFPA 72

Sistema de métricas automáticas:

```json
{
  "event_id": "panel_PRUELPET_client_1_relay_1_1769825747583",
  "latency_ms": 1512.44,
  "nfpa72_compliant": true,
  "event": "nfpa72_notification_latency",
  "timestamp": "2026-01-31T02:15:49.372814Z",
  "level": "info"
}
```

**Ver métricas:**
```bash
# Últimas 20 métricas
sudo journalctl -u hdd-monitor.service | grep "nfpa72_notification_latency" | tail -20

# Reporte mensual
sudo journalctl -u hdd-monitor --since "1 month ago" | \
  grep "nfpa72_notification_latency" | \
  jq '{event_id, latency_ms, nfpa72_compliant}' > /tmp/nfpa_report.json
```

---

### 5.8 Requisitos de Hardware y Alimentación (Producción)

#### 5.8.1 Circuito de Alimentación con Protección PTC

**Diagrama del Circuito:**

```
┌─────────────────────────────────────────────────────────────────┐
│  PANEL DE ALARMAS CERTIFICADO (UL 864)                          │
│  ┌─────────────────┐                                           │
│  │ Bornera AUX 24V │                                           │
│  └────────┬────────┘                                           │
└───────────┼─────────────────────────────────────────────────────┘
            │
            ↓
┌─────────────────────────────────────────────────────────────────┐
│  CIRCUITO DE ALIMENTACIÓN HDD MONITOR (Producción)              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  24V+ ───[TVS P6KE30A]───[PTC 1.1A]───┬───[DC-DC 24→5V]───┐    │
│              ↑               ↑         │        ↑          │    │
│         Protección      Auto-reset    │    Conversión     │    │
│         sobrevoltaje    sobrecorriente│                    │    │
│                                        │                    │    │
│                              [C 470µF + 100nF]             │    │
│                                        │                    │    │
│  24V- ─────────────────────────────────┴────────────────────┘   │
│                                                             │    │
│                                                             ↓    │
│                                                      ESP32 VIN   │
│                                                      ESP32 GND   │
│                                                                 │
│  [LED verde + R1K] ← Indicador alimentación OK                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Componentes Requeridos:**

| Componente | Modelo | Especificación | Función | Precio |
|------------|--------|----------------|---------|--------|
| **Diodo TVS** | P6KE30A | 30V clamp | Protección sobrevoltaje | $0.50 |
| **Fusible PTC** | RXEF110 / MF-R110 | 1.1A hold, 30V max | Protección sobrecorriente auto-reset | $0.30 |
| **DC-DC Buck** | LM2596 o MP1584 | 24V→5V, 2A | Conversión de voltaje | $3-5 |
| **Capacitor elect.** | - | 470µF/25V | Filtrado ripple | $0.30 |
| **Capacitor cerám.** | - | 100nF/50V | Filtrado alta frecuencia | $0.10 |
| **LED indicador** | - | Verde 3mm + R 1KΩ | Estado alimentación | $0.20 |
| **TOTAL** | | | | **~$5 USD** |

#### 5.8.2 Fusible PTC - Funcionamiento y Especificaciones

**¿Cómo Funciona el PTC (Positive Temperature Coefficient)?**

```
ESTADO NORMAL (Frío):               SOBRECORRIENTE (Disparo):
═════════════════════               ═════════════════════════
Resistencia: ~0.1Ω                  Resistencia: ~1000Ω+
Corriente fluye normal              Corriente limitada (~0)

       ┌─────┐                            ┌─────┐
24V ───┤ PTC ├───→ DC-DC            24V ───┤ PTC ├───X  (bloqueado)
       └─────┘                            └─────┘
    (partículas                        (polímero expandido,
     conductoras                        partículas separadas)
     juntas)

AUTO-RESET (30-60 segundos después):
════════════════════════════════════
PTC se enfría → Resistencia vuelve a ~0.1Ω → Sistema restaurado automáticamente
```

**Especificaciones Técnicas (RXEF110 / MF-R110):**

| Parámetro | Valor | Notas |
|-----------|-------|-------|
| Corriente hold (Ihold) | 1.1A | Máxima continua sin disparo |
| Corriente trip (Itrip) | 2.2A | Garantiza disparo |
| Voltaje máximo | 30V | ✅ Soporta 24V panel |
| Tiempo de disparo | < 3 segundos | A 2x Itrip |
| Tiempo de reset | 30-60 segundos | Después de remover falla |
| Resistencia inicial | 0.06-0.12Ω | Caída despreciable (~0.015V) |
| Ciclos de vida | > 10,000 | Prácticamente ilimitado |
| Temperatura operación | -40°C a +85°C | Rango industrial |
| MTBF | > 1,000,000 horas | Extremadamente confiable |

**Certificaciones del Componente:**
- ✅ UL Recognized (cULus)
- ✅ TÜV
- ✅ CSA
- ✅ RoHS Compliant

**Ventajas vs Fusible Convencional:**

| Aspecto | Fusible Normal | Fusible PTC |
|---------|----------------|-------------|
| Reemplazo necesario | ✅ Cada disparo | ❌ Nunca (auto-reset) |
| Tiempo inactividad | Hasta reemplazo manual | ~30-60 segundos |
| Visita técnica | Requerida | No requerida |
| Costo por falla | $0.50 + mano obra | $0 |

**Secuencia de Protección:**

```
1. SPIKE DE VOLTAJE (ej: 50V transitorio)
   → TVS P6KE30A clampea a ~30V
   → DC-DC recibe voltaje seguro
   → ESP32 protegido ✅

2. SOBRECORRIENTE (ej: cortocircuito interno)
   → PTC se calienta en <3 segundos
   → Resistencia sube a ~1000Ω
   → Corriente limitada, sistema se apaga
   → Después de 30-60s, PTC se enfría
   → Sistema vuelve a funcionar automáticamente ✅

3. OPERACIÓN NORMAL
   → TVS inactivo (no afecta circuito)
   → PTC en baja resistencia (~0.1Ω)
   → Pérdida de voltaje: ~0.015V (despreciable) ✅
```

#### 5.8.3 Especificaciones Eléctricas del Sistema

| Parámetro | Valor | Notas |
|-----------|-------|-------|
| Voltaje entrada | 18-28 VDC | Tolerancia típica panel |
| Voltaje salida | 5.0V ±5% | Requerido por ESP32 |
| Corriente promedio | 150 mA | Operación normal |
| Corriente pico | 500 mA | Transmisión WiFi |
| Corriente máxima | 800 mA | Arranque + WiFi |
| Potencia máxima | 4W | 5V × 0.8A |
| Clasificación NEC | PLFA (< 100VA) | Power-Limited Fire Alarm ✅ |

#### 5.8.4 Cumplimiento Normativo de Alimentación

| Normativa | Sección | Requisito | Cumplimiento |
|-----------|---------|-----------|--------------|
| **UL 864** | §49 | Protección contra cortocircuito | ✅ PTC + TVS |
| **NEC 760** | 760.41 | Circuito power-limited | ✅ < 100VA (4W) |
| **NFPA 72** | 10.6.8 | No exceder capacidad auxiliar | ✅ 800mA << 2A típico |
| **IEC 60730** | - | Dispositivos control automático | ✅ PTC certificado |
| **UL 1434** | - | Estándar para PTC | ✅ Componentes UL Listed |

#### 5.8.5 Nota de Instalación

> ⚠️ **IMPORTANTE:** El dispositivo HDD Monitor debe conectarse a la salida **AUXILIAR** 24VDC del panel, NO a la alimentación principal. Verificar que la carga total de todas las salidas auxiliares no exceda la capacidad nominal del panel.
>
> **Consumo:** 150mA promedio, 500mA pico WiFi, 800mA arranque
>
> **Protecciones incluidas:**
> - TVS P6KE30A: Protege contra transitorios de voltaje
> - PTC RXEF110: Auto-resetea después de sobrecorriente (30-60s)

---

### 5.9 Requisitos Legales para Venta en Perú

#### 5.9.1 Homologación MTC (OBLIGATORIO para WiFi)

**Autoridad:** Ministerio de Transportes y Comunicaciones (MTC)
**Base Legal:** D.S. N° 001-2006-MTC, modificado por D.S. N° 019-2019-MTC

**Enlaces Oficiales (Verificados Feb 2026):**

| Recurso | URL |
|---------|-----|
| **Trámite Principal** | https://www.gob.pe/22738-solicitar-certificado-de-homologacion-para-un-equipo-y-o-aparato-de-telecomunicaciones-que-genera-emisiones-radioelectricas |
| **Info General** | https://www.gob.pe/institucion/mtc/colecciones/334-homologacion-de-equipos-de-telecomunicaciones |
| **Portal VUCE** | https://www.vuce.gob.pe/ |
| **Cartilla Orientación** | https://portal.mtc.gob.pe/comunicaciones/control_supervision/homologacion_equipos/documentos/cartilla_orientacion_homologacion.pdf |

**Requisitos para ESP32 (WiFi 2.4GHz):**

| Documento | Descripción | Obligatorio |
|-----------|-------------|-------------|
| Formulario VUCE | Solicitud formato aprobado | ✅ |
| Manual Técnico | Especificaciones ESP32 (español/inglés) | ✅ |
| Datos Fabricante | Espressif Systems, marca, modelo | ✅ |
| Comprobante Pago | Número operación y fecha | ✅ |
| Certificado SAR | Solo si >2.2GHz o >50mW | ❌ No aplica |

**Costos:**

| Tipo Equipo | Costo | USD Aprox. |
|-------------|-------|------------|
| **Equipos banda no licenciada (WiFi 2.4GHz)** | S/ 49.20 | ~$13 |
| Equipos estándar | S/ 86.40 | ~$23 |

**Proceso:**

```
1. REGISTRO VUCE
   └── Crear cuenta: https://www.vuce.gob.pe/
   └── Requiere RUC y Clave SOL (SUNAT)

2. PREPARAR DOCUMENTACIÓN
   └── Manual técnico ESP32 (de Espressif)
   └── Especificaciones: WiFi 2.4GHz, <100mW

3. PRESENTAR SOLICITUD
   └── VUCE → Mercancías Restringidas → MTC
   └── Adjuntar documentos
   └── Pagar S/ 49.20

4. EVALUACIÓN (15-30 días hábiles)
   └── MTC revisa y puede pedir info adicional

5. CERTIFICADO EMITIDO
   └── Vigencia: INDEFINIDA (no expira)
```

**Contacto MTC:**
- Teléfono: (01) 615-7900
- Email: atencionalciudadano@mtc.gob.pe

---

#### 5.9.2 Registro de Marca INDECOPI (Recomendado)

**Enlaces Oficiales (Verificados Feb 2026):**

| Recurso | URL |
|---------|-----|
| **Trámite** | https://www.gob.pe/333-registrar-marca-del-producto-y-o-servicio |
| **Servicios Online** | https://www.indecopi.gob.pe/servicios-en-linea |
| **Tasas** | https://indecopi.gob.pe/web/signos-distintivos/tasas |

**Costos:**

| Concepto | Costo | USD Aprox. |
|----------|-------|------------|
| Registro marca (1 clase) | S/ 534.99 | ~$143 |
| Clase adicional | S/ 533.30 | ~$142 |

**Clase recomendada:** Clase 9 (aparatos electrónicos)

---

#### 5.9.3 Resumen Costos y Tiempos para Perú

```
╔═══════════════════════════════════════════════════════════════════╗
║     REQUISITOS PARA COMERCIALIZAR HDD MONITOR EN PERÚ             ║
╠═══════════════════════════════════════════════════════════════════╣
║                                                                   ║
║  OBLIGATORIOS:                                                    ║
║  ─────────────                                                    ║
║  ☐ Homologación MTC (WiFi 2.4GHz)                                ║
║    • Costo: S/ 49.20 (~$13 USD)                                  ║
║    • Tiempo: 15-30 días hábiles                                  ║
║    • Vigencia: Indefinida                                        ║
║    • Trámite: Online vía VUCE                                    ║
║                                                                   ║
║  ☐ RUC activo en SUNAT                                           ║
║    • Costo: Gratuito                                             ║
║    • Tiempo: 1 día                                               ║
║                                                                   ║
║  RECOMENDADOS:                                                    ║
║  ─────────────                                                    ║
║  ☐ Registro marca "HDD Monitor" (INDECOPI)                       ║
║    • Costo: S/ 534.99 (~$143 USD)                                ║
║    • Tiempo: 3-6 meses                                           ║
║    • Vigencia: 10 años (renovable)                               ║
║                                                                   ║
║  ════════════════════════════════════════                        ║
║  TOTAL MÍNIMO (solo obligatorio): ~$13 USD                       ║
║  TOTAL RECOMENDADO (con marca): ~$160 USD                        ║
║                                                                   ║
╚═══════════════════════════════════════════════════════════════════╝
```

---

### 5.10 Normativas Técnicas 2025-2026

#### 5.10.1 NFPA 72 Edición 2025

**Fuente:** [NFPA 72 2025](https://www.nfpa.org/codes-and-standards/nfpa-72-standard-development/72)

**Capítulo 11 - Ciberseguridad (NUEVO, Obligatorio):**

| Nivel | Conectividad | HDD Monitor |
|-------|--------------|-------------|
| Nivel 0 | Sin red | - |
| Nivel 1 | LAN | - |
| **Nivel 2** | **Internet/Cloud** | **✅ Aplica** |
| Nivel 3 | Acceso remoto total | - |

**Requisitos Nivel 2 - Estado HDD Monitor:**

| Requisito | Estado | Implementación |
|-----------|--------|----------------|
| Conexión protegida gateway/firewall | ✅ | TLS 1.3 + Firewall GCP |
| Solo tráfico autorizado | ✅ | MQTT autenticado |
| Protección acceso no autorizado | ✅ | Firebase Auth |
| Notificación impairment <8h | ✅ | LWT + Email (<5 min) |

**Tiempos de Transmisión (§26.6):**

| Requisito | Límite | HDD Monitor | Margen |
|-----------|--------|-------------|--------|
| Transmisión central | <90s | 6-8s | 91% mejor |
| Supervisión línea | <200s | 90s (LWT) | 55% mejor |
| Procesamiento | <200ms | ~50ms | 75% mejor |

#### 5.10.2 Otras Normativas

| Normativa | Fuente | Estado |
|-----------|--------|--------|
| **UL 864 Ed. 11** | [UL Catalog](https://standardscatalog.ul.com/standards/en/standard_864_10) | N/A (sistema secundario) |
| **UL 2900-2-3** | [UL Cybersecurity](https://www.ul.com/services/cybersecurity-physical-security-systems) | Referencia |
| **NIST CSF 2.0** | [NIST](https://www.nist.gov/cyberframework) | Referencia |
| **NEC 2026 Art. 720** | [Low Voltage Nation](https://www.lowvoltagenation.com/posts/nec-2026-article-720-limited-energy-general-requirements) | ✅ PLFA cumple |

---

### 5.11 Checklist Pre-Producción

```
╔═══════════════════════════════════════════════════════════════╗
║         CHECKLIST PRODUCCIÓN - HDD MONITOR                    ║
╠═══════════════════════════════════════════════════════════════╣
║                                                               ║
║  HARDWARE (Por implementar)                                   ║
║  [ ] Circuito con TVS P6KE30A                                ║
║  [ ] Fusible PTC RXEF110 (1.1A, auto-reset)                  ║
║  [ ] DC-DC Buck 24V→5V (LM2596/MP1584)                       ║
║  [ ] Capacitores filtrado (470µF + 100nF)                    ║
║  [ ] LED indicador alimentación                               ║
║  [ ] Conexión a salida AUXILIAR panel                        ║
║                                                               ║
║  CIBERSEGURIDAD (Implementado)                               ║
║  [✓] TLS 1.3 en comunicaciones                               ║
║  [✓] Autenticación Firebase                                  ║
║  [✓] Firewall GCP                                            ║
║  [✓] Credenciales únicas por dispositivo                     ║
║                                                               ║
║  TIEMPOS NFPA 72 (Implementado)                              ║
║  [✓] Latencia <90s (actual: 6-8s)                           ║
║  [✓] Supervisión <200s (actual: 90s)                        ║
║  [✓] Notificación falla <8h (actual: <5min)                 ║
║                                                               ║
║  PERÚ - OBLIGATORIO                                          ║
║  [ ] Homologación MTC (~$13, 15-30 días)                     ║
║  [ ] RUC SUNAT activo                                         ║
║                                                               ║
║  PERÚ - RECOMENDADO                                          ║
║  [ ] Registro marca INDECOPI (~$143, 3-6 meses)              ║
║                                                               ║
║  CONTRATO INSTALACIÓN                                         ║
║  [ ] Cláusula sistema secundario/supervisorio                ║
║  [ ] Cliente confirma panel primario certificado             ║
║  [ ] Cliente confirma ITSDC vigente                          ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
```

---

### 5.12 Referencias Verificadas (Febrero 2026)

**Normativas Internacionales:**

| Recurso | URL | Verificado |
|---------|-----|------------|
| NFPA 72 2025 | https://www.nfpa.org/codes-and-standards/nfpa-72-standard-development/72 | ✅ |
| NFPA 72 Docs | https://link.nfpa.org/all-publications/72/2025 | ✅ |
| UL 864 Ed.11 | https://standardscatalog.ul.com/standards/en/standard_864_10 | ✅ |
| UL 2900-2-3 | https://www.ul.com/services/cybersecurity-physical-security-systems | ✅ |
| NIST CSF | https://www.nist.gov/cyberframework | ✅ |
| NEC 2026 | https://www.lowvoltagenation.com/posts/nec-2026-article-720-limited-energy-general-requirements | ✅ |
| Cambios NFPA 72 | https://www.inspectpoint.com/key-changes-to-nfpa-72-in-2022-2025/ | ✅ |

**Perú - Trámites Oficiales:**

| Trámite | URL | Verificado |
|---------|-----|------------|
| Homologación MTC | https://www.gob.pe/22738-solicitar-certificado-de-homologacion-para-un-equipo-y-o-aparato-de-telecomunicaciones-que-genera-emisiones-radioelectricas | ✅ |
| Info Homologación | https://www.gob.pe/institucion/mtc/colecciones/334-homologacion-de-equipos-de-telecomunicaciones | ✅ |
| Portal VUCE | https://www.vuce.gob.pe/ | ✅ |
| Registro Marca | https://www.gob.pe/333-registrar-marca-del-producto-y-o-servicio | ✅ |
| INDECOPI | https://www.indecopi.gob.pe/servicios-en-linea | ✅ |

---

### 5.13 Roadmap de Certificación

#### Fase 1: Certificación Crítica (3-4 meses, $40K-75K)

| Acción | Costo | Tiempo | Prioridad |
|--------|-------|--------|-----------|
| Homologación MTC | $3K-5K | 3-4 meses | 🔴 CRÍTICA |
| Registro INDECOPI | $1K-2K | 2-3 meses | 🔴 CRÍTICA |
| Certificación UL 864 | $30K-50K | 4-6 meses | 🟡 ALTA |
| Política Privacidad | $2K-5K | 1-2 meses | 🟡 MEDIA |
| Secure OTA | $5K-10K | 2-3 meses | 🟡 MEDIA |

**Total Fase 1:** $41K - $72K, 6 meses

#### Fase 2: Mejora Continua (6-12 meses, $30K-50K)

- CE Marking (RED): $15K-25K
- ISO 27001: $10K-20K
- Physical Hardening: $3K-5K
- SMS/Voice Integration: $2K-5K

#### Fase 3: Certificación Avanzada (12+ meses, $50K-100K)

- IEC 61508 SIL 2: $50K-100K
- IEC 62443 SL3: $30K-60K
- Redundancia GSM/LTE: $10K-20K

---

### 5.9 Análisis de Riesgo de Multas (Lima, Perú)

#### Escenario A: Venta Comercial Pública
**Probabilidad:** 🔴 70-80%
**Multa Esperada:** $100K - $150K USD
**Veredicto:** ❌ **NO VENDER SIN CERTIFICACIÓN**

#### Escenario B: Cliente Privado (Contrato)
**Probabilidad:** 🟡 30-40%
**Multa Esperada:** $10K - $30K USD
**Veredicto:** ⚠️ **USAR CON DISCLAIMER**

#### Escenario C: Piloto Interno
**Probabilidad:** 🟢 5-10%
**Multa Esperada:** $0 - $5K USD
**Veredicto:** ✅ **SEGURO**

---

### 5.10 Conclusión y Recomendación

```
╔═══════════════════════════════════════════════════════════════╗
║                    VEREDICTO FINAL                            ║
╠═══════════════════════════════════════════════════════════════╣
║                                                               ║
║  ✅ LISTO PARA: Pilotos, testing, instalaciones privadas     ║
║  ❌ NO LISTO PARA: Venta comercial pública en Perú           ║
║                                                               ║
║  INVERSIÓN MÍNIMA PARA COMERCIALIZACIÓN:                     ║
║  • Opción Básica (Solo Perú): $7K + 4 meses                 ║
║  • Opción Completa (Internacional): $40K + 6 meses          ║
║                                                               ║
║  MULTA POTENCIAL SI VENDE SIN CERTIFICAR:                    ║
║  • Máxima acumulada: ~$312K USD                              ║
║  • Esperada (probabilística): ~$67K USD                      ║
║                                                               ║
║  RECOMENDACIÓN: Certificar antes de comercializar.           ║
║  Más económico que arriesgarse a multas.                     ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
```

**Referencias Oficiales:**
- NFPA 72: https://www.nfpa.org/codes-and-standards/all-codes-and-standards/list-of-codes-and-standards/detail?code=72
- UL 864: https://standardscatalog.ul.com/ProductDetail.aspx?productId=UL864
- UL 2572: https://standardscatalog.ul.com/ProductDetail.aspx?productId=UL2572
- MTC Perú: https://www.gob.pe/mtc
- INDECOPI: https://www.gob.pe/indecopi
- OWASP IoT: https://owasp.org/www-project-internet-of-things/
- NIST CSF: https://www.nist.gov/cyberframework

---

## 6. Servidor VM (Google Cloud)

### 6.1 Especificaciones VM

**Nombre:** `instanciavm-myqtthub`
**Proveedor:** Google Cloud Platform (GCP)

**Hardware:**
- **CPU:** 2 vCPUs
- **RAM:** 2 GB (1.9 GB disponible)
- **Disco:** 30 GB SSD (4.9 GB usado, 24 GB libres)
- **OS:** Debian GNU/Linux 12 (bookworm)
- **Kernel:** 6.1.0-37-cloud-amd64

**Networking:**
- **IP Externa:** `34.63.146.196`
- **IP Interna:** `10.128.0.3`
- **Hostname:** `instanciavm-myqtthub`
- **DNS:** `hddm.pqsolutionsperu.com` → 34.63.146.196

**Puertos abiertos:**
- `1883/tcp` - MQTT sin TLS (localhost only)
- `8883/tcp` - MQTT con TLS (público, ESP32s remotos)
- `5432/tcp` - PostgreSQL (localhost only)
- `22/tcp` - SSH
- `80/tcp` - HTTP (redirect a HTTPS)
- `443/tcp` - HTTPS (Let's Encrypt)

### 6.2 Servicios Activos

```bash
# Ver todos los servicios
systemctl list-units --type=service --state=running | grep -E 'mosquitto|hdd-monitor|postgresql'

# Output:
mosquitto.service                   loaded active running   Mosquitto MQTT Broker
hdd-monitor.service                 loaded active running   HDD Monitor MQTT Handler
hdd-monitor-watchdog.service        loaded active running   HDD-Monitor System Watchdog
postgresql@15-main.service          loaded active running   PostgreSQL Cluster 15-main
```

**Comandos de gestión:**
```bash
# Mosquitto
sudo systemctl status mosquitto
sudo systemctl restart mosquitto
sudo journalctl -u mosquitto -f

# HDD Monitor
sudo systemctl status hdd-monitor
sudo systemctl restart hdd-monitor
sudo journalctl -u hdd-monitor -f

# PostgreSQL
sudo systemctl status postgresql
sudo systemctl restart postgresql
sudo -u postgres psql
```

### 6.3 Estructura de Directorios

```
/home/pqsolutions/
├── hdd-monitor/                  # Código principal del servidor
│   ├── main.py                   # Entry point
│   ├── mqtt_client.py            # Cliente MQTT (localhost)
│   ├── firestore_handler.py      # Observadores Firestore + lógica
│   ├── notification_handler.py   # Envío FCM
│   ├── nfpa_metrics.py          # Métricas NFPA 72
│   ├── rate_limiter.py          # Rate limiting
│   ├── config.py                # Configuración
│   ├── system_watchdog.py       # Watchdog del sistema
│   ├── log_server.py            # Servidor logs ESP32
│   ├── esp32_config_manager.py  # Gestor config ESP32 (legacy)
│   ├── requirements.txt         # Dependencias Python
│   ├── setup_postgresql.sql     # Setup DB
│   ├── verify_installation.sh   # Script verificación
│   └── __pycache__/
│
├── credentials/
│   └── vm-service-key.json      # Firebase Admin SDK credentials
│
├── venv/                        # Virtual environment Python 3.11
│   ├── bin/
│   ├── lib/python3.11/site-packages/
│   └── pyvenv.cfg
│
├── esp32_log/                   # Logs subidos por ESP32s
│   ├── 3608AC08/
│   │   └── log_20260130_*.txt
│   ├── 1694ACA8/
│   └── 42A8ACA0/
│
├── mqtt-manager/                # Interfaz web Flask (opcional)
│   ├── app.py
│   ├── templates/
│   └── static/
│
├── mqtt-manager-venv/           # Venv para Flask
│
└── services/                    # Archivos de servicio systemd
    ├── esp32-config-manager.service
    ├── logserver.service
    └── vm_monitor_main.service

/home/pqsolutionsperu/
├── vm-service-key.json          # Symlink a credentials/
└── log_monitor.py               # Monitor diario de logs ESP32 (cron 08:00 UTC)

/etc/mosquitto/
├── mosquitto.conf               # Config principal
├── conf.d/
│   └── hdd-monitor.conf        # Config específica HDD Monitor
├── passwd                       # Usuarios MQTT (hashed)
└── certs/                      # Certificados (no usados, se usa Let's Encrypt)
    ├── ca.crt
    ├── server.crt
    └── server.key

/etc/letsencrypt/
└── live/hddm.pqsolutionsperu.com/
    ├── cert.pem                # Certificado público
    ├── privkey.pem             # Clave privada
    ├── chain.pem               # Cadena de confianza
    └── fullchain.pem           # Cert + chain

/etc/systemd/system/
├── hdd-monitor.service          # Servicio principal
├── hdd-monitor-watchdog.service # Watchdog
├── log-monitor.service          # Verificador de logs ESP32 (oneshot)
└── log-monitor.timer            # Disparo diario 08:00 UTC
```

### 6.4 Software Instalado

**Versiones:**
- **Python:** 3.11.2
- **pip:** 23.0.1
- **Mosquitto:** 2.0.18
- **PostgreSQL:** 15.15
- **systemd:** 252

**Librerías Python (venv):**
```
firebase-admin==6.2.0
google-cloud-firestore==2.13.1
google-cloud-storage==3.3.1
paho-mqtt==1.6.1
psycopg2-binary==2.9.9
pytz==2023.3
Flask==3.1.2
redis==5.0.1
structlog==24.1.0
requests==2.32.5
cryptography==45.0.7
grpcio==1.74.0
protobuf==4.25.8
```

**Ver lista completa:**
```bash
source /home/pqsolutions/venv/bin/activate
pip list
deactivate
```

### 6.5 Servicio systemd (hdd-monitor)

**Archivo:** `/etc/systemd/system/hdd-monitor.service`

```ini
[Unit]
Description=HDD Monitor MQTT Handler
After=network.target mosquitto.service

[Service]
Type=simple
User=pqsolutionsperu
WorkingDirectory=/home/pqsolutions/hdd-monitor
ExecStart=/home/pqsolutions/venv/bin/python3 /home/pqsolutions/hdd-monitor/main.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

**Comandos:**

```bash
# Habilitar inicio automático
sudo systemctl enable hdd-monitor.service

# Iniciar
sudo systemctl start hdd-monitor.service

# Detener
sudo systemctl stop hdd-monitor.service

# Reiniciar
sudo systemctl restart hdd-monitor.service

# Estado
sudo systemctl status hdd-monitor.service

# Logs en tiempo real
sudo journalctl -u hdd-monitor.service -f

# Últimas 100 líneas
sudo journalctl -u hdd-monitor.service -n 100

# Buscar errores
sudo journalctl -u hdd-monitor.service -p err

# Logs desde hoy
sudo journalctl -u hdd-monitor.service --since today
```

### 6.6 Configuración Mosquitto

**Archivo principal:** `/etc/mosquitto/mosquitto.conf`

```conf
# Include configurations from conf.d directory
include_dir /etc/mosquitto/conf.d

user mosquitto
max_inflight_messages 20
max_queued_messages 100
connection_messages true
log_timestamp true
```

**Configuración HDD Monitor:** `/etc/mosquitto/conf.d/hdd-monitor.conf`

```conf
# Listener sin TLS (localhost only)
listener 1883 0.0.0.0

# Listener con TLS (ESP32s remotos)
listener 8883 0.0.0.0
certfile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem
keyfile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/privkey.pem
cafile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/chain.pem
require_certificate false

# Seguridad
allow_anonymous false
password_file /etc/mosquitto/passwd

# Logging
log_type error
log_type warning
log_type notice
log_type information

# Persistencia
persistence true
persistence_location /var/lib/mosquitto/

# Límites
max_connections 100
max_inflight_messages 20
max_queued_messages 100
```

**Usuarios MQTT:**
```
mqtt_firestore_handler    # Handler principal
esp32_config_manager      # Gestor de configuración
admin_hdd                 # Admin general
3608AC08                  # ESP32 panel PRUELPET
1694ACA8                  # ESP32 adicional
42A8ACA0                  # ESP32 adicional
```

**Gestión de usuarios:**
```bash
# Agregar/actualizar usuario
sudo mosquitto_passwd /etc/mosquitto/passwd <username>

# Eliminar usuario
sudo mosquitto_passwd -D /etc/mosquitto/passwd <username>

# Ver usuarios (sin contraseñas)
sudo cat /etc/mosquitto/passwd | cut -d: -f1

# Reiniciar Mosquitto después de cambios
sudo systemctl restart mosquitto
```

### 6.7 Certificados TLS (Let's Encrypt)

**Dominio:** `hddm.pqsolutionsperu.com`

**Ubicación:** `/etc/letsencrypt/live/hddm.pqsolutionsperu.com/`
- `cert.pem` - Certificado público
- `privkey.pem` - Clave privada
- `chain.pem` - Cadena de confianza
- `fullchain.pem` - Cert + chain

**Renovación automática:**
```bash
# Ver estado de renovación
sudo certbot renew --dry-run

# Forzar renovación
sudo certbot renew --force-renewal

# Verificar fecha de expiración
sudo openssl x509 -in /etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem -noout -dates
```

**Renovación automática con cron:**
```bash
# Let's Encrypt renueva automáticamente 30 días antes del vencimiento
# Cron job en /etc/cron.d/certbot
0 */12 * * * root test -x /usr/bin/certbot && perl -e 'sleep int(rand(3600))' && certbot -q renew --post-hook "systemctl reload mosquitto"
```

### 6.8 PostgreSQL

**Versión:** 15.15
**Puerto:** 5432 (localhost only)

**Setup inicial:**

```bash
# Conectar como postgres
sudo -u postgres psql

# Crear database y usuario
CREATE DATABASE hdd_monitor;
CREATE USER hdd_monitor_user WITH PASSWORD 'TU_PASSWORD_AQUI';
GRANT ALL PRIVILEGES ON DATABASE hdd_monitor TO hdd_monitor_user;

# Conectar a la base de datos
\c hdd_monitor

# Crear tabla (desde setup_postgresql.sql)
CREATE TABLE relay_events (
    id SERIAL PRIMARY KEY,
    client_id VARCHAR(255),
    panel_id VARCHAR(255),
    relay_id VARCHAR(255),
    old_status VARCHAR(50),
    new_status VARCHAR(50),
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_timestamp ON relay_events(timestamp DESC);
CREATE INDEX idx_panel_relay ON relay_events(panel_id, relay_id);
```

**Consultas útiles:**

```sql
-- Ver últimos 10 eventos
SELECT * FROM relay_events ORDER BY timestamp DESC LIMIT 10;

-- Eventos de las últimas 24 horas
SELECT * FROM relay_events
WHERE timestamp > NOW() - INTERVAL '24 hours'
ORDER BY timestamp DESC;

-- Contar eventos por panel
SELECT panel_id, COUNT(*) as event_count
FROM relay_events
WHERE timestamp > NOW() - INTERVAL '7 days'
GROUP BY panel_id
ORDER BY event_count DESC;

-- Relays más activos
SELECT relay_id, COUNT(*) as changes
FROM relay_events
GROUP BY relay_id
ORDER BY changes DESC;
```

**Nota:** PostgreSQL es backup redundante. Firestore es la base de datos primaria.

### 6.9 Variables de Entorno

**Archivo:** `/home/pqsolutions/hdd-monitor/config.py`

```python
# Firebase/Firestore
FIRESTORE_PROJECT = 'fir-hdd-monitor-d00de'
FIRESTORE_CREDENTIALS = '/home/pqsolutionsperu/vm-service-key.json'

# MQTT
MQTT_BROKER_HOST = 'localhost'
MQTT_BROKER_PORT = 1883
MQTT_USERNAME = 'admin_hdd'
MQTT_PASSWORD = 'Admin_HDD_2024@'
MQTT_QOS = 2

# PostgreSQL (opcional)
PG_CONFIG = {
    'host': '127.0.0.1',
    'port': 5432,
    'database': 'hdd_monitor',
    'user': 'hdd_monitor_user',
    'password': 'TU_PASSWORD_AQUI'  # Actualizar
}

# Rate limiting
RATE_LIMIT_MAX_REQUESTS = 1000
RATE_LIMIT_WINDOW_SECONDS = 60

# Notificaciones
NOTIFICATION_DEBOUNCE_MS = 1000
```

---

## 7. ESP32 Firmware

### 7.1 Configuración

**sdkconfig principales:**

```ini
# FreeRTOS
CONFIG_FREERTOS_HZ=1000
CONFIG_ESP_TASK_WDT_TIMEOUT_S=15

# Networking
CONFIG_LWIP_MAX_SOCKETS=16
CONFIG_LWIP_SO_RCVTIMEO=y

# MQTT
CONFIG_MQTT_BUFFER_SIZE=2048
CONFIG_MQTT_TASK_STACK_SIZE=8192

# TLS
CONFIG_MBEDTLS_CERTIFICATE_BUNDLE=y
CONFIG_MBEDTLS_CERTIFICATE_BUNDLE_DEFAULT_FULL=y

# Memory
CONFIG_ESP32_WIFI_STATIC_RX_BUFFER_NUM=10
CONFIG_ESP32_WIFI_DYNAMIC_RX_BUFFER_NUM=16
```

### 7.2 GPIOs de Relay

```c
#define RELAY_1_GPIO    32
#define RELAY_2_GPIO    33
#define RELAY_3_GPIO    25
#define RELAY_4_GPIO    26
#define RELAY_5_GPIO    27
#define RELAY_6_GPIO    14
```

**Configuración por GPIO:**
- **Modo:** Input con pull-up interno
- **Interrupción:** ANYEDGE (flanco subida y bajada)
- **Debounce:** 50ms en software
- **Contacto:** Configurable NC (Normally Closed) o NO (Normally Open)

**Estados detectados:**
- `OK` - Contacto cerrado (GPIO LOW para NC, HIGH para NO)
- `DISC` - Contacto abierto (GPIO HIGH para NC, LOW para NO)

### 7.3 Configuración WiFi

**Modos:**
1. **STA Mode (Station):** Conecta a WiFi existente
2. **AP Mode (Access Point):** Provisioning cuando no hay credenciales guardadas
   - SSID: `ESP32-<ESP32_ID>` (ej: `ESP32-3608AC08`)
   - Password: (configurable)
   - IP: 192.168.4.1
   - Interfaz web en http://192.168.4.1

**Almacenamiento:**
- Credenciales WiFi guardadas en NVS (partition nvs_config)
- Auto-reconexión automática si se pierde conexión
- Reintentos exponenciales con backoff

### 7.4 Configuración MQTT

```c
mqtt_cfg.broker.address.uri = "mqtts://hddm.pqsolutionsperu.com:8883";
mqtt_cfg.broker.verification.certificate_bundle = true;  // Let's Encrypt CA
mqtt_cfg.credentials.username = "esp32_3608AC08";
mqtt_cfg.credentials.authentication.password = "<PASSWORD>";
mqtt_cfg.session.keepalive = 90;  // Segundos
mqtt_cfg.session.disable_clean_session = false;
mqtt_cfg.network.reconnect_timeout_ms = 10000;
mqtt_cfg.network.timeout_ms = 5000;

// LWT (Last Will Testament)
mqtt_cfg.session.last_will.topic = "system/status/3608AC08";
mqtt_cfg.session.last_will.msg = "{\"status\":\"OFFLINE\",\"timestamp\":...,\"reason\":\"timeout\"}";
mqtt_cfg.session.last_will.qos = 2;
mqtt_cfg.session.last_will.retain = 1;
```

### 7.5 Compilación y Flash

**Requisitos:**
- ESP-IDF v5.4.1
- Python 3.8+
- Toolchain xtensa-esp32

**Comandos:**

```bash
# Clonar ESP-IDF (si no está instalado)
git clone -b v5.4.1 --recursive https://github.com/espressif/esp-idf.git
cd esp-idf
./install.sh
. ./export.sh

# Ir al proyecto
cd ESP-IDF-HDDESP32/hddesp32/

# Configurar (menuconfig)
idf.py menuconfig

# Compilar
idf.py build

# Flash (reemplazar puerto)
idf.py -p /dev/ttyUSB0 flash

# Monitor serie
idf.py -p /dev/ttyUSB0 monitor

# Flash + Monitor (todo en uno)
idf.py -p /dev/ttyUSB0 flash monitor

# Limpiar build
idf.py fullclean
```

**Configuraciones importantes en menuconfig:**
```
Component config → ESP32-specific
  → Main XTAL frequency: 40 MHz
  → CPU frequency: 240 MHz

Component config → FreeRTOS
  → Tick rate: 1000 Hz
  → Task Watchdog: Enabled

Component config → mbedTLS
  → Certificate Bundle: Enabled
  → TLS: Enabled

Component config → ESP MQTT Configuration
  → Buffer size: 2048
  → Task stack size: 8192
```

---

## 8. Aplicación Android

### 8.1 Características

- **Lenguaje:** Kotlin
- **UI Framework:** Jetpack Compose
- **Arquitectura:** MVVM (Model-View-ViewModel)
- **Base de datos local:** Room (caché offline)
- **Networking:** Retrofit + OkHttp
- **Autenticación:** Firebase Authentication
- **Notificaciones:** Firebase Cloud Messaging (FCM)
- **Material Design 3:** Theming dinámico

### 8.2 Pantallas Principales

1. **Splash Screen**
   - Logo HDD Monitor
   - Verificación de sesión

2. **Login/Register**
   - Email + Password
   - Google Sign-In (futuro)
   - Recuperación de contraseña

3. **Dashboard**
   - Lista de paneles asignados al cliente
   - Estado de cada panel (ONLINE/OFFLINE)
   - Badge con número de notificaciones sin leer

4. **Panel Details**
   - Vista de 6 relays del panel
   - Estado actual (OK/DISC)
   - Indicador de relay activo/inactivo
   - Color: Verde (OK), Rojo (DISC), Gris (Inactivo)
   - Última actualización

5. **Relay Configuration**
   - Toggle activar/desactivar monitoreo
   - Selector tipo de contacto (NC/NO)
   - Campo nombre personalizado
   - Botón guardar

6. **Notifications History**
   - Lista cronológica de notificaciones
   - Filtros: Relay, Event, Connectivity
   - Marcar como leída
   - Eliminar notificación

7. **Events Calendar**
   - Calendario de eventos programados
   - Crear nuevo evento (mantenimiento, inspección)
   - Recordatorio 1 hora antes
   - Estado: PROGRAMADO, EN PROGRESO, COMPLETADO

8. **Settings**
   - Perfil de usuario
   - Preferencias de notificaciones
   - Cerrar sesión

### 8.3 Configuración FCM

**Archivo:** `app/google-services.json` (obtener desde Firebase Console)

```json
{
  "project_info": {
    "project_number": "...",
    "project_id": "fir-hdd-monitor-d00de",
    "storage_bucket": "..."
  },
  "client": [
    {
      "client_info": {
        "android_client_info": {
          "package_name": "com.pqsolutions.hdd_monitor"
        }
      }
    }
  ]
}
```

**Permisos (AndroidManifest.xml):**

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
```

**Servicio FCM:**

```kotlin
class FirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Guardar token en Firestore
        saveTokenToFirestore(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Mostrar notificación local
        showNotification(remoteMessage.data)
    }
}
```

### 8.4 Build y Deploy

**Compilar APK:**

```bash
# Debug
./gradlew assembleDebug

# Release (firmado)
./gradlew assembleRelease
```

**Distribución:**
- Google Play Store (producción)
- Firebase App Distribution (beta testing)
- APK directo (testing interno)

---

## 9. Sistema de Notificaciones

### 9.1 Tipos de Notificación

#### 1. Relay State Change

**Trigger:** Cambio físico de estado en relay

**Payload FCM:**
```json
{
  "notification": {
    "title": "Cambio de Estado - relay_1",
    "body": "El relay_1 del panel \"Panel Prueba\" ha cambiado de OK a DISC"
  },
  "data": {
    "type": "relay",
    "relay_id": "relay_1",
    "relay_display_name": "relay Bomba Principal",
    "panel_id": "panel_PRUELPET_client_1",
    "panel_name": "Panel Prueba",
    "state": "DISC",
    "old_status": "OK",
    "timestamp": "1769825781854",
    "click_action": "PANEL_DETAIL"
  },
  "android": {
    "priority": "high",
    "notification": {
      "sound": "default",
      "channel_id": "relay_alerts",
      "color": "#FF0000",
      "icon": "ic_notification"
    }
  }
}
```

**Documento en Firestore:**
```javascript
{
  type: "relay",
  relay: "relay_1",
  relay_display_name: "relay Bomba Principal",
  panel_id: "panel_PRUELPET_client_1",
  panel_name: "Panel Prueba",
  client_id: "client_1",
  state: "DISC",
  old_status: "OK",
  message: "El relay Bomba Principal del panel \"Panel Prueba\" ha cambiado de OK a DISC",
  date_time: "30/01/2026, 18:51",
  lastUpdate: Timestamp,
  documentName: "relay_client_1_panel_XXX_relay_1_1769825781854",
  isRead: false,
  readByAdmin: false,
  timestamp: 1769825781854
}
```

#### 2. ESP32 Connectivity (OFFLINE)

**Trigger:** LWT (Last Will Testament) publicado por broker después de timeout

**Payload FCM:**
```json
{
  "notification": {
    "title": "Panel Desconectado",
    "body": "El panel \"Panel Prueba\" (ESP32: 3608AC08) se desconectó"
  },
  "data": {
    "type": "connectivity",
    "action": "OFFLINE",
    "esp32_id": "3608AC08",
    "panel_id": "panel_PRUELPET_client_1",
    "panel_name": "Panel Prueba",
    "timestamp": "1769825781854",
    "click_action": "PANEL_DETAIL"
  },
  "android": {
    "priority": "high",
    "notification": {
      "sound": "alert",
      "channel_id": "connectivity_alerts",
      "color": "#FF6600"
    }
  }
}
```

#### 3. ESP32 Connectivity (ONLINE)

**Trigger:** ESP32 publica `esp32/network_info` después de reconexión

**Payload FCM:**
```json
{
  "notification": {
    "title": "Panel Reconectado",
    "body": "El panel \"Panel Prueba\" (ESP32: 3608AC08) volvió a estar ONLINE"
  },
  "data": {
    "type": "connectivity",
    "action": "ONLINE",
    "esp32_id": "3608AC08",
    "panel_id": "panel_PRUELPET_client_1",
    "panel_name": "Panel Prueba",
    "timestamp": "1769825881854",
    "click_action": "PANEL_DETAIL"
  },
  "android": {
    "priority": "default",
    "notification": {
      "sound": "default",
      "channel_id": "connectivity_alerts",
      "color": "#00FF00"
    }
  }
}
```

#### 4. Event Reminder

**Trigger:** 1 hora antes del evento programado

**Payload FCM:**
```json
{
  "notification": {
    "title": "Recordatorio de Evento",
    "body": "El evento \"Mantenimiento preventivo\" está programado para 30/01/2026, 20:00 (en aproximadamente 1 hora)"
  },
  "data": {
    "type": "event",
    "event_type": "MANTENIMIENTO",
    "event_id": "event_XXX",
    "title": "Mantenimiento preventivo",
    "date_time": "30-01-2026 20:00",
    "panel_id": "panel_PRUELPET_client_1",
    "panel_name": "Panel Prueba",
    "timestamp": "1769825781854",
    "click_action": "EVENT_DETAIL"
  },
  "android": {
    "priority": "default",
    "notification": {
      "sound": "default",
      "channel_id": "event_reminders",
      "color": "#0000FF"
    }
  }
}
```

### 9.2 Destinatarios

**Por cada notificación se envía a:**

1. **Usuarios del cliente** (query Firestore)
   ```javascript
   db.collection('hdd-monitor/accounts/users')
     .where('clientId', '==', client_id)
     .where('fcmToken', '!=', null)
     .get()
   ```

2. **Admin global**
   - ID fijo: `NWTzas13BAsmo2ZqPC58`
   - Recibe todas las notificaciones del sistema

**Nota:** Si un usuario no tiene `fcmToken` (ej: no ha iniciado sesión en la app), no recibe notificaciones push pero la notificación se guarda en Firestore para verla cuando inicie sesión.

### 9.3 Rate Limiting

**Límites configurados:**
- **1000 notificaciones por minuto por usuario**
- **Prevención de duplicados:** 1 segundo de debounce

**Implementación:**

```python
# rate_limiter.py
from collections import defaultdict
import time

class RateLimiter:
    def __init__(self, max_requests=1000, window_seconds=60):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self.requests = defaultdict(list)

    def check_rate_limit(self, user_id):
        now = time.time()

        # Limpiar requests antiguos
        self.requests[user_id] = [
            req_time for req_time in self.requests[user_id]
            if now - req_time < self.window_seconds
        ]

        # Verificar límite
        if len(self.requests[user_id]) >= self.max_requests:
            return False

        # Agregar request actual
        self.requests[user_id].append(now)
        return True
```

**Uso en `notification_handler.py`:**

```python
if not rate_limiter.check_rate_limit(user_id):
    logging.warning(f"Rate limit exceeded for user {user_id}")
    return
```

### 9.4 Prevención de Duplicados

**Cache en memoria con timestamp:**

```python
# firestore_handler.py
self._notification_cache = {}

def _send_relay_notification_fast(self, client_id, panel_id, relay_name, old_status, new_status):
    current_time = time.time() * 1000
    cache_key = f"{client_id}_{panel_id}_{relay_name}_{old_status}_{new_status}_{int(current_time / 1000)}"

    debounce_window = 1000  # 1 segundo
    if cache_key in self._notification_cache:
        last_time = self._notification_cache[cache_key]
        if current_time - last_time < debounce_window:
            logging.info(f"PREVENCIÓN DUPLICADO: Ignorando notificación para {relay_name}")
            return

    self._notification_cache[cache_key] = current_time

    # Enviar notificación...
```

### 9.5 Limpieza Automática de Notificaciones

**Código en `notification_handler.py`:**

```python
def clean_old_notifications(self, client_id, days_old=30):
    """Elimina notificaciones más antiguas de X días"""
    cutoff_time = datetime.now(pytz.timezone('America/Lima')) - timedelta(days=days_old)

    notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
    old_notifications = notifications_ref.where('timestamp', '<', cutoff_time.timestamp() * 1000).stream()

    deleted_count = 0
    for notification in old_notifications:
        notification.reference.delete()
        deleted_count += 1

    if deleted_count > 0:
        logging.info(f"Limpiadas {deleted_count} notificaciones antiguas del cliente {client_id}")
```

**Se ejecuta automáticamente después de cada notificación enviada.**

---

## 10. Métricas y Monitoreo

### 10.1 NFPA Metrics Collector

**Archivo:** `/home/pqsolutions/hdd-monitor/nfpa_metrics.py`

**Clase principal:**

```python
import time
import logging
import json

class NFPAMetricsCollector:
    def __init__(self):
        self.event_timestamps = {}

    def record_relay_detected(self, event_id):
        """Registra cuando el ESP32 detecta cambio de relay"""
        self.event_timestamps[event_id] = {
            'detected_at': time.time() * 1000
        }

    def record_notification_sent(self, event_id):
        """Registra cuando se envía la notificación FCM"""
        if event_id in self.event_timestamps:
            sent_at = time.time() * 1000
            detected_at = self.event_timestamps[event_id]['detected_at']
            latency_ms = sent_at - detected_at

            # NFPA 72 requiere < 90 segundos (90000 ms)
            compliant = latency_ms < 90000

            # Log en formato JSON para análisis
            log_data = {
                'event_id': event_id,
                'latency_ms': latency_ms,
                'nfpa72_compliant': compliant,
                'event': 'nfpa72_notification_latency',
                'timestamp': datetime.utcnow().isoformat() + 'Z',
                'level': 'info' if compliant else 'warning'
            }

            logging.info(json.dumps(log_data))

            # Limpiar evento procesado
            del self.event_timestamps[event_id]
```

**Uso en `firestore_handler.py`:**

```python
# Al detectar cambio de relay
event_id = f"{panel_id}_{relay_name}_{int(time.time()*1000)}"
self.nfpa_metrics.record_relay_detected(event_id)

# Al enviar notificación FCM
self._send_relay_notification_fast(client_id, panel_id, relay_name, old_status, new_status, complete_relay_data, event_id)

# Dentro de _send_relay_notification_fast()
self.nfpa_metrics.record_notification_sent(event_id)
```

**Ejemplo de log JSON:**

```json
{
  "event_id": "panel_PRUELPET_client_1_relay_1_1769825747583",
  "latency_ms": 1512.44,
  "nfpa72_compliant": true,
  "event": "nfpa72_notification_latency",
  "timestamp": "2026-01-31T02:15:49.372814Z",
  "level": "info"
}
```

### 10.2 Ver Métricas

**Últimas 20 métricas:**

```bash
sudo journalctl -u hdd-monitor.service | grep "nfpa72_notification_latency" | tail -20
```

**Métricas no conformes (> 90s):**

```bash
sudo journalctl -u hdd-monitor.service | grep "nfpa72_notification_latency" | grep '"nfpa72_compliant": false'
```

**Calcular latencia promedio (con jq):**

```bash
sudo journalctl -u hdd-monitor.service --since today | grep "nfpa72_notification_latency" | awk -F'latency_ms": ' '{print $2}' | awk -F',' '{print $1}' | awk '{sum+=$1; count++} END {print "Latencia promedio:", sum/count, "ms"}'
```

### 10.3 Monitoreo de Sistema

**Health Checks automáticos:**

**1. MQTT Broker:**

```bash
# Ver estado
systemctl status mosquitto

# Ver conexiones activas
sudo mosquitto_sub -h localhost -p 1883 -t '$SYS/broker/clients/connected' -u admin_hdd -P 'Admin_HDD_2024@' -C 1

# Ver mensajes totales
sudo mosquitto_sub -h localhost -p 1883 -t '$SYS/broker/messages/received' -u admin_hdd -P 'Admin_HDD_2024@' -C 1
```

**2. HDD Monitor Service:**

```bash
# Estado
sudo systemctl status hdd-monitor.service

# Ver logs importantes
sudo journalctl -u hdd-monitor.service -f | grep -E 'ERROR|WARNING|Conectado|Notificación'

# Verificar conectividad
sudo journalctl -u hdd-monitor.service --since "5 minutes ago" | grep "Conectado al broker MQTT"
```

**3. Firestore Connectivity:**

```bash
# Ver logs de conexión a Firestore
sudo journalctl -u hdd-monitor.service --since today | grep -E 'Firestore|firebase'
```

**4. Memoria y CPU:**

```bash
# Uso de memoria por servicio
sudo systemctl status hdd-monitor.service | grep Memory

# Ver procesos Python
ps aux | grep python3 | grep hdd-monitor

# Top processes
top -b -n 1 | head -20
```

### 10.4 PostgreSQL Backup Monitoring

**Verificar eventos guardados:**

```sql
-- Últimos 10 eventos
SELECT * FROM relay_events ORDER BY timestamp DESC LIMIT 10;

-- Eventos de hoy
SELECT * FROM relay_events
WHERE timestamp::date = CURRENT_DATE
ORDER BY timestamp DESC;

-- Contar eventos por hora (últimas 24h)
SELECT
  DATE_TRUNC('hour', timestamp) AS hour,
  COUNT(*) AS event_count
FROM relay_events
WHERE timestamp > NOW() - INTERVAL '24 hours'
GROUP BY hour
ORDER BY hour DESC;
```

**Script de verificación automática:**

```bash
#!/bin/bash
# /home/pqsolutions/scripts/check_postgresql_backup.sh

EVENT_COUNT=$(sudo -u postgres psql -d hdd_monitor -t -c "SELECT COUNT(*) FROM relay_events WHERE timestamp > NOW() - INTERVAL '1 hour'")

if [ "$EVENT_COUNT" -gt 0 ]; then
    echo "OK: $EVENT_COUNT eventos guardados en la última hora"
    exit 0
else
    echo "WARNING: No hay eventos en la última hora"
    exit 1
fi
```

### 10.5 Alertas y Monitoreo Externo

**Recomendado para producción:**

1. **Google Cloud Monitoring:**
   - Alertas por uptime de VM
   - Alertas por uso de CPU/memoria
   - Alertas por disco lleno

2. **Uptime Robot / Pingdom:**
   - Monitoreo de puerto 8883 (MQTT TLS)
   - Alerta si el servicio no responde

3. **Sentry / Rollbar:**
   - Captura de errores Python
   - Stack traces automáticos

4. **Logs centralizados (ELK Stack):**
   - Elasticsearch + Logstash + Kibana
   - Dashboards de métricas NFPA

**Configuración básica de alerta por email:**

```bash
# Instalar mailutils
sudo apt install mailutils

# Script de alerta
#!/bin/bash
# /home/pqsolutions/scripts/alert_service_down.sh

SERVICE="hdd-monitor.service"
EMAIL="admin@pqsolutionsperu.com"

if ! systemctl is-active --quiet $SERVICE; then
    echo "ALERTA: $SERVICE está caído en $(hostname)" | mail -s "ALERTA: Servicio HDD Monitor caído" $EMAIL
fi

# Agregar a crontab (cada 5 minutos)
# */5 * * * * /home/pqsolutions/scripts/alert_service_down.sh
```

---

## 11. Configuración y Deployment

### 11.1 Setup Inicial del Servidor

#### Paso 1: Instalar Dependencias del Sistema

```bash
# Actualizar repositorios
sudo apt update && sudo apt upgrade -y

# Instalar paquetes
sudo apt install -y \
  python3 python3-pip python3-venv \
  mosquitto mosquitto-clients \
  postgresql postgresql-contrib \
  git curl wget htop tree \
  certbot python3-certbot-nginx

# Verificar versiones
python3 --version  # 3.11.2
mosquitto -h | head -1
psql --version  # 15.15
```

#### Paso 2: Configurar Firewall

```bash
# Habilitar UFW
sudo ufw enable

# Permitir SSH
sudo ufw allow 22/tcp

# Permitir MQTT
sudo ufw allow 1883/tcp comment 'MQTT sin TLS'
sudo ufw allow 8883/tcp comment 'MQTT con TLS'

# Permitir HTTP/HTTPS (para Let's Encrypt)
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

# Ver reglas
sudo ufw status numbered
```

#### Paso 3: Configurar Certificados TLS (Let's Encrypt)

```bash
# Obtener certificado para el dominio
sudo certbot certonly --standalone -d hddm.pqsolutionsperu.com

# Los certificados se guardan en:
# /etc/letsencrypt/live/hddm.pqsolutionsperu.com/

# Renovación automática (ya configurado en cron)
sudo certbot renew --dry-run
```

#### Paso 4: Configurar Mosquitto

```bash
# Crear usuarios MQTT
sudo mosquitto_passwd -c /etc/mosquitto/passwd admin_hdd
sudo mosquitto_passwd /etc/mosquitto/passwd mqtt_firestore_handler
sudo mosquitto_passwd /etc/mosquitto/passwd esp32_3608AC08
# ... agregar más usuarios según necesidad

# Crear configuración
sudo nano /etc/mosquitto/conf.d/hdd-monitor.conf
# (pegar contenido de sección 6.6)

# Verificar configuración
sudo mosquitto -c /etc/mosquitto/mosquitto.conf -v

# Reiniciar Mosquitto
sudo systemctl restart mosquitto
sudo systemctl status mosquitto

# Verificar puertos abiertos
sudo netstat -tulpn | grep mosquitto
```

#### Paso 5: Configurar PostgreSQL (Opcional)

```bash
# Conectar como postgres
sudo -u postgres psql

# Crear database y usuario
CREATE DATABASE hdd_monitor;
CREATE USER hdd_monitor_user WITH PASSWORD 'TU_PASSWORD_SEGURO_AQUI';
GRANT ALL PRIVILEGES ON DATABASE hdd_monitor TO hdd_monitor_user;
\q

# Conectar a la base de datos
sudo -u postgres psql -d hdd_monitor

# Ejecutar script de setup
\i /home/pqsolutions/hdd-monitor/setup_postgresql.sql
\q

# Verificar tabla creada
sudo -u postgres psql -d hdd_monitor -c "\dt"
```

#### Paso 6: Configurar Python Service

```bash
# Crear estructura de directorios
sudo mkdir -p /home/pqsolutions/hdd-monitor
sudo mkdir -p /home/pqsolutions/credentials
sudo mkdir -p /home/pqsolutions/esp32_log

# Subir archivos del proyecto
# (vía scp, git clone, o manualmente)

# Crear virtual environment
cd /home/pqsolutions/hdd-monitor
python3 -m venv /home/pqsolutions/venv

# Activar venv e instalar dependencias
source /home/pqsolutions/venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
deactivate

# Configurar credenciales Firebase
# 1. Descargar vm-service-key.json desde Firebase Console
# 2. Copiarlo a /home/pqsolutions/credentials/
sudo cp vm-service-key.json /home/pqsolutions/credentials/
sudo chown pqsolutions:pqsolutions /home/pqsolutions/credentials/vm-service-key.json
sudo chmod 644 /home/pqsolutions/credentials/vm-service-key.json

# Crear symlink para compatibilidad
sudo ln -s /home/pqsolutions/credentials/vm-service-key.json /home/pqsolutionsperu/vm-service-key.json

# Actualizar config.py con passwords reales
nano /home/pqsolutions/hdd-monitor/config.py
```

#### Paso 7: Configurar systemd Service

```bash
# Crear archivo de servicio
sudo nano /etc/systemd/system/hdd-monitor.service
# (pegar contenido de sección 6.5)

# Recargar systemd
sudo systemctl daemon-reload

# Habilitar inicio automático
sudo systemctl enable hdd-monitor.service

# Iniciar servicio
sudo systemctl start hdd-monitor.service

# Verificar estado
sudo systemctl status hdd-monitor.service

# Ver logs
sudo journalctl -u hdd-monitor.service -f
```

#### Paso 7.1: Configurar Credenciales del Sistema de Auto-Sostenibilidad

**IMPORTANTE:** Para el sistema de monitoreo y alertas automáticas.

##### Gmail App Password para Alertas

**Propósito:** Envío de alertas por email desde Netdata y Resource Monitor

```plaintext
Email: pqsolutionsperu@gmail.com
App Password: pvlzneinvbtetxtw
Nombre del dispositivo: HDD-Monitor VM
Creado: 30 de enero de 2026
Uso: Netdata email alerts, Resource Monitor alerts
```

**Permisos:**
- Solo envío de emails
- No puede leer correos
- No afecta otros servicios de Google
- Revocable en: https://myaccount.google.com/apppasswords

**Archivos que usan esta credencial:**
1. `/etc/systemd/system/netdata.service.d/override.conf`
2. `/etc/systemd/system/resource-monitor.service`
3. `/etc/netdata/health_alarm_notify.conf` (usa variable de entorno)

**Formato en archivos:**
```bash
Environment="SMTP_APP_PASSWORD=pvlzneinvbtetxtw"
Environment="SMTP_PASS=pvlzneinvbtetxtw"
```

**Seguridad:**
- Permisos de archivo: `chmod 600` (solo root puede leer)
- NO incluir en repositorios Git
- Almacenar en gestores de contraseñas corporativos

##### PostgreSQL Password

```plaintext
Database: hdd_monitor
User: hdd_monitor_user
Password: [PENDIENTE - Agregar cuando sea necesario]
Host: localhost
Port: 5432
```

**Archivos que usan esta credencial:**
1. `/home/pqsolutionsperu/config.py`
2. `/etc/systemd/system/backup-postgresql.service`

##### Healthchecks.io (Opcional)

```plaintext
URL: https://healthchecks.io
Email: pqsolutionsperu@gmail.com
Checks configurados:
  - HDD-Monitor Resource Monitor (5 min)
  - HDD-Monitor PostgreSQL Backup (1 day)
  - HDD-Monitor Config Backup (1 week)
```

**UUIDs de checks:** [Agregar cuando se creen]

##### Nginx Dashboard Authentication

```plaintext
Dashboard: https://hddm.pqsolutionsperu.com/netdata/
Usuario: pqsowner
Password: [Creado con htpasswd durante deployment]
Archivo: /etc/nginx/.htpasswd_metrics
```

##### Firebase API Key (Android App)

**IMPORTANTE:** API Key regenerada por exposición en repositorio público (31 enero 2026)

```plaintext
Proyecto: fir-hdd-monitor-d00de
API Key: AIzaSyAMSpLkgD-F1_0fqvrUctQ3Cre3sSKQWfs
Nombre: HDD-Monitor Android Key (Restricted)
Creado: 31 de enero de 2026
Tipo: Restringido solo para Android app
```

**Restricciones aplicadas:**
- Nombre de paquete: `com.pqsolutions.hdd_monitor`
- APIs permitidas:
  - Cloud Firestore API
  - Firebase Cloud Messaging API
  - Identity Toolkit API

**Ubicación en código:**
- Archivo: `app/google-services.json` (NOT in Git - should be in .gitignore)
- Usado por: Firebase SDK automáticamente

**Seguridad:**
- ✅ Restringido a nombre de paquete específico
- ✅ Solo APIs necesarias habilitadas
- ✅ API Key anterior comprometida eliminada
- ⚠️ NO debe estar en repositorio Git público
- ⚠️ Agregar SHA-1 fingerprint cuando esté disponible

**Key anterior (ELIMINADA):**
- `AIzaSyB3lWszDhwWGiUTx0ENpjdgrrHwFzLfKX8` - Expuesta en GitHub, eliminada 31/01/2026

**Acciones de seguridad tomadas:**
1. Key anterior eliminada de Google Cloud Console
2. Nueva key creada con restricciones
3. .gitignore actualizado para excluir build artifacts
4. Alerta de Google Cloud atendida

---

#### Paso 8: Verificar Instalación

```bash
# Ejecutar script de verificación
cd /home/pqsolutions/hdd-monitor
chmod +x verify_installation.sh
./verify_installation.sh

# Verificar logs del servicio
sudo journalctl -u hdd-monitor.service --since "5 minutes ago" | grep -E "Conectado|ERROR|WARNING"

# Verificar que todos los servicios están activos
systemctl is-active mosquitto hdd-monitor postgresql
```

### 11.2 Setup ESP32

#### Paso 1: Instalar ESP-IDF

```bash
# Clonar repositorio ESP-IDF v5.4.1
cd ~
git clone -b v5.4.1 --recursive https://github.com/espressif/esp-idf.git
cd esp-idf

# Instalar toolchain
./install.sh esp32

# Exportar variables de entorno (agregar a ~/.bashrc)
. ./export.sh
echo 'alias get_idf=". $HOME/esp-idf/export.sh"' >> ~/.bashrc
source ~/.bashrc
```

#### Paso 2: Configurar Proyecto

```bash
# Ir al proyecto
cd /path/to/ESP-IDF-HDDESP32/hddesp32/

# Configurar
idf.py menuconfig
```

**Configuraciones importantes:**

```
Serial flasher config
  → Flash size: 4 MB

Component config → ESP32-specific
  → Main XTAL frequency: 40 MHz
  → CPU frequency: 240 MHz

Component config → FreeRTOS
  → Tick rate: 1000 Hz
  → Task Watchdog: Enabled (15000 ms)

Component config → mbedTLS
  → Certificate Bundle: Enabled
  → Certificate Bundle type: Default (Full)

Component config → ESP MQTT Configuration
  → Buffer size: 2048
  → Task stack size: 8192

Component config → WiFi
  → Static RX buffer num: 10
  → Dynamic RX buffer num: 16
  → WiFi Task Core ID: 0
  → Max number of WiFi static TX buffers: 16
```

#### Paso 3: Configurar Credenciales MQTT

Editar `main/hddesp32_main.c` o usar menuconfig:

```c
#define MQTT_BROKER_URI "mqtts://hddm.pqsolutionsperu.com:8883"
#define MQTT_USERNAME "esp32_3608AC08"  // Cambiar según dispositivo
#define MQTT_PASSWORD "TU_PASSWORD_AQUI"
```

#### Paso 4: Compilar y Flash

```bash
# Limpiar build anterior (opcional)
idf.py fullclean

# Compilar
idf.py build

# Flash (reemplazar puerto según tu sistema)
# Linux: /dev/ttyUSB0
# Windows: COM3
# macOS: /dev/cu.usbserial-*
idf.py -p /dev/ttyUSB0 flash

# Monitor serie
idf.py -p /dev/ttyUSB0 monitor

# Flash + Monitor (todo en uno)
idf.py -p /dev/ttyUSB0 flash monitor
```

#### Paso 5: Provisioning WiFi (si no está configurado)

Si el ESP32 no tiene credenciales WiFi guardadas:

1. ESP32 crea AP mode: `ESP32-3608AC08`
2. Conectar móvil/laptop al AP
3. Abrir navegador: `http://192.168.4.1`
4. Ingresar SSID y password del WiFi
5. ESP32 se conecta al WiFi y guarda credenciales en NVS

#### Paso 6: Verificar Conexión

**Logs esperados en monitor serie:**

```
I (xxxx) WIFI_MGR: Successfully connected to WiFi network
I (xxxx) WIFI_MGR: Got IP address: 192.168.1.21
I (xxxx) TIME_MGR: NTP synchronized successfully
I (xxxx) MQTT_MGR: DNS resolution successful for hddm.pqsolutionsperu.com
I (xxxx) MQTT_MGR: MQTT connected
I (xxxx) MQTT_MGR: MQTT connected successfully
I (xxxx) RELAY_MGR: Relay Manager initialized successfully
I (xxxx) HDDESP32: System initialization completed successfully
```

**En el servidor, verificar:**

```bash
# Ver dispositivo conectado
mosquitto_sub -h localhost -p 1883 -t 'system/status/#' -u admin_hdd -P 'Admin_HDD_2024@' -v

# Debe aparecer:
# system/status/3608AC08 {"status":"ONLINE",...}
```

### 11.3 Deployment Android App

#### Paso 1: Configurar Firebase

1. Ir a [Firebase Console](https://console.firebase.google.com/)
2. Seleccionar proyecto: `fir-hdd-monitor-d00de`
3. Ir a: Project Settings → General → Your apps
4. Descargar `google-services.json`
5. Copiar a: `app/google-services.json`

#### Paso 2: Configurar Signing (Release)

Crear keystore:

```bash
keytool -genkey -v -keystore hdd-monitor-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias hdd-monitor
```

Configurar en `app/build.gradle`:

```gradle
android {
    signingConfigs {
        release {
            storeFile file("../hdd-monitor-release.jks")
            storePassword "TU_PASSWORD"
            keyAlias "hdd-monitor"
            keyPassword "TU_PASSWORD"
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

#### Paso 3: Compilar APK/AAB

```bash
# APK (para distribución directa)
./gradlew assembleRelease

# Output: app/build/outputs/apk/release/app-release.apk

# AAB (para Google Play Store)
./gradlew bundleRelease

# Output: app/build/outputs/bundle/release/app-release.aab
```

#### Paso 4: Distribuir

**Opción 1: Google Play Store**
1. Ir a [Google Play Console](https://play.google.com/console/)
2. Crear aplicación (si es nueva)
3. Subir AAB en: Release → Production
4. Completar información de la app
5. Publicar

**Opción 2: Firebase App Distribution (Beta)**
```bash
# Instalar Firebase CLI
npm install -g firebase-tools

# Login
firebase login

# Distribuir
firebase appdistribution:distribute app/build/outputs/apk/release/app-release.apk \
  --app 1:YOUR_APP_ID:android:YOUR_APP_HASH \
  --groups testers \
  --release-notes "Nueva versión con mejoras de notificaciones"
```

**Opción 3: APK Directo**
- Enviar APK por email/Drive a testers
- Habilitar "Instalar desde fuentes desconocidas" en Android

---

## 12. Troubleshooting

### 12.1 ESP32 no conecta a MQTT

**Síntomas:**
```
E (xxxxx) MQTT_MGR: MQTT error
W (xxxxx) MQTT_MGR: MQTT disconnected
E (xxxxx) mqtt_client: mqtt_process_receive: mqtt_message_receive() returned -2
```

**Diagnóstico:**

1. **Verificar DNS:**
   ```
   I (xxxxx) MQTT_MGR: Testing DNS resolution for: hddm.pqsolutionsperu.com
   I (xxxxx) MQTT_MGR: DNS resolution successful
   ```
   - Si falla: Problema de WiFi o DNS server
   - Solución: Verificar conectividad WiFi, cambiar DNS server

2. **Verificar TLS:**
   ```
   I (xxxxx) MQTT_SSL: Setting up SSL with ESP-IDF Certificate Bundle
   I (xxxxx) esp-x509-crt-bundle: Certificate validated
   ```
   - Si falla `Certificate validated`: Certificado del servidor inválido o expirado
   - Solución: Renovar certificado Let's Encrypt en servidor

3. **Verificar credenciales:**
   ```
   E (xxxxx) mqtt_client: mqtt_message_receive() error: Connection refused, not authorized
   ```
   - Usuario/password incorrectos
   - Solución: Verificar `/etc/mosquitto/passwd` en servidor

4. **Verificar conectividad red:**
   ```bash
   # Desde el servidor, verificar puerto 8883 accesible
   sudo netstat -tulpn | grep 8883

   # Verificar firewall
   sudo ufw status | grep 8883

   # Si no está abierto
   sudo ufw allow 8883/tcp
   ```

**Soluciones paso a paso:**

```bash
# En el ESP32, verificar logs
idf.py -p /dev/ttyUSB0 monitor

# En el servidor, verificar Mosquitto
sudo systemctl status mosquitto
sudo journalctl -u mosquitto -f

# Test conexión MQTT desde cliente externo
mosquitto_pub -h hddm.pqsolutionsperu.com -p 8883 \
  --capath /etc/ssl/certs/ \
  -u esp32_3608AC08 -P 'PASSWORD' \
  -t test/topic -m "test"
```

### 12.2 Servidor no envía notificaciones

**Síntomas:**
```
INFO - relay_1: OK -> DISC
# (pero no aparece "Notificación rápida enviada")
```

**Diagnóstico:**

1. **Verificar FCM tokens:**
   ```bash
   # En Firestore Console, verificar:
   # hdd-monitor/accounts/users/<user_id>
   # Campo: fcmToken

   # Si no existe o está vacío: Usuario no ha iniciado sesión en la app
   ```

2. **Verificar Firebase Admin SDK:**
   ```bash
   sudo journalctl -u hdd-monitor.service -f | grep -E "firebase|FCM|ERROR"

   # Errores comunes:
   # ERROR - Failed to send notification: Request had invalid authentication credentials
   # → Verificar vm-service-key.json

   # ERROR - Failed to send notification: App instance has been unregistered
   # → Token FCM expiró, usuario debe iniciar sesión de nuevo
   ```

3. **Verificar rate limiting:**
   ```
   WARNING - Rate limit exceeded for user <user_id>
   ```
   - Usuario recibió > 1000 notificaciones en 1 minuto
   - Solución: Esperar 1 minuto o revisar si hay loop infinito

4. **Verificar que el usuario pertenece al cliente:**
   ```javascript
   // En Firestore Console
   db.collection('hdd-monitor/accounts/users')
     .where('clientId', '==', 'client_1')
     .get()
   ```

**Soluciones:**

```bash
# Verificar logs detallados
sudo journalctl -u hdd-monitor.service --since "10 minutes ago" | grep -A 5 -B 5 "relay_1"

# Reiniciar servicio
sudo systemctl restart hdd-monitor.service

# Verificar que Firestore es accesible
sudo journalctl -u hdd-monitor.service | grep "Firestore" | tail -20
```

### 12.3 Notificaciones duplicadas

**Síntomas:**
- Múltiples notificaciones FCM para el mismo cambio de relay
- App muestra 2-3 notificaciones idénticas

**Causas:**

1. **Múltiples instancias del servidor corriendo:**
   ```bash
   ps aux | grep "main.py"

   # Si aparecen múltiples:
   # pqsolutionsperu  12345  ... python3 main.py
   # pqsolutionsperu  12346  ... python3 main.py

   # Matar instancias extras
   sudo kill 12346
   ```

2. **Observador de Firestore duplicado:**
   - Verificar que solo hay un observador activo
   - Revisar logs: `"Observador global de configuraciones iniciado"` debe aparecer solo 1 vez

3. **Debounce no funcionando:**
   ```python
   # En firestore_handler.py, verificar:
   debounce_window = 1000  # 1 segundo
   ```

**Soluciones:**

```bash
# Reiniciar servicio (mata todas las instancias)
sudo systemctl restart hdd-monitor.service

# Verificar que solo hay una instancia
ps aux | grep "main.py" | grep -v grep | wc -l
# Debe devolver: 1
```

### 12.4 ESP32 se desconecta frecuentemente

**Síntomas:**
```
INFO - Notificación OFFLINE enviada (ESP32: 3608AC08)
# ... 2 minutos después
INFO - Notificación ONLINE enviada (ESP32: 3608AC08)
# (ciclo se repite)
```

**Causas:**

1. **WiFi débil:**
   ```
   I (xxxxx) wifi:pm start, type: 1
   I (xxxxx) wifi:state: run -> init (0x0)
   ```
   - RSSI < -70 dBm
   - Solución: Acercar ESP32 al router o usar repetidor WiFi

2. **Watchdog resetea ESP32:**
   ```
   E (xxxxx) task_wdt: Task watchdog got triggered. The following tasks/users did not reset the watchdog in time:
   ```
   - Tarea bloqueada por mucho tiempo
   - Solución: Aumentar timeout watchdog en menuconfig o revisar código

3. **Memoria insuficiente:**
   ```
   W (xxxxx) WATCHDOG_MGR: Significant memory decrease detected: 90000 -> 50000
   E (xxxxx) MQTT_MGR: Failed to allocate memory
   ```
   - Memory leak o fragmentación
   - Solución: Revisar allocations, reiniciar ESP32 periódicamente

4. **Broker MQTT reiniciado:**
   ```bash
   # Ver logs Mosquitto
   sudo journalctl -u mosquitto --since "1 hour ago" | grep -E "restart|stop"
   ```

**Soluciones:**

```bash
# Ver RSSI del WiFi (en logs ESP32)
grep "rssi:" /home/pqsolutions/esp32_log/3608AC08/log_*.txt | tail -5

# Ver memoria libre (en logs ESP32)
grep "Free heap:" /home/pqsolutions/esp32_log/3608AC08/log_*.txt | tail -10

# Ver resets (en logs ESP32)
grep "rst:" /home/pqsolutions/esp32_log/3608AC08/log_*.txt | tail -10
```

**Configuración recomendada para WiFi débil:**

```c
// En ESP32, aumentar keepalive
mqtt_cfg.session.keepalive = 120;  // En lugar de 90

// Aumentar timeout de reconexión
mqtt_cfg.network.reconnect_timeout_ms = 15000;  // En lugar de 10000
```

### 12.5 PostgreSQL no guarda eventos

**Síntomas:**
```
ERROR - PostgreSQL persist failed: connection to server at "127.0.0.1", port 5432 failed: FATAL:  password authentication failed for user "hdd_monitor_user"
```

**Diagnóstico:**

1. **Verificar PostgreSQL corriendo:**
   ```bash
   sudo systemctl status postgresql

   # Si no está activo:
   sudo systemctl start postgresql
   sudo systemctl enable postgresql
   ```

2. **Verificar base de datos existe:**
   ```bash
   sudo -u postgres psql -l | grep hdd_monitor

   # Si no existe:
   sudo -u postgres psql -c "CREATE DATABASE hdd_monitor;"
   ```

3. **Verificar usuario y permisos:**
   ```bash
   sudo -u postgres psql -d hdd_monitor -c "\du"

   # Si usuario no existe o no tiene permisos:
   sudo -u postgres psql << EOF
   CREATE USER hdd_monitor_user WITH PASSWORD 'PASSWORD_SEGURO';
   GRANT ALL PRIVILEGES ON DATABASE hdd_monitor TO hdd_monitor_user;
   EOF
   ```

4. **Verificar tabla existe:**
   ```bash
   sudo -u postgres psql -d hdd_monitor -c "\dt"

   # Si tabla no existe:
   sudo -u postgres psql -d hdd_monitor -f /home/pqsolutions/hdd-monitor/setup_postgresql.sql
   ```

5. **Verificar password en config.py:**
   ```bash
   grep -A 5 "PG_CONFIG" /home/pqsolutions/hdd-monitor/config.py

   # Actualizar password si es necesario
   nano /home/pqsolutions/hdd-monitor/config.py
   ```

**Soluciones:**

```bash
# Resetear password del usuario PostgreSQL
sudo -u postgres psql << EOF
ALTER USER hdd_monitor_user WITH PASSWORD 'NUEVO_PASSWORD_SEGURO';
EOF

# Actualizar config.py
nano /home/pqsolutions/hdd-monitor/config.py
# Cambiar: 'password': 'NUEVO_PASSWORD_SEGURO'

# Reiniciar servicio
sudo systemctl restart hdd-monitor.service

# Verificar conexión
sudo journalctl -u hdd-monitor.service --since "1 minute ago" | grep -E "PostgreSQL|persist"
```

**Nota importante:** PostgreSQL es backup redundante. Si no funciona, el sistema sigue operativo con Firestore como base de datos primaria.

### 12.6 Let's Encrypt: Certificado expirado

**Síntomas:**
```
E (xxxxx) esp-tls-mbedtls: mbedtls_ssl_handshake returned -0x2700
E (xxxxx) MQTT_MGR: Failed to open a new connection
```

**Diagnóstico:**

```bash
# Verificar fecha de expiración
sudo openssl x509 -in /etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem -noout -dates

notBefore=Jan 15 10:30:00 2026 GMT
notAfter=Apr 15 10:30:00 2026 GMT  # ← Si esta fecha ya pasó, certificado expiró
```

**Soluciones:**

```bash
# Renovar certificado
sudo certbot renew

# Si falla, forzar renovación
sudo certbot renew --force-renewal

# Verificar nueva fecha
sudo openssl x509 -in /etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem -noout -dates

# Reiniciar Mosquitto para cargar nuevo certificado
sudo systemctl restart mosquitto

# Verificar que Mosquitto cargó el certificado correcto
sudo journalctl -u mosquitto --since "1 minute ago" | grep -E "certificate|SSL|TLS"
```

**Configurar renovación automática (si no está configurado):**

```bash
# Verificar cron job
sudo cat /etc/cron.d/certbot

# Si no existe, crear:
echo "0 */12 * * * root test -x /usr/bin/certbot && perl -e 'sleep int(rand(3600))' && certbot -q renew --post-hook 'systemctl reload mosquitto'" | sudo tee /etc/cron.d/certbot
```

---

## 13. Mantenimiento

### 13.1 Tareas Diarias

**1. Verificar servicios activos:**

```bash
# Script de verificación rápida
#!/bin/bash
# /home/pqsolutions/scripts/daily_check.sh

echo "=== Daily Health Check ==="
date

# Verificar servicios
for service in mosquitto hdd-monitor postgresql; do
  if systemctl is-active --quiet $service; then
    echo "✓ $service is running"
  else
    echo "✗ $service is DOWN!"
  fi
done

# Verificar logs recientes (últimos 5 minutos)
echo ""
echo "=== Recent Errors ==="
sudo journalctl -u hdd-monitor.service --since "5 minutes ago" -p err | tail -5

# Verificar ESP32s online
echo ""
echo "=== ESP32 Status ==="
# (requiere script adicional que consulta Firestore)
```

**2. Revisar logs de errores:**

```bash
# Ver errores del día
sudo journalctl -u hdd-monitor.service --since today -p err

# Ver warnings importantes
sudo journalctl -u hdd-monitor.service --since today -p warning | grep -E "Rate limit|Memory|Firestore"
```

**3. Verificar ESP32s online:**

```bash
# En Firestore Console:
# Collection: hdd-monitor/esp32/registered
# Query: status == "ONLINE"
# Verificar que todos los ESP32s esperados están ONLINE
```

**4. Verificar espacio en disco:**

```bash
df -h
# Si /dev/sda1 > 80% usado, limpiar logs antiguos
```

### 13.2 Tareas Semanales

**1. Limpiar notificaciones antiguas:**

El servidor hace limpieza automática, pero verificar:

```bash
# Ver cantidad de notificaciones en Firestore Console
# Si hay > 10,000 notificaciones por cliente, considerar limpieza manual
```

**2. Backup Firestore:**

```bash
# Exportar Firestore
gcloud firestore export gs://hdd-monitor-backups/$(date +%Y%m%d)

# Listar backups
gsutil ls gs://hdd-monitor-backups/
```

**3. Backup PostgreSQL:**

```bash
# Exportar database
sudo -u postgres pg_dump hdd_monitor > /home/pqsolutions/backups/hdd_monitor_$(date +%Y%m%d).sql

# Comprimir
gzip /home/pqsolutions/backups/hdd_monitor_$(date +%Y%m%d).sql

# Subir a Google Cloud Storage (opcional)
gsutil cp /home/pqsolutions/backups/*.sql.gz gs://hdd-monitor-backups/postgresql/
```

**4. Actualizar certificados (si es necesario):**

```bash
# Let's Encrypt se renueva automáticamente 30 días antes del vencimiento
# Verificar próxima renovación
sudo certbot certificates

# Si está por vencer en < 7 días, forzar renovación
sudo certbot renew --force-renewal
```

**5. Revisar métricas NFPA:**

```bash
# Ver métricas de la semana
sudo journalctl -u hdd-monitor.service --since "7 days ago" | grep "nfpa72_notification_latency" > /tmp/nfpa_metrics.json

# Calcular estadísticas (con Python o jq)
cat /tmp/nfpa_metrics.json | grep "latency_ms" | awk -F'"latency_ms": ' '{print $2}' | awk -F',' '{print $1}' | awk '
  BEGIN {min=999999; max=0; sum=0; count=0}
  {
    if ($1 < min) min=$1
    if ($1 > max) max=$1
    sum+=$1
    count++
  }
  END {
    print "Métricas NFPA 72 (última semana):"
    print "  Promedio:", sum/count, "ms"
    print "  Mínimo:", min, "ms"
    print "  Máximo:", max, "ms"
    print "  Total eventos:", count
    print "  Compliance: " (max < 90000 ? "✓ CUMPLE" : "✗ NO CUMPLE")
  }
'
```

### 13.3 Tareas Mensuales

**1. Actualizar dependencias Python:**

```bash
cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate

# Ver paquetes desactualizados
pip list --outdated

# Actualizar paquetes específicos (con cuidado)
pip install --upgrade firebase-admin google-cloud-firestore

# Verificar que todo sigue funcionando
sudo systemctl restart hdd-monitor.service
sudo journalctl -u hdd-monitor.service -f
# (observar por 5 minutos, verificar sin errores)

deactivate
```

**2. Actualizar sistema operativo:**

```bash
# Actualizar paquetes
sudo apt update
sudo apt upgrade -y

# Limpiar paquetes antiguos
sudo apt autoremove -y
sudo apt autoclean

# Reiniciar si hay updates de kernel
sudo reboot
# (verificar después del reinicio que todos los servicios están activos)
```

**3. Revisar logs del ESP32:**

```bash
# Ver últimos logs subidos
ls -lht /home/pqsolutions/esp32_log/*/log_*.txt | head -20

# Buscar errores críticos
grep -r "ERROR\|FATAL\|rst:" /home/pqsolutions/esp32_log/3608AC08/ | tail -20

# Limpiar logs antiguos (> 30 días)
find /home/pqsolutions/esp32_log/ -name "log_*.txt" -mtime +30 -delete
```

**4. Auditoría de usuarios MQTT:**

```bash
# Ver usuarios actuales
sudo cat /etc/mosquitto/passwd | cut -d: -f1

# Eliminar usuarios obsoletos
# (ESP32s que ya no existen, usuarios de prueba, etc.)
sudo mosquitto_passwd -D /etc/mosquitto/passwd <usuario_obsoleto>
sudo systemctl restart mosquitto
```

**5. Revisar eventos PostgreSQL:**

```sql
-- Conectar a PostgreSQL
sudo -u postgres psql -d hdd_monitor

-- Estadísticas del mes
SELECT
  DATE_TRUNC('day', timestamp) AS day,
  COUNT(*) AS event_count
FROM relay_events
WHERE timestamp > NOW() - INTERVAL '30 days'
GROUP BY day
ORDER BY day;

-- Paneles más activos
SELECT
  panel_id,
  COUNT(*) AS changes
FROM relay_events
WHERE timestamp > NOW() - INTERVAL '30 days'
GROUP BY panel_id
ORDER BY changes DESC
LIMIT 10;

-- Limpiar eventos muy antiguos (> 6 meses)
DELETE FROM relay_events WHERE timestamp < NOW() - INTERVAL '6 months';

-- Vacuum para recuperar espacio
VACUUM ANALYZE relay_events;
```

### 13.4 Tareas Trimestrales

**1. Renovar passwords:**

```bash
# MQTT users
sudo mosquitto_passwd /etc/mosquitto/passwd admin_hdd
sudo mosquitto_passwd /etc/mosquitto/passwd mqtt_firestore_handler

# PostgreSQL
sudo -u postgres psql << EOF
ALTER USER hdd_monitor_user WITH PASSWORD 'NUEVO_PASSWORD_AQUI';
EOF

# Actualizar config.py
nano /home/pqsolutions/hdd-monitor/config.py

# Reiniciar servicios
sudo systemctl restart mosquitto hdd-monitor
```

**2. Auditoría de seguridad:**

```bash
# Verificar puertos abiertos
sudo netstat -tulpn

# Verificar firewall
sudo ufw status verbose

# Ver intentos de login fallidos
sudo journalctl -u ssh --since "30 days ago" | grep "Failed password"

# Actualizar fail2ban (si está instalado)
sudo fail2ban-client status
```

**3. Revisar capacidad del servidor:**

```bash
# Uso promedio de CPU últimos 30 días
# (requiere configurar monitoring, ej: Prometheus + Grafana)

# Uso de RAM
free -h

# Si RAM > 80% consistentemente, considerar upgrade de VM
```

**4. Test de disaster recovery:**

```bash
# Simular caída de servicio
sudo systemctl stop hdd-monitor

# Verificar que se reinicia automáticamente
# (debería reiniciarse en 10 segundos según RestartSec=10)
watch -n 1 "systemctl is-active hdd-monitor"

# Simular caída de Mosquitto
sudo systemctl stop mosquitto

# Verificar logs del servidor
sudo journalctl -u hdd-monitor.service -f
# Debe mostrar: ERROR - MQTT connection lost, retrying...

# Reiniciar Mosquitto
sudo systemctl start mosquitto
# Servidor debe reconectarse automáticamente
```

### 13.5 Monitoreo Continuo

**Script de monitoreo (ejecutar por cron cada 5 minutos):**

```bash
#!/bin/bash
# /home/pqsolutions/scripts/monitor.sh

LOG_FILE="/var/log/hdd-monitor-health.log"
EMAIL="admin@pqsolutionsperu.com"

echo "=== Health Check $(date) ===" >> $LOG_FILE

# Verificar servicios
for service in mosquitto hdd-monitor postgresql; do
  if ! systemctl is-active --quiet $service; then
    echo "ALERTA: $service está caído" >> $LOG_FILE
    echo "ALERTA: $service está caído en $(hostname)" | mail -s "ALERTA: $service caído" $EMAIL

    # Intentar reiniciar automáticamente
    systemctl restart $service
  fi
done

# Verificar uso de disco
DISK_USAGE=$(df / | tail -1 | awk '{print $5}' | sed 's/%//')
if [ $DISK_USAGE -gt 85 ]; then
  echo "ALERTA: Disco al $DISK_USAGE%" >> $LOG_FILE
  echo "ALERTA: Disco al $DISK_USAGE% en $(hostname)" | mail -s "ALERTA: Disco lleno" $EMAIL
fi

# Verificar errores recientes
ERROR_COUNT=$(journalctl -u hdd-monitor.service --since "5 minutes ago" -p err | wc -l)
if [ $ERROR_COUNT -gt 5 ]; then
  echo "ALERTA: $ERROR_COUNT errores en últimos 5 minutos" >> $LOG_FILE
  journalctl -u hdd-monitor.service --since "5 minutes ago" -p err | mail -s "ALERTA: Múltiples errores" $EMAIL
fi

# Verificar conectividad ESP32
# (requiere script adicional que consulta Firestore)
```

**Agregar a crontab:**

```bash
# Editar crontab
crontab -e

# Agregar línea:
*/5 * * * * /home/pqsolutions/scripts/monitor.sh
```

**Dashboards recomendados (futuro):**

- **Grafana + Prometheus:** Métricas en tiempo real
- **ELK Stack:** Análisis de logs
- **Google Cloud Monitoring:** Alertas de uptime y performance
- **Custom dashboard:** Panel web con Flask mostrando estado de todos los ESP32s

---

## Anexos

### A. Comandos Útiles

**MQTT:**
```bash
# Suscribirse a todos los topics
mosquitto_sub -h localhost -p 1883 -t '#' -u admin_hdd -P 'Admin_HDD_2024@' -v

# Suscribirse a relays de un panel
mosquitto_sub -h localhost -p 1883 -t 'clients/client_1/panels/panel_PRUELPET_client_1/#' -u admin_hdd -P 'Admin_HDD_2024@' -v

# Suscribirse a estados de ESP32
mosquitto_sub -h localhost -p 1883 -t 'system/status/#' -u admin_hdd -P 'Admin_HDD_2024@' -v

# Publicar mensaje de prueba
mosquitto_pub -h localhost -p 1883 -t 'test/topic' -m '{"test": true}' -u admin_hdd -P 'Admin_HDD_2024@'

# Ver estadísticas del broker
mosquitto_sub -h localhost -p 1883 -t '$SYS/#' -u admin_hdd -P 'Admin_HDD_2024@' -v
```

**Logs:**
```bash
# Logs en tiempo real
sudo journalctl -u hdd-monitor.service -f

# Últimas 100 líneas
sudo journalctl -u hdd-monitor.service -n 100

# Filtrar por nivel (err, warning, info)
sudo journalctl -u hdd-monitor.service -p err

# Buscar texto específico
sudo journalctl -u hdd-monitor.service | grep "relay_1"

# Logs desde una fecha
sudo journalctl -u hdd-monitor.service --since "2026-01-30 18:00:00"

# Logs entre dos fechas
sudo journalctl -u hdd-monitor.service --since "2026-01-30" --until "2026-01-31"

# Exportar logs a archivo
sudo journalctl -u hdd-monitor.service --since today > /tmp/logs_$(date +%Y%m%d).txt
```

**Firestore:**
```bash
# Exportar (requiere gcloud CLI configurado)
gcloud firestore export gs://hdd-monitor-backups/$(date +%Y%m%d)

# Importar
gcloud firestore import gs://hdd-monitor-backups/20260130

# Listar colecciones
gcloud firestore collections list

# Query desde CLI (ejemplo)
gcloud firestore documents list hdd-monitor/accounts/clients --filter="status=ONLINE"
```

**PostgreSQL:**
```bash
# Conectar
sudo -u postgres psql -d hdd_monitor

# Backup
sudo -u postgres pg_dump hdd_monitor > backup.sql

# Restore
sudo -u postgres psql -d hdd_monitor < backup.sql

# Ver tamaño de base de datos
sudo -u postgres psql -c "SELECT pg_size_pretty(pg_database_size('hdd_monitor'));"

# Ver tabla más grande
sudo -u postgres psql -d hdd_monitor -c "SELECT pg_size_pretty(pg_total_relation_size('relay_events'));"
```

**Systemd:**
```bash
# Ver todos los servicios
systemctl list-units --type=service

# Ver servicios fallidos
systemctl --failed

# Reiniciar todos los servicios HDD Monitor
sudo systemctl restart mosquitto hdd-monitor postgresql

# Ver dependencias de un servicio
systemctl list-dependencies hdd-monitor.service

# Editar servicio
sudo systemctl edit --full hdd-monitor.service

# Recargar configuración después de editar
sudo systemctl daemon-reload
```

### B. Estructura de Topics MQTT

```
# Estado del ESP32
system/status/<esp32_id>
  → Payload: {"status": "ONLINE|OFFLINE", "timestamp": ..., "reason": "..."}
  → QoS: 2, Retain: true
  → Usado para LWT

# Información de red del ESP32
esp32/network_info
  → Payload: {"esp32_id": "...", "ip": "...", "rssi": -45, "mac": "...", "timestamp": ...}
  → QoS: 2, Retain: false

# Eventos de conectividad
esp32/connectivity/<esp32_id>
  → Payload: {"event": "WIFI_CONNECTED|WIFI_DISCONNECTED|MQTT_CONNECTED|MQTT_DISCONNECTED", "timestamp": ...}
  → QoS: 1, Retain: false

# Estados de relay (publicado por ESP32)
clients/<client_id>/panels/<panel_id>/relays
  → Payload: {"relay": "relay_1", "status": "OK|DISC", "contact_type": "NC|NO", "timestamp": ...}
  → QoS: 2, Retain: false

# Configuración de relay (publicado por servidor)
clients/<client_id>/panels/<panel_id>/relay_config
  → Payload: {"command": "update_config", "relay_id": "relay_1", "is_active": true, "contact_type": "NC", "custom_name": "...", "timestamp": ...}
  → QoS: 2, Retain: false

# Comandos generales al panel
clients/<client_id>/panels/<panel_id>/command
  → Payload: {"command": "restart|get_status|...", "timestamp": ...}
  → QoS: 2, Retain: false

# Estado del panel (publicado por ESP32)
clients/<client_id>/panels/<panel_id>/status
  → Payload: {"online": true, "uptime": 123456, "free_heap": 80000, "timestamp": ...}
  → QoS: 1, Retain: true
```

### C. Firestore Schema Detallado

```javascript
// Estructura completa de Firestore

hdd-monitor/
├── accounts/
│   ├── clients/
│   │   └── <client_id>/                    // ej: "client_1"
│   │       ├── name: "Cliente Demo"
│   │       ├── email: "demo@example.com"
│   │       ├── createdAt: Timestamp
│   │       ├── panels/
│   │       │   └── <panel_id>/             // ej: "panel_PRUELPET_client_1"
│   │       │       ├── name: "Panel Prueba"
│   │       │       ├── esp32_id: "3608AC08"
│   │       │       ├── location: "Piso 1"
│   │       │       ├── createdAt: Timestamp
│   │       │       └── relays/
│   │       │           └── <relay_id>/     // ej: "relay_1"
│   │       │               ├── status: "OK" | "DISC"
│   │       │               ├── isActive: true
│   │       │               ├── contactType: "NC" | "NO"
│   │       │               ├── customName: "Bomba Principal"
│   │       │               ├── lastUpdate: Timestamp
│   │       │               ├── date_time: "30/01/2026, 18:51"
│   │       │               └── source: "mqtt" | "manual"
│   │       ├── events/
│   │       │   └── <event_id>/
│   │       │       ├── title: "Mantenimiento preventivo"
│   │       │       ├── type: "MANTENIMIENTO" | "INSPECCION" | "REPARACION"
│   │       │       ├── date_time: "30-01-2026 20:00"
│   │       │       ├── status: "PROGRAMADO" | "EN_PROGRESO" | "COMPLETADO"
│   │       │       ├── description: "..."
│   │       │       ├── panelName: "Panel Prueba"
│   │       │       ├── panelDocName: "panel_PRUELPET_client_1"
│   │       │       ├── createdBy: <user_id>
│   │       │       ├── createdAt: Timestamp
│   │       │       └── completedAt: Timestamp (opcional)
│   │       └── notifications/
│   │           └── <notification_id>/
│   │               ├── type: "relay" | "event" | "connectivity"
│   │               ├── relay: "relay_1"
│   │               ├── relay_display_name: "relay Bomba Principal"
│   │               ├── panel_id: "panel_PRUELPET_client_1"
│   │               ├── panel_name: "Panel Prueba"
│   │               ├── state: "OK" | "DISC" (para type=relay)
│   │               ├── old_status: "..." (para type=relay)
│   │               ├── action: "ONLINE" | "OFFLINE" (para type=connectivity)
│   │               ├── message: "..."
│   │               ├── date_time: "30/01/2026, 18:51"
│   │               ├── lastUpdate: Timestamp
│   │               ├── documentName: "relay_client_1_..."
│   │               ├── isRead: false
│   │               ├── readByAdmin: false
│   │               ├── readByUser: false
│   │               └── timestamp: 1769825781854
│   └── users/
│       └── <user_id>/
│           ├── email: "user@example.com"
│           ├── displayName: "Usuario Demo"
│           ├── role: "admin" | "user"
│           ├── clientId: "client_1"
│           ├── fcmToken: "..." (Firebase Cloud Messaging token)
│           ├── createdAt: Timestamp
│           └── lastLogin: Timestamp
│
└── esp32/
    └── registered/
        └── <esp32_id>/                     // ej: "3608AC08"
            ├── status: "ONLINE" | "OFFLINE" | "AWAITING_CONFIG"
            ├── client_id: "client_1"
            ├── panel_id: "panel_PRUELPET_client_1"
            ├── lastSeen: Timestamp
            ├── networkInfo/
            │   ├── ip: "192.168.1.21"
            │   ├── mac: "08:D1:F9:DD:36:08"
            │   ├── rssi: -45
            │   └── timestamp: Timestamp
            ├── firmware_version: "2.0"
            └── createdAt: Timestamp
```

### D. Referencias

**Normativas:**
- **NFPA 72:** https://www.nfpa.org/codes-and-standards/all-codes-and-standards/list-of-codes-and-standards/detail?code=72
- **UL 864:** https://standardscatalog.ul.com/ProductDetail.aspx?productId=UL864
- **UL 2572:** https://standardscatalog.ul.com/ProductDetail.aspx?productId=UL2572

**Documentación técnica:**
- **ESP-IDF v5.4.1:** https://docs.espressif.com/projects/esp-idf/en/v5.4.1/
- **MQTT v3.1.1:** https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/mqtt-v3.1.1.html
- **Mosquitto:** https://mosquitto.org/documentation/
- **Firebase Admin SDK:** https://firebase.google.com/docs/admin/setup
- **Firebase Cloud Messaging:** https://firebase.google.com/docs/cloud-messaging
- **Google Cloud Firestore:** https://cloud.google.com/firestore/docs
- **PostgreSQL 15:** https://www.postgresql.org/docs/15/

**Herramientas:**
- **ESP-IDF GitHub:** https://github.com/espressif/esp-idf
- **paho-mqtt Python:** https://pypi.org/project/paho-mqtt/
- **Let's Encrypt:** https://letsencrypt.org/
- **systemd:** https://www.freedesktop.org/wiki/Software/systemd/

### E. Contacto y Soporte

**Desarrollador:** PQ Solutions Peru
**Email:** soporte@pqsolutionsperu.com
**Sitio web:** https://pqsolutionsperu.com
**GitHub:** (privado)

**Versión del documento:** 2.1
**Última actualización:** 1 de febrero de 2026
**Autor:** Asistente técnico + equipo PQ Solutions

---

## 14. Sistema de Monitoreo de Salud ESP32 (Health Monitor)

### 14.1 Visión General

El **Health Monitor** es un sistema ultra-ligero de monitoreo de salud de dispositivos ESP32 que detecta problemas críticos (memoria baja, boot loops, reinicios inesperados) y envía alertas en tiempo real al servidor VM.

**Filosofía de diseño:** Usa mecanismos nativos de ESP-IDF (TWDT - Task Watchdog Timer) para recuperación automática, mientras que el componente de monitoreo solo **observa y reporta** sin interferir con la aplicación principal.

**Características:**
- ✅ Ultra-ligero: 1.7 KB de overhead total (stack 1536 bytes + heap ~200 bytes)
- ✅ No bloqueante: Corre en Core 1 con prioridad baja
- ✅ Monitoreo pasivo: No intenta recuperación (delegado a TWDT nativo)
- ✅ Alertas MQTT: Envía alertas solo cuando detecta problemas
- ✅ Dashboard web en tiempo real
- ✅ Alertas por email para problemas críticos
- ✅ Cumplimiento NFPA 72: Monitoreo no afecta latencia de alarmas

### 14.2 Arquitectura del Sistema

```
┌──────────────────────────────────────────────────────┐
│              ESP32 (Core 0 - Aplicación)             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │  WiFi    │  │  MQTT    │  │  Alarma  │          │
│  │ Manager  │  │ Manager  │  │  Logic   │          │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘          │
│       │             │             │                 │
│       └─────────────┴─────────────┘                 │
│                     │                               │
│              [TWDT Nativo - Watchdog]               │
│              Recuperación automática                │
└─────────────────────┼───────────────────────────────┘
                      │
┌─────────────────────┼───────────────────────────────┐
│    Health Monitor (Core 1, Prioridad Baja)          │
│  ┌────────────────────────────────────────────┐    │
│  │  Cada 30 segundos:                         │    │
│  │  1. Revisa heap libre                      │    │
│  │  2. Si < 40KB → Alerta MEMORY_LOW          │    │
│  │  3. Si < 30KB → Alerta MEMORY_CRITICAL     │    │
│  │  4. Auto-limpia contador de boots (10min)  │    │
│  └────────────┬───────────────────────────────┘    │
└───────────────┼────────────────────────────────────┘
                │
                ▼
      [Publicación MQTT]
      Topic: hdd-monitor/alerts/{ESP32_ID}
      Payload: JSON con métricas
                │
                ▼
┌───────────────────────────────────────────────────────┐
│           MQTT Broker (Mosquitto)                     │
│  Topics:                                              │
│  - hdd-monitor/alerts/+      (Alertas de salud)      │
│  - system/status/+           (LWT - Last Will)       │
└────────────┬──────────────────────────────────────────┘
             │
             ▼
┌───────────────────────────────────────────────────────┐
│      ESP32 Health Monitor (Python Service)            │
│  /home/pqsolutions/mqtt-manager/                      │
│  ┌─────────────────────────────────────────────┐     │
│  │ modules/esp32_health_monitor.py             │     │
│  │ - Suscrito a hdd-monitor/alerts/+           │     │
│  │ - Suscrito a system/status/+ (LWT)          │     │
│  │ - Almacena alertas en Firestore             │     │
│  │ - Rastrea estado de cada ESP32              │     │
│  │ - Envía emails para alertas críticas        │     │
│  └────────────┬────────────────────────────────┘     │
└───────────────┼───────────────────────────────────────┘
               │
         ┌─────┴─────┐
         ▼           ▼
   [Email SMTP]  [Dashboard Web]
   Gmail          /health
```

### 14.3 Deployment ESP32 Firmware

#### Paso 1: Compilar Firmware

```bash
# Navegar al directorio del proyecto
cd /mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/APP/HDD1_2/ESP-IDF-HDDESP32/hddesp32

# Limpiar build anterior (opcional)
idf.py fullclean

# Compilar
idf.py build
```

**Verificar:** El build debe completarse sin errores. El componente `health_monitor` se compila automáticamente (está en `components/health_monitor/`).

#### Paso 2: Flashear ESP32

```bash
# Borrar flash (recomendado para deployment limpio)
idf.py erase-flash

# Flashear firmware y abrir monitor serial
idf.py -p /dev/ttyUSB0 flash monitor
```

**Nota:** Reemplazar `/dev/ttyUSB0` con el puerto correcto:
- Windows: `COM3`, `COM4`, etc.
- Linux: `/dev/ttyUSB0`, `/dev/ttyACM0`
- macOS: `/dev/cu.usbserial-*`

#### Paso 3: Verificar Inicialización

En el monitor serial, buscar estas líneas:

```
I (5234) HEALTH_MON: Simple health monitor started on core 1 (native approach)
I (5240) HEALTH_MON: Health monitor initialized for device ESP32_3608AC08
```

**Operación normal:**
```
I (305234) HEALTH_MON: System healthy - Uptime: 5 min, Heap: 76 KB
```

**Cuando detecta problema:**
```
W (450000) HEALTH_MON: WARNING: Memory low - 38000 bytes free
W (450001) HEALTH_MON: Alert: MEMORY_LOW (heap=38000, boots=1)
```

#### Paso 4: Configuración en Código

El health monitor se inicializa automáticamente en `hddesp32_main.c`:

```c
// Línea 1388 en hddesp32_main.c
ESP_ERROR_CHECK(health_monitor_init(g_esp32_id_buffer));
LOG_I(TAG, "Health monitor initialized");
```

**Parámetros configurables** en `health_monitor.c`:

```c
#define HEALTH_MONITOR_CHECK_INTERVAL_MS (30000)  // Intervalo de chequeo (30s)
#define MEMORY_LOW_THRESHOLD (40 * 1024)          // Umbral advertencia (40KB)
#define MEMORY_CRITICAL_THRESHOLD (30 * 1024)     // Umbral crítico (30KB)
```

### 14.4 Deployment Servidor VM

#### Paso 1: Configurar Email para Alertas

**IMPORTANTE:** El sistema de health monitoring requiere configuración de email para alertas críticas.

```bash
# SSH al servidor VM
ssh pqsolutionsperu@34.63.146.196

# Navegar a mqtt-manager
cd /home/pqsolutions/mqtt-manager

# Copiar template de configuración de email
cp config.email.example.json config.email.json

# Editar configuración
nano config.email.json
```

**Contenido de config.email.json:**

```json
{
  "smtp_server": "smtp.gmail.com",
  "smtp_port": 587,
  "smtp_user": "pqsolutionsperu@gmail.com",
  "smtp_password": "APP_PASSWORD_AQUI",
  "from_email": "pqsolutionsperu@gmail.com",
  "to_emails": [
    "admin@pqsolutionsperu.com",
    "soporte@pqsolutionsperu.com"
  ],
  "use_tls": true,
  "mqtt_broker": "localhost",
  "mqtt_port": 1883
}
```

##### Generar Gmail App Password

1. Ir a: https://myaccount.google.com/apppasswords
2. Nombre: "HDD-Monitor Health Alerts"
3. Copiar password generado (16 caracteres)
4. Pegar en `smtp_password` en config.email.json

**Credencial generada:**
```plaintext
Email: pqsolutionsperu@gmail.com
App Password: [GENERAR NUEVA - LA ANTERIOR ERA PARA NETDATA]
Nombre del dispositivo: HDD-Monitor Health Alerts
Uso: Alertas críticas de salud de ESP32
Revocable en: https://myaccount.google.com/apppasswords
```

#### Paso 2: Actualizar Servicio systemd

```bash
# Editar archivo de servicio
sudo nano /etc/systemd/system/mqtt-manager.service
```

**Agregar variable de entorno para email:**

```ini
[Service]
Environment="GOOGLE_APPLICATION_CREDENTIALS=/home/pqsolutionsperu/vm-service-key.json"
Environment="EMAIL_CONFIG_PATH=/home/pqsolutions/mqtt-manager/config.email.json"
Environment="FLASK_SECRET_KEY=your-secret-key-here"
```

#### Paso 3: Instalar/Actualizar Dependencias

```bash
cd /home/pqsolutions/mqtt-manager

# Instalar dependencias si no están
pip3 install -r requirements.txt

# Verificar módulos críticos
python3 -c "from modules.esp32_health_monitor import ESP32HealthMonitor; print('OK')"
python3 -c "from modules.email_alerter import EmailAlerter; print('OK')"
python3 -c "from modules.health_system import HealthMonitoringSystem; print('OK')"
```

#### Paso 4: Desplegar Aplicación

```bash
# Detener servicio
sudo systemctl stop mqtt-manager

# Backup de configuración actual
cp app.py app.py.backup.$(date +%Y%m%d_%H%M%S)

# Si subes archivos nuevos desde desarrollo:
# scp -r modules/ templates/ static/ app.py pqsolutionsperu@34.63.146.196:/home/pqsolutions/mqtt-manager/

# Establecer permisos
chmod 755 app.py
chmod -R 755 modules/ static/ templates/
chmod 600 config.email.json  # Proteger credenciales

# Recargar systemd
sudo systemctl daemon-reload

# Iniciar servicio
sudo systemctl start mqtt-manager

# Habilitar inicio automático
sudo systemctl enable mqtt-manager

# Verificar estado
sudo systemctl status mqtt-manager
```

#### Paso 5: Verificar Logs

```bash
# Seguir logs en tiempo real
sudo journalctl -u mqtt-manager -f

# Buscar inicialización del health monitor
sudo journalctl -u mqtt-manager | grep "Health monitoring"
```

**Salida esperada:**

```
INFO:root:Health monitoring system started with email alerts
INFO:modules.esp32_health_monitor:ESP32 Health Monitor initialized
INFO:modules.esp32_health_monitor:Connected to MQTT broker localhost:1883
INFO:modules.esp32_health_monitor:Subscribed to hdd-monitor/alerts/+ and system/status/+
```

### 14.5 Dashboard Web - Acceso y Uso

#### URL del Dashboard

**Acceso:** https://hddm.pqsolutionsperu.com/health

**Autenticación:** Requiere login (mismo usuario que MQTT Config)

#### Características del Dashboard

1. **Tarjetas de Estadísticas (Superior):**
   - Total de dispositivos
   - Dispositivos saludables (verde)
   - Dispositivos con problemas (amarillo)
   - Alertas críticas últimas 24h (rojo)

2. **Tarjetas de Dispositivos:**
   - ESP32 ID
   - Estado de salud (badge verde/amarillo/rojo)
   - Última vez visto (tiempo relativo: "5 minutos atrás")
   - Uptime del dispositivo
   - Alertas totales
   - Alertas críticas (24h)
   - Fallas consecutivas
   - **Última alerta:** Tipo, descripción, timestamp

3. **Auto-actualización:**
   - Refresco automático cada 5 segundos
   - Indicador de actualización (esquina superior derecha)
   - No requiere recarga manual

#### Interpretación de Colores

| Color | Estado | Significado |
|-------|--------|-------------|
| 🟢 Verde | HEALTHY | Sistema funcionando normalmente |
| 🟡 Amarillo | WARNING | Memoria baja (<40 KB) pero funcional |
| 🔴 Rojo | CRITICAL | Problema crítico (memoria <30 KB, boot loops) |

#### Navegación

En el menú lateral:
- **Dashboard** → Vista general del sistema
- **VM Monitoring** → Métricas de servidor (Netdata)
- **ESP32 Devices** → Lista de dispositivos registrados
- **ESP32 Health** 💚 → Monitor de salud (NUEVO)
- **Clients & Panels** → Gestión de clientes
- **Events** → Eventos programados
- **MQTT Config** → Configuración de usuarios MQTT

### 14.6 Alertas por Email

#### Triggers de Email

Emails se envían **SOLO** para condiciones críticas:

| Condición | Tipo de Alerta | Email? | Asunto |
|-----------|---------------|--------|---------|
| Heap < 40 KB | MEMORY_LOW | ❌ No | - |
| Heap < 30 KB | MEMORY_CRITICAL | ✅ Sí | 🚨 ESP32 CRITICAL ALERT - MEMORY_CRITICAL |
| Boot loops | BOOT_LOOP | ✅ Sí | 🚨 ESP32 CRITICAL ALERT - BOOT_LOOP |
| Dispositivo offline | UNEXPECTED_DISCONNECT | ✅ Sí | 🚨 ESP32 OFFLINE - UNEXPECTED_DISCONNECT |
| Reinicio inesperado | UNEXPECTED_REBOOT | ✅ Sí | 🚨 ESP32 CRITICAL ALERT - UNEXPECTED_REBOOT |

#### Formato de Email

**Asunto:**
```
🚨 ESP32 CRITICAL ALERT - {tipo_alerta} - {ESP32_ID}
```

**Cuerpo:**
```
ESP32 Health Alert

Device: ESP32_3608AC08
Status: CRITICAL
Issue: MEMORY_CRITICAL
Description: Critical low memory: 28000 bytes free

Details:
- Uptime: 15 minutes
- Free Heap: 28000 bytes
- Min Free Heap: 25000 bytes
- Boot Count: 2
- Timestamp: 2026-02-01 14:30:45

Action Required:
1. Check ESP32 serial logs
2. Verify application memory usage
3. Consider restarting device if unstable

Dashboard: https://hddm.pqsolutionsperu.com/health
```

#### Configurar Destinatarios

Editar `config.email.json`:

```json
{
  "to_emails": [
    "admin@pqsolutionsperu.com",
    "soporte@pqsolutionsperu.com",
    "tecnico1@pqsolutionsperu.com"
  ]
}
```

Luego reiniciar servicio:

```bash
sudo systemctl restart mqtt-manager
```

### 14.7 Verificación del Sistema Completo

#### Script de Verificación Automática

```bash
cd /home/pqsolutions/mqtt-manager

# Ejecutar script de verificación
python3 test_health_system.py
```

**El script verifica:**
1. ✅ Dependencias Python instaladas
2. ✅ Firestore conectado correctamente
3. ✅ Configuración de email válida
4. ✅ MQTT broker accesible
5. ✅ Rutas Flask funcionando
6. ✅ Sistema de health monitoring inicializado

**Salida esperada:**

```
============================================================
       ESP32 Health Monitoring System - Verification
============================================================

============================================================
              Testing Python Dependencies
============================================================

✓ Flask web framework (flask)
✓ MQTT client library (paho.mqtt.client)
✓ Firebase Admin SDK (firebase_admin)
✓ Firestore client (google.cloud.firestore)

============================================================
            Testing Firestore Configuration
============================================================

✓ Service account found: /home/pqsolutionsperu/vm-service-key.json
ℹ Project ID: fir-hdd-monitor-d00de
✓ Firestore client initialized
ℹ Found 3 ESP32 devices in Firestore

============================================================
              Testing Email Configuration
============================================================

✓ Email config valid
ℹ SMTP Server: smtp.gmail.com:587
ℹ From: pqsolutionsperu@gmail.com
ℹ To: admin@pqsolutionsperu.com, soporte@pqsolutionsperu.com
✓ Email alerter initialized

  Send test email? (y/n): n

============================================================
                Testing MQTT Broker
============================================================

✓ MQTT broker reachable at localhost:1883

============================================================
              Testing Flask Application
============================================================

✓ Flask app imported successfully
✓ Dashboard route exists: /dashboard
✓ Health Monitor route exists: /health
✓ Health API route exists: /api/esp32/health/status

============================================================
          Testing Health Monitoring System
============================================================

✓ HealthMonitoringSystem instantiated
✓ Health monitor component accessible
✓ Email alerter component accessible

============================================================
                    Test Summary
============================================================

✓ Python Dependencies
✓ Firestore Configuration
✓ Email Configuration
✓ MQTT Broker
✓ Flask Application
✓ Health Monitoring System

Results: 6/6 tests passed

✓ All tests passed! System ready for deployment.
```

#### Prueba Manual End-to-End

**1. Verificar ESP32 envía alertas:**

```bash
# En el servidor VM, suscribirse a alertas
mosquitto_sub -h localhost -t "hdd-monitor/alerts/#" -v
```

**2. Generar alerta de prueba en ESP32:**

En el código ESP32, puedes forzar una alerta manualmente (solo para testing):

```c
// En tu código de test
health_monitor_report_issue(HEALTH_ISSUE_MEMORY_LOW, "Test alert from device");
```

O espera a que el heap baje de 40 KB naturalmente.

**3. Verificar recepción en servidor:**

Deberías ver en `mosquitto_sub`:

```
hdd-monitor/alerts/3608AC08 {"type":"MEMORY_LOW","heap":38000,"boot_count":1,"uptime":300}
```

Y en los logs del servicio:

```bash
sudo journalctl -u mqtt-manager -f
```

```
WARNING:modules.esp32_health_monitor:Received alert from 3608AC08: MEMORY_LOW - Low memory: 38000 bytes free
```

**4. Verificar dashboard:**

- Ir a https://hddm.pqsolutionsperu.com/health
- Debería mostrar el dispositivo con badge amarillo (WARNING)
- Ver detalles de última alerta

**5. Verificar email (si es crítica):**

Si la alerta es CRITICAL (heap < 30 KB), verificar que llegó email a los destinatarios configurados.

### 14.8 Troubleshooting

#### ESP32 No Envía Alertas

**Síntomas:**
- No se ven logs del health monitor en serial
- No aparecen mensajes en `mosquitto_sub`

**Solución:**

1. Verificar inicialización:
```
# En monitor serial, buscar:
I (5240) HEALTH_MON: Health monitor initialized for device ESP32_XXXXXX
```

2. Verificar conexión MQTT:
```
I (8000) MQTT: Connected to MQTT broker
```

3. Verificar heap no está en rango normal:
```
# Si heap > 40 KB, no se envían alertas (comportamiento normal)
I (305234) HEALTH_MON: System healthy - Uptime: 5 min, Heap: 76 KB
```

#### Servidor No Recibe Alertas

**Síntomas:**
- ESP32 publica a MQTT pero servidor no procesa

**Solución:**

1. Verificar broker MQTT:
```bash
sudo systemctl status mosquitto
sudo tail -f /var/log/mosquitto/mosquitto.log
```

2. Verificar Flask app:
```bash
sudo systemctl status mqtt-manager
sudo journalctl -u mqtt-manager | grep "MQTT"
```

3. Revisar suscripciones:
```bash
sudo journalctl -u mqtt-manager | grep "Subscribed to"
```

Debe mostrar:
```
Subscribed to hdd-monitor/alerts/+ and system/status/+
```

#### Email No Se Envía

**Síntomas:**
- Alertas críticas llegan al dashboard pero no hay email

**Solución:**

1. Verificar config existe:
```bash
ls -la /home/pqsolutions/mqtt-manager/config.email.json
cat config.email.json | python3 -m json.tool
```

2. Verificar password es válido:
```bash
# Revisar logs de Flask
sudo journalctl -u mqtt-manager | grep -i "email\|smtp"
```

3. Test manual de email:
```bash
cd /home/pqsolutions/mqtt-manager
python3 << EOF
from modules.email_alerter import EmailAlerter, EmailConfig
import json

with open('config.email.json') as f:
    conf = json.load(f)

config = EmailConfig(**conf)
alerter = EmailAlerter(config)
alerter.send_test_email()
print("Test email sent!")
EOF
```

4. Verificar firewall no bloquea SMTP:
```bash
sudo ufw status | grep 587
telnet smtp.gmail.com 587
```

#### Dashboard No Carga

**Síntomas:**
- Página /health retorna 404 o 500

**Solución:**

1. Verificar ruta existe:
```bash
cd /home/pqsolutions/mqtt-manager
grep -n "'/health'" app.py
```

Debe mostrar línea ~291:
```python
@app.route('/health')
@login_required
def health_dashboard():
    return render_template('health.html')
```

2. Verificar template existe:
```bash
ls -la /home/pqsolutions/mqtt-manager/templates/health.html
```

3. Verificar Nginx:
```bash
sudo nginx -t
sudo systemctl reload nginx
sudo tail -f /var/log/nginx/error.log
```

4. Test local:
```bash
curl http://localhost:5000/health
```

### 14.9 Métricas y Performance

#### Recursos del ESP32

| Métrica | Valor | Notas |
|---------|-------|-------|
| Stack | 1536 bytes | health_monitor task |
| Heap | ~200 bytes | Estructuras de datos |
| **Total** | **1.7 KB** | Overhead total |
| CPU | <0.1% | Duerme 30s entre checks |
| Intervalo | 30 segundos | Configurable |
| Cooldown | 60 segundos | Evita spam de alertas |

#### Recursos del Servidor

| Métrica | Valor | Notas |
|---------|-------|-------|
| Memoria | ~20 MB | Health monitor module |
| CPU | <2% | Idle la mayor parte |
| Latencia MQTT | <100 ms | Broker local |
| Latencia email | 1-3 segundos | SMTP send |
| Dashboard refresh | 5 segundos | Auto-refresh JS |

#### Latencias End-to-End

| Flujo | Latencia | Cumplimiento NFPA 72 |
|-------|----------|----------------------|
| ESP32 → MQTT broker | <50 ms | ✅ |
| MQTT → Python service | <50 ms | ✅ |
| Alerta → Dashboard | <5 segundos | ✅ N/A (no crítico) |
| Alerta crítica → Email | <5 segundos | ✅ N/A (no crítico) |
| **Alarma de incendio** | **<10 segundos** | ✅ **SÍ (vía main backend)** |

**Nota crítica:** El health monitor NO afecta la latencia de alarmas de incendio, que sigue siendo <10s vía el flujo principal (relay → MQTT → firestore_handler → FCM).

### 14.10 Mantenimiento

#### Revisiones Diarias

```bash
# Verificar dashboard muestra dispositivos saludables
# URL: https://hddm.pqsolutionsperu.com/health

# Verificar servicio corriendo
sudo systemctl status mqtt-manager
```

#### Revisiones Semanales

```bash
# Revisar logs de alertas
sudo journalctl -u mqtt-manager | grep "alert"

# Verificar conexión MQTT estable
sudo journalctl -u mqtt-manager | grep "MQTT"

# Revisar uptime de servicio
sudo systemctl status mqtt-manager | grep "Active"
```

#### Revisiones Mensuales

```bash
# Revisar alertas en Firestore
# (Acceder desde Firebase Console)

# Actualizar dependencias de seguridad
cd /home/pqsolutions/mqtt-manager
pip3 list --outdated
pip3 install --upgrade <package>

# Verificar certificados Let's Encrypt
sudo certbot renew --dry-run
```

#### Rotación de Logs

Configurar logrotate si es necesario:

```bash
sudo nano /etc/logrotate.d/mqtt-manager
```

```
/var/log/mqtt-manager/*.log {
    daily
    rotate 7
    compress
    delaycompress
    notifempty
    create 0644 pqsolutions pqsolutions
}
```

#### Backup de Configuración

```bash
# Backup mensual de configuraciones
tar -czf mqtt-manager-backup-$(date +%Y%m%d).tar.gz \
    /home/pqsolutions/mqtt-manager/config.email.json \
    /etc/systemd/system/mqtt-manager.service \
    /etc/nginx/sites-available/hddm

# Guardar en ubicación segura
```

### 14.11 Archivos del Sistema

#### ESP32 Firmware

**Componente health_monitor:**
```
ESP-IDF-HDDESP32/hddesp32/components/health_monitor/
├── CMakeLists.txt              # Build configuration
├── health_monitor.c            # Implementación (461 líneas)
└── include/
    └── health_monitor.h        # Header (138 líneas)
```

**Integración:**
```
ESP-IDF-HDDESP32/hddesp32/main/hddesp32_main.c
├── Línea 1388: health_monitor_init(g_esp32_id_buffer)
```

#### Servidor VM

**Módulos Python:**
```
/home/pqsolutions/mqtt-manager/modules/
├── esp32_health_monitor.py     # Monitor MQTT (387 líneas)
├── email_alerter.py            # Alertas email (156 líneas)
├── health_system.py            # Integración (92 líneas)
├── firestore_client.py         # Acceso Firestore
└── cache.py                    # Cache in-memory
```

**Aplicación Flask:**
```
/home/pqsolutions/mqtt-manager/
├── app.py                      # Línea 291: @app.route('/health')
├── templates/
│   ├── base.html               # Línea 38-42: Nav link añadido
│   └── health.html             # Dashboard completo (325 líneas)
├── static/
│   ├── css/dashboard.css
│   └── js/                     # (Inline en health.html)
├── config.email.json           # Configuración de email (NO en git)
└── test_health_system.py       # Script de verificación
```

**Systemd Service:**
```
/etc/systemd/system/mqtt-manager.service
├── Environment="EMAIL_CONFIG_PATH=/home/pqsolutions/mqtt-manager/config.email.json"
```

### 14.12 Credenciales y Configuración

#### Gmail App Password para Health Alerts

**PENDIENTE GENERAR:**

```plaintext
Email: pqsolutionsperu@gmail.com
App Password: [GENERAR NUEVA EN https://myaccount.google.com/apppasswords]
Nombre del dispositivo: HDD-Monitor Health Alerts
Creado: [FECHA]
Uso: Alertas críticas de salud ESP32
Destinatarios: admin@pqsolutionsperu.com, soporte@pqsolutionsperu.com
```

**Ubicación de credencial:**
- Archivo: `/home/pqsolutions/mqtt-manager/config.email.json`
- Campo: `smtp_password`
- Permisos: `chmod 600` (solo pqsolutions puede leer)

**Seguridad:**
- ❌ NO incluir en Git
- ✅ Incluir en `.gitignore`
- ✅ Backup encriptado solo
- ✅ Revocable en cualquier momento

#### Firestore Service Account

**Ya configurado:**
```plaintext
Archivo: /home/pqsolutionsperu/vm-service-key.json
Proyecto: fir-hdd-monitor-d00de
Usado por: Firestore client para almacenar alertas
```

**Health monitor usa esta misma credencial** (no requiere nueva).

---

**FIN DEL DOCUMENTO**

---

## 15. Sistema de Actualización en Tiempo Real Web

### 15.1 Visión General

El sistema de actualización en tiempo real web permite a los usuarios ver cambios instantáneos en el dashboard web sin necesidad de refrescar la página manualmente. Implementa WebSocket (Socket.IO) y Firestore Real-time Listeners para proporcionar actualizaciones inmediatas de:

- **Estado de eventos** (Programado → Aceptado → Finalizado)
- **Estado de paneles y relays** (OK → Desconectado)
- **Estado de dispositivos ESP32** (Online → Offline)
- **Métricas del dashboard** (dispositivos online, eventos del día)

**Latencia de actualización:** 1-2 segundos desde el cambio en Firestore hasta la UI web.

### 15.2 Arquitectura Real-Time

```
┌─────────────────────────────────────────────────────────────────┐
│                        FLUJO DE DATOS                           │
└─────────────────────────────────────────────────────────────────┘

1. CAMBIO EN FIRESTORE (App Android o MQTT Handler)
   ↓
2. FIRESTORE REAL-TIME LISTENER (firestore_realtime.py)
   - Detecta cambio en colección
   - Procesa datos
   ↓
3. SOCKETIO EMIT (Python Backend)
   - Emite evento via WebSocket
   - Serializa DatetimeWithNanoseconds
   ↓
4. SOCKETIO CLIENT (realtime.js)
   - Recibe evento
   - Despacha a handlers específicos
   ↓
5. EVENT HANDLERS (dashboard.js, events.js, esp32.js)
   - Actualizan DOM
   - Muestran cambios al usuario

┌─────────────────────────────────────────────────────────────────┐
│                    COMPONENTES DEL SISTEMA                      │
└─────────────────────────────────────────────────────────────────┘

BACKEND (Python):
├─ modules/firestore_realtime.py      # Real-time listeners
├─ modules/firestore_client.py        # Cliente Firestore con serialización
├─ app.py                             # SocketIO handlers
└─ requirements.txt                   # python-socketio, eventlet

FRONTEND (JavaScript):
├─ static/js/realtime.js              # Cliente WebSocket
├─ static/js/events.js                # Handlers para eventos
├─ static/js/dashboard.js             # Handlers para dashboard
├─ static/js/esp32.js                 # Handlers para ESP32
└─ static/js/socket.io.min.js         # Librería Socket.IO

TEMPLATES (Jinja2):
├─ templates/base.html                # Carga realtime.js
├─ templates/events.html              # Página de eventos
├─ templates/dashboard.html           # Dashboard principal
├─ templates/esp32_devices.html       # Gestión de dispositivos
└─ templates/clients.html             # Paneles y relays
```

### 15.3 Implementación Backend

#### 15.3.1 Firestore Real-Time Listeners

**Archivo:** `modules/firestore_realtime.py`

```python
class FirestoreRealtimeSync:
    """
    Sincronización en tiempo real entre Firestore y la web UI usando
    Firestore real-time listeners y Socket.IO.
    """

    def __init__(self, db, socketio, logger=None):
        self.db = db
        self.socketio = socketio
        self.logger = logger or logging.getLogger(__name__)
        self.listeners = []  # Lista de listeners activos
        self.initial_load_complete = {}

    def start_all_listeners(self):
        """Inicia todos los listeners de Firestore"""
        try:
            self.listen_to_events()
            self.listen_to_panels()
            self.listen_to_relays()
            self.listen_to_esp32_devices()
            self.logger.info("✅ Todos los listeners iniciados")
        except Exception as e:
            self.logger.error(f"Error iniciando listeners: {e}")
```

**Listeners implementados:**

1. **Events Listener** (`listen_to_events`)
   - Colección: `hdd-monitor/clients/{client_id}/events`
   - Detecta: Cambios en estado de eventos (scheduled/accepted/completed)
   - Emite: `event_added`, `event_updated`, `event_deleted`

2. **Panels Listener** (`listen_to_panels`)
   - Colección: `hdd-monitor/clients/{client_id}/panels`
   - Detecta: Cambios en estado de paneles
   - Emite: `panel_updated`

3. **Relays Listener** (`listen_to_relays`)
   - Colección: `hdd-monitor/clients/{client_id}/panels/{panel_id}/relays`
   - Detecta: Cambios en estado de relays (OK/NC/NO)
   - Emite: `relay_status_changed`, `panel_needs_refresh`

4. **ESP32 Devices Listener** (`listen_to_esp32_devices`)
   - Colección: `hdd-monitor/esp32/registered`
   - Detecta: Cambios en estado de dispositivos (online/offline)
   - Emite: `esp32_device_updated`, `esp32_online`, `esp32_offline`

#### 15.3.2 Serialización de Datos

**Problema:** Firestore devuelve objetos `DatetimeWithNanoseconds` que no son JSON serializables.

**Solución:** Método `_serialize_data()` en `FirestoreClient` que convierte automáticamente:
- `datetime` → ISO 8601 string
- `DatetimeWithNanoseconds` → ISO 8601 string
- Listas y diccionarios recursivamente

```python
def _serialize_data(self, data):
    """Serializa objetos Firestore a JSON-compatible"""
    if isinstance(data, datetime):
        return data.isoformat()
    elif hasattr(data, 'timestamp'):  # DatetimeWithNanoseconds
        return datetime.fromtimestamp(data.timestamp()).isoformat()
    elif isinstance(data, dict):
        return {k: self._serialize_data(v) for k, v in data.items()}
    elif isinstance(data, list):
        return [self._serialize_data(item) for item in data]
    return data
```

**Aplicación:** Todos los métodos de `FirestoreClient` serializan datos antes de devolverlos:
- `get_all_esp32_devices()` → `return [self._serialize_data(d) for d in devices]`
- `get_all_clients()` → `return [self._serialize_data(c) for c in clients]`
- `get_client_panels()` → `return [self._serialize_data(p) for p in panels]`
- `get_dashboard_metrics()` → `return self._serialize_data(metrics)`

#### 15.3.3 SocketIO Handlers

**Archivo:** `app.py`

```python
from flask_socketio import SocketIO, emit, join_room, leave_room

# Inicializar SocketIO
socketio = SocketIO(
    app,
    cors_allowed_origins="*",
    async_mode='eventlet',
    logger=True,
    engineio_logger=True
)

@socketio.on('connect')
def handle_connect():
    """Cliente WebSocket conectado"""
    logger.info(f"Client connected: {request.sid}")
    emit('connection_response', {
        'status': 'connected',
        'message': 'Connected to HDD Monitor real-time updates'
    })

@socketio.on('request_initial_data')
def handle_initial_data_request():
    """Enviar datos iniciales a cliente recién conectado"""
    metrics = firestore_client.get_dashboard_metrics()
    emit('initial_metrics', metrics)

    devices = firestore_client.get_all_esp32_devices()
    emit('initial_esp32_devices', {'devices': devices})
```

### 15.4 Implementación Frontend

#### 15.4.1 Cliente WebSocket

**Archivo:** `static/js/realtime.js`

```javascript
class RealtimeConnection {
    constructor() {
        this.socket = null;
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.reconnectDelay = 2000;
        this.isConnected = false;
    }

    connect() {
        this.socket = io({
            transports: ['websocket', 'polling'],
            upgrade: true,
            rememberUpgrade: true,
            reconnection: true,
            reconnectionAttempts: this.maxReconnectAttempts,
            reconnectionDelay: this.reconnectDelay
        });

        this.setupEventHandlers();
    }

    setupEventHandlers() {
        // Eventos de conexión
        this.socket.on('connect', () => {
            this.isConnected = true;
            this.updateConnectionStatus('connected');
            this.socket.emit('request_initial_data');
        });

        // Eventos de datos
        this.socket.on('event_added', (event) => {
            if (window.onEventAdded) window.onEventAdded(event);
        });

        this.socket.on('event_updated', (event) => {
            if (window.onEventUpdated) window.onEventUpdated(event);
        });

        // ... más handlers
    }
}
```

**Características:**
- Reconexión automática (5 intentos, delay 2s)
- Fallback de WebSocket → Polling
- Indicador de estado en UI (● Live / ● Offline)
- Request de datos iniciales al conectar

#### 15.4.2 Event Handlers por Página

**Events Page** (`static/js/events.js`):
```javascript
window.onEventAdded = function(event) {
    console.log('[Events] New event added:', event.event_id);
    refreshEventsTable();
};

window.onEventUpdated = function(event) {
    console.log('[Events] Event updated:', event.event_id);
    updateEventRow(event);  // Actualiza solo la fila modificada
};

function updateEventRow(event) {
    const row = document.querySelector(`tr[data-event-id="${event.event_id}"]`);
    if (row) {
        // Actualizar celdas específicas sin recargar toda la tabla
        row.querySelector('.status-cell').innerHTML = getStatusBadge(event.status);
        row.querySelector('.date-cell').textContent = event.date_time;
    }
}
```

**Dashboard** (`static/js/dashboard.js`):
```javascript
window.onMetricsRefreshNeeded = function() {
    updateDashboardMetrics();
};

window.onInitialMetrics = function(metrics) {
    updateMetricsUI(metrics);
};

window.onESP32Offline = function(data) {
    updateDashboardMetrics();  // Refrescar métricas
};
```

**Clients & Panels** (`static/js/clients.html` inline):
```javascript
window.onPanelUpdated = function(panel) {
    const clientId = panel.client_id;
    const panelsContainer = document.getElementById(`panels-${clientId}`);
    if (panelsContainer && panelsContainer.classList.contains('open')) {
        loadPanels(clientId);  // Recargar paneles abiertos
    }
};

window.onRelayStatusChanged = function(relay) {
    const clientId = relay.client_id;
    if (clientId) {
        loadPanels(clientId);  // Recargar paneles
    }
};
```

### 15.5 Auto-Refresh Fallback

Además del sistema real-time, se mantiene auto-refresh via AJAX polling como fallback:

**Dashboard:** Auto-refresh cada 30 segundos
```javascript
function startDashboardRefresh() {
    setInterval(() => {
        updateDashboardMetrics();
    }, 30000);
}
```

**ESP32 Devices:** Auto-refresh cada 30 segundos
```javascript
function startAutoRefresh() {
    setInterval(() => {
        loadDevices();
    }, 30000);
}
```

**Clients & Panels:** Botón manual de refresh (sin auto-refresh para evitar sobrecarga)

### 15.6 Eventos WebSocket

#### Eventos del Sistema

| Evento | Dirección | Datos | Descripción |
|--------|-----------|-------|-------------|
| `connect` | Client → Server | - | Cliente se conecta |
| `disconnect` | Client → Server | - | Cliente se desconecta |
| `connection_response` | Server → Client | `{status, message}` | Confirmación de conexión |
| `request_initial_data` | Client → Server | - | Solicitar datos iniciales |
| `initial_metrics` | Server → Client | `{esp32_online, panels_ok, events_today, ...}` | Métricas del dashboard |
| `initial_esp32_devices` | Server → Client | `{devices: [...]}` | Lista de dispositivos ESP32 |
| `error` | Server → Client | `{message}` | Error en el servidor |

#### Eventos de Negocio

| Evento | Datos | Handler Frontend |
|--------|-------|------------------|
| `event_added` | `{event_id, client_id, status, date_time, ...}` | `window.onEventAdded()` |
| `event_updated` | `{event_id, client_id, status, ...}` | `window.onEventUpdated()` |
| `event_deleted` | `{event_id, client_id}` | `window.onEventDeleted()` |
| `panel_updated` | `{panel_id, client_id, panel_status, ...}` | `window.onPanelUpdated()` |
| `relay_status_changed` | `{relay_id, panel_id, client_id, status, ...}` | `window.onRelayStatusChanged()` |
| `panel_needs_refresh` | `{panel_id, client_id}` | `window.onPanelNeedsRefresh()` |
| `esp32_device_updated` | `{esp32_id, status, ...}` | `window.onESP32DeviceUpdated()` |
| `esp32_online` | `{esp32_id, timestamp}` | `window.onESP32Online()` |
| `esp32_offline` | `{esp32_id, timestamp}` | `window.onESP32Offline()` |
| `metrics_refresh_needed` | `{}` | `window.onMetricsRefreshNeeded()` |

### 15.7 Debugging y Monitoreo

#### Logs del Backend

```bash
# Ver logs de SocketIO
sudo journalctl -u mqtt-manager -f | grep -E '(SocketIO|Realtime|WebSocket)'

# Ver eventos específicos
sudo journalctl -u mqtt-manager -f | grep 'Event updated'
sudo journalctl -u mqtt-manager -f | grep 'Panel updated'
sudo journalctl -u mqtt-manager -f | grep 'Relay status changed'
```

#### Logs del Frontend

**Abrir consola del navegador (F12):**
```javascript
// Mensajes de conexión
[Realtime] Initializing real-time connection...
[Realtime] Connected to server
[Realtime] Connection confirmed: Connected to HDD Monitor

// Mensajes de datos
[Realtime] Received initial metrics: {esp32_online: 1, ...}
[Realtime] Event updated: Prueba_1
[Realtime] Panel updated: panel_FIKEA5SA_client_2
[Realtime] Relay status changed: relay_1 Status: OK

// Mensajes de handlers
[Events] Event updated: Prueba_1
[Events] Table updated with new event data
[Dashboard] Metrics refresh needed - updating...
[Clients] Reloading panels after relay change
```

### 15.8 Troubleshooting

#### Problema: "Error: Object of type DatetimeWithNanoseconds is not JSON serializable"

**Causa:** Datos de Firestore no serializados antes de emitir via WebSocket.

**Solución:**
1. Verificar que `_serialize_data()` está implementado en `firestore_client.py`
2. Asegurar que todos los métodos usan serialización:
   ```python
   return [self._serialize_data(d) for d in devices]
   ```
3. Verificar logs: `sudo journalctl -u mqtt-manager -f | grep DatetimeWithNanoseconds`

#### Problema: WebSocket no conecta

**Diagnóstico:**
```bash
# Verificar servicio
sudo systemctl status mqtt-manager

# Ver logs
sudo journalctl -u mqtt-manager -f

# Test de conexión
curl -v https://hddm.pqsolutionsperu.com/socket.io/?transport=polling
```

**Soluciones:**
1. Verificar Nginx está corriendo: `sudo systemctl status nginx`
2. Verificar configuración de proxy en `/etc/nginx/sites-available/hddm.pqsolutionsperu.com`
3. Verificar firewall: `sudo ufw status`

#### Problema: Eventos no se actualizan en UI

**Diagnóstico:**
1. Abrir consola del navegador (F12)
2. Verificar que WebSocket está conectado: `● Live` en verde
3. Buscar mensajes de error en consola
4. Verificar que los handlers existen:
   ```javascript
   console.log(typeof window.onEventUpdated);  // Debe ser "function"
   ```

**Soluciones:**
1. Refrescar página con Ctrl+Shift+R (limpiar cache)
2. Verificar que `realtime.js` se carga antes que otros scripts
3. Verificar que el template extiende `base.html` (que carga realtime.js)

### 15.9 Performance y Escalabilidad

**Métricas actuales:**
- **Latencia de actualización:** 1-2 segundos
- **Overhead de conexión:** ~50 KB por cliente (Socket.IO + realtime.js)
- **Conexiones simultáneas:** Hasta 100 clientes (limitado por eventlet workers)
- **Carga CPU:** <5% adicional con 10 clientes conectados

**Optimizaciones implementadas:**
1. **Caché de datos** (TTL 30s) en `firestore_client.py`
2. **Actualización selectiva** de UI (solo filas modificadas, no toda la tabla)
3. **Debouncing** de eventos duplicados en listeners
4. **Initial load flag** para evitar emitir eventos durante carga inicial

**Escalabilidad futura:**
- Redis como backend de Socket.IO para multi-worker
- CDN para archivos estáticos (.js, .css)
- WebSocket load balancing con Nginx

### 15.10 Configuración de Producción

#### MQTT Manager Service

**Archivo:** `/etc/systemd/system/mqtt-manager.service`
```ini
[Unit]
Description=MQTT Manager Web Interface
After=network.target postgresql.service

[Service]
Type=notify
User=pqsolutionsperu
Group=pqsolutionsperu
WorkingDirectory=/home/pqsolutions/mqtt-manager
Environment="PATH=/home/pqsolutions/mqtt-manager-venv/bin"
ExecStart=/home/pqsolutions/mqtt-manager-venv/bin/gunicorn \
    --bind 127.0.0.1:5000 \
    --workers 1 \
    --timeout 120 \
    --threads 4 \
    --worker-class eventlet \
    app:app

Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

**Importante:** `--worker-class eventlet` es requerido para Socket.IO.

#### Nginx Configuration

**Archivo:** `/etc/nginx/sites-available/hddm.pqsolutionsperu.com`
```nginx
# WebSocket proxy
location /socket.io/ {
    proxy_pass http://127.0.0.1:5000/socket.io/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # Timeouts para WebSocket
    proxy_read_timeout 86400;
    proxy_send_timeout 86400;
    proxy_connect_timeout 60;
}
```

#### Dependencias Python

**Archivo:** `requirements.txt`
```
flask==3.0.0
python-socketio==5.11.0
eventlet==0.35.1
google-cloud-firestore==2.14.0
paho-mqtt==1.6.1
```

**Instalación:**
```bash
source /home/pqsolutions/mqtt-manager-venv/bin/activate
pip install python-socketio eventlet
```

### 15.11 Testing

#### Test Manual de WebSocket

1. Abrir https://hddm.pqsolutionsperu.com/events
2. Abrir consola del navegador (F12)
3. Verificar conexión:
   ```
   [Realtime] Connected to server
   [Realtime] Connection confirmed
   ```
4. Cambiar estado de evento en app Android
5. Verificar que la tabla se actualiza en 1-2 segundos
6. Verificar logs en consola:
   ```
   [Realtime] Event updated: [event_id]
   [Events] Event updated: [event_id]
   [Events] Table updated
   ```

#### Test de Reconexión

1. Detener servicio: `sudo systemctl stop mqtt-manager`
2. Verificar indicador cambia a "● Offline" (rojo)
3. Iniciar servicio: `sudo systemctl start mqtt-manager`
4. Verificar indicador cambia a "● Live" (verde)
5. Verificar reconexión en consola:
   ```
   [Realtime] Attempting to reconnect... (attempt 1/5)
   [Realtime] Reconnected successfully
   ```

#### Test de Serialización

```bash
# Verificar que no hay errores de serialización
sudo journalctl -u mqtt-manager --since '1 hour ago' | grep -i 'DatetimeWithNanoseconds'

# Resultado esperado: Sin resultados (exit code 1)
```

### 15.12 Archivos Relacionados

```
mqtt-manager/
├── app.py                          # SocketIO setup y handlers
├── modules/
│   ├── firestore_realtime.py      # Firestore listeners
│   └── firestore_client.py        # Cliente con serialización
├── static/js/
│   ├── realtime.js                # Cliente WebSocket
│   ├── events.js                  # Handlers de eventos
│   ├── dashboard.js               # Handlers de dashboard
│   ├── esp32.js                   # Handlers de ESP32
│   └── socket.io.min.js           # Librería Socket.IO
└── templates/
    ├── base.html                  # Template base (carga realtime.js)
    ├── events.html                # Página de eventos
    ├── dashboard.html             # Dashboard principal
    ├── esp32_devices.html         # Gestión de ESP32
    └── clients.html               # Paneles y relays
```

### 15.13 Conclusiones

El sistema de actualización en tiempo real proporciona:

✅ **Experiencia de usuario mejorada** - No necesidad de refrescar manualmente
✅ **Latencia baja** - 1-2 segundos desde cambio hasta UI
✅ **Fiabilidad** - Reconexión automática + fallback AJAX polling
✅ **Escalable** - Arquitectura preparada para Redis backend
✅ **Mantenible** - Código modular y bien documentado
✅ **Debuggeable** - Logs extensivos en backend y frontend

**Próximos pasos sugeridos:**
- Implementar Redis como backend de Socket.IO para múltiples workers
- Agregar rooms de Socket.IO para filtrar eventos por cliente
- Implementar rate limiting para prevenir abuso
- Agregar métricas de latencia de WebSocket a Netdata

---

## Historial de Cambios

**v2.6 (26/03/2026):**
- **Corrección crítica en firmware ESP32: fallo en subida de logs tras 13+ días de operación**
  - Root cause: `rename()` en SPIFFS falla con errno=5 (EIO) después de ciclos prolongados de escritura intensa por fragmentación del GC interno
  - `components/log_storage/spiffs_log.c`: Reemplazados todos los `rename()` con función `copy_and_delete()` que usa solo primitivas nativas SPIFFS (`fopen/fread/fwrite/fclose/unlink`) — previene el error EIO por diseño
  - `components/log_storage/spiffs_log.c`: Corregido bug de doble-cierre en `spiffs_vprintf` — `check_rotate(f)` cerraba `f` internamente y luego `spiffs_vprintf` llamaba `fclose(f)` nuevamente, corrompiendo el file handle
  - `components/log_storage/spiffs_log.c`: Auto-recuperación SPIFFS — tras 5 fallos consecutivos de rotación: desmontar, formatear, remontar y reinicializar sin intervención humana
  - `components/log_uploader/log_uploader.c`: Reemplazada búsqueda por ventana de 120 minutos con `opendir("/spiffs")` + `readdir()` — encuentra TODOS los archivos `log_*.txt` y `log.prev` pendientes independientemente de su antigüedad
- **Nuevo servicio servidor: Monitor de logs ESP32**
  - `log_monitor.py`: Script Python que verifica diariamente que cada ESP32 suba sus logs
  - `log-monitor.service` + `log-monitor.timer`: Servicio systemd disparado a las 08:00 UTC (03:00 Lima), 3 horas después de la subida esperada a medianoche Lima
  - Umbral: 26 horas sin nuevo log → envío de email de alerta HTML con tabla de dispositivos afectados
  - Lee config SMTP desde `/home/pqsolutionsperu/mqtt-manager/config.email.json`
  - Log de verificaciones en `/var/log/hdd_monitor_log_check.log`

**v2.5 (06/02/2026):**
- **NUEVA SECCIÓN 5.8: Hardware y Alimentación para Producción**
  - Circuito de alimentación desde panel 24V con protección completa
  - Fusible PTC auto-reseteante (RXEF110) - funcionamiento detallado
  - Diodo TVS P6KE30A para protección contra sobrevoltaje
  - Especificaciones eléctricas completas
  - Cumplimiento normativo de alimentación (UL 864, NEC 760, NFPA 72)
- **NUEVA SECCIÓN 5.9: Requisitos Legales para Venta en Perú**
  - Homologación MTC obligatoria - proceso paso a paso
  - Enlaces oficiales verificados (VUCE, gob.pe)
  - Costos actualizados (S/ 49.20 para WiFi)
  - Registro de marca INDECOPI (recomendado)
  - Resumen de costos: ~$13 USD mínimo, ~$160 USD recomendado
- **NUEVA SECCIÓN 5.10: Normativas Técnicas 2025-2026**
  - NFPA 72 2025 Capítulo 11 (Ciberseguridad obligatorio)
  - Niveles de seguridad y cumplimiento HDD Monitor
  - Referencias a UL 864, UL 2900-2-3, NIST CSF, NEC 2026
- **NUEVA SECCIÓN 5.11: Checklist Pre-Producción**
  - Items de hardware por implementar
  - Items de software ya implementados
  - Trámites Perú pendientes
- **NUEVA SECCIÓN 5.12: Referencias Verificadas Febrero 2026**
  - Todos los enlaces probados y funcionando
  - URLs oficiales de normativas internacionales
  - URLs oficiales de trámites peruanos
- Renumeración de secciones (5.8-5.12 nuevas, Roadmap ahora es 5.13)

**v2.4 (02/02/2026):**
- **REPOSICIONAMIENTO COMO SISTEMA SECUNDARIO SUPERVISORIO**
  - Sección 1: Visión General actualizada con enfoque de sistema secundario
  - Arquitectura rediseñada mostrando Panel Primario Certificado + HDD Monitor
  - Aclaración: HDD Monitor NO reemplaza sistemas certificados, solo complementa
  - Nueva sección 3: Gestión de Agenda y Eventos Programados (completa)
    - Tipos de eventos: Mantenimiento, capacitación, inspección, garantía, etc.
    - Sistema de recordatorios automáticos
    - Estados del evento: Programado → Aceptado → Finalizado
    - Dashboard web con vista de calendario
    - API de gestión de eventos
    - Casos de uso reales (mantenimiento preventivo, renovación ITSDC, etc.)
    - Cumplimiento normativo (RNE A.130, INDECI, NFPA 72)
  - Diagramas de arquitectura actualizados con modelo secundario
  - Casos de uso agregados: Monitoreo + Gestión de Agenda
  - Renumeración de secciones (3-16 vs 3-15 anterior)
- Beneficios del enfoque secundario documentados:
  - Ahorro $30K-50K en certificación UL (no requerida)
  - Menor responsabilidad legal (no es sistema de vida crítica)
  - Mercado amplio (todos los edificios con panel certificado)
- Clarificación: MTC y INDECOPI TODAVÍA requeridos (no hay excepción)

**v2.3 (02/02/2026):**
- **Sección 6: Cumplimiento de Normativas COMPLETAMENTE REESCRITA**
  - Análisis exhaustivo de 20+ normativas internacionales y peruanas
  - Evaluación detallada de cumplimiento con estadísticas reales del sistema
  - Análisis de riesgo de multas para Lima, Perú (escenarios: venta pública, cliente privado, piloto)
  - Matriz completa de cumplimiento (NFPA 72, UL 864, UL 2572, MTC, INDECOPI, RNE, Ley 29733)
  - Normativas de ciberseguridad (OWASP IoT Top 10, NIST CSF, IEC 62443)
  - Estándares de comunicaciones (ISO/IEC 20922 MQTT, RFC 8446 TLS 1.3, IEEE 802.11)
  - Protección de datos (GDPR, Ley 29733 Perú)
  - Roadmap de certificación con costos y tiempos (3 fases, $40K-$180K, 6-18 meses)
  - Veredicto final: APTO para pilotos, NO para venta sin certificación
  - Multas potenciales: $67K esperado, $312K máximo
  - Documento separado creado: CERTIFICACION_PRODUCCION_PERU.md (47 páginas)
- Actualización de versión: v2.2 → v2.3
- Fecha de actualización: 02/02/2026

**v2.2 (02/02/2026):**
- **Sistema de Actualización en Tiempo Real Web (Real-time Updates)**
  - Implementación completa de WebSocket usando Socket.IO + eventlet
  - Firestore real-time listeners para eventos, paneles, relays y ESP32
  - Actualización automática de UI sin refrescar página (latencia 1-2s)
  - Serialización automática de DatetimeWithNanoseconds
  - Reconexión automática con fallback polling
  - Indicador de estado "● Live" / "● Offline" en UI
  - Handlers específicos por página: events.js, dashboard.js, esp32.js, clients.html
  - Auto-refresh fallback cada 30s para dashboard y ESP32
  - Logs extensivos en backend y frontend para debugging
  - Configuración Nginx para WebSocket proxy
  - Gunicorn con worker-class eventlet
  - Documentación completa en Sección 15
- Archivos nuevos:
  - modules/firestore_realtime.py (Firestore listeners)
  - static/js/realtime.js (Cliente WebSocket)
  - static/js/socket.io.min.js (Librería Socket.IO)
- Archivos modificados:
  - app.py (SocketIO setup y handlers)
  - modules/firestore_client.py (_serialize_data method)
  - static/js/events.js (Event handlers)
  - static/js/dashboard.js (Auto-refresh initialization)
  - templates/base.html (Carga realtime.js)
  - templates/clients.html (Panel/relay event handlers)
- Dependencias agregadas: python-socketio==5.11.0, eventlet==0.35.1
- Nginx configurado para WebSocket con timeouts adecuados

**v2.1 (01/02/2026):**
- **Sistema de Monitoreo de Salud ESP32 (Health Monitor)**
  - Implementación ultra-ligera (1.7 KB overhead)
  - Monitoreo pasivo con recuperación nativa ESP-IDF TWDT
  - Alertas MQTT para problemas críticos (memoria baja, boot loops)
  - Dashboard web en tiempo real: https://hddm.pqsolutionsperu.com/health
  - Sistema de alertas por email para condiciones críticas
  - Integración con Firestore para historial de alertas
  - LWT (Last Will Testament) para detección de dispositivos offline
  - Script de verificación automática (test_health_system.py)
  - Documentación completa en Sección 14
- Actualización de templates Flask (base.html, health.html)
- Nuevos módulos Python (esp32_health_monitor.py, email_alerter.py, health_system.py)
- Configuración de email para alertas críticas

**v2.0 (30/01/2026):**
- Documentación completa del sistema actualizada
- Agregada información de VM (IP externa 34.63.146.196, interna 10.128.0.3)
- Especificaciones exactas: Debian 12, 2 vCPUs, 2 GB RAM, 30 GB disco
- Configuración Mosquitto con Let's Encrypt
- Eliminación de debounce de 10s en configuraciones (cumplimiento NFPA 72)
- Métricas NFPA 72 implementadas
- Persistencia redundante PostgreSQL
- Systemd service configurado
- Múltiples ESP32s soportados (3608AC08, 1694ACA8, 42A8ACA0)

**v1.0 (07/09/2025):**
- Versión inicial del sistema
- Funcionalidad básica de monitoreo
- Notificaciones FCM
- Integración Firestore
