/**
 *  Insteon dimmer switch or plug in module
 */

allowedRampRates = [480,420,360,300,270,240,210,180,150,120,90,60,47,43,38.5,
                    34,32,30,28,26,23.5,21.5,19,8.5,6.5,4.5,2,0.5,0.3,0.2,0.1]

metadata {
    definition(name: 'Insteon Dimmer Switch', namespace: 'cjs', author: 'Carl Seelye') {
        capability 'Switch'
        capability "SwitchLevel"
        capability 'Refresh'

        attribute 'level', 'number'
        attribute 'switch', 'enum', ['on', 'off']
        attribute 'rampRate', 'number'
        attribute 'onLevel', 'number'
        attribute 'ledBrightness', 'number'

        command 'faston'
        command 'fastoff'
        command 'setRampRate', [[name: 'rate', description: 'Ramp time in seconds', type:'ENUM',
                                 constraints: allowedRampRates.sort()]]
        command 'setOnLevel', [[name: 'level', description: 'Default ON level, as a percentage 1-100', type: 'NUMBER']]
    }
    preferences {
        input(name: 'minLevel',
              title: 'Minimum level to dim the device to (off and fastoff always go to 0)',
              type: 'number',
              defaultValue: 5)
        input(name: 'maxLevel',
              title: 'Maximum level to brighten the device to (faston always goes to 100%)',
              type: 'number',
              defaultValue: 100)
        input(name: 'useOnLevel',
              title: 'Use onLevel when turning switch on',
              type: 'bool',
              defaultValue: true)
        input(name: 'useDurationInSetLevel',
              title: 'Use duration when setting switch level. Not all devices support this. If the device does not respond to ' +
                     'Set Level when using a duration, turn this setting off and the device rampRate will be used.',
              type: 'bool',
              defaultValue: true)
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

void updated() {
    if (settings.minLevel < 0)
        device.updateSetting('minLevel', 0)
    if (settings.maxLevel > 100)
        device.updateSetting('maxLevel', 100)
}

void refresh() {
    parent.refreshDevice(device.deviceNetworkId)
}

void on() {
    onLevel = null
    if (settings.useOnLevel)
        onLevel = device.currentValue('onLevel') as Integer
    if (!onLevel)
        onLevel = 100
    if (onLevel > settings.maxLevel)
        onlevel = settings.maxLevel
    if (onLevel < settings.minLevel)
        onlevel = settings.minLevel
    debug "Setting device=[${device.name}] deviceID=[${device.deviceNetworkId}] state=[ON] level=[${onLevel}]"
    parent.deviceOn(device.deviceNetworkId, onLevel)
}

void faston() {
    debug "Setting device=[${device.name}] deviceID=[${device.deviceNetworkId}] state=[ON] (fast-on)"
    parent.deviceFastOn(device.deviceNetworkId)
}

void off() {
    debug "Setting device=[${device.name}] deviceID=[${device.deviceNetworkId}] state=[OFF]"
    parent.deviceOff(device.deviceNetworkId)
}

void fastoff() {
    debug "Setting device=[${device.name}] deviceID=[${device.deviceNetworkId}] state=[OFF] (fast-off)"
    parent.deviceFastOff(device.deviceNetworkId)
}

void setLevel(level, duration = null) {
    if (!settings.useDurationInSetLevel)
        duration = null

    onLevel = level as Integer
    if (onLevel > settings.maxLevel)
        onLevel = settings.maxLevel
    if (onLevel < settings.minLevel)
        onLevel = settings.minLevel

    debug "Setting device=[${device.name}] deviceID=[${device.deviceNetworkId}] level=[" + onLevel + "] duration=[" +
          duration + "]"
    parent.deviceOn(device.deviceNetworkId, onLevel as Integer, duration as Float)
}

void setRampRate(String rate) {
    newRate = rate as Float
    setRampRate(newRate)
}

void setRampRate(Float rate) {
    if (!rate)
        return
    if (! rate in allowedRampRates) {
        error "Requested rampRate=${rate} is not allowed. rampRate must be one of ${allowedRampRates}"
        return
    }
    debug "Setting device=[${device.name}] deviceID=[${device.deviceNetworkId}] rampRate=[" + rate + "]"
    parent.deviceSetRampRate(device.deviceNetworkId, rate)
}

void setOnLevel(Float level) {
    if (!level)
        return
    if (level < 1 || level > 100) {
        error "Invalid onLevel level=${level} values must be 1-100"
        return
    }
    onLevel = level
    if (onLevel > settings.maxLevel)
        onlevel = settings.maxLevel
    if (onLevel < settings.minLevel)
        onlevel = settings.minLevel
    debug "Setting device=[${device.name}] deviceID=[${device.deviceNetworkId}] onLevel=[" + onLevel + "]"
    parent.deviceSetOnLevel(device.deviceNetworkId, onLevel as Integer)
}
