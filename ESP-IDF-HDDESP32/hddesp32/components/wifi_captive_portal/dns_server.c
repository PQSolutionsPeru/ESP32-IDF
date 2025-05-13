#include "dns_server.h"
#include <string.h>
#include <sys/param.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "lwip/err.h"
#include "lwip/sockets.h"
#include "lwip/sys.h"
#include "lwip/netdb.h"
#include "lwip/dns.h"
#include "esp_wifi.h"
#include "esp_mac.h"

#define TAG "DNS_SERVER"

#define DNS_MAX_LEN 512

// Estructura del encabezado DNS
typedef struct __attribute__((__packed__))
{
    uint16_t id;
    uint8_t flags;
    uint8_t rcode;
    uint16_t qdcount;
    uint16_t ancount;
    uint16_t nscount;
    uint16_t arcount;
} dns_header_t;

// Estructura de la respuesta DNS
typedef struct __attribute__((__packed__))
{
    uint16_t type;
    uint16_t class;
    uint32_t ttl;
    uint16_t rdlength;
    uint32_t rdata;
} dns_answer_t;

// Estructura del servidor DNS
struct dns_server {
    int sock;
    TaskHandle_t task;
    dns_server_resolve_cb_t resolve_cb;
};

// IP del Access Point por defecto
static ip4_addr_t default_resolve(const char *hostname) {
    // Por defecto, todas las solicitudes se resuelven a la IP del AP
    ip4_addr_t addr;
    // Usamos la IP del Access Point, generalmente 192.168.4.1
    addr.addr = ipaddr_addr("192.168.4.1");
    return addr;
}

// Extraer nombre de dominio de la consulta DNS
static int extract_name(char *dest, const uint8_t *query, size_t query_len) {
    if (!dest || !query || query_len == 0) {
        return -1;
    }
    
    int i = 0, j = 0;
    
    while (i < query_len) {
        uint8_t len = query[i++];
        
        if (len == 0) {
            dest[j] = '\0';
            return i;
        }
        
        if (len > (query_len - i)) {
            return -1;
        }
        
        if (j > 0) {
            dest[j++] = '.';
        }
        
        memcpy(&dest[j], &query[i], len);
        i += len;
        j += len;
    }
    
    return -1;
}

// Tarea del servidor DNS
static void dns_server_task(void *pvParameters) {
    struct dns_server *server = (struct dns_server *)pvParameters;
    uint8_t rx_buffer[DNS_MAX_LEN];
    uint8_t tx_buffer[DNS_MAX_LEN];
    
    struct sockaddr_in server_addr = {
        .sin_family = AF_INET,
        .sin_addr.s_addr = htonl(INADDR_ANY),
        .sin_port = htons(53),
    };
    
    server->sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (server->sock < 0) {
        ESP_LOGE(TAG, "Failed to create socket: %d", errno);
        vTaskDelete(NULL);
        return;
    }
    
    int err = bind(server->sock, (struct sockaddr *)&server_addr, sizeof(server_addr));
    if (err < 0) {
        ESP_LOGE(TAG, "Failed to bind socket: %d", errno);
        close(server->sock);
        vTaskDelete(NULL);
        return;
    }
    
    ESP_LOGI(TAG, "DNS Server started on port 53");
    
    struct sockaddr_in source_addr;
    socklen_t socklen = sizeof(source_addr);
    
    // Bucle principal del servidor DNS
    while (1) {
        int len = recvfrom(server->sock, rx_buffer, sizeof(rx_buffer), 0, 
                         (struct sockaddr *)&source_addr, &socklen);
        
        if (len < 0) {
            ESP_LOGE(TAG, "Error receiving data: %d", errno);
            continue;
        }
        
        if (len < sizeof(dns_header_t)) {
            ESP_LOGW(TAG, "Received packet too short");
            continue;
        }
        
        // Procesar la consulta DNS
        dns_header_t *header = (dns_header_t *)rx_buffer;
        uint16_t qdcount = ntohs(header->qdcount);
        
        if (qdcount != 1) {
            ESP_LOGW(TAG, "Only one question supported");
            continue;
        }
        
        // Preparar respuesta
        memcpy(tx_buffer, rx_buffer, len);
        dns_header_t *tx_header = (dns_header_t *)tx_buffer;
        
        // Poner flags para respuesta
        tx_header->flags = 0x84;  // Respuesta autoritativa
        tx_header->rcode = 0;     // No error
        tx_header->ancount = htons(1);  // 1 respuesta
        
        // Extraer el nombre de dominio
        char domain[256] = {0};
        uint8_t *q = rx_buffer + sizeof(dns_header_t);
        int name_len = extract_name(domain, q, len - sizeof(dns_header_t));
        
        if (name_len < 0) {
            ESP_LOGW(TAG, "Failed to extract domain name");
            continue;
        }
        
        ESP_LOGI(TAG, "DNS query for domain: %s", domain);
        
        // Obtener la respuesta IP (siempre apunta al servidor)
        ip4_addr_t resolved_ip;
        if (server->resolve_cb) {
            resolved_ip = server->resolve_cb(domain);
        } else {
            resolved_ip = default_resolve(domain);
        }
        
        uint8_t *response_ptr = tx_buffer + len;
        
        // Agregar puntero de nombre
        *response_ptr++ = 0xC0;
        *response_ptr++ = sizeof(dns_header_t);
        
        // Agregar tipo (A) y clase (IN)
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x01;  // Tipo A
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x01;  // Clase IN
        
        // Agregar TTL (60 segundos)
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x3C;
        
        // Agregar longitud de datos (4 bytes para IPv4)
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x04;
        
        // Agregar datos (dirección IP)
        *response_ptr++ = (resolved_ip.addr >> 0) & 0xFF;
        *response_ptr++ = (resolved_ip.addr >> 8) & 0xFF;
        *response_ptr++ = (resolved_ip.addr >> 16) & 0xFF;
        *response_ptr++ = (resolved_ip.addr >> 24) & 0xFF;
        
        // Calcular longitud total de la respuesta
        int tx_len = response_ptr - tx_buffer;
        
        // Enviar respuesta
        sendto(server->sock, tx_buffer, tx_len, 0, 
              (struct sockaddr *)&source_addr, socklen);
    }
}

// Inicia el servidor DNS
esp_err_t dns_server_start(const dns_server_config_t *config, dns_server_handle_t *handle) {
    if (!config || !handle) {
        return ESP_ERR_INVALID_ARG;
    }
    
    struct dns_server *server = calloc(1, sizeof(struct dns_server));
    if (!server) {
        return ESP_ERR_NO_MEM;
    }
    
    server->resolve_cb = config->resolve_cb;
    
    // Crear tarea para el servidor DNS
    BaseType_t ret = xTaskCreate(dns_server_task, "dns_server", 4096, server, 5, &server->task);
    if (ret != pdPASS) {
        free(server);
        return ESP_ERR_NO_MEM;
    }
    
    *handle = server;
    return ESP_OK;
}

// Detiene el servidor DNS
esp_err_t dns_server_stop(dns_server_handle_t handle) {
    if (!handle) {
        return ESP_ERR_INVALID_ARG;
    }
    
    struct dns_server *server = (struct dns_server *)handle;
    
    // Detener la tarea
    if (server->task) {
        vTaskDelete(server->task);
    }
    
    // Cerrar el socket
    if (server->sock >= 0) {
        close(server->sock);
    }
    
    free(server);
    return ESP_OK;
}