/**
 * SmartThings Bridge
 *
 * Parent app that discovers SmartThings devices and creates
 * Hubitat child devices bridged via SmartThings REST API.
 *
 * Version: 0.1.0
 */
definition(
    name: "SmartThings Bridge",
    namespace: "zekaizer",
    author: "luke.lee",
    description: "Bridge SmartThings devices to Hubitat via REST API",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "SmartThings Bridge", install: true, uninstall: true) {
        section("SmartThings API") {
            input "stToken", "text", title: "Personal Access Token", required: true, submitOnChange: true
        }
        section("Polling") {
            input "pollInterval", "enum", title: "Polling Interval",
                options: ["1": "1 minute", "5": "5 minutes", "10": "10 minutes"],
                defaultValue: "5"
        }
        if (stToken) {
            discoverDevices()
            if (state.discoveredDevices) {
                section("Devices") {
                    input "selectedDevices", "enum", title: "Select devices to bridge",
                        options: state.discoveredDevices, multiple: true, required: false
                }
            } else {
                section {
                    paragraph "No switch-capable devices found. Check your token."
                }
            }
        }
    }
}

void discoverDevices() {
    Map params = [
        uri: "https://api.smartthings.com/v1/devices",
        headers: ["Authorization": "Bearer ${stToken}"],
        contentType: "application/json",
        timeout: 10
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                Map devices = [:]
                resp.data.items.each { dev ->
                    boolean hasSwitch = dev.components.any { comp ->
                        comp.capabilities.any { cap -> cap.id == "switch" }
                    }
                    if (hasSwitch) {
                        devices[dev.deviceId] = dev.label ?: dev.name
                    }
                }
                state.discoveredDevices = devices
            }
        }
    } catch (e) {
        log.error "Discovery failed: ${e.message}"
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
    syncChildDevices()

    switch (pollInterval ?: "5") {
        case "1":  runEvery1Minute("pollAll");  break
        case "5":  runEvery5Minutes("pollAll"); break
        case "10": runEvery10Minutes("pollAll"); break
    }
    runIn(2, "pollAll")
}

void syncChildDevices() {
    // Create missing children
    selectedDevices?.each { stDeviceId ->
        String dni = "ST-${stDeviceId}"
        if (!getChildDevice(dni)) {
            String label = state.discoveredDevices?."${stDeviceId}" ?: stDeviceId
            addChildDevice("zekaizer", "SmartThings Bridge Light", dni, [
                label: label,
                isComponent: false
            ])
            log.info "Created: ${label}"
        }
    }

    // Remove deselected children
    getChildDevices().each { child ->
        String stId = child.deviceNetworkId.replace("ST-", "")
        if (!selectedDevices?.contains(stId)) {
            deleteChildDevice(child.deviceNetworkId)
            log.info "Removed: ${child.label}"
        }
    }
}

// Called by child driver
String getToken() {
    return stToken
}

// Called by child driver
String getStDeviceId(String dni) {
    return dni.replace("ST-", "")
}

void pollAll() {
    getChildDevices().each { child ->
        refreshChild(child)
    }
}

void refreshChild(child) {
    String stDeviceId = child.deviceNetworkId.replace("ST-", "")
    Map params = [
        uri: "https://api.smartthings.com/v1/devices/${stDeviceId}/status",
        headers: ["Authorization": "Bearer ${stToken}"],
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
        if (switchVal) {
            child.updateSwitch(switchVal)
        }
    } catch (e) {
        log.error "Parse ${child.label}: ${e.message}"
    }
}

void childCommand(String dni, String cmd) {
    String stDeviceId = dni.replace("ST-", "")
    String body = groovy.json.JsonOutput.toJson([
        commands: [[
            component: "main",
            capability: "switch",
            command: cmd
        ]]
    ])

    Map params = [
        uri: "https://api.smartthings.com/v1/devices/${stDeviceId}/commands",
        headers: [
            "Authorization": "Bearer ${stToken}",
            "Content-Type": "application/json"
        ],
        body: body,
        contentType: "application/json",
        timeout: 10
    ]

    asynchttpPost("handleCommand", params, [dni: dni, cmd: cmd])
}

void handleCommand(resp, Map data) {
    def child = getChildDevice(data.dni)
    if (!child) return

    if (resp.hasError()) {
        log.error "Command '${data.cmd}' for ${child?.label} failed: ${resp.getErrorMessage()}"
        if (child) runIn(1, "pollAll")
        return
    }
    runIn(3, "pollAll")
}

void uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}
