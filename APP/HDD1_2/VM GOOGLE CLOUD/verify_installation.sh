#!/bin/bash
# Script de Verificación - HDD Monitor Safety System
# Ejecutar en la VM después del deployment

echo "=========================================="
echo "  HDD Monitor - Verificación de Sistema  "
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SUCCESS=0
FAILED=0

# Función para verificar
check() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓${NC} $1"
        ((SUCCESS++))
    else
        echo -e "${RED}✗${NC} $1"
        ((FAILED++))
    fi
}

# 1. Verificar archivos nuevos
echo "1. Verificando archivos nuevos..."
[ -f /home/pqsolutions/hdd-monitor/rate_limiter.py ]
check "rate_limiter.py"

[ -f /home/pqsolutions/hdd-monitor/nfpa_metrics.py ]
check "nfpa_metrics.py"

[ -f /home/pqsolutions/hdd-monitor/system_watchdog.py ]
check "system_watchdog.py (en hdd-monitor)"

[ -f /home/pqsolutionsperu/system_watchdog.py ]
check "system_watchdog.py (en home)"

[ -f /home/pqsolutions/hdd-monitor/requirements.txt ]
check "requirements.txt"

echo ""

# 2. Verificar dependencias Python
echo "2. Verificando dependencias Python..."
source /home/pqsolutions/venv/bin/activate 2>/dev/null

python3 -c "import structlog" 2>/dev/null
check "structlog instalado"

python3 -c "import redis" 2>/dev/null
check "redis instalado"

python3 -c "import psycopg2" 2>/dev/null
check "psycopg2 instalado"

echo ""

# 3. Verificar Redis
echo "3. Verificando Redis..."
systemctl is-active --quiet redis-server
check "Redis service activo"

redis-cli ping > /dev/null 2>&1
check "Redis responde PONG"

HEARTBEAT=$(redis-cli GET server:last_heartbeat 2>/dev/null)
if [ ! -z "$HEARTBEAT" ]; then
    echo -e "${GREEN}✓${NC} Heartbeat encontrado: $HEARTBEAT"
    ((SUCCESS++))
else
    echo -e "${YELLOW}⚠${NC} Heartbeat no encontrado (normal si servicio no se ha iniciado)"
fi

echo ""

# 4. Verificar PostgreSQL
echo "4. Verificando PostgreSQL..."
systemctl is-active --quiet postgresql
check "PostgreSQL service activo"

sudo -u postgres psql -lqt | cut -d \| -f 1 | grep -qw hdd_monitor
check "Base de datos hdd_monitor existe"

sudo -u postgres psql -c "\du" 2>/dev/null | grep -q hdd_monitor_user
check "Usuario hdd_monitor_user existe"

# Verificar tablas
PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -c "\dt" 2>/dev/null | grep -q relay_events
check "Tabla relay_events existe"

PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -c "\dt" 2>/dev/null | grep -q connectivity_events
check "Tabla connectivity_events existe"

echo ""

# 5. Verificar Watchdog
echo "5. Verificando Watchdog..."
systemctl is-enabled --quiet hdd-monitor-watchdog
check "Watchdog enabled"

systemctl is-active --quiet hdd-monitor-watchdog
check "Watchdog activo"

[ -f /etc/systemd/system/hdd-monitor-watchdog.service ]
check "Servicio systemd instalado"

echo ""

# 6. Verificar servicio principal
echo "6. Verificando servicio principal..."
MAIN_SERVICE=$(sudo systemctl list-units --type=service --state=running | grep -iE "logserver|hdd.*monitor" | awk '{print $1}' | head -1)

if [ ! -z "$MAIN_SERVICE" ]; then
    echo -e "${GREEN}✓${NC} Servicio principal encontrado: $MAIN_SERVICE"
    systemctl is-active --quiet $MAIN_SERVICE
    check "Servicio principal activo"
    ((SUCCESS++))
else
    echo -e "${YELLOW}⚠${NC} No se pudo identificar el servicio principal"
    echo "  Ejecuta: sudo systemctl list-units --type=service --state=running | grep -i monitor"
fi

echo ""

# 7. Verificar imports en Python
echo "7. Verificando imports Python..."
cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate 2>/dev/null

python3 -c "from rate_limiter import RateLimiter, EventPriority" 2>/dev/null
check "rate_limiter importa correctamente"

python3 -c "from nfpa_metrics import NFPAMetricsCollector" 2>/dev/null
check "nfpa_metrics importa correctamente"

python3 -c "import config; print(config.PG_CONFIG)" 2>/dev/null > /dev/null
check "config.py tiene PG_CONFIG"

echo ""

# 8. Resumen
echo "=========================================="
echo "           RESUMEN DE VERIFICACIÓN        "
echo "=========================================="
echo -e "${GREEN}Exitosos: $SUCCESS${NC}"
echo -e "${RED}Fallidos: $FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ SISTEMA COMPLETAMENTE INSTALADO${NC}"
    echo ""
    echo "Próximos pasos:"
    echo "1. Reiniciar servicio principal"
    echo "2. Monitorear logs: sudo journalctl -u [servicio] -f"
    echo "3. Verificar heartbeat: watch -n 5 'redis-cli GET server:last_heartbeat'"
else
    echo -e "${YELLOW}⚠ REVISIÓN NECESARIA${NC}"
    echo ""
    echo "Elementos fallidos: $FAILED"
    echo "Revisa los pasos en DEPLOYMENT_GUIDE.md"
fi

echo ""
echo "Para más información:"
echo "  - Guía completa: DEPLOYMENT_GUIDE.md"
echo "  - Arquitectura: ARQUITECTURA_VM.md"
echo ""
