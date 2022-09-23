library (
  author: 'cseelye',
  description: 'Utility functions',
  name: 'util',
  namespace: 'cjs'
)

// Dump info about an object to debug log
void dumpObj(String name, Object var) {
    dumpout = "Dumping ${name} (${var.class})\n"
    dumpout += "${name}=${var}\n"

    dumpout += 'Properties:\n'
    try {
        var.properties.sort().each { prop ->
            varname = prop.key
            varval = prop.value
            vartype = 'null'
            if (varval) {
                vartype = varval.class
            }
            dumpout += "    ${varname}=${varval}  (${vartype})\n"
        }
    }
    catch (all) { }

    dumpout += 'Methods:\n'
    try {
        parent.metaClass.methods.sort().each { method ->
            dumpout += "    ${method}\n"
        }
    }
    catch (all) { }

    log.debug dumpout
}

// Get the name of the function that called a logging function (debug, info, warn, error)
String getCaller(String className='user_driver_cjs_Insteon', int offset=4) {
    try {
        marker = new Throwable()
        filtered = []
        // namespace is sometimes null, so we cannot auto-generate this
        // mangled_name = "user_driver_${namespace}_${device.typeName.replace(' ', '_')}"
        for (int i=0; i<marker.stackTrace.size(); i++) {
            if (marker.stackTrace[i].className.startsWith(className)) { // only stack frames from this class/script
                filtered.add(marker.stackTrace[i])
            }
        }
        for (int i=0; i<filtered.size(); i++) {
            if (filtered[i].methodName == 'getCaller') {
                idx = offset
                // skip stack frames from async calls framework
                if (filtered[i+idx].methodName == 'doCall' ||
                    filtered[i+idx].methodName == 'callCurrent') {
                    idx++
                }
                return filtered[i+idx].methodName
            }
        }
    }
    catch (Exception ex) {
        log.debug("Error getting stack: ${ex}")
        return 'unknown'
    }
}

String logPrefix() {
    return "${device.displayName}: [${getCaller()}] "
}

void debug(String message) {
    if (debugEnable) {
        log.debug("${logPrefix()}${message}")
    }
}

void info(String message) {
    log.info("${logPrefix()}${message}")
}

void warn(String message) {
    log.warn("${logPrefix()}${message}")
}

void error(String message) {
    log.error("${logPrefix()}${message}")
}
