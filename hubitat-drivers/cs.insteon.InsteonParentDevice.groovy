/**
 *  Insteon Parent Device
 *
 *  Copyright 2022 Carl Seelye
 *
 **/

import hubitat.helper.InterfaceUtils
import groovy.json.JsonSlurper
import groovy.json.JsonException
import groovy.json.JsonOutput

metadata {
    definition(name: 'Insteon Parent Device', namespace: 'cs.insteon', author: 'Carl Seelye') {
        capability 'Initialize'
        capability 'Refresh'

        attribute 'bridgeConnection', 'string'
        attribute 'insteonConnection', 'string'

        command 'deviceOn', [[name: 'deviceID', type: 'STRING'],
                             [name: 'level', type: 'NUMBER'],
                             [name: 'rate', type: 'NUMBER']]
        command 'deviceOff', [[name: 'deviceID', type: 'STRING']]
        command 'deviceFastOff', [[name: 'deviceID', type: 'STRING']]
        command 'deviceFastOn', [[name: 'deviceID', type: 'STRING']]
        command 'deviceSetLevel', [[name: 'deviceID', type: 'STRING'],
                                   [name: 'level', type: 'NUMBER'],
                                   [name: 'rate', type: 'NUMBER']]
        command 'refreshDevice', [[name: 'deviceID', type: 'STRING']]

        // command 'insteonCommand', [[name: 'cmd', type: 'STRING'],
        //                            [name: 'param', type: 'STRING'],
        //                            [name: 'deviceID', type: 'STRING']]
    }

    preferences {
        input(name: 'bridgeIP',
            title: 'Bridge IP Address',
            description: 'The IP address of your insteon-bridge server',
            type: 'text',
            required: true,
            defaultValue: '192.168.3.83',
        )
        input(name: 'bridgePort',
            title: 'Bridge Port',
            description: 'The insteon-bridge server port',
            type: 'text',
            required: true,
            defaultValue: 8080,
        )

        input(name: 'debugEnable',
            title: 'Enable debug logging',
            type: 'bool',
            defaultValue: true,
        )

        input(name: "advancedSettings",
              title: "Show advanced settings",
              type: "bool",
              defaultValue: false)

        if (advancedSettings) {
            input(name: 'deleteChildrenWhenParentDeleted',
                  title: 'Remove all child devices when this parent device is deleted',
                  type: 'bool',
                  defaultValue: true)
            input(name: 'autoDeleteChildren',
                  title: 'Auto-delete child devices when they are removed from the insteon-bridge server',
                  type: 'bool',
                  defaultValue: true)
        }
    }
}

#include cs.helpers

/*
 * Called when device is first created.
 */
void installed() { }

/*
 * Called when the device is deleted
 * Disconnect and cleanup child devices.
 */
void uninstalled() {
    // Remove any background tasks
    unschedule()

    // Close connection to websocket server
    interfaces.webSocket.close()

    // Remove all child devices
    if (settings.deleteChildrenWhenParentDeleted) {
        this.childDevices.each { child ->
            deleteChildDevice(child)
        }
    }
}

/*
 * Called when the user presses Refresh
 * Refresh the list of devices and local cache
 */
void refresh() {
    requestDeviceList(true)
}

/*
 * Called when device or preferences are changed eg "Save Device" or "Save Preferences".
 * Since we cannot know what changed in the settings, reinitialize everything.
 */
void updated() {
    info "updated"
    initialize()
}

/*
 * Called when hub boots up (the admin can also request a manual initialize).
 * Connect to the insteon-bridge server and refresh the list of devices and status.
 */
void initialize() {
    // Remove any background tasks
    unschedule()

    // Update local list of child devices
    updateCache()

    // Connect to the insteon-bridge server
    // A successful connection will automatically query the list of devices and current state
    interfaces.webSocket.close()
    connectWebsocket()
}

/*
 * Called when new data is received on the websocket.
 */
void parse(String description) {
    sendEvent(name: 'bridgeConnection', value: 'active')
    debug "Raw message: ${description}"

    // All messages are in JSON format with two required fields:
    //  type: the type of message.
    //  data: the payload of the message. The structure of this will vary based on the type

    // Parse message to JSON
    JsonSlurper jsonSlurper = new JsonSlurper()
    Object msg
    try {
        msg = jsonSlurper.parseText(description)
    }
    catch (JsonException ex) {
        error "Invalid message from insteon-bridge: error=[${ex}] message=[${description}]"
        return
    }

    switch (msg.type) {
        case 'bridgestatus':
            info "insteon-bridge status: ${msg.data.message}"
            sendEvent(name: 'insteonConnection', value: msg.data.insteonConnection)
            break

        case 'error':
            error "insteon-bridge error: ${msg.data.message}"
            break

        case 'listDevices':
            msg.data.each{dev -> dev.deviceID = dev.deviceID.toLowerCase()}
            onDeviceList(msg.data)
            break

        case 'deviceInfo':
            msg.data.deviceID = msg.data.deviceID.toLowerCase()
            onDeviceInfo(msg.data)
            break

        case 'event':
            msg.data.deviceID = msg.data.deviceID.toLowerCase()
            onStatusEvent(msg.data)
            break

        default:
            warn "Unknown message type from insteon-bridge type=${msg.type}"
    }
}

/*
 * Connect to the insteon-bridge server. If connection fails, automatically schedule a retry
 */
void connectWebsocket() {
    try {
        url = "ws://${settings.bridgeIP}:${settings.bridgePort}"
        info "Attempting connect to insteon-bridge ${url}"
        interfaces.webSocket.connect(url, pingInterval: 30)
    }
    catch (Exception ex) {
        error "Failed to connect insteon-bridge: ${ex}"
        sendEvent(name: 'bridgeConnection', value: 'failed')
        scheduleReconnect()
    }
}

/*
 * Schedule a retry, using a delay between attempts
 */
void scheduleReconnect() {
    // Exponential backoff, up to 5 minutes
    state.backoff = state.backoff ? Math.min(state.backoff * 2, 300) : 1

    // Retry connection after backoff interval
    runIn(state.backoff, connectWebsocket)
}

/*
 * Called when the websocket connection status is changed
 */
void webSocketStatus(String message) {
    (type, status) = message.split('[:\\s*]+')
    if (type.toLowerCase() == 'status') {
        if (status.toLowerCase() == 'open') {
            info "Connected websocket to insteon-bridge"
            sendEvent(name: 'bridgeConnection', value: 'connected')
            state.backoff = 0 // Reset backoff on successful connection
            runIn(0, onConnect)
        } else if (status.toLowerCase() == 'closing') {
            info "Disconnected websocket from insteon-bridge"
            sendEvent(name: 'bridgeConnection', value: 'disconnected')
        } else {
            warn "Unexpected websocket status: [${status}]"
            scheduleReconnect()
        }
    } else if (type.toLowerCase() == 'failure') {
        error "Failed to connect websocket to insteon-bridge: ${status}"
        sendEvent(name: 'bridgeConnection', value: 'failed')
        sendEvent(name: 'insteonConnection', value: "unknown")
        scheduleReconnect()
    } else {
        warn "Unexpected websocket status: [${message}]"
        scheduleReconnect()
    }
}

/*
 * Called when the websocket is successfully connected
 */
void onConnect() {
    // Request the device list
    requestDeviceList()
}

/*
 * Called when a device info update is sent over the websocket
 */
void onDeviceInfo(info) {
    child = getChildDevice(info.deviceID)
    info.remove('deviceID')
    if (child) {
        info.each{key, value ->
            if (value == null)
                return
            unit = null
            switch (key) {
                case 'rampRate':
                    value = (value as Float) / 1000
                    unit = 'seconds'
                    break
                case 'onLevel':
                case 'level':
                    unit = '%'
                    break
            }
            child.sendEvent([name: key, value: value, unit: unit])
        }
    }
}

/*
 * Called when a device list is sent over the websocket
 */
void onDeviceList(deviceList) {
    // Why is namespace sometimes null?
    namespace = namespace ?: 'cs.insteon'

    //  Refresh cache if requested
    if (state.forceRefreshCache){
        updateCache()
        state.forceRefreshCache = false
    }

    // Iterate through the message and check for new or updated devices
    messageDevices = []
    for (dev in deviceList)
    {
        // Check if device already exists or we need to create it
        deviceID = dev.deviceID.toLowerCase()
        messageDevices << deviceID
        if (!state.childIDCache.contains(deviceID)) {
            switch (dev.deviceType)
            {
                case 'motionsensor':
                    type = 'Insteon Motion Sensor'; break
                case 'doorsensor':
                case 'windowsensor':
                case 'contactsensor':
                    type = 'Insteon Contact Sensor'; break
                case 'dimmer':
                case 'lightbulb':
                    type = 'Insteon Dimmer Switch'; break
                case 'switch':
                    type = 'Insteon On/Off Switch'; break
                case 'leaksensor':
                    type = 'Insteon Leak Sensor'; break
                default:
                    warn "Unknown deviceType: ${dev.deviceType}"
                    return
            }

            info "Creating device: name=[${dev.name}] ID=[${deviceID}] type=[${type}]"
            newchild = addChildDevice(namespace, type, deviceID, [label: dev.name, isComponent: false, name: type])
            // Pass parent debug setting to child
            newchild.updateSetting('debugEnable', [value: debugEnable, type: 'bool'])
        }
        else {
            debug "Not creating device because it already exists: name=[${dev.name}] ID=[${deviceID}]"
        }
    }

    // Check for deleted devices
    if (settings.autoDeleteChildren)
    {
        for (deviceID in state.childIDCache)
        {
            if (!messageDevices.contains(deviceID))
            {
                child = getChildDevice(deviceID)
                warning "Deleting device: name=[${child.name}] ID=[${deviceID}] type=[${child.typeName}]"
                deleteChildDevice(deviceID)
            }
        }
    }

    // Update the cache if we made any changes
    if (messageDevices.sort() != state.childIDCache) {
        updateCache()
    }
}

/*
 * Called when a status change is sent over the websocket
 */
void onStatusEvent(data) {
    events = []
    switch (data.deviceType)
    {
        case 'switch':
            newState = data.state as Integer
            events << [name: 'switch', value: (newState > 0) ? 'on' : 'off']
            break

        case 'dimmer':
        case 'lightbulb':
            newState = data.state as Integer
            events << [name: 'level', value: newState]
            events << [name: 'switch', value: (newState > 0) ? 'on' : 'off']
            break

        case 'doorsensor':
        case 'windowsensor':
        case 'contactsensor':
            events << [name: 'contact', value: data.state]
            break

        case 'leaksensor':
            events << [name: 'water', value: data.state]
            break

        default:
            warn "Received event for unknown deviceType ${data.deviceType}"
            return
    }
    child = getChildDevice(data.deviceID)
    if (!child) {
        warn "Could not find child device with deviceNetworkId=${data.deviceID}"
        return
    }
    events.each {event ->
        info "device=[${child.label}] deviceID=[${data.deviceID}] event=[${event.name}] state=[${event.value}]"
        child.sendEvent(event)
    }
}

/*
 * Query hubitat to find the list of child devices and save a local copy of the device IDs.
 */
void updateCache() {
    debug 'Updating child cache'
    childIDs = []
    this.childDevices.each { child ->
        childIDs << child.deviceNetworkId.toLowerCase()
    }
    state.childIDCache = childIDs.sort()
    debug "childIDCache=${state.childIDCache}"
}

/*
 * Send a message to the insteon-bridge to request a list of known devices.
 */
void requestDeviceList(Boolean forceRefreshCache = false) {
    state.forceRefreshCache = forceRefreshCache
    wsCommand('listDevices')
}

/*
 * Request the latest info and state about a device
 */
void refreshDevice(String deviceID) {
    deviceGetInfo(deviceID)
    deviceGetLevel(deviceID)
}

/*
 * Request the config details of a device
 */
void deviceGetInfo(String deviceID) {
    wsCommand('deviceInfo', [deviceID: deviceID])
}

/*
 * Request the current level of a device
 */
void deviceGetLevel(String deviceID) {
    wsCommand('deviceLevel', [deviceID: deviceID])
}

/*
 * Asynchronously send a websocket command
 */
void wsCommand(String command, Object params = {}) {
    debug "Sending websocket request command=${command} params=${params}"
    message = JsonOutput.toJson([method: command, params: params])
    interfaces.webSocket.sendMessage(message)
}

/*
Keeping this for posterity but the current design sends all commands through the insteon-bridge server instead of directly to the hub

void insteonCommand(String cmd, String param, String deviceID, String callback = 'insteonCommandResponse') {
    url = "http://***:****@${settings.hubIP}:${settings.hubPort}//3?0262${deviceID}0F${cmd}${param}=I=3"
    debug "Sending command to hub\ndevice=${deviceID} command=${cmd} param=${param} url=${url}"
    url = url.replaceFirst('\\*\\*\\*', "${settings.username}")
    url = url.replaceFirst('\\*\\*\\*\\*', "${settings.password}")
    asynchttpGet(callback, [uri: url], [deviceID: deviceID, cmd: cmd, param: param])
}
def insteonCommandResponse(response, data) {
    if (response.getStatus() == 200 || response.getStatus() == 207) {
        debug "Successfully sent command to hub\ndevice=${data.deviceID} command=${data.cmd} param=${data.param}"
    } else {
        error "Error sending command to hub: ${response.getErrorMessage()}\n" +
              "device=${data.deviceID} command=${data.cmd} param=${data.param}"
    }
}
*/

/*
 * Turn a device on
 */
void deviceOn(String deviceID, Integer level = null, Float rate = null) {
    if (rate)
        rate = (rate*1000.0)
    wsCommand('deviceOn', [deviceID: deviceID, level: level, rate: rate as Integer])
}

/*
 * Turn a device off
 */
void deviceOff(String deviceID) {
    wsCommand('deviceOff', [deviceID: deviceID])
}

/*
 * Turn a device fast on
 */
void deviceFastOn(String deviceID) {
    wsCommand('deviceFastOn', [deviceID: deviceID])
}

/*
 * Turn a device fast off
 */
void deviceFastOff(String deviceID) {
    wsCommand('deviceFastOff', [deviceID: deviceID])
}

/*
 * Turn a device on to a specified level over a specified period of time
 */
void deviceSetLevel(String deviceID, Integer level, Float duration = null) {
    if (duration)
        duration = (duration*1000) as Integer
    wsCommand('deviceOn', [deviceID: deviceID, level: level, rate: duration as Integer])
}

/*
 * Set the ramp rate for a device
 */
void deviceSetRampRate(String deviceID, Float rate) {
    if (rate)
        rate = (rate*1000.0)
    wsCommand('deviceSetRampRate', [deviceID: deviceID, rate: rate as Integer])
}

/*
 * Set the default on level for a device
 */
void deviceSetOnLevel(String deviceID, Integer level) {
    if (level <= 0 || level > 100) {
        error "Invalid onLevel for deviceID=${deviceID}"
        return
    }
    wsCommand('deviceSetOnLevel', [deviceID: deviceID, level: level])
}
