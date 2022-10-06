/**
 *  Insteon door/window contact sensor
 */

metadata {
    definition(name: 'Insteon Contact Sensor', namespace: 'cjs', author: 'Carl Seelye') {
        capability 'Contact Sensor'

        attribute 'contact', 'enum', ['open', 'closed']
    }
    // preferences {
    //     input(name: 'debugEnable',
    //         title: 'Enable debug logging',
    //         type: 'bool',
    //         defaultValue: true,
    //     )
    // }
}
