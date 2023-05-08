# Morpheus Amazon Web Services Plugin

This library provides an integration between AWS and Morpheus. A `CloudProvider` (for syncing the Cloud related objects) and `ProvisionProvider` (for provisioning into AWS EC2) are implemented  

### Requirements
Nutanix Prism Central - Version pc.2022.6 or greater, NCC Version: 4.6.0 or greater

### Building
`./gradlew shadowJar`

### Configuration
The following options are required when setting up a Morpheus Cloud to a Nutanix Prism Central environment using this plugin:
1. API URL: The Nutanix API endpoint (i.e. https://10.100.10.100:9440/)
2. Username
3. Password

#### Features
Cloud sync: Clusters, categories, datastores, networks, virtual private clouds, images, hosts, and virtual machines are fetched from Nutanix and inventoried in Morpheus. Any additions, updates, and removals to these objects are reflected in Morpheus.

Provisioning: Virtual machines can be provisioned from Morpheus via this plugin.
