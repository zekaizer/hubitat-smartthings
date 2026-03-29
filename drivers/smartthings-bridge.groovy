/**
 * SmartThings Bridge
 *
 * Parent device driver that discovers SmartThings devices and creates
 * Generic Component Switch child devices bridged via SmartThings Cloud API.
 *
 * Version: 0.2.3
 */
metadata {
    definition(name: "SmartThings Bridge", namespace: "zekaizer", author: "luke.lee") {
        capability "Configuration"
        capability "Refresh"
        capability "Initialize"

        command "discoverDevices"
        command "removeAllDevices"
        command "checkChildren"
        command "testSwitch", [[name: "cmd", type: "ENUM", constraints: ["on", "off"]]]
    }

    preferences {
        input name: "stToken", type: "text", title: "SmartThings API Token", required: true
        input name: "pollInterval", type: "enum", title: "Polling Interval",
              options: ["1": "1 minute", "5": "5 minutes", "10": "10 minutes"],
              defaultValue: "5", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

void installed() {
    initialize()
}

void updated() {
    initialize()
}

void initialize() {
    unschedule()
    if (!settings.stToken) {
        log.warn "SmartThings API Token is required"
        return
    }

    switch (settings.pollInterval ?: "5") {
        case "1":  runEvery1Minute("refresh");  break
        case "5":  runEvery5Minutes("refresh"); break
        case "10": runEvery10Minutes("refresh"); break
    }
    runIn(2, "refresh")
    if (settings.logEnable) log.info "Initialized, polling every ${settings.pollInterval} min"
}

void configure() {
    discoverDevices()
}

// --- Discovery ---

void discoverDevices() {
    if (!settings.stToken) {
        log.warn "Set API token first"
        return
    }

    Map params = [
        uri: "https://api.smartthings.com/v1/devices",
        headers: ["Authorization": "Bearer ${settings.stToken}"],
        contentType: "application/json",
        timeout: 10
    ]

    asynchttpGet("handleDiscovery", params)
}

void handleDiscovery(resp, data) {
    if (resp.hasError()) {
        log.error "Discovery failed: ${resp.getErrorMessage()}"
        return
    }

    try {
        Map json = new groovy.json.JsonSlurper().parseText(resp.getData())
        int created = 0
        json.items.each { dev ->
            boolean hasSwitch = dev.components.any { comp ->
                comp.capabilities.any { cap -> cap.id == "switch" }
            }
            if (hasSwitch) {
                String label = dev.label ?: dev.name
                def child = fetchChild(dev.deviceId, label, "Switch")
                if (child) created++
            }
        }
        log.info "Discovery complete: ${created} switch devices"
    } catch (e) {
        log.error "Discovery parse error: ${e.message}"
    }
}

// --- Child Device Management (Bestin pattern) ---

private fetchChild(String stDeviceId, String label, String type) {
    String dni = "${device.deviceNetworkId}-${stDeviceId}"
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("hubitat", "Generic Component ${type}", dni, [
                name: label,
                label: label,
                isComponent: false
            ])
            if (settings.logEnable) log.debug "Created child: ${label}"
        } catch (e) {
            log.error "Failed to create child ${label}: ${e.message}"
        }
    }
    return child
}

void removeAllDevices() {
    getChildDevices().each {
        if (settings.logEnable) log.debug "Removing: ${it.label}"
        deleteChildDevice(it.deviceNetworkId)
    }
    log.info "All child devices removed"
}

// --- Diagnostics ---

void checkChildren() {
    log.info "Parent DNI: ${device.deviceNetworkId}, Parent ID: ${device.id}"
    getChildDevices().each { child ->
        log.info "Child: id=${child.id}, dni=${child.deviceNetworkId}, label=${child.label}, name=${child.name}, parent=${child.getParent()}"
    }
    if (!getChildDevices()) log.warn "No child devices found"
}

void testSwitch(String cmd) {
    def children = getChildDevices()
    if (!children) {
        log.error "No child devices"
        return
    }
    def child = children[0]
    String stDeviceId = extractStDeviceId(child)
    log.info "testSwitch: cmd=${cmd}, child=${child.label}, stDeviceId=${stDeviceId}"
    sendSTCommand(stDeviceId, cmd)
}

// --- Component Methods (called by Generic Component Switch children) ---

void componentOn(child) {
    if (settings.logEnable) log.debug "componentOn: ${child.displayName}"
    child.sendEvent(name: "switch", value: "on", descriptionText: "${child.displayName} is on")
    sendSTCommand(extractStDeviceId(child), "on")
}

void componentOff(child) {
    if (settings.logEnable) log.debug "componentOff: ${child.displayName}"
    child.sendEvent(name: "switch", value: "off", descriptionText: "${child.displayName} is off")
    sendSTCommand(extractStDeviceId(child), "off")
}

void componentRefresh(child) {
    if (settings.logEnable) log.debug "componentRefresh: ${child.label}"
    refreshChild(child)
}

// --- Polling ---

void refresh() {
    getChildDevices().each { child ->
        refreshChild(child)
    }
}

private void refreshChild(child) {
    String stDeviceId = extractStDeviceId(child)
    if (!stDeviceId) return

    Map params = [
        uri: "https://api.smartthings.com/v1/devices/${stDeviceId}/status",
        headers: ["Authorization": "Bearer ${settings.stToken}"],
        contentType: "application/json",
        timeout: 10
    ]

    asynchttpGet("handleRefresh", params, [dni: child.deviceNetworkId])
}

void handleRefresh(resp, Map data) {
    def child = getChildDevice(data.dni)
    if (!child) return

    if (resp.hasError()) {
        log.error "Refresh ${child.label} failed: ${resp.getErrorMessage()}"
        return
    }

    try {
        Map json = new groovy.json.JsonSlurper().parseText(resp.getData())
        String switchVal = json?.components?.main?.switch?.switch?.value
        if (switchVal && child.currentValue("switch") != switchVal) {
            child.sendEvent(name: "switch", value: switchVal, descriptionText: "${child.displayName} is ${switchVal}")
            if (settings.logEnable) log.debug "Updated ${child.displayName}: switch=${switchVal}"
        }
    } catch (e) {
        log.error "Parse ${child.label}: ${e.message}"
    }
}

// --- SmartThings API ---

private void sendSTCommand(String stDeviceId, String cmd) {
    String body = groovy.json.JsonOutput.toJson([
        commands: [[
            component: "main",
            capability: "switch",
            command: cmd
        ]]
    ])

    Map params = [
        uri: "https://api.smartthings.com/v1/devices/${stDeviceId}/commands",
        headers: ["Authorization": "Bearer ${settings.stToken}"],
        requestContentType: "application/json",
        contentType: "application/json",
        body: body,
        timeout: 10
    ]

    try {
        asynchttpPost("handleCommand", params, [stDeviceId: stDeviceId, cmd: cmd])
        if (settings.logEnable) log.debug "Sent command '${cmd}' to ${stDeviceId}"
    } catch (e) {
        log.error "Failed to send command '${cmd}': ${e.message}"
    }
}

void handleCommand(resp, Map data) {
    if (resp.hasError()) {
        log.error "Command '${data.cmd}' for ${data.stDeviceId} failed: ${resp.getErrorMessage()}"
        runIn(1, "refresh")
        return
    }
    if (settings.logEnable) log.debug "Command '${data.cmd}' for ${data.stDeviceId} OK"
    runIn(3, "refresh")
}

// --- Helpers ---

private String extractStDeviceId(child) {
    return child.deviceNetworkId.replace("${device.deviceNetworkId}-", "")
}
