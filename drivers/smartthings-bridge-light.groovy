/**
 * SmartThings Bridge Light
 *
 * Bridges a SmartThings on/off device to Hubitat via SmartThings REST API.
 * Intended as a temporary bridge until devices are physically migrated.
 *
 * Version: 0.1.0
 *
 * Setup:
 *   1. Install this driver: Drivers Code > New Driver > paste > Save
 *   2. Create device: Devices > Add Device > Virtual > select this driver
 *   3. Set preferences: SmartThings API Token + Device ID
 */
metadata {
    definition(name: "SmartThings Bridge Light", namespace: "zekaizer", author: "luke.lee") {
        capability "Actuator"
        capability "Switch"
        capability "Light"
        capability "Refresh"
        capability "Initialize"
    }

    preferences {
        input name: "stToken", type: "text", title: "SmartThings API Token", required: true
        input name: "stDeviceId", type: "text", title: "SmartThings Device ID", required: true
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
    if (settings.stToken && settings.stDeviceId) {
        switch (settings.pollInterval ?: "5") {
            case "1":
                runEvery1Minute("refresh")
                break
            case "5":
                runEvery5Minutes("refresh")
                break
            case "10":
                runEvery10Minutes("refresh")
                break
        }
        runIn(2, "refresh")
        if (settings.logEnable) log.info "${device.displayName} initialized, polling every ${settings.pollInterval} min"
    } else {
        log.warn "SmartThings API Token and Device ID are required"
    }
}

void on() {
    sendSTCommand("on")
}

void off() {
    sendSTCommand("off")
}

void refresh() {
    if (!settings.stToken || !settings.stDeviceId) return

    Map params = [
        uri: "https://api.smartthings.com/v1/devices/${settings.stDeviceId}/status",
        headers: ["Authorization": "Bearer ${settings.stToken}"],
        contentType: "application/json",
        timeout: 10
    ]

    asynchttpGet("handleRefresh", params)
}

void handleRefresh(resp, data) {
    if (resp.hasError()) {
        log.error "Refresh failed: ${resp.getErrorMessage()}"
        return
    }

    try {
        Map json = new groovy.json.JsonSlurper().parseText(resp.getData())
        String switchVal = json?.components?.main?.switch?.switch?.value
        if (switchVal) {
            if (device.currentValue("switch") != switchVal) {
                sendEvent(name: "switch", value: switchVal, descriptionText: "${device.displayName} is ${switchVal}")
                if (settings.logEnable) log.debug "State updated: switch=${switchVal}"
            }
        }
    } catch (e) {
        log.error "Parse error: ${e.message}"
    }
}

private void sendSTCommand(String cmd) {
    if (!settings.stToken || !settings.stDeviceId) return

    String body = groovy.json.JsonOutput.toJson([
        commands: [[
            component: "main",
            capability: "switch",
            command: cmd
        ]]
    ])

    Map params = [
        uri: "https://api.smartthings.com/v1/devices/${settings.stDeviceId}/commands",
        headers: [
            "Authorization": "Bearer ${settings.stToken}",
            "Content-Type": "application/json"
        ],
        body: body,
        contentType: "application/json",
        timeout: 10
    ]

    // Optimistic update
    sendEvent(name: "switch", value: cmd, descriptionText: "${device.displayName} is ${cmd}")

    asynchttpPost("handleCommand", params, [cmd: cmd])
}

void handleCommand(resp, data) {
    if (resp.hasError()) {
        log.error "Command '${data.cmd}' failed: ${resp.getErrorMessage()}"
        runIn(1, "refresh")
        return
    }
    if (settings.logEnable) log.debug "Command '${data.cmd}' sent successfully"
    // Verify state after short delay
    runIn(3, "refresh")
}
