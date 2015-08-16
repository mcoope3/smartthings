/**
 *  Monoprice Smoke Detector which is the same as the Everspring SF813
 *  
 *
 *  Author: cuboy29
 *		Based on SmartThings code and tosa68 code for the Everspring/Utilitech Water Sensor
 *           
 *  Date: 2015-08-13
 *
 */

preferences {
    // manufacturer default wake up is every hour; optionally increase for better battery life
    input "userWakeUpInterval", "number", title: "Wake Up Interval (seconds)", description: "Default 3600 sec (10 mins - 7 days)", defaultValue: '3600', required: false, displayDuringSetup: true
}

metadata {
	definition (name: "Monoprice Smoke Sensor", namespace: "cuboy29/smartthings", author: "cuboy29") {
		capability "Smoke Detector"
		capability "Sensor"
		capability "Battery"
        
        //Support Command Class
        //0x30 Sensor Binary
        //0x71 Alarm/Notification
        //0x72 Manufacture Specific
        //0x86 Version
        //0x85 Association V2
        //0x80 Battery
        //0x84 Wake Up V2

        fingerprint deviceId: "0xA100", inClusters: "0x30,0x71,0x72,0x86,0x85,0x80,0x84"
    
	}
}

	tiles {
		standardTile("smoke", "device.smoke", width: 2, height: 2) {
			state("clear", label:"clear", icon:"st.alarm.smoke.clear", backgroundColor:"#ffffff")
			state("smoke", label:"SMOKE", icon:"st.alarm.smoke.smoke", backgroundColor:"#e86d13")	
            state("tamper", label:"TAMPER", icon:"st.alarm.smoke.test", backgroundColor:"#e86d13")
            state("batt", label:"BAT_LOW", icon:"st.alarm.smoke.test", backgroundColor:"#e86d13")
            state("unkown", label:"UNKOWN", icon:"st.unknown.zwave.device")
		}
        standardTile("refresh", "command.refresh", inactiveLabel: false, decoration: "flat") {
            state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
        }
		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat") {
			state "battery", label:'${currentValue}% battery', unit:""
		}

        
		main "smoke"
		details(["smoke", "battery"])
	}

def parse(String description) {
    def result = null 
    
    def cmd = zwave.parse(description, [ 0x71: 1, 0x85: 2, 0x72: 1, 0x84: 2, 0x86: 1] )
    if (cmd) {
        result = zwaveEvent(cmd)
    } else {
        log.debug "Non-parsed event: ${description}"
    }

    return result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
        def result = [createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)]
        def userWake = getUserWakeUp(userWakeUpInterval)
        
        log.debug "Wake up event"

        // Only ask for battery if we haven't had a BatteryReport in a while
        if (!state.lastbatt || (new Date().time) - state.lastbatt > 24*60*60*1000) {
                result << response(zwave.batteryV1.batteryGet())
                result << response("delay 1200")  // leave time for device to respond to batteryGet
        }
        
        // If user has changed userWakeUpInterval, send the new interval to the device 
    	if (state.wakeUpInterval != userWake){
       		state.wakeUpInterval = userWake
        	result << response("delay 200")
        	result << response(zwave.wakeUpV2.wakeUpIntervalSet(seconds:state.wakeUpInterval, nodeid:zwaveHubNodeId))
        	result << response("delay 200")
        	result << response(zwave.wakeUpV2.wakeUpIntervalGet())
    	}
        
        result << response(zwave.wakeUpV2.wakeUpNoMoreInformation())
        result
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
        def map = [ name: "battery", unit: "%" ]
        
        log.debug "Current Battery Remaining: " + cmd.batteryLevel + " %"
        if (cmd.batteryLevel == 0xFF) {  // Special value for low battery alert
                map.value = 1
                map.descriptionText = "${device.displayName} has a low battery"
                map.isStateChange = true
        } else {
                map.value = cmd.batteryLevel
        }
        // Store time of last battery update so we don't ask every wakeup, see WakeUpNotification handler
        state.lastbatt = new Date().time
        createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd){
	
    log.debug "Physicalgraph.zwave.commands.basicv1.BasicSet: " + cmd.value
    //we don't really care so don't do anything
}

def zwaveEvent(physicalgraph.zwave.commands.alarmv1.AlarmReport cmd){
	
	log.debug "AlarmType: " + cmd.alarmType
    log.debug "AlarmLevel: " + cmd.alarmLevel
    
    if (cmd.alarmType == 0x01){
    	if (cmd.alarmLevel == 0x01) {
        	createEvent(name: "smoke", value: "batt", descriptionText: "$device.displayName: battery is low")
        } else if (cmd.alarmLevel == 0xFF){
        	createEvent(name: "smoke", value: "smoke", descriptionText: "$device.displayName: smoke is detected")
        } else if (cmd.alarmLevel == 0x00){
        	createEvent(name: "smoke", value: "clear", descriptionText: "$device.displayName: smoke has cleared")
        }  
    }
    //Monoprice documenation show that they support tamper alarm but not sure if they send it or not.  
    //All I see is the same alarmType and alarmLevel as smoke detected alarm.
   
    else if (cmd.alarmType == 0x02 && cmd.alarmLevel == 0xFF){
    	createEvent(name: "smoke", value: "tamper", descriptionText: "$device.displayName: tamper alarm")
    } else {
    	createEvent(name: "smoke", value: "unknown", "$device.displayName status unknown")
    }
}

def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd) {
        def result = []
        if (cmd.nodeId.any { it == zwaveHubNodeId }) {
                result << createEvent(descriptionText: "$device.displayName is associated in group ${cmd.groupingIdentifier}")
        } else if (cmd.groupingIdentifier == 1) {
                // We're not associated properly to group 1, set association
                result << createEvent(descriptionText: "Associating $device.displayName in group ${cmd.groupingIdentifier}")
                result << response(zwave.associationV1.associationSet(groupingIdentifier:cmd.groupingIdentifier, nodeId:zwaveHubNodeId))
        }
        result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpIntervalReport cmd) {

	def map = [ name: "userWakeUpInterval", unit: "seconds" ]
	map.value = cmd.seconds
	map.displayed = false

	log.debug "userWakeUpInterval: " + cmd.seconds

	createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.Command cmd, results) {
	def event = [ displayed: false ]
	event.linkText = device.label ?: device.name
	event.descriptionText = "$event.linkText: $cmd"
	results << createEvent(event)
}

private getUserWakeUp(userWake) {

    if (!userWake)                       { userWake =     '3600' }  // set default 1 hr if no user preference 

    // make sure user setting is within valid range for device 
    if (userWake.toInteger() <       60) { userWake =       '600' }  // 10 minutes
    if (userWake.toInteger() > 16761600) { userWake = '604600' }  // 1 week

	/*
     * Ideally, would like to reassign userWakeUpInterval to min or max when needed,
     * so it more obviously reflects in 'preferences' in the IDE and mobile app
     * for the device. Referencing the preference on the RH side is ok, but
     * haven't figured out how to reassign it on the LH side?
     *
     */
    //userWakeUpInterval = userWake 

    return userWake.toInteger()
}
