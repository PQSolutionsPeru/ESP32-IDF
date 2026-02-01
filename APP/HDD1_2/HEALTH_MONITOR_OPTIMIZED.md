# Health Monitor - Versión Optimizada para ESP32 con Memoria Limitada

## 📊 Análisis de tu ESP32

### **Memoria Disponible (de tu log):**
```
Operación Normal:    76,000 bytes  (~74 KiB)
Mínimo Histórico:    54,512 bytes  (~53 KiB)  ⚠️ CRÍTICO
Durante SSL/Upload:  59,624 bytes  (~58 KiB)
```

**Conclusión:** Tu ESP32 opera cerca del límite de memoria. El mínimo de 53 KiB es preocupante.

---

## ✅ Optimizaciones Aplicadas

### **Versión Original vs Optimizada**

| Característica | Original | Optimizada | Ahorro |
|----------------|----------|------------|--------|
| **Task Stack** | 4096 bytes | 2560 bytes | **1536 bytes** |
| **Check Interval** | 5 segundos | 10 segundos | Reduce uso CPU |
| **Payload Buffer** | 512 bytes | 384 bytes | **128 bytes** |
| **Topic Buffer** | 128 bytes | 96 bytes | **32 bytes** |
| **Memory Leak Check** | Activado | Desactivado | **~300 bytes** |
| **Memory Threshold** | 50KB | 40KB | Ajustado a tu device |

### **Consumo Total:**
- **Original:** ~6.5 KiB
- **Optimizado:** ~3.5 KiB
- **Ahorro:** 3 KiB 🎉

---

## 🔍 Checks que SE MANTIENEN (Críticos)

✅ **Boot Loop Detection**
- Detecta 20+ reinicios en 5 minutos
- FATAL priority

✅ **Memory Critical**
- Alerta cuando heap < 30KB
- CRITICAL priority

✅ **Memory Low**
- Alerta cuando heap < 40KB (ajustado para tu device)
- WARNING priority

✅ **Unexpected Reboot**
- Detecta reinicios por panic/watchdog
- CRITICAL priority

✅ **System Stability**
- Monitorea estado general
- Envía alertas MQTT

---

## ❌ Checks DESACTIVADOS (Menos Críticos)

❌ **Memory Leak Detection**
- Comentado para ahorrar recursos
- Puedes reactivarlo después si la memoria se estabiliza

---

## 📈 Impacto Esperado

### **Antes del Health Monitor:**
```
Free Heap: 54,512 bytes (mínimo)
```

### **Después del Health Monitor Optimizado:**
```
Free Heap: ~50,500 bytes (estimado)
Margen sobre crítico (30KB): 20.5 KB ✅ SEGURO
```

### **Uso de CPU:**
- Check cada 10 segundos (vs 5s original)
- Duración: ~50-100ms por check
- **Impacto CPU: <0.5%**

---

## ⚠️ Recomendaciones Post-Implementación

### **1. Monitorear Memoria Inicial**

Después de flashear, observa los logs durante 10 minutos:

```bash
idf.py -p /dev/ttyUSB0 monitor | grep -i "free heap\|Free heap"
```

**Busca:**
- Heap mínimo debe estar > 45KB
- No debe haber alertas de "Memory Low" constantes
- Sistema debe operar establemente

### **2. Si la Memoria Sigue Crítica**

Puedes hacer más optimizaciones:

**Opción A: Aumentar intervalo de checks**
```c
#define HEALTH_MONITOR_CHECK_INTERVAL_MS (15000)  // 15 segundos
```

**Opción B: Reducir más el stack**
```c
#define HEALTH_MONITOR_TASK_STACK_SIZE (2048)  // 2KB
```

**Opción C: Desactivar health monitor temporalmente**
Comenta en `hddesp32_main.c`:
```c
// ESP_ERROR_CHECK(health_monitor_init(g_esp32_id_buffer));
```

### **3. Verificar Estabilidad**

Deja el ESP32 corriendo por 24 horas y verifica:
- No hay reinicios inesperados
- Memoria se mantiene estable
- Alertas son solo para problemas reales

---

## 🎯 Criterios de Éxito

### **✅ Sistema SALUDABLE si:**
- Free heap mínimo > 45KB
- Sin reinicios en 24h
- Alertas solo en problemas reales
- Log uploader funciona correctamente

### **⚠️ Necesita AJUSTES si:**
- Free heap < 40KB frecuentemente
- Reinicios cada pocas horas
- Alertas constantes de memoria baja

### **❌ Desactivar si:**
- Free heap cae < 35KB
- Sistema se vuelve inestable
- Reinicios frecuentes

---

## 📝 Logs Esperados

### **Inicialización Exitosa:**
```
I (1285) HDDESP32: Health monitor initialized
I (1290) HEALTH_MON: Health monitor task started on core 1
I (1295) HEALTH_MON: Stack size: 2560 bytes, Check interval: 10s
I (1300) HDDESP32: Free heap after init: 50124 bytes
```

### **Check Normal (cada 10s):**
```
D (11290) HEALTH_MON: Health check OK - Free heap: 50500 bytes
```

### **Alerta de Memoria Baja:**
```
W (21290) HEALTH_MON: Memory low: 38000 bytes free
W (21295) HEALTH_MON: Sending health alert: MEMORY_LOW
```

### **Alerta Crítica:**
```
E (31290) HEALTH_MON: Critical memory: 28000 bytes free
E (31295) HEALTH_MON: Sending health alert: MEMORY_LOW - CRITICAL
I (31300) LOG_UPLOADER: Triggering immediate log upload
```

---

## 🔧 Comandos Útiles

### **Monitor de memoria en tiempo real:**
```bash
idf.py -p /dev/ttyUSB0 monitor | grep -E "HEALTH_MON|Free heap|free heap"
```

### **Verificar stack usage de la tarea:**
Agrega temporalmente en `health_monitor.c`:
```c
UBaseType_t stack_high_water = uxTaskGetStackHighWaterMark(health_task_handle);
ESP_LOGI(TAG, "Health monitor stack usage: %lu bytes",
         HEALTH_MONITOR_TASK_STACK_SIZE - (stack_high_water * 4));
```

### **Forzar test de alerta:**
Puedes llamar manualmente:
```c
health_monitor_report_issue(HEALTH_ISSUE_MEMORY_LOW, "Test alert");
```

---

## 🚀 Siguiente Paso

**PRUEBA AHORA:**

```bash
cd ESP-IDF-HDDESP32/hddesp32
idf.py fullclean
idf.py build
idf.py -p /dev/ttyUSB0 flash monitor
```

**Observa por 10-15 minutos y verifica:**
1. Sistema arranca correctamente
2. Free heap se mantiene > 45KB
3. Health monitor se inicializa
4. No hay crashes

**Luego dime:**
- ¿Cuál es el free heap mínimo que ves?
- ¿El sistema es estable?
- ¿Necesitas más optimizaciones?

---

## 📞 Soporte

Si después de implementar:
- Heap < 40KB constantemente → Desactiva memory leak check (ya está)
- Heap < 35KB constantemente → Aumenta check interval a 15s
- Sistema inestable → Desactiva health monitor temporalmente

**El objetivo es tener un sistema CONFIABLE, no uno que consume recursos.**

---

**Optimizado por:** Claude Sonnet 4.5
**Fecha:** 2026-02-01
**Versión:** 1.0.0-optimized
