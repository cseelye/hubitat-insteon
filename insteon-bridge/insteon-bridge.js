"use strict"

require("log-timestamp")
let Insteon = require("home-controller").Insteon
let fs = require("fs")
let debug = require("debug")("insteon-bridge")
let websocket = require("ws")


// Main entrypoint
InsteonBridge()


// Read and validate config file, fill in defaults
function readConfig() {
    // Read the config file and fill in defaults
    console.log("Reading config file")
    let config
    try {
        config = JSON.parse(fs.readFileSync("config.json", "utf8"))
    } catch (ex) {
        if (ex instanceof SyntaxError) {
            console.log(`Invalid JSON in config file: ${ex.message}`)
        } else {
            if (ex.code == "ENOENT")
                console.log("Could not find config file. Please make sure config.json is present.")
            else if (ex.code == "EISDIR")
                console.log("Config file is a directory. If you are using a container, make sure your mount syntax is correct.")
            else
                console.log(ex)
        }
        process.exit(1)
    }

    // Object.keys(config).forEach(key => {
    //     console.log(`${key}=${config[key]} (${config[key].constructor.name})`)
    // })

    // Accept some different names for compatibility with other servers
    if (config.user && !config.username) {
        config.username = config.user
    }
    if (config.pass && !config.password) {
        config.password = config.pass
    }
    if (config.port && !config.hubPort) {
        config.hubPort = config.port
    }

    // Fill in defaults for optional fields
    config.hubPort = config.hubPort || 25105
    config.bridgePort = config.bridgePort || 8080

    console.log("Validating config file")

    // Remove any unknown keys from config
    let KNOWN_KEYS = ["name", "username", "password", "host", "model", "devices"]
    for (let key in Object.keys(config)) {
        if (!KNOWN_KEYS.includes(key))
            delete config[key]
    }

    // Check required keys are present
    let requiredKeys = ["name", "host", "model", "devices"]
    let diff = requiredKeys.filter(key => !Object.keys(config).includes(key))
    if (config.model && config.model.toString() == "2245"){
        let additionalRequired = ["username", "password"]
        requiredKeys = requiredKeys.concat(additionalRequired)
        additionalRequired.forEach(key => {
            if (!Object.keys(config).includes(key))
                diff.push(key)
        })
    }
    if (diff.length > 0) {
        console.log(`config is missing required keys: ${diff}`)
        process.exit(1)
    }

    // Check for correct types
    if (!(config.devices instanceof Array))
    {
        console.log("devices must be an array")
        process.exit(1)
    }
    // Convert values to strings
    for (let key of requiredKeys) {
        if (key == "devices") continue
        config[key] = config[key].toString()
    }
    
    let knownHubs = ["2245", "2243", "2243", "plm"]
    if (!knownHubs.includes(config.model)) {
        console.log(`Unknown hub model=[${config.model}]. Known models are ${knownHubs}`)
        process.exit(1)
    }

    // Make sure all devices are configured correctly
    for (let dev of config.devices) {
        let requiredDeviceKeys = ["name", "deviceID", "deviceType"]
        let diff = requiredDeviceKeys.filter(key => !Object.keys(dev).includes(key))
        if (diff.length > 0) {
            console.log(`A device is missing required keys: ${diff}`)
            process.exit(1)
        }

        dev.name = dev.name.toString()
        dev.deviceID = dev.deviceID.toString().toUpperCase().replace(new RegExp('[.:]', 'g'), '')
        dev.deviceType = dev.deviceType.toString().toLowerCase()

        let knownTypes = ["switch", "dimmer", "lightbulb", "leaksensor", "contactsensor", "windowsensor", "doorsensor"]
        if (!knownTypes.includes(dev.deviceType)) {
            console.log(`Device name=[${dev.name}] is unknown deviceType=[${dev.deviceType}]. Known device types are ${knownTypes}`)
            process.exit(1)
        }
    }

    // Log a copy of the config with credentials removed
    {
        let config_copy = Object.assign({}, config)
        config_copy.username = "*****"
        config_copy.password = "*****"
        console.log(`config=${JSON.stringify(config_copy)}`)
    }

    return config
}


function InsteonBridge() {

    let config = readConfig()
    let hub = new Insteon()
    let server = new websocket.Server({ port: config.bridgePort })

    // Gracefully shutdown on unhandled exception
    process.on("uncaughtException", err => {
        console.error("UNHANDLED EXCEPTION\n", err)
        server.clients.forEach(function(socket) {
            socket.close()
        })
        process.exit(1)
    })

    // Build a table of devices
    let devices = {}
    config.devices.forEach(function(devConfig) {
        devConfig.deviceID = devConfig.deviceID.toUpperCase()

        switch (devConfig.deviceType) {
            case "doorsensor":
            case "windowsensor":
            case "contactsensor":
                devices[devConfig.deviceID] = hub.door(devConfig.deviceID); break
            case "leaksensor":
                devices[devConfig.deviceID] = hub.leak(devConfig.deviceID); break
            case "switch":
                devices[devConfig.deviceID] = hub.light(devConfig.deviceID); break
            case "lightbulb":
            case "dimmer":
                devices[devConfig.deviceID] = hub.light(devConfig.deviceID); break
        }

        devices[devConfig.deviceID].deviceID = devConfig.deviceID
        devices[devConfig.deviceID].name = devConfig.name
        devices[devConfig.deviceID].deviceType = devConfig.deviceType
        devices[devConfig.deviceID].isDimmable = function() {
            return ["lightbulb", "dimmer"].includes(this.deviceType)
        }
    })

    // Create a message string
    function createMessage(type, data) {
        return JSON.stringify({
            "type": type,
            "data": data
        })
    }

    // Send message to all clients
    function broadcastMessage(type, data) {
        console.log(`Sending to all clients type=${type} message=${JSON.stringify(data)}`)
        server.clients.forEach(function(client) {
            if (client.readyState === WebSocket.OPEN) {
                client.send(createMessage(type, data))
            }
        })
    }

    let insteonConnected = false
    function connectToHub() {
        switch(config.model) {
            case "2245":
                console.log(`Connecting to [${config.name}] address=[${config.host}:${config.hubPort}]: Insteon 2245 hub...`)
                hub.httpClient({host: config.host, port: config.hubPort, user: config.username, password: config.password}, function(){
                    insteonConnected = true
                    console.log("Connected to hub")
                    broadcastMessage("bridgestatus", {"message":"Connected to hub", "insteonConnection": insteonConnected ? "connected" : "disconnected"})
                })
                break
            case "2243":
                console.log(`Connecting to [${config.name}] port=[${config.host}]: Insteon 2243 hub...`)
                hub.serial(host, { baudRate: 19200 }, function() {
                    insteonConnected = true
                    console.log("Connected to hub")
                    broadcastMessage("bridgestatus", {"message":"Connected to hub", "insteonConnection": insteonConnected ? "connected" : "disconnected"})
                })
                break
            case "2242":
                console.log(`Connecting to [${config.name}] address=[${config.host}]: Insteon 2242 hub...`)
                hub.connect(config.host, function() {
                    insteonConnected = true
                    console.log("Connected to hub")
                    broadcastMessage("bridgestatus", {"message":"Connected to hub", "insteonConnection": insteonConnected ? "connected" : "disconnected"})
                })
                break
            case "plm":
                console.log(`Connecting to [${config.name}] port=[${config.host}]: Insteon PLM...`)
                hub.serial(host, { baudRate: 19200 }, function() {
                    insteonConnected = true
                    console.log("Connected to PLM")
                    broadcastMessage("bridgestatus", {"message":"Connected to PLM", "insteonConnection": insteonConnected ? "connected" : "disconnected"})
                })
                break
        }
    }


    function init() {
        console.log("Starting websocket server")

        // Setup ping for all clients to monitor connections
        // If clients miss a ping, assume the connection is dead and kill it
        const interval = setInterval(function () {
            server.clients.forEach(function (ws) {
                if (ws.isAlive === false) {
                    console.log(`Terminating stale connection to client=[${ws.clientIP}]`)
                    return ws.terminate();
                }
                // Set isAlive to false and send a ping. The client should respond with pong before the next check
                ws.isAlive = false;
                debug(`Sending PING request to client=[${ws.clientIP}]`)
                ws.ping();
            });
        }, 30000);

        server.on("close", function() {
            clearInterval(interval);
        });

        // Main server implementation
        server.on("connection", function (ws, req) {
            ws.isAlive = true
            ws.clientIP = req.socket.remoteAddress.replace("::ffff:", "")
            console.log(`Client [${ws.clientIP}] connected to websocket`)

            ws.on("close", function () {
                console.log(`Websocket closed by client=[${ws.clientIP}]`)
                ws.isAlive = false
            })

            // Client responded to a ping so mark the connection alive
            ws.on("pong", function(){
                debug(`Received PONG response from client=[${ws.clientIP}]`)
                ws.isAlive = true
            })

            ws.send(createMessage("bridgestatus", {"message": "Bridge connection established", "insteonConnection": insteonConnected ? "connected" : "disconnected"}))

            // Client is requesting ping from the server
            ws.on("ping", function() {
                debug(`Received PING request from client=[${ws.clientIP}]`)
                ws.isAlive = true
            })

            ws.on("message", function (message) {
                debug(`Client request raw message=${message}`)

                let request = null

                // Compatibility for older driver that sends getDevices directly
                if (message == "getDevices") {
                    request = {
                        "method": "listDevices",
                        "params": { }
                    }
                } else {
                    request = JSON.parse(message)
                }

                // Validate deviceID
                if (request.params.deviceID) {
                    request.params.deviceID = request.params.deviceID.toUpperCase()
                    if (!request.params.deviceID in devices) {
                        ws.send(createMessage("error", {"message": `Unknown device ID ${params.deviceID} in call ${request.method}`}))
                        return
                    }
                }

                // Helper function for sending response on websocket
                function sendResponse(data, type=request.method) {
                    if (ws.isAlive) {
                        console.log(`Sending type=${type} message=${JSON.stringify(data)}`)
                        ws.send(createMessage(type, data))
                    }
                }

                if (request.method == "listDevices") {
                    sendResponse(config.devices)
                    return
                }

                // Helper to periodically send an event with the device level until it hits the expected level or timeout
                function trackDeviceLevel(device, expectedLevel=null, pollInterval=500, timeout=null) {
                    if (expectedLevel == null && timeout == null) {
                        throw new Error("trackDeviceLevel cannot have both expectedLevel and timeout undefined")
                    }
                    let startTime = new Date();

                    let nextUpdate = (timeout && timeout < pollInterval) ? timeout : pollInterval
                    debug(`Device=[${device.name}] status will be updated in ${nextUpdate/1000} second`)
                    setTimeout(update, nextUpdate)

                    function update() {
                        device.level().then(function(devLevel) {
                            let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: devLevel }
                            sendResponse(message, "event")

                            // Stop updating if we hit the expected level
                            if (expectedLevel != null && devLevel == expectedLevel) {
                                debug(`Device=[${device.name}] status update reached expectedLevel=${expectedLevel}`)
                                return
                            }
                            // Stop updating if we hit the timeout
                            if ((new Date() - startTime) > timeout) {
                                debug(`Device=[${device.name}] status update reached timeout=${timeout}`)
                                return
                            }

                            nextUpdate = (timeout && timeout < pollInterval) ? timeout : pollInterval
                            debug(`Device=[${device.name}] status will be updated in ${nextUpdate/1000} second (timeout=${timeout} expectedLevel=${expectedLevel})`)
                            setTimeout(update, nextUpdate)
                        })
                    }
                }
                // Helper to select a polling interval depending on the ramp rate
                function getPollInterval(rampTime) {
                    if (rampTime <= 2000)
                        return 500
                    else if (rampTime <= 60000)
                        return 1000
                    else
                        return 30000
                }

                let device = devices[request.params.deviceID]
                switch(request.method) {
                    case "deviceInfo":
                        device.info().then(function(info){
                            info["deviceID"] = request.params.deviceID
                            if (!device.isDimmable()) {
                                delete info["rampRate"]
                                delete info["onLevel"]
                            }
                            sendResponse(info)
                        })
                        break

                    case "deviceLevel":
                        device.level().then(function(devLevel){
                            let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: devLevel }
                            sendResponse(message, "event")
                        })
                        break

                    case "deviceOff":
                        if (request.params.rate == null) request.params.rate = undefined
                        device.turnOff(request.params.rate).then(function(cmdStatus){
                            if (cmdStatus.success) {
                                if (device.isDimmable()) {
                                    // The rampRate can be several seconds or even minutes and immediately sending an event with the
                                    // level will only report how much the light has turned off so far. So, get the ramp rate from
                                    // the device and set a timer to poll the status until the ramp rate or expected level
                                    if (request.params.rate) {
                                        trackDeviceLevel(device, 0, getPollInterval(request.params.rate), request.params.rate + 5000)
                                    } else {
                                        device.info().then(function(info){
                                            trackDeviceLevel(device, 0, getPollInterval(info.rampRate), info.rampRate + 5000)
                                        })
                                    }
                                } else {
                                    // For non-dimmable devices, send the status right away
                                    let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: 0 }
                                    sendResponse(message, "event")
                                }
                            } else {
                                console.log(`ERROR: failed to turn off device=[${device.name}]\n${JSON.stringify(cmdStatus, null, "  ")}`)
                                sendResponse({"message": `ERROR: failed to turn off device=[${device.name}]`}, "error")
                            }
                        })
                        break

                    case "deviceOn":
                        if (request.params.level == null) request.params.level = undefined
                        if (request.params.rate == null) request.params.rate = undefined
                        device.turnOn(request.params.level, request.params.rate).then(function(cmdStatus) {
                            if (cmdStatus.success) {
                                if (device.isDimmable()) {
                                    // The rampRate can be several seconds or even minutes and immediately sending an event with the
                                    // level will only report how much the light has turned on so far. We could cheat and send an
                                    // event with the expected level, assuming the light will get there eventually, but this function
                                    // allows you to turn the light on without specifying a level, so we don't know what level it
                                    // will go to. So, get the ramp rate from the device and poll the status until the ramp rate or
                                    // expected level
                                    // Send first level update right away
                                    device.level().then(function(devLevel) {
                                        let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: devLevel }
                                        sendResponse(message, "event")
                                    })
                                    // Get the onLevel/rampRate and then track status
                                    device.info().then(function(info){
                                        let rampTime = request.params.rate
                                        if (!rampTime)
                                            rampTime = info.rampRate
                                        let expectedLevel = request.params.level
                                        if (!expectedLevel)
                                            expectedLevel = info.onLevel
                                        trackDeviceLevel(device, expectedLevel, getPollInterval(rampTime), rampTime + 5000)
                                    })
                                } else {
                                    // For non-dimmable devices, send the status right away
                                    let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: 100 }
                                    sendResponse(message, "event")
                                }
                            } else {
                                console.log(`ERROR: failed to turn on device=[${device.name}]\n${JSON.stringify(cmdStatus, null, "  ")}`)
                                sendResponse({"message": `ERROR: failed to turn on device=[${device.name}]`}, "error")
                            }
                        })
                        break

                    case "deviceFastOn":
                        device.turnOnFast().then(function(cmdStatus){
                            if (cmdStatus.success) {
                                device.level().then(function(devLevel) {
                                    let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: devLevel }
                                    sendResponse(message, "event")
                                })
                            } else {
                                console.log(`ERROR: failed to turn on (fast) device=[${device.name}]\n${JSON.stringify(cmdStatus, null, "  ")}`)
                                sendResponse({"message": `ERROR: failed to turn on (fast) device=[${device.name}]`}, "error")
                            }
                        })
                        break

                    case "deviceFastOff":
                        device.turnOffFast().then(function(cmdStatus){
                            if (cmdStatus.success) {
                                let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: 0 }
                                sendResponse(message, "event")
                            } else {
                                console.log(`ERROR: failed to turn off (fast) device=[${device.name}]\n${JSON.stringify(cmdStatus, null, "  ")}`)
                                sendResponse({"message": `ERROR: failed to turn off (fast) device=[${device.name}]`}, "error")
                            }
                        })
                        break

                    case "deviceSetRampRate":
                        if (device.isDimmable()) {
                            sendResponse({"message":"Cannot set rampRate on non-dimmable device"}, "error")
                            break
                        }
                        device.rampRate(1, request.params.rate).then(function(devRampRate){
                            if (devRampRate == null) {
                                device.info().then(function(devInfo){
                                    devInfo.deviceID = device.deviceID
                                    sendResponse(devInfo, "deviceInfo")
                                })
                            } else {
                                sendResponse({deviceID: device.deviceID, "rampRate": devRampRate}, "deviceInfo")
                            }
                        })
                        break

                    case "deviceSetOnLevel":
                        if (device.isDimmable()) {
                            sendResponse({"message":"Cannot set onLevel on non-dimmable device"}, "error")
                            break
                        }
                        device.onLevel(1, request.params.level).then(function(devOnLevel){
                            if (devOnLevel == null) {
                                device.info().then(function(devInfo){
                                    devInfo.deviceID = device.deviceID
                                    sendResponse(devInfo, "deviceInfo")
                                })
                            } else {
                                sendResponse({deviceID: device.deviceID, "onLevel": devOnLevel}, "deviceInfo")
                            }
                        })
                        break

                    case "deviceSetLevel":
                        if (device.isDimmable()) {
                            sendResponse({"message":"Cannot set level on non-dimmable device"}, "error")
                            break
                        }
                        if (request.params.rate == null) request.params.rate = undefined
                        device.level(request.params.level).then(function(cmdStatus){
                            if (cmdStatus.success) {
                                device.level().then(function(devLevel) {
                                    let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: devLevel }
                                    sendResponse(message, "event")
                                })
                            } else {
                                console.log(`ERROR: failed to set level=${request.params.level} device=[${device.name}]\n${JSON.stringify(cmdStatus, null, "  ")}`)
                                sendResponse({"message": `ERROR: failed to set level=${request.params.level} device=[${device.name}]`}, "error")
                            }
                        })
                        break

                    default:
                        sendResponse({"message": `Unknown method=[${request.method}]`}, "error")
                        return
                }
            })

            // Register event handlers for each device
            for (let deviceID in devices) {
                let device = devices[deviceID]

                function sendStatus(newStatus) {
                    let message = { name: device.name, deviceID: device.deviceID, deviceType: device.deviceType, state: newStatus }
                    console.log(`    Sending message=${JSON.stringify(message)}`)
                    if (ws.isAlive) { ws.send(createMessage("event", message)) }
                }

                switch (device.deviceType) {
                    case "doorsensor":
                    case "windowsensor":
                    case "contactsensor":
                        device.on("opened", function() {
                            console.log(`EVENT [opened] from device=[${device.name}]`)
                            sendStatus("open")
                        })

                        device.on("closed", function() {
                            console.log(`EVENT [closed] from device=[${device.name}]`)
                            sendStatus("closed")
                        })

                        break

                    case "leaksensor":
                        device.on("dry", function() {
                            console.log(`EVENT [dry] from device=[${device.name}]`)
                            sendStatus("dry")
                        })

                        device.on("wet", function() {
                            console.log(`EVENT [wet] from device=[${device.name}]`)
                            sendStatus("wet")
                        })

                        break

                    case "switch":
                        device.on("turnOn", function(group, level) {
                            console.log(`EVENT [on] from device=[${device.name}]`)
                            sendStatus(100)
                        })
                        device.on("turnOnFast", function(group, level) {
                            console.log(`EVENT [fast on] from device=[${device.name}]`)
                            sendStatus(100)
                        })
                        device.on("turnOff", function() {
                            console.log(`EVENT [off] from device=[${device.name}]`)
                            sendStatus(0)
                        })
                        device.on("turnOffFast", function(group, level) {
                            console.log(`EVENT [fast off] from device=[${device.name}]`)
                            sendStatus(0)
                        })

                        break

                    case "lightbulb":
                    case "dimmer":
                        function processEvent(eventName, level) {
                            // level will be set for events caused by commands, but level is not set for events caused by physical button press on the device
                            if (level === undefined || level == null) {
                                device.light.level().then(function(devLevel){
                                    console.log(`EVENT [${eventName}] from device=[${device.name}] level=[${devLevel}]`)
                                    sendStatus(devLevel)
                                })
                            } else {
                                console.log(`EVENT [${eventName}] from device=[${device.name}] level=[${devLevel}]`)
                                sendStatus(level)
                            }
                        }

                        device.on("turnOn", function(group, level) {
                            processEvent("on", undefined)
                        })
                        device.on("turnOnFast", function(group, level) {
                            processEvent("faston", undefined)
                        })
                        device.on("turnOff", function() {
                            processEvent("off", 0)
                        })
                        device.on("turnOffFast", function(group, level) {
                            processEvent("fastoff", 0)
                        })
                        device.on("brightened", function() {
                            processEvent("brightened", undefined)
                        })
                        break
                }
            }

        })
    }


    // Connect to the hub and setup the websocket server
    connectToHub()
    init()

}
