package com.morpheusdata.aws

import com.amazonaws.services.elasticloadbalancing.model.ApplySecurityGroupsToLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancing.model.AttachLoadBalancerToSubnetsRequest
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerListenersRequest
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerResult
import com.amazonaws.services.elasticloadbalancing.model.DeleteLoadBalancerListenersRequest
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult
import com.amazonaws.services.elasticloadbalancing.model.Listener
import com.amazonaws.services.elasticloadbalancing.model.ListenerDescription
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.amazonaws.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.network.loadbalancer.LoadBalancerProvider
import com.morpheusdata.model.AccountCertificate
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetworkLoadBalancer
import com.morpheusdata.model.NetworkLoadBalancerInstance
import com.morpheusdata.model.NetworkLoadBalancerMonitor
import com.morpheusdata.model.NetworkLoadBalancerNode
import com.morpheusdata.model.NetworkLoadBalancerPolicy
import com.morpheusdata.model.NetworkLoadBalancerPool
import com.morpheusdata.model.NetworkLoadBalancerProfile
import com.morpheusdata.model.NetworkLoadBalancerRule
import com.morpheusdata.model.NetworkLoadBalancerType
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class ELBLoadBalancerProvider implements LoadBalancerProvider {
    MorpheusContext morpheusContext
    Plugin plugin

    private static final PROVIDER_CODE = 'amazon-elb'

    public ELBLoadBalancerProvider(Plugin plugin, MorpheusContext context) {
        super()
        this.@plugin = plugin
        this.@morpheusContext = context
    }

    @Override
    String getDescription() {
        return null
    }

    @Override
    Icon getIcon() {
        return null
    }

    @Override
    Collection<OptionType> getOptionTypes() {
        return null
    }

    @Override
    Collection<NetworkLoadBalancerType> getLoadBalancerTypes() {
        NetworkLoadBalancerType type = new NetworkLoadBalancerType(
            code:getLoadBalancerTypeCode(),
            name:'Amazon ELB - Plugin', // will change after testing
            internal:false,
            enabled:true,
            createType:'instance',
            supportsCerts:true,
            creatable:false,
            viewSet:'amazon',
            supportsHostname:true,
            certSize:2048,
            format:'external',
            supportsScheme:true,
            hasVirtualServers:false,
            hasMonitors:false,
            hasNodes:false,
            hasPolicies:false,
            hasProfiles:false,
            hasRules:false,
            hasScripts:false,
            hasServices:false,
            hasPools:false,
            createVirtualServers:false,
            createMonitors:false,
            createNodes:false,
            createPolicies:false,
            createProfiles:false,
            createRules:false,
            createScripts:false,
            createServices:false,
            createPools:false,
            editable:true,
            removable:true,
            nameEditable:false,
            createPricePlans: false,
            vipOptionTypes:getVirtualServerOptionTypes(),
            instanceOptionTypes:getInstanceOptionTypes()
        )
        return [type]
    }

    @Override
    ServiceResponse validate(NetworkLoadBalancer loadBalancer, Map opts) {
        def rtn = ServiceResponse.success()
        if(loadBalancer.name && loadBalancer.name.length() > 32) {
            rtn.success = false
            rtn.addError('name', 'name can be a maximum of 32 characters')
        }
        return rtn
    }

    @Override
    ServiceResponse initializeLoadBalancer(NetworkLoadBalancer loadBalancer, Map opts) {
        return null
    }

    @Override
    ServiceResponse refresh(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerProfile(NetworkLoadBalancerProfile profile) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerProfile(NetworkLoadBalancerProfile profile) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerProfile(NetworkLoadBalancerProfile profile) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerPolicy(NetworkLoadBalancerPolicy loadBalancerPolicy) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerPolicy(NetworkLoadBalancerPolicy loadBalancerPolicy) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerPolicy(NetworkLoadBalancerPolicy loadBalancerPolicy) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerRule(NetworkLoadBalancerRule rule) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerRule(NetworkLoadBalancerRule rule) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerRule(NetworkLoadBalancerRule rule) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            //need a name
            if(!instance.vipName) {
                rtn.addError('vipName', 'Name is required')
            }
            if(!instance.vipHostname) {
                rtn.addError('vipHostname', 'Hostname is required')
            }

            if(!instance.vipPort) {
                rtn.addError('vipPort', 'Vip Port is required')
            }
            rtn.success = rtn.errors.size() == 0
        } catch(e) {
            log.error("error validating virtual server: ${e}", e)
        }
        return rtn
    }


    @Override
    ServiceResponse addInstance(NetworkLoadBalancerInstance instance) {
        def opts = instance.getConfigProperty('options')
        log.debug "addInstance: ${instance}, ${opts}"
        return updateOrCreateInstance(instance, opts)
    }

    @Override
    ServiceResponse removeInstance(NetworkLoadBalancerInstance instance) {
        log.debug "removeInstance: ${instance}"
        def rtn = [success: false, deleted: false]
        try {
            def loadBalancer = instance.loadBalancer
            def lbName = loadBalancer.name
            com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(loadBalancer.zone, false, loadBalancer.region?.regionCode)
            //delete lb
            def lbRequest = new com.amazonaws.services.elasticloadbalancing.model.DeleteLoadBalancerRequest()
            lbRequest.setLoadBalancerName(lbName)
            def lbResult = amazonClient.deleteLoadBalancer(lbRequest)
            rtn.success = true
        } catch (e) {
            log.error("removeInstance error: ${e}", e)
        }
        return rtn
    }

    @Override
    ServiceResponse updateInstance(NetworkLoadBalancerInstance instance) {
        def opts = instance.getConfigProperty('options')
        log.debug "updateInstance: ${instance}, ${opts}"
        return updateOrCreateInstance(instance, opts)
    }

    @Override
    ServiceResponse addLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse setAdditionalConfiguration(NetworkLoadBalancer loadBalancer, Map opts) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerInstance(NetworkLoadBalancerInstance loadBalancerInstance) {
        def opts = loadBalancerInstance.getConfigProperty('options')
        log.debug "validateLoadBalancerInstance: ${loadBalancerInstance}, ${opts}"
        ServiceResponse rtn = ServiceResponse.success()
        try {
            log.info("validateLoadBalancerInstance: ${loadBalancerInstance}, ${opts}")
            if(opts.proxyProtocol == 'HTTPS' && !opts.sslCert) {
                rtn.msg = "When specifying HTTPS, an SSL Cert must be selected."
                rtn.success = false
            }
        } catch (e) {
            log.error("validateLoadBalancerInstance error: ${e}", e)
        }
        return rtn
    }

    @Override
    ServiceResponse validateLoadBalancerInstanceConfiguration(NetworkLoadBalancer loadBalancer, Instance instance) {
        def opts = loadBalancer.getConfigProperty('options')
        log.debug("validateLoadBalancerInstanceConfiguration: ${instance}, ${opts}")
        ServiceResponse rtn = ServiceResponse.success()
        try {
            def hostname = opts.vipHostname ?: instance.hostName
            if(hostname ==~ /^([a-zA-Z\d-\*\.]+)$/) {
                rtn.success = true
            } else {
                rtn.msg = 'Host Name must be fully qualified. For example, *.domain.com or www.domain.com'
                rtn.errors = ['vipHostname':'host name must be a fully qualified domain']
            }
        } catch(e)  {
            log.error "Error validating instance: ${e}", e
            rtn.msg = e.message
            rtn.success = false
        }
        return rtn
    }

    protected updateOrCreateInstance(NetworkLoadBalancerInstance loadBalancerInstance, opts) {
        log.debug "updateOrCreateInstance: ${loadBalancerInstance}, ${opts}"
        def rtn = [success: false, errors: [:]]
        try {
            def instance = loadBalancerInstance.instance

            def loadBalancer = loadBalancerInstance.loadBalancer
            def cloud = loadBalancer.cloud
            def createRequired = !loadBalancer.externalId
            CloudRegion regionFromInstance = instance.containers?.getAt(0)?.server?.region
            if(regionFromInstance && !loadBalancer.region?.regionCode) {
                loadBalancer.region = regionFromInstance
            }
            AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(cloud,loadBalancer.region?.regionCode)
            log.debug "Opts: sslCert: ${opts.sslCert}"
            def certId
            if(opts.sslCert instanceof String && opts.sslCert.isLong()) {
                certId = Long.valueOf(opts.sslCert)
            }
            else {
                certId = opts.sslCert && opts.sslCert?.id.toLong() > 0 ? opts.sslCert.id?.toLong() : loadBalancerInstance.sslCert?.id
            }
            def elbProtocol = getVipServiceMode(loadBalancerInstance)
            def elbPort = opts.instance?.customPort ?: loadBalancerInstance.vipPort?.toString()
            def instanceProtocol = getBackendServiceMode(loadBalancerInstance)
            def instancePort = loadBalancerInstance.servicePort.toLong()
            def sslRedirectMode = loadBalancerInstance.sslRedirectMode

            log.debug "elbProtocol: ${elbProtocol} elbPort: $elbPort instancePort: $instancePort, instanceProtocol: $instanceProtocol"
            // Handle the cert
            def certificateArn
            if(certId) {
                def certService = morpheus.loadBalancer.certificate
                AccountCertificate cert = certService.getAccountCertificateById(certId).blockingGet().value
                if(cert) {
                    certificateArn = AmazonComputeUtility.uploadOrGetCert(cert, cloud)
                }
            }
            // Gather the security groups and subnets
            def subnetList = []
            def securityList = []
            def lbSubnets = new LinkedList<String>()
            def lbSecurity = new LinkedList<String>()
            instance.containers?.findAll{ctr -> ctr.inService}?.each { container ->
                def subnetId = container.getConfigProperty('subnetId')
                def networkId
                if(subnetId) {
                    // The 'old' way
                    networkId = subnetId.toLong()
                }
                else {
                    if (container.server?.interfaces) {
                        networkId = container.server.interfaces.first().network?.id?.toLong()
                    }
                }
                // once we have the network id, fetch the model to get its external id and add it to our list
                if (networkId) {
                    morpheus.network.listById([networkId]).blockingSubscribe { network ->
                        subnetId = network.externalId
                    }
                }
                if (subnetId) {
                    subnetList << subnetId
                }
                def securityId = container.getConfigProperty('securityId')
                if(securityId) {
                    // The 'old' way
                    securityList << container.getConfigProperty('securityId')
                } else {
                    if(container.getConfigProperty('securityGroups')) {
                        securityId = container.getConfigProperty('securityGroups').first()?.id
                        if(securityId) {
                            securityList << securityId
                        }
                    } else if(container.server.getConfigProperty('securityGroups')) {
                        securityId = container.server.getConfigProperty('securityGroups').first()?.id
                        if(securityId) {
                            securityList << securityId
                        }
                    }
                }
            }
            if(subnetList?.size() > 0) {
                subnetList.unique().each { subnetId ->
                    lbSubnets.add(subnetId)
                }
            }
            if(securityList?.size() > 0) {
                securityList.unique().each { securityId ->
                    lbSecurity.add(securityId)
                }
            }
            if(createRequired) {
                log.debug "Existing LB NOT found... creating"
                def lbName = getLoadBalancerName(loadBalancer)
                CreateLoadBalancerRequest lbCreateRequest = new CreateLoadBalancerRequest()
                lbCreateRequest.setLoadBalancerName(lbName)
                if(lbSubnets?.size() > 0) {
                    lbCreateRequest.setSubnets(lbSubnets)
                }
                if(lbSecurity?.size() > 0) {
                    lbCreateRequest.setSecurityGroups(lbSecurity)
                }
                if(loadBalancer.configMap.scheme == 'internal') {
                    log.debug "setting scheme to internal"
                    lbCreateRequest.setScheme('internal')
                }
                // Listeners
                def lbListeners = new LinkedList<Listener>()
                Listener lbListener = new Listener(elbProtocol, elbPort.toInteger(), instancePort.toInteger())
                lbListener.instanceProtocol = instanceProtocol
                if(certificateArn)
                    lbListener.setSSLCertificateId(certificateArn)
                lbListeners.add(lbListener)

                // If passthrough.. need another listener
                if(sslRedirectMode == 'passthrough') {
                    Listener lbListenerRedirect = new Listener('http', 80, instancePort.toInteger())
                    lbListenerRedirect.instanceProtocol = instanceProtocol
                    lbListeners.add(lbListenerRedirect)
                }

                lbCreateRequest.setListeners(lbListeners)
                CreateLoadBalancerResult lbResult = amazonClient.createLoadBalancer(lbCreateRequest)
                loadBalancer.externalId = ':loadbalancer/' + lbName
                loadBalancer.sshHost = lbResult.getDNSName()
                morpheus.async.loadBalancer.bulkSave([loadBalancer]).blockingGet()
            } else {
                log.debug "Existing LB found.. updating"
                DescribeLoadBalancersRequest lbRequest = new DescribeLoadBalancersRequest().withLoadBalancerNames(new LinkedList<String>())
                lbRequest.getLoadBalancerNames().add(new String(loadBalancer.name))
                LoadBalancerDescription lbDescription
                try {
                    DescribeLoadBalancersResult tmpList = amazonClient.describeLoadBalancers(lbRequest)
                    lbDescription = tmpList.loadBalancerDescriptions.size() > 0 ? tmpList.loadBalancerDescriptions.get(0) : null
                } catch (e) {
                    // eat it... exceptions if not found
                }

                // Always delete and add the listener
                lbDescription?.listenerDescriptions?.each { ListenerDescription ld ->
                    // Delete any matching listeners on the instanceport of the loadbalancerport
                    if((ld.listener.instancePort?.toString() == instancePort.toString()) || (ld.listener.loadBalancerPort?.toString() == elbPort.toString())) {
                        log.debug "Deleting existing listener on instancePort: ${ld.listener.instancePort} and loadBalancerPort: ${ld.listener.loadBalancerPort}"
                        DeleteLoadBalancerListenersRequest deleteListenerRequest = new DeleteLoadBalancerListenersRequest(loadBalancer.name, new LinkedList<Integer>())
                        deleteListenerRequest.getLoadBalancerPorts().add(ld.listener.loadBalancerPort)
                        amazonClient.deleteLoadBalancerListeners(deleteListenerRequest)
                    }
                }
                // Now.. add the listener back
                CreateLoadBalancerListenersRequest createLoadBalancerListenersRequest = new CreateLoadBalancerListenersRequest(loadBalancer.name, new LinkedList<Listener>())
                Listener listener = new Listener(elbProtocol, elbPort.toInteger(), instancePort.toInteger())
                listener.instanceProtocol = instanceProtocol
                // Upload the cert (if needed)
                if(certificateArn)
                    listener.setSSLCertificateId(certificateArn)
                createLoadBalancerListenersRequest.listeners.add(listener)

                // If passthrough.. need another listener
                if(sslRedirectMode == 'passthrough') {
                    Listener lbListenerRedirect = new Listener('http', 80, instancePort.toInteger())
                    lbListenerRedirect.instanceProtocol = instanceProtocol
                    createLoadBalancerListenersRequest.listeners.add(lbListenerRedirect)
                }

                amazonClient.createLoadBalancerListeners(createLoadBalancerListenersRequest)
                // Security groups and subnets
                if(subnetList?.size() > 0) {
                    AttachLoadBalancerToSubnetsRequest attachLBToSubnets = new AttachLoadBalancerToSubnetsRequest()
                    attachLBToSubnets.loadBalancerName = loadBalancer.name
                    attachLBToSubnets.subnets = lbSubnets
                    amazonClient.attachLoadBalancerToSubnets(attachLBToSubnets)
                }
                if(lbSecurity?.size() > 0) {
                    ApplySecurityGroupsToLoadBalancerRequest attachLBToSecurityGroups = new ApplySecurityGroupsToLoadBalancerRequest()
                    attachLBToSecurityGroups.loadBalancerName = loadBalancer.name
                    attachLBToSecurityGroups.securityGroups = lbSecurity
                    amazonClient.applySecurityGroupsToLoadBalancer(attachLBToSecurityGroups)
                }
            }

            // Finally.. register all of the containers with the ELB
            instance.containers?.findAll{ctr -> ctr.inService}?.each { container ->
                def lbInstances = new LinkedList<com.amazonaws.services.elasticloadbalancing.model.Instance>()
                def lbInstance = new com.amazonaws.services.elasticloadbalancing.model.Instance(container.server.externalId)
                lbInstances.add(lbInstance)
                def lbRegisterRequest = new RegisterInstancesWithLoadBalancerRequest(loadBalancer.name, lbInstances)
                amazonClient.registerInstancesWithLoadBalancer(lbRegisterRequest)
            }
            rtn.success = true
        } catch (e) {
            rtn.message = e.message
            log.error("updateInstance error: ${e}", e)
        }
        return rtn
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.@morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.@plugin
    }

    @Override
    String getCode() {
        return PROVIDER_CODE
    }

    @Override
    String getName() {
        return 'Amazon ELB'
    }

    protected getVipServiceMode(NetworkLoadBalancerInstance loadBalancerInstance) {
        def rtn = 'http'
        def vipProtocol = loadBalancerInstance.vipProtocol
        if(vipProtocol == 'http') {
            rtn = 'http'
        } else if(vipProtocol == 'tcp') {
            rtn = 'tcp'
        } else if(vipProtocol == 'udp') {
            rtn = 'udp'
        } else if(vipProtocol == 'https') {
            def vipMode = loadBalancerInstance.vipMode
            //if we are terminating ssl on the lb - mode is https
            if(vipMode == 'passthrough')
                rtn = 'http'
            else if(vipMode == 'terminated' || vipMode == 'endtoend')
                rtn = 'https'
        }
        return rtn
    }

    protected getBackendServiceMode(NetworkLoadBalancerInstance loadBalancerInstance) {
        def rtn = 'http'
        def vipProtocol = loadBalancerInstance.vipProtocol
        if(vipProtocol == 'http') {
            rtn = 'http'
        } else if(vipProtocol == 'tcp') {
            rtn = 'tcp'
        } else if(vipProtocol == 'udp') {
            rtn = 'udp'
        } else if(vipProtocol == 'https') {
            def vipMode = loadBalancerInstance.vipMode
            //if we are terminating ssl on the lb - mode is https
            if(vipMode == 'passthrough' || vipMode == 'endtoend')
                rtn = 'https'
            else if(vipMode == 'terminated')
                rtn = 'http'
        }
        return rtn
    }

    private getLoadBalancerName(NetworkLoadBalancer loadBalancer) {
        // Must make sure it is unique within AWS
        def zone = loadBalancer.zone
        AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(zone, false, loadBalancer.region?.regionCode)

        // Must contain only letters, numbers, dashes and start with an alpha character
        def currentName = loadBalancer.name.replaceAll(/[^a-zA-Z0-9\-]/, '')?.take(255)
        def baseName = currentName

        def hasLBName = true
        def i = 0
        while(hasLBName == true && i < 10) {
            log.debug "looking for loadbalancer with name of ${currentName}"
            DescribeLoadBalancersRequest lbRequest = new DescribeLoadBalancersRequest().withLoadBalancerNames(new LinkedList<String>())
            lbRequest.getLoadBalancerNames().add(new String(currentName))
            try {
                DescribeLoadBalancersResult tmpList = amazonClient.describeLoadBalancers(lbRequest)
                hasLBName = tmpList.loadBalancerDescriptions.size() > 0 ? tmpList.loadBalancerDescriptions.get(0) : null
            }catch (e) {
                // eat it... exceptions if not found
                hasLBName = false
            }

            if(hasLBName) {
                currentName = "${baseName}${i}"
                i++
            }
        }
        return currentName
    }

    Collection<OptionType> getVirtualServerOptionTypes() {
        Collection<OptionType> virtualServerOptions = new ArrayList<OptionType>()
        virtualServerOptions << new OptionType(
            name:'ELB vipName',
            code:'plugin.aws.elb.vip.vipName',
            fieldName:'vipName',
            fieldCode:'gomorpheus.optiontype.Name',
            fieldContext:'domain',
            displayOrder:0,
            fieldLabel:'VIP Name',
            required:true,
            editable:false,
            inputType:OptionType.InputType.TEXT,
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ELB vipAddress',
            code:'plugin.aws.elb.vip.vipAddress',
            fieldName:'vipAddress',
            fieldCode:'gomorpheus.optiontype.VipAddress',
            fieldContext:'domain',
            displayOrder:10,
            fieldLabel:'VIP Address',
            required:true,
            inputType:OptionType.InputType.TEXT,
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ELB vipPort',
            code:'plugin.aws.elb.vip.vipPort',
            fieldName:'vipPort',
            fieldCode:'gomorpheus.optiontype.VipPort',
            fieldContext:'domain',
            displayOrder:12,
            fieldLabel:'VIP Port',
            required:true,
            inputType:OptionType.InputType.TEXT,
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ELB vipScheme',
            code:'plugin.aws.elb.vip.vipScheme',
            fieldName:'vipScheme',
            fieldCode:'gomorpheus.optiontype.Scheme',
            fieldContext:'domain',
            displayOrder:13,
            fieldLabel:'Scheme',
            required:false,
            noBlank:true,
            optionSource:'elbScheme',
            inputType:OptionType.InputType.SELECT,
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ELB sslCert',
            code:'plugin.aws.elb.vip.sslCert',
            fieldName:'sslCert',
            fieldCode: 'gomorpheus.optiontype.SslCertificate',
            fieldLabel:'SSL Certificate',
            fieldContext:'domain',
            displayOrder:14,
            required:true,
            editable:true,
            inputType:OptionType.InputType.SELECT,
            optionSource:'accountSslCertificate',
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ELB sslRedirectMode',
            code:'plugin.aws.elb.vip.sslRedirectMode',
            fieldName:'sslRedirectMode',
            fieldContext:'domain',
            fieldCode: 'gomorpheus.optiontype.UseExternalAddressForBackendNodes',
            fieldLabel:'Use external address for backend nodes',
            displayOrder:15,
            required:false,
            editable:true,
            inputType:OptionType.InputType.SELECT,
            optionSource:'sslRedirectMode',
            visibleOnCode:'loadBalancer.sslCert:([^0]),loadBalancerInstance.sslCert:([^0])',
            wrapperClass:'vip-ssl-redirect-mode-input',
            category:'zoneType.amazon'
        )
        return virtualServerOptions
    }

    Collection<OptionType> getInstanceOptionTypes() {
        Collection<OptionType> instanceOptions = new ArrayList<OptionType>()
        instanceOptions << new OptionType(
            name:'ELB vipName',
            code:'plugin.aws.elb.vip.vipName',
            fieldName:'vipName',
            fieldCode:'gomorpheus.optiontype.Name',
            fieldContext:'domain',
            displayOrder:0,
            fieldLabel:'VIP Name',
            required:true,
            editable:false,
            inputType:OptionType.InputType.TEXT,
            category:'zoneType.amazon'
        )
        instanceOptions << new OptionType(
            name:'ELB vipHostname',
            code:'plugin.aws.elb.vip.vipHostname',
            fieldName:'vipHostname',
            fieldCode:'gomorpheus.optiontype.VipHostname',
            fieldContext:'domain',
            displayOrder:10,
            fieldLabel:'VIP Hostname',
            required:true,
            editable:true,
            inputType:OptionType.InputType.TEXT,
            category:'zoneType.amazon'
        )
        instanceOptions << new OptionType(
            name:'ELB vipPort',
            code:'plugin.aws.elb.vip.vipPort',
            fieldName:'vipPort',
            fieldCode:'gomorpheus.optiontype.VipPort',
            fieldContext:'domain',
            displayOrder:12,
            fieldLabel:'VIP Port',
            required:true,
            inputType:OptionType.InputType.TEXT,
            category:'zoneType.amazon'
        )
        instanceOptions << new OptionType(
            name:'ELB vipScheme',
            code:'plugin.aws.elb.vip.vipScheme',
            fieldName:'vipScheme',
            fieldCode:'gomorpheus.optiontype.Scheme',
            fieldContext:'domain',
            displayOrder:13,
            fieldLabel:'Scheme',
            required:false,
            noBlank:true,
            optionSource:'elbScheme',
            inputType:OptionType.InputType.SELECT,
            category:'zoneType.amazon'
        )
        instanceOptions << new OptionType(
            name:'ELB sslCert',
            code:'plugin.aws.elb.vip.sslCert',
            fieldName:'sslCert',
            fieldCode: 'gomorpheus.optiontype.SslCertificate',
            fieldLabel:'SSL Certificate',
            fieldContext:'domain',
            displayOrder:14,
            required:true,
            editable:true,
            inputType:OptionType.InputType.SELECT,
            optionSource:'accountSslCertificate',
            category:'zoneType.amazon'
        )
        instanceOptions << new OptionType(
            name:'ELB sslRedirectMode',
            code:'plugin.aws.elb.vip.sslRedirectMode',
            fieldName:'sslRedirectMode',
            fieldContext:'domain',
            fieldCode: 'gomorpheus.optiontype.UseExternalAddressForBackendNodes',
            fieldLabel:'Use external address for backend nodes',
            displayOrder:15,
            required:false,
            editable:true,
            inputType:OptionType.InputType.SELECT,
            optionSource:'sslRedirectMode',
            visibleOnCode:'loadBalancer.sslCert:([^0]),loadBalancerInstance.sslCert:([^0])',
            wrapperClass:'vip-ssl-redirect-mode-input',
            category:'zoneType.amazon'
        )
        return instanceOptions
    }

    String getLoadBalancerTypeCode() {
        return PROVIDER_CODE
    }

    protected getAmazonClient(Cloud cloud, Boolean fresh = false, String region=null) {
        return this.@plugin.getAmazonClient(cloud, fresh, region)
    }

    protected getAmazonElbClient(Cloud cloud, Boolean fresh = false, String region = null) {
        return this.@plugin.getAmazonElbClient(cloud, fresh, region)
    }
}
