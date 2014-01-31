Intellij plugin development with Maven
--------------------------------------

This plugin provides minimal support for developing IntelliJ plugins using IntelliJ
Maven integration.

First, this plugin imports Maven module as plugin modules (PLUGIN_MODULE) if one of
the following is true:

 * Packaging is set to 'ij-plugin'</li>
 * Packaging is set to 'jar' and 'ij.plugin' property is set to 'true'</li>
 * Packaging is set to 'jar' and 'com.guidewire.build:ijplugin-maven-plugin' Maven plugin is configured for the module

Second, it removes all dependencies which have groupId starting with 'com.jetbrains.intellij.'
after the import, assuming they would be provided through the IDEA SDK (so you can use
these dependencies during the command-line build and have them fulfilled by IDEA SDK
automatically when you import your module)

Third, this plugin updates plugin descriptor location using one of the following:

 * Explicitly configured value in the ijplugin-maven-plugin configuration.</li>
 * Value of the 'ij.pluginDescriptor' property in the POM</li>
 * Default value of "META-INF/plugin.xml"</li>
