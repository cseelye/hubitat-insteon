/**
 *  Insteon on/off switch or plug in module
 */

metadata {
    definition(name: 'Insteon On/Off Switch', namespace: 'cjs', author: 'Carl Seelye') {
        capability 'Switch'
        capability 'Refresh'

        attribute 'switch', 'enum', ['on', 'off']
        attribute 'ledBrightness', 'number'
    }
    preferences {
        input(name: 'debugEnable',
            title: 'Enable debug logging',
            type: 'bool',
            defaultValue: true,
        )
    }
}

#include cjs.util

void installed() {
    refresh()
}

void uninstalled() {
    debug "Removing device"
    parent.updateCache()
}

void updated() {}

void refresh() {
    parent.refreshDevice(device.deviceNetworkId)
}

void on() {
    debug "Setting device=[${device.label}] deviceID=[${device.deviceNetworkId}] state=[ON]"
    parent.deviceOn(device.deviceNetworkId)
}

void off() {
    debug "Setting device=[${device.label}] deviceID=[${device.deviceNetworkId}] state=[OFF]"
    parent.deviceOff(device.deviceNetworkId)
}
