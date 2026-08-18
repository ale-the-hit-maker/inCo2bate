// ===================================================================
// Mock Ethernet Layer for Wokwi Simulation
// ===================================================================
// When WOKWI_SIM is defined, this provides mock Ethernet functions
// to allow testing other firmware features without real W5500 hardware.
// The mock simulates a connected Ethernet interface with DHCP success.
//
// For production hardware, this file is not included; real Ethernet
// library is used instead.
// ===================================================================

#ifndef ETHERNET_MOCK_H
#define ETHERNET_MOCK_H

#ifdef WOKWI_SIM

#include <Arduino.h>
#include <Client.h>

// Mock LinkStatus enum
enum LinkStatus_t {
    Unknown = 0,
    LinkON = 1,
    LinkOFF = 2
};

// Mock HardwareStatus enum
enum HardwareStatus_t {
    EthernetNoHardware = 0,
    EthernetW5100 = 1,
    EthernetW5500 = 2,
    EthernetW6100 = 3
};

// Mock Ethernet client for MQTT — must inherit from Client for PubSubClient
class EthernetClient : public Client {
public:
    int connect(const char *host, uint16_t port) override {
        Serial.printf("[ETH_MOCK] Simulated connect to %s:%d\n", host, port);
        return 1;  // 1 = success
    }
    
    int connect(IPAddress ip, uint16_t port) override {
        Serial.printf("[ETH_MOCK] Simulated connect to IP:%d\n", port);
        return 1;
    }
    
    uint8_t connected() override { return 1; }
    
    void stop() override { Serial.println("[ETH_MOCK] Simulated disconnect"); }
    
    size_t write(uint8_t data) override { return 1; }
    
    size_t write(const uint8_t *buf, size_t size) override { return size; }
    
    int read() override { return -1; }
    
    int read(uint8_t *buf, size_t size) override { return 0; }
    
    int available() override { return 0; }
    
    int peek() override { return -1; }
    
    void flush() override {}
    
    operator bool() override { return true; }
};

// Mock Ethernet static class with all static methods
class EthernetMock {
private:
    static bool _connected;
    static IPAddress _localIP;
    
public:
    static void init(uint8_t csPin) {
        Serial.printf("[ETH_MOCK] init() called with CS pin %d\n", csPin);
    }
    
    static int begin(uint8_t *mac) {
        Serial.println("[ETH_MOCK] Simulating DHCP success");
        _localIP = IPAddress(192, 168, 1, 101);
        _connected = true;
        return 1;  // DHCP success
    }
    
    static int begin(uint8_t *mac, IPAddress local_ip, 
                     IPAddress dns_server, IPAddress gateway, 
                     IPAddress subnet_mask) {
        Serial.println("[ETH_MOCK] Using static IP");
        _localIP = local_ip;
        _connected = true;
        return 1;
    }
    
    static HardwareStatus_t hardwareStatus() {
        Serial.println("[ETH_MOCK] Hardware status: W5500 present (simulated)");
        return EthernetW5500;
    }
    
    static LinkStatus_t linkStatus() {
        Serial.println("[ETH_MOCK] Link status: ON (simulated)");
        return LinkON;
    }
    
    static IPAddress localIP() {
        return _localIP;
    }
    
    static void maintain() {
        // No-op for mock
    }
};

// Static member definitions
bool EthernetMock::_connected = false;
IPAddress EthernetMock::_localIP;

// Global Ethernet instance for compatibility
extern EthernetMock Ethernet;

#endif // WOKWI_SIM

#endif // ETHERNET_MOCK_H
