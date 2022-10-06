/**
 *  Insteon leak sensor
 */

metadata {
    definition(name: 'Insteon Leak Sensor', namespace: 'cs.insteon', author: 'Carl Seelye') {
        capability 'Water Sensor'

        attribute 'water', 'enum', ['wet', 'dry']
    }
    // preferences {
    //     input(name: 'debugEnable',
    //         title: 'Enable debug logging',
    //         type: 'bool',
    //         defaultValue: true,
    //     )
    // }
}
