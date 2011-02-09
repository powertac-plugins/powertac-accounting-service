class PowertacAccountingServiceGrailsPlugin {
    // the plugin version
    def version = "0.1.1-SNAPSHOT"
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "1.3.6 > *"
    // the other plugins this plugin depends on
    def dependsOn = ['powertacCommon':'0.9 > *', 'powertacServerInterface':'0.1 > *']
    //def dependsOn = ['powertacCommon':'0.9 > *']
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
            "grails-app/views/error.gsp"
    ]

    // TODO Fill in these fields
    def author = "Carsten Block"
    def authorEmail = "powertac@carstenblock.org"
    def title = "Accounting Service for PowerTAC competition"
    def description = '''\\
Accounting Service is a plugin for the PowerTAC competition that does all the bookkeeping, i.e. it
keeps track of cash accounts, stock positions, and tariff contracts etc.
'''

    // URL to the plugin's documentation
    def documentation = "http://powertac.org/plugin/powertac-accounting-service"

    def doWithWebDescriptor = { xml ->
        // TODO Implement additions to web.xml (optional), this event occurs before 
    }

    def doWithSpring = {
        // TODO Implement runtime spring config (optional)
    }

    def doWithDynamicMethods = { ctx ->
        // TODO Implement registering dynamic methods to classes (optional)
    }

    def doWithApplicationContext = { applicationContext ->
        // TODO Implement post initialization spring config (optional)
    }

    def onChange = { event ->
        // TODO Implement code that is executed when any artefact that this plugin is
        // watching is modified and reloaded. The event contains: event.source,
        // event.application, event.manager, event.ctx, and event.plugin.
    }

    def onConfigChange = { event ->
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }
}
