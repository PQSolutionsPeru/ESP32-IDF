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

struct dns_server {
    int sock;
    TaskHandle_t task;
    dns_server_resolve_cb_t resolve_cb;
};

static struct dns_server g_dns_server_context = {0};
static bool g_dns_server_in_use = false;

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

typedef struct __attribute__((__packed__))
{
    uint16_t type;
    uint16_t class;
    uint32_t ttl;
    uint16_t rdlength;
    uint32_t rdata;
} dns_answer_t;

static ip4_addr_t default_resolve(const char *hostname) {
    ip4_addr_t addr;
    addr.addr = ipaddr_addr("192.168.4.1");
    return addr;
}

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
        
        dns_header_t *header = (dns_header_t *)rx_buffer;
        uint16_t qdcount = ntohs(header->qdcount);
        
        if (qdcount != 1) {
            ESP_LOGW(TAG, "Only one question supported");
            continue;
        }
        
        memcpy(tx_buffer, rx_buffer, len);
        dns_header_t *tx_header = (dns_header_t *)tx_buffer;
        
        tx_header->flags = 0x84;
        tx_header->rcode = 0;
        tx_header->ancount = htons(1);
        
        char domain[256] = {0};
        uint8_t *q = rx_buffer + sizeof(dns_header_t);
        int name_len = extract_name(domain, q, len - sizeof(dns_header_t));
        
        if (name_len < 0) {
            ESP_LOGW(TAG, "Failed to extract domain name");
            continue;
        }
        
        ESP_LOGI(TAG, "DNS query for domain: %s", domain);
        
        ip4_addr_t resolved_ip;
        if (server->resolve_cb) {
            resolved_ip = server->resolve_cb(domain);
        } else {
            resolved_ip = default_resolve(domain);
        }
        
        uint8_t *response_ptr = tx_buffer + len;
        
        *response_ptr++ = 0xC0;
        *response_ptr++ = sizeof(dns_header_t);
        
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x01;
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x01;
        
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x3C;
        
        *response_ptr++ = 0x00;
        *response_ptr++ = 0x04;
        
        *response_ptr++ = (resolved_ip.addr >> 0) & 0xFF;
        *response_ptr++ = (resolved_ip.addr >> 8) & 0xFF;
        *response_ptr++ = (resolved_ip.addr >> 16) & 0xFF;
        *response_ptr++ = (resolved_ip.addr >> 24) & 0xFF;
        
        int tx_len = response_ptr - tx_buffer;
        
        sendto(server->sock, tx_buffer, tx_len, 0, 
              (struct sockaddr *)&source_addr, socklen);
    }
}

esp_err_t dns_server_start(const dns_server_config_t *config, dns_server_handle_t *handle) {
    if (!config || !handle) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (g_dns_server_in_use) {
        ESP_LOGE(TAG, "DNS server already in use");
        return ESP_ERR_INVALID_STATE;
    }
    
    struct dns_server *server = &g_dns_server_context;
    memset(server, 0, sizeof(struct dns_server));
    
    server->resolve_cb = config->resolve_cb;
    
    BaseType_t ret = xTaskCreate(dns_server_task, "dns_server", 4096, server, 5, &server->task);
    if (ret != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    
    g_dns_server_in_use = true;
    *handle = server;
    return ESP_OK;
}

esp_err_t dns_server_stop(dns_server_handle_t handle) {
    if (!handle || handle != &g_dns_server_context) {
        return ESP_ERR_INVALID_ARG;
    }
    
    struct dns_server *server = (struct dns_server *)handle;
    
    if (server->task) {
        vTaskDelete(server->task);
    }
    
    if (server->sock >= 0) {
        close(server->sock);
    }
    
    g_dns_server_in_use = false;
    memset(&g_dns_server_context, 0, sizeof(g_dns_server_context));
    
    return ESP_OK;
}