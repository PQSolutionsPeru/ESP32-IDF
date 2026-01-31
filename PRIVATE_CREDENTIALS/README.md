# 🔐 PRIVATE CREDENTIALS - NO SUBIR A GIT

Esta carpeta contiene información sensible que **NUNCA** debe subirse a repositorios públicos.

## 📄 DOCUMENTO PRINCIPAL

### ⭐ **VM_MASTER_DOCUMENTATION.md** ⭐
**🎯 ARCHIVO MAESTRO - LEE ESTE PRIMERO**

Documento unificado de **82KB** que contiene **TODO** sobre la VM:
- Arquitectura completa
- Credenciales y contraseñas
- Deployment paso a paso
- Código fuente completo
- Configuración de servicios
- Troubleshooting
- Monitoreo NFPA 72
- Scripts auxiliares

**Este es el único documento que necesitas para operar la VM.**

---

## 📁 Archivos Adicionales (Documentación de Soporte):

### 1. `ARQUITECTURA_VM.md`
**Contiene:** Arquitectura detallada de la VM, servicios, estructura de directorios
**Incluido en:** VM_MASTER_DOCUMENTATION.md

### 2. `DEPLOYMENT_GUIDE.md`
**Contiene:** Guía de deployment completa con comandos paso a paso
**Incluido en:** VM_MASTER_DOCUMENTATION.md (Sección 4)

### 3. `UPLOAD_FILES_GCP.md`
**Contiene:** Cómo subir archivos vía SSH browser de GCP
**Incluido en:** VM_MASTER_DOCUMENTATION.md (Sección 4.1)

### 4. `IMPLEMENTATION_SUMMARY.md`
**Contiene:** Resumen de implementación del sistema NFPA 72
**Incluido en:** VM_MASTER_DOCUMENTATION.md (Sección 3)

### 5. `SECURITY_UPDATE_README.md`
**Contiene:** Actualización de seguridad con nueva contraseña PostgreSQL
**Incluido en:** VM_MASTER_DOCUMENTATION.md (Sección 1)

---

## 🔐 Archivos de Credenciales:

### 6. `MQTT_MANAGER_LOGIN_SETUP_hddm.pqsolutionsperu.com.md`
**Contiene:**
- SECRET_KEY de Flask: `0b9e46369d7de802127fd58ad3ffc0f5bf27bdf70c22bb7f`
- ADMIN_PASSWORD_HASH (scrypt)

**Riesgo:** Si estos valores se filtran, atacantes pueden crear sesiones falsas o acceder al panel MQTT Manager.

### 7. `setup_postgresql.sql`
**Contiene:**
- Contraseña PostgreSQL: `nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z`
- Schema de base de datos

**Riesgo:** Acceso total a la base de datos con información de relés (alarmas de incendio).

---

## ⚠️ IMPORTANTE

1. **NO subir esta carpeta a Git**
2. **NO compartir estos archivos por email sin cifrado**
3. **NO incluir en respaldos públicos**
4. **Cambiar contraseñas después de cada deployment**
5. **Usar variables de entorno en producción**

---

## 🔄 Cómo usar estos archivos de forma segura

### Opción 1: Variables de entorno (RECOMENDADO)
```bash
# En la VM, agregar a ~/.bashrc:
export PG_PASSWORD='nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z'
export SMTP_USER='alerts@pqsolutions.com'
export SMTP_PASS='tu_smtp_password'

# Luego en el código usar: os.environ.get('PG_PASSWORD')
```

### Opción 2: Archivo .env local (no versionado)
```bash
# Crear .env en el servidor (NO en Git)
echo "PG_PASSWORD='nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z'" > ~/.env
echo "export PG_PASSWORD" >> ~/.bashrc
source ~/.bashrc
```

### Opción 3: Google Secret Manager (para producción)
```bash
# Guardar en Secret Manager de GCP
gcloud secrets create pg-password --data-file=- <<< 'nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z'

# Recuperar en runtime
gcloud secrets versions access latest --secret="pg-password"
```

---

## 📞 Contacto

Si necesitas acceso a estos archivos:
- Contactar al administrador del sistema
- Verificar identidad antes de compartir
- Usar canal seguro (nunca Slack/WhatsApp)

---

**Última actualización:** 2026-01-30
**Generado por:** Claude Code - Sistema de Seguridad HDD-Monitor
