/**
 * SmartThings Bridge Light
 *
 * Child driver for SmartThings Bridge app.
 * Delegates API calls to parent app.
 *
 * Version: 0.1.0
 */
metadata {
    definition(name: "SmartThings Bridge Light", namespace: "zekaizer", author: "luke.lee") {
        capability "Actuator"
        capability "Switch"
        capability "Light"
        capability "Refresh"
    }
}

void on() {
    sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} is on")
    parent.childCommand(device.deviceNetworkId, "on")
}

void off() {
    sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} is off")
    parent.childCommand(device.deviceNetworkId, "off")
}

void refresh() {
    parent.refreshChild(device)
}

// Called by parent app
void updateSwitch(String val) {
    if (device.currentValue("switch") != val) {
        sendEvent(name: "switch", value: val, descriptionText: "${device.displayName} is ${val}")
    }
}
