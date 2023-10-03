package com.morpheusdata.aws

import com.amazonaws.services.certificatemanager.AWSCertificateManagerClient
import com.amazonaws.services.certificatemanager.model.AddTagsToCertificateRequest
import com.amazonaws.services.certificatemanager.model.CertificateSummary
import com.amazonaws.services.certificatemanager.model.ImportCertificateRequest
import com.amazonaws.services.certificatemanager.model.ImportCertificateResult
import com.amazonaws.services.certificatemanager.model.ListCertificatesRequest
import com.amazonaws.services.certificatemanager.model.ListCertificatesResult
import com.amazonaws.services.certificatemanager.model.ListTagsForCertificateRequest
import com.amazonaws.services.certificatemanager.model.ListTagsForCertificateResult
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancingv2.model.Action
import com.amazonaws.services.elasticloadbalancingv2.model.ActionTypeEnum
import com.amazonaws.services.elasticloadbalancingv2.model.AddTagsRequest
import com.amazonaws.services.elasticloadbalancingv2.model.Certificate
import com.amazonaws.services.elasticloadbalancingv2.model.CreateListenerRequest
import com.amazonaws.services.elasticloadbalancingv2.model.CreateListenerResult
import com.amazonaws.services.elasticloadbalancingv2.model.CreateLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancingv2.model.CreateLoadBalancerResult
import com.amazonaws.services.elasticloadbalancingv2.model.CreateRuleRequest
import com.amazonaws.services.elasticloadbalancingv2.model.CreateTargetGroupRequest
import com.amazonaws.services.elasticloadbalancingv2.model.CreateTargetGroupResult
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteListenerRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteRuleRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteTargetGroupRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeListenersRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeListenersResult
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersResult
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeRulesRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeRulesResult
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsResult
import com.amazonaws.services.elasticloadbalancingv2.model.Listener
import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancerSchemeEnum
import com.amazonaws.services.elasticloadbalancingv2.model.ModifyListenerRequest
import com.amazonaws.services.elasticloadbalancingv2.model.ModifyTargetGroupAttributesRequest
import com.amazonaws.services.elasticloadbalancingv2.model.RegisterTargetsRequest
import com.amazonaws.services.elasticloadbalancingv2.model.Rule
import com.amazonaws.services.elasticloadbalancingv2.model.RuleCondition
import com.amazonaws.services.elasticloadbalancingv2.model.SetSecurityGroupsRequest
import com.amazonaws.services.elasticloadbalancingv2.model.SetSubnetsRequest
import com.amazonaws.services.elasticloadbalancingv2.model.Tag
import com.amazonaws.services.elasticloadbalancingv2.model.TargetDescription
import com.amazonaws.services.elasticloadbalancingv2.model.TargetGroup
import com.amazonaws.services.elasticloadbalancingv2.model.TargetGroupAttribute
import com.amazonaws.services.route53.model.ThrottlingException
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.network.loadbalancer.LoadBalancerProvider
import com.morpheusdata.model.AccountCertificate
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.CloudType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
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

import java.nio.ByteBuffer

@Slf4j
class ALBLoadBalancerProvider implements LoadBalancerProvider {
    MorpheusContext morpheusContext
    AWSPlugin plugin

    static final PROVIDER_CODE = 'amazon-alb'
    static final SCHEME_INTERNAL = 'internal'
    static final SCHEME_INTERNET_FACING = 'Internet-facing'

    public ALBLoadBalancerProvider(Plugin plugin, MorpheusContext context) {
        super()
        this.@plugin = (AWSPlugin)plugin
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

    /**
     * This method will return a list of option types that are used to add an ALB integration to morpheus
     * @return
     */
    @Override
    Collection<OptionType> getOptionTypes() {
        List options = []
        options << new OptionType(
            name:'ALB Name',
            code:'plugin.aws.alb.name',
            fieldName:'name',
            fieldLabel:'Name',
            displayOrder:0,
            fieldCode:'gomorpheus.optiontype.Name',
            fieldContext:'domain',
            inputType:OptionType.InputType.TEXT,
            category:'loadBalancer.alb',
            required:true,
        )
        options << new OptionType(
            name:'ALB Description',
            code:'plugin.aws.alb.description',
            fieldName:'description',
            fieldLabel:'Description',
            displayOrder:1,
            fieldCode:'gomorpheus.label.description',
            fieldContext:'domain',
            inputType:OptionType.InputType.TEXT,
            category:'loadBalancer.alb',
            required:false
        )
        options << new OptionType(
            name:'Amazon Cloud Id',
            code:'plugin.aws.alb.cloud',
            fieldName:'zone.id',
            displayOrder:2,
            fieldLabel:'Cloud',
            fieldCode:'gomorpheus.label.cloud',
            required:true,
            inputType:OptionType.InputType.SELECT,
            optionSource: 'amazonClouds',
            fieldContext:'domain',
            category:'loadBalancer.alb'
        )
        options << new OptionType(
            name:'ALB scheme',
            code:'plugin.aws.alb.scheme',
            fieldName:'scheme',
            displayOrder:3,
            fieldLabel:'Scheme',
            fieldCode:'gomorpheus.label.scheme',
            required:true,
            inputType:OptionType.InputType.SELECT,
            optionSource: 'loadBalancerSchemes',
            optionSourceType: 'amazon',
            fieldContext:'config',
            category:'loadBalancer.alb'
        )
        options << new OptionType(
            name:'Amazon VPCs',
            code:'plugin.aws.alb.vpc',
            fieldName:'amazonVpc',
            displayOrder:4,
            fieldLabel:'VPC',
            fieldCode:'gomorpheus.label.amazon.vpcs',
            required:true,
            inputType:OptionType.InputType.SELECT,
            optionSource: 'zonePools',
            fieldContext:'config',
            category:'zoneType.amazon'
        )
        options << new OptionType(
            name:'Amazon Subnets',
            code:'plugin.aws.alb.subnets',
            fieldName:'subnetIds',
            displayOrder:5,
            fieldLabel:'Amazon Subnets',
            fieldCode:'gomorpheus.label.amazon.subnets',
            required:true,
            inputType:OptionType.InputType.MULTI_SELECT,
            optionSource:'amazonSubnet',
            optionSourceType:'amazon',
            global:true,
            fieldContext:'config',
            category:'zoneType.amazon',
            helpBlock:'gomorpheus.help.lbSubnets',
            config:'{"recommendedCount":2}'
        )
        options << new OptionType(
            name:'Amazon Security Groups',
            code:'plugin.aws.alb.securityGroups',
            fieldName:'securityGroupIds',
            displayOrder:6,
            fieldLabel:'Amazon Security Groups',
            fieldCode:'gomorpheus.label.amazon.securityGroups',
            required:true,
            inputType:OptionType.InputType.MULTI_SELECT,
            optionSource:'amazonSecurityGroup',
            optionSourceType:'amazon',
            fieldContext:'config',
            category:'zoneType.amazon'
        )

        return options
    }

    @Override
    Collection<NetworkLoadBalancerType> getLoadBalancerTypes() {
        NetworkLoadBalancerType type = new NetworkLoadBalancerType(
            code:getLoadBalancerTypeCode(),
            name:'Amazon ALB', // will change after testing
            internal:false,
            enabled:true,
            createType:'multi',
            supportsCerts:true,
            creatable:true,
            viewSet:'amazonALB',
            supportsHostname:true,
            certSize:2048,
            cloudType:new CloudType([code:AWSCloudProvider.PROVIDER_CODE]),
            format:'external',
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
            instanceOptionTypes:getVirtualServerOptionTypes(),
            optionTypes:getOptionTypes()
        )
        return [type]
    }

    Collection<OptionType> getVirtualServerOptionTypes() {
        Collection<OptionType> virtualServerOptions = new ArrayList<OptionType>()
        virtualServerOptions << new OptionType(
            name:'ALB vipName',
            code:'plugin.aws.alb.vip.vipName',
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
            name:'ALB vipAddress',
            code:'plugin.aws.alb.vip.vipHostname',
            fieldName:'vipHostname',
            fieldCode:'gomorpheus.optiontype.VipHostname',
            fieldContext:'domain',
            displayOrder:10,
            fieldLabel:'VIP Hostname',
            required:false,
            inputType:OptionType.InputType.TEXT,
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ALB vipPort',
            code:'plugin.aws.alb.vip.vipPort',
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
            name:'ALB vipBalance',
            code:'plugin.aws.alb.vip.vipBalance',
            fieldName:'vipBalance',
            fieldCode:'gomorpheus.optiontype.BalanceMode',
            fieldContext:'domain',
            displayOrder:13,
            fieldLabel:'Balance Mode',
            required:true,
            optionSource:'loadBalancerMode',
            inputType:OptionType.InputType.SELECT,
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ALB sslCert',
            code:'plugin.aws.alb.vip.sslCert',
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
            name:'ALB sslRedirectMode',
            code:'plugin.aws.alb.vip.sslRedirectMode',
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
        virtualServerOptions << new OptionType(
            name:'ALB vipSticky',
            code:'plugin.aws.alb.vip.vipSticky',
            fieldName:'vipSticky',
            fieldCode:'gomorpheus.optiontype.StickyMode',
            fieldLabel:'Sticky Mode',
            fieldContext:'domain',
            displayOrder:16,
            required:true,
            editable:true,
            inputType:OptionType.InputType.SELECT,
            optionSource:'albLoadBalancerSticky',
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ALB stickyDuration',
            code:'plugin.aws.alb.vip.stickyDuration',
            fieldName:'stickyDuration',
            fieldCode: 'gomorpheus.optiontype.alb.sticky.duration.seconds',
            fieldLabel:'Sticky Duration (secs)',
            fieldContext:'config',
            displayOrder:17,
            required:true,
            editable:true,
            inputType:OptionType.InputType.NUMBER,
            defaultValue:3600,
            visibleOnCode:'loadBalancer.vipSticky:(lb|app),loadBalancerInstance.vipSticky(lb|app)',
            category:'zoneType.amazon'
        )
        virtualServerOptions << new OptionType(
            name:'ALB stickyCookieName',
            code:'plugin.aws.alb.vip.stickyCookieName',
            fieldName:'stickyCookieName',
            fieldCode: 'gomorpheus.optiontype.alb.sticky.cookie.name',
            fieldLabel:'App Cookie Name',
            fieldContext:'config',
            displayOrder:18,
            required:true,
            inputType:OptionType.InputType.TEXT,
            visibleOnCode:'loadBalancer.vipSticky:app,loadBalancerInstance.vipSticky:app',
            category:'zoneType.amazon'
        )
        return virtualServerOptions
    }

    String getLoadBalancerTypeCode() {
        return PROVIDER_CODE
    }

    @Override
    ServiceResponse validate(NetworkLoadBalancer loadBalancer, Map opts) {
        if (opts)
            return validateLoadBalancer(loadBalancer, opts)
        else
            return validateLoadBalancer(loadBalancer)
    }

    @Override
    ServiceResponse validateLoadBalancerInstance(NetworkLoadBalancerInstance loadBalancerInstance) {
        def opts = loadBalancerInstance.getConfigProperty('options')
        log.info "validateLoadBalancerInstance: ${loadBalancerInstance}, ${opts}"
        ServiceResponse rtn = ServiceResponse.success()
        try {
            if(!(opts.newHostName ==~ /^([a-zA-Z\d-\*]+)\.([a-zA-Z\d-]+)\.([a-zA-Z\d-]+)$/)){
                //rtn.msg = messageSource.getMessage('gomorpheus.error.alb.hostname', null, LocaleContextHolder.locale)
                rtn.msg = 'Host Name must be fully qualified. For example, *.domain.com or www.domain.com'
                rtn.errors = [vipHostname: rtn.msg]
                rtn.success = false
            }
            if(rtn.success && opts.proxyProtocol == 'HTTPS' && !opts.sslCert) {
                rtn.msg = "When specifying HTTPS, an SSL Cert must be selected."
                rtn.success = false
            }
            if(rtn.success && opts.proxyProtocol == 'HTTPS') {
                if(!opts.newHostName || !(opts.newHostName ==~ /^([a-zA-Z\d-\*]+)\.([a-zA-Z\d-]+)\.([a-zA-Z\d-]+)$/)) {
                    //rtn.msg = messageSource.getMessage('gomorpheus.error.alb.hostname', null, LocaleContextHolder.locale)
                    rtn.msg = 'Host Name must be fully qualified. For example, *.domain.com or www.domain.com'
                    rtn.errors = [vipHostname: rtn.msg]
                    rtn.success = false
                }
            }
            if(rtn.success && opts.stickyMode == 'app') {
                if(!opts.config?.stickyCookieName){
                    //rtn.msg = messageSource.getMessage('gomorpheus.error.alb.app.cookie.empty', null, LocaleContextHolder.locale)
                    rtn.msg = 'Cookie name is required'
                    rtn.errors = [stickyCookieName: rtn.msg]
                    rtn.success = false
                } else if(opts.config?.stickyCookieName ==~ /(.*)(\(|\)|\s|<|>|@|,|;|:|"|\/|\[|]|\?|=|\{|})(.*)/) {
                    //rtn.msg = messageSource.getMessage('gomorpheus.error.alb.app.cookie.format', null, LocaleContextHolder.locale)
                    rtn.msg = 'Cookie name must not contain whitespaces or any of the following characters: ( ) < > @ , ; : " / [ ] ? = { }'
                    rtn.errors = [stickyCookieName: rtn.msg]
                    rtn.success = false
                }
            }
            if(rtn.success) {
                // Validate that the port specified is not already being utilized by a different instance
                def port = opts.instance.customPort?.toInteger()
                NetworkLoadBalancer loadBalancer = loadBalancerInstance.loadBalancer
                AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(loadBalancer.zone, false, loadBalancer.region?.regionCode)
                // Get the target group for the instance
                Listener listener = getListenerByPort(port, loadBalancer, amazonClient)
                if(!listener) {
                    // Make sure we will not exceed 10 listeners
                    if(getListeners(loadBalancer, amazonClient)?.size() == 10) {
                        rtn.msg = "The maximum number of listeners for this load balancer has been reached"
                        rtn.success = false
                    }
                }
            }
        } catch (e) {
            log.error("validateLoadBalancerInstance error: ${e}", e)
            rtn.msg = "Error validating Load Balancer settings"
        }
        return rtn
    }

    @Override
    ServiceResponse validateLoadBalancerInstanceConfiguration(NetworkLoadBalancer loadBalancer, Instance instance) {
        def opts = loadBalancer.getConfigProperty('options')
        log.debug "validateLoadBalancerInstanceConfiguration: ${loadBalancer}, ${instance}, ${opts}"
        ServiceResponse rtn = ServiceResponse.success()

        if(!loadBalancer) {
            rtn.success = false
            rtn.error = 'Invalid load balancer'
            return rtn
        }

        try {
            if(opts.protocol?.toUpperCase() == 'HTTPS' && !opts.sslCert) {
                rtn.msg = "When specifying HTTPS, an SSL Cert must be selected."
                rtn.success = false
            }

            def port = opts.vipPort ? opts.vipPort.toInteger() : null
            if(!port) {
                rtn.msg = "Port must be specified"
                rtn.success = false
            }

            // NOTE: The instance.id check is a hack.. if it is null, we are coming through the provision workflow and we do not need
            // to verify the name in that case
            if(rtn.success && !(opts.vipHostname ==~ /^([a-zA-Z\d-\*]+)\.([a-zA-Z\d-]+)\.([a-zA-Z\d-]+)$/)) {
                rtn.msg = 'VIP Hostname must be fully qualified. For example, *.domain.com or www.domain.com'
                rtn.errors = [vipHostname:rtn.msg]
                rtn.success = false
            }

            if(opts.stickyMode == 'app') {
                if(!opts.config?.stickyCookieName){
                    rtn.msg = 'Cookie name is required'
                    rtn.errors = [stickyCookieName: rtn.msg]
                    rtn.success = false
                } else if(opts.config?.stickyCookieName ==~ /(.*)(\(|\)|\s|<|>|@|,|;|:|"|\/|\[|]|\?|=|\{|})(.*)/) {
                    rtn.msg = 'Cookie name must not contain whitespaces or any of the following characters: ( ) < > @ , ; : " / [ ] ? = { }'
                    rtn.errors = [stickyCookieName: rtn.msg]
                    rtn.success = false
                }
            }

            if(rtn.success) {
                // Application Load Balancers have a max limit of 10 listeners.. make sure we will not exceed that number
                AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(loadBalancer.cloud, false, loadBalancer.region?.regionCode)
                def listeners = getListeners(loadBalancer, amazonClient)
                def listenerExists = getListenerByPort(port, loadBalancer, amazonClient) != null
                if(listeners?.size() == 10 && !listenerExists) {
                    rtn.msg = "The maximum number of listeners for this load balancer has been reached"
                    rtn.success = false
                }
            }

            if(rtn.errors.size() > 0) {
                rtn.success = false
            }

        } catch (e) {
            log.error("validateLoadBalancerInstance error: ${e}", e)
            rtn.success = false
            rtn.msg = "Error validating Load Balancer settings"
        }

        return rtn
    }

    @Override
    ServiceResponse setAdditionalConfiguration(NetworkLoadBalancer loadBalancer, Map opts) {
        ServiceResponse rtn = ServiceResponse.success()
        log.debug "setAdditionalConfiguration: ${loadBalancer}, ${opts}"

        if(opts.networkLoadBalancer?.scheme) {
            loadBalancer.setConfigProperty('scheme', opts.networkLoadBalancer?.scheme)
        }

        // Must set the security groups AND the subnets
        def subnetIds = opts?.networkLoadBalancer?.config?.subnetIds ?: opts?.config?.subnetIds ?: []
        if(opts?.networkLoadBalancer?.amazonSubnet) {
            if(opts.networkLoadBalancer.amazonSubnet instanceof String[]) {
                opts.networkLoadBalancer.amazonSubnet?.each { it ->
                    if(it) {
                        subnetIds << it.toLong()
                    }
                }
                subnetIds = subnetIds.unique()
            } else {
                subnetIds << opts.networkLoadBalancer.amazonSubnet.toLong()
            }
        }
        loadBalancer.setConfigProperty('subnetIds', subnetIds)

        def securityGroupIds = opts?.networkLoadBalancer?.config?.securityGroupIds ?: opts?.config?.securityGroupIds ?: []
        if(opts?.networkLoadBalancer?.amazonSecurityGroup) {
            if(opts.networkLoadBalancer.amazonSecurityGroup instanceof String[]) {
                opts.networkLoadBalancer.amazonSecurityGroup?.each { it ->
                    if(it) {
                        securityGroupIds << it
                    }
                }
                securityGroupIds = securityGroupIds.unique()
            } else {
                securityGroupIds << opts.networkLoadBalancer.amazonSecurityGroup
            }
        }

        loadBalancer.setConfigProperty('securityGroupIds', securityGroupIds)

        def amazonVpc = opts?.networkLoadBalancer?.config?.amazonVpc ?: opts?.networkLoadBalancer?.amazonVpc
        if(amazonVpc) {
            loadBalancer.setConfigProperty('amazonVpc', amazonVpc)
        }

        return rtn
    }

    @Override
    ServiceResponse addLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return updateOrCreateLoadBalancer(loadBalancer)
    }

    @Override
    ServiceResponse updateLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return updateOrCreateLoadBalancer(loadBalancer)
    }

    @Override
    ServiceResponse deleteLoadBalancer(NetworkLoadBalancer loadBalancer) {
        log.debug "Deleting Load Balancer: ${loadBalancer.id}"
        ServiceResponse rtn = ServiceResponse.error()

        try {
            def cloud = loadBalancer.cloud
            AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(cloud, false, loadBalancer.region?.regionCode)

            DeleteLoadBalancerRequest deleteLoadBalancerRequest = new DeleteLoadBalancerRequest()
            deleteLoadBalancerRequest.setLoadBalancerArn(loadBalancer.getConfigProperty('arn'))
            amazonClient.deleteLoadBalancer(deleteLoadBalancerRequest)

            rtn.success = true
        } catch(Exception e) {
            log.error "Error deleting loadbalancer: ${e}", e
            rtn.success = false
            rtn.msg = "Error deleting load balancer"
        }

        return rtn
    }


    @Override
    ServiceResponse initializeLoadBalancer(NetworkLoadBalancer loadBalancer, Map opts) {
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
        return updateOrCreateInstance(instance, instance.configMap)
    }

    @Override
    ServiceResponse validateLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        ServiceResponse rtn = ServiceResponse.success()
        try {
            //need a name
            if(!instance.vipName) {
                rtn.errors.vipName = 'Name is required'
            }
            if(!instance.vipHostname) {
                rtn.errors.vipHostname = 'Hostname is required'
            }
            if(!instance.vipPort) {
                rtn.errors.vipPort = 'Vip Port is required'
            }
            rtn.success = rtn.errors ? rtn.errors.size() == 0 : true
        } catch(e) {
            log.error("error validating virtual server: ${e}", e)
        }
        return rtn
    }

    @Override
    ServiceResponse addInstance(NetworkLoadBalancerInstance instance) {
        return updateOrCreateInstance(instance, instance.configMap)
    }

    @Override
    ServiceResponse removeInstance(NetworkLoadBalancerInstance loadBalancerInstance) {
        log.debug "removeInstance: ${loadBalancerInstance}"
        ServiceResponse rtn = ServiceResponse.error()
        try {
            removeLegacy(loadBalancerInstance)

            removeTargetGroup(loadBalancerInstance, getTargetName(loadBalancerInstance))

            rtn.success = true
        } catch (e) {
            log.error("removeInstance error: ${e}", e)
        }
        return rtn
    }

    @Override
    ServiceResponse updateInstance(NetworkLoadBalancerInstance instance) {
        return updateOrCreateInstance(instance, instance.configMap)
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
        return 'Amazon ALB'
    }

    private updateOrCreateLoadBalancer(NetworkLoadBalancer loadBalancer) {
        log.debug "updateOrCreateLoadBalancer: ${loadBalancer.id}"
        def rtn = ServiceResponse.prepare()
        def networkService = morpheus.async.network
        def regionService = morpheus.async.cloud.region
        def loadBalancerService = morpheus.async.loadBalancer

        try {
            def createRequired = !loadBalancer.externalId
            def cloud = loadBalancer.cloud
            String regionCode = loadBalancer.region?.regionCode


            // Gather the security groups and subnets
            def lbSubnets = new LinkedList<String>()
            def lbSecurity = new LinkedList<String>()

            for (subnetId in loadBalancer.getConfigProperty('subnetIds')) {
                Network tmpSubnet
                networkService.listById([subnetId.toLong()]).blockingSubscribe { n ->
                    tmpSubnet = n
                }
                def externalId = tmpSubnet?.externalId
                if(externalId) {
                    lbSubnets << externalId
                }
                if(!regionCode && tmpSubnet?.regionCode) {
                    loadBalancer.region = regionService.findByCloudAndRegionCode(cloud.id, tmpSubnet?.regionCode).blockingGet().get()
                    regionCode = tmpSubnet.regionCode
                }
            }


            for (securityGroupId in loadBalancer.getConfigProperty('securityGroupIds')) {
                lbSecurity << securityGroupId
            }

            AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(cloud, false, loadBalancer.region?.regionCode)


            if(createRequired) {
                log.debug "Existing LB NOT found... creating"
                def lbName = getLoadBalancerName(loadBalancer)

                CreateLoadBalancerRequest lbCreateRequest = new CreateLoadBalancerRequest()
                lbCreateRequest.setName(lbName)

                switch(loadBalancer.getConfigProperty('scheme')) {
                    case SCHEME_INTERNAL:
                        lbCreateRequest.setScheme(LoadBalancerSchemeEnum.Internal)
                        break
                    case SCHEME_INTERNET_FACING:
                        lbCreateRequest.setScheme(LoadBalancerSchemeEnum.InternetFacing)
                        break
                }

                if (lbSubnets?.size() > 0) {
                    lbCreateRequest.setSubnets(lbSubnets)
                }

                if (lbSecurity?.size() > 0) {
                    lbCreateRequest.setSecurityGroups(lbSecurity)
                }

                lbCreateRequest.setTags(new LinkedList<Tag>())
                lbCreateRequest.tags.add(new Tag().withKey('morpheus-id').withValue(loadBalancer.id.toString()))

                CreateLoadBalancerResult lbResult = amazonClient.createLoadBalancer(lbCreateRequest)

                loadBalancer.externalId = ':' + lbResult.loadBalancers[0].loadBalancerArn.split(':')[5..-1].join(':')
                loadBalancer.setConfigProperty('arn', lbResult.loadBalancers[0].loadBalancerArn)
                loadBalancer.sshHost = lbResult.loadBalancers[0].getDNSName()

                // save load balancer changes
                rtn.success = loadBalancerService.bulkSave([loadBalancer]).blockingGet()
            } else {
                log.debug "Existing LB found.. updating"
                def arn = loadBalancer.getConfigProperty('arn')
                AddTagsRequest addTagsRequest = new AddTagsRequest().withResourceArns(arn)
                addTagsRequest.setTags(new LinkedList<Tag>())
                addTagsRequest.tags.add(new Tag().withKey('morpheus-id').withValue(loadBalancer.id.toString()))
                amazonClient.addTags(addTagsRequest)

                // Security groups and subnets
                if (lbSubnets?.size() > 0) {
                    SetSubnetsRequest setSubnetsRequest = new SetSubnetsRequest()
                    setSubnetsRequest.setLoadBalancerArn(arn)
                    setSubnetsRequest.setSubnets(lbSubnets)
                    amazonClient.setSubnets(setSubnetsRequest)
                }

                if (lbSecurity?.size() > 0) {
                    SetSecurityGroupsRequest setSecurityGroupsRequest = new SetSecurityGroupsRequest()
                    setSecurityGroupsRequest.setLoadBalancerArn(arn)
                    setSecurityGroupsRequest.setSecurityGroups(lbSecurity)
                    amazonClient.setSecurityGroups(setSecurityGroupsRequest)
                }
                rtn.success = true
            }
        } catch(Exception e) {
            log.error "Error updating loadbalancer: ${e}", e
            rtn.success = false
            rtn.msg = "Error updating load balancer: ${e.message}"
        }

        return rtn
    }

    private getLoadBalancerName(NetworkLoadBalancer loadBalancer) {
        // Must make sure it is unique within AWS
        def cloud = loadBalancer.cloud
        AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(cloud, false, loadBalancer.region?.regionCode)

        // Must contain only letters, numbers, dashes and start with an alpha character
        def currentName = loadBalancer.name.replaceAll(/[^a-zA-Z0-9\-]/, '')?.take(255)
        def baseName = currentName

        def hasLBName = true
        def i = 0
        while (hasLBName == true && i < 10) {
            log.debug "looking for loadbalancer with name of ${currentName}"
            DescribeLoadBalancersRequest lbRequest = new DescribeLoadBalancersRequest().withNames(new LinkedList<String>())
            lbRequest.getNames().add(new String(currentName))
            try {
                DescribeLoadBalancersResult tmpList = amazonClient.describeLoadBalancers(lbRequest)
                hasLBName = tmpList.loadBalancers.size() > 0 ? tmpList.loadBalancers.get(0) : null
            }catch (e) {
                // eat it... exceptions if not found
                hasLBName = false
            }

            if(hasLBName) {
                currentName = "${baseName}${i}"
                i++
            }
        }
        currentName
    }

    private updateOrCreateInstance(NetworkLoadBalancerInstance loadBalancerInstance, opts) {
        log.info "updateOrCreateInstance: ${loadBalancerInstance.id}, ${opts}"

        def rtn = [success: false]
        def zonePoolService = morpheus.cloud.pool
        def regionService = morpheus.cloud.region

        try {
            removeLegacy(loadBalancerInstance)

            NetworkLoadBalancer loadBalancer = loadBalancerInstance.loadBalancer
            def loadBalancerArn = loadBalancer.getConfigProperty('arn')
            def instance = loadBalancerInstance.instance
            def albProtocol = getVipServiceMode(loadBalancerInstance)?.toUpperCase()
            def albPort = opts.vipPort?.toInteger() ?: loadBalancerInstance.vipPort
            def instancePort = loadBalancerInstance.servicePort.toLong()
            def instanceProtocol = getBackendServiceMode(loadBalancerInstance)?.toUpperCase()
            def sslRedirectMode = loadBalancerInstance.sslRedirectMode
            def cloud = loadBalancer.cloud
            def vpcId = cloud.getConfigProperty('vpc') ?: loadBalancer.getConfigProperty('amazonVpc')

            String regionCode = loadBalancer.region?.regionCode
            if(!regionCode) {
                if(vpcId) {
                    CloudPool pool
                    zonePoolService.listByCloudAndExternalIdIn(cloud.id, [vpcId]).blockingSubscribe { zonePool ->
                        pool = zonePool
                    }
                    regionCode = pool.regionCode
                    loadBalancer.region = regionService.findByCloudAndRegionCode(cloud.id, regionCode).blockingGet().get()
                }
            }
            AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(cloud, false, regionCode)
            // 1. Get/Create the target group
            def targetName = getTargetName(loadBalancerInstance)
            log.debug "Working on targetName :${targetName}"
            TargetGroup targetGroup = getTargetGroup(targetName, amazonClient)
            if (!targetGroup) {
                log.debug "TargetGroup '${targetName}' does not exist... creating"
                CreateTargetGroupRequest createRequest = new CreateTargetGroupRequest()
                createRequest.name = targetName
                createRequest.port = instancePort
                createRequest.protocol = instanceProtocol
                createRequest.vpcId = vpcId
                CreateTargetGroupResult createTargetGroupResult = amazonClient.createTargetGroup(createRequest)
                targetGroup = createTargetGroupResult.targetGroups[0]
            }
            // 2. Update the target group with the port and actual containers for the instance
            if(instance.scale?.type?.code == AWSScaleProvider.PROVIDER_CODE) {
                AWSScaleProvider scaleProvider = (AWSScaleProvider)plugin.getProviderByCode(AWSScaleProvider.PROVIDER_CODE)
                // Rather than register the instances as targets, just tell the scale group what target to use and it adds the instances
                scaleProvider.registerTargetWithScaleGroup(instance, instance.scale, targetGroup)
            } else {
                RegisterTargetsRequest registerRequest = new RegisterTargetsRequest()
                registerRequest.targetGroupArn = targetGroup.targetGroupArn
                registerRequest.targets = new LinkedList<TargetDescription>()
                instance.containers?.findAll { ctr -> ctr.inService }?.each { container ->
                    TargetDescription targetDescription = new TargetDescription()
                    targetDescription.port = instancePort
                    targetDescription.id = container.server.externalId
                    log.debug "Adding container '${container}' to target group '${targetName}: ${targetDescription}"
                    registerRequest.targets.add(targetDescription)
                }
                amazonClient.registerTargets(registerRequest)

                // Configure the target group attributes
                ModifyTargetGroupAttributesRequest attributesRequest = new ModifyTargetGroupAttributesRequest().withTargetGroupArn(targetGroup.targetGroupArn)
                attributesRequest.attributes = new LinkedList<TargetGroupAttribute>()

                def stickinessEnabled = (loadBalancerInstance.vipSticky != 'off')
                attributesRequest.attributes.add(new TargetGroupAttribute().withKey('stickiness.enabled').withValue(stickinessEnabled.toString()))

                if(stickinessEnabled) {
                    def saveInstance = false
                    def appSticky = (loadBalancerInstance.vipSticky == 'app')
                    attributesRequest.attributes.add(new TargetGroupAttribute().withKey('stickiness.type').withValue(appSticky ? 'app_cookie' : 'lb_cookie'))
                    def duration = loadBalancerInstance.getConfigProperty('stickyDuration')
                    if(!duration && opts.config?.stickyDuration) {
                        duration = opts.config.stickyDuration
                        loadBalancerInstance.setConfigProperty('stickyDuration', duration)
                        saveInstance = true
                    }
                    if(appSticky) {
                        def stickyCookieName = loadBalancerInstance.getConfigProperty('stickyCookieName')
                        if(!stickyCookieName && opts.config?.stickyCookieName) {
                            stickyCookieName = opts.config.stickyCookieName
                            loadBalancerInstance.setConfigProperty('stickyCookieName', stickyCookieName)
                            saveInstance = true
                        }
                        attributesRequest.attributes.add(new TargetGroupAttribute().withKey('stickiness.app_cookie.cookie_name').withValue(stickyCookieName))
                        attributesRequest.attributes.add(new TargetGroupAttribute().withKey('stickiness.app_cookie.duration_seconds').withValue(duration.toString()))
                    } else {
                        attributesRequest.attributes.add(new TargetGroupAttribute().withKey('stickiness.lb_cookie.duration_seconds').withValue(duration.toString()))
                    }
                    if (saveInstance) {
                        this.morpheus.loadBalancer.instance.bulkSave([loadBalancerInstance]).blockingGet()
                    }
                }
                attributesRequest.attributes.add(new TargetGroupAttribute().withKey('load_balancing.algorithm.type').withValue(loadBalancerInstance.vipBalance == 'roundrobin' ? 'round_robin' : 'least_outstanding_requests'))

                log.debug "Updating targetGroup with : ${attributesRequest}"
                def attributesResult = amazonClient.modifyTargetGroupAttributes(attributesRequest)
                log.debug "Update result ${attributesResult}"
            }

            // 3. Handle the cert.. if needed
            def certId
            if(opts.sslCert instanceof String && opts.sslCert.isLong()) {
                certId = Long.valueOf(opts.sslCert)
            }
            else {
                certId = opts.sslCert && opts.sslCert?.id.toLong() > 0 ? opts.sslCert.id?.toLong() : loadBalancerInstance.sslCert?.id
            }
            // Handle the cert
            def certificateArn
            if (certId) {
                def certService = this.morpheus.loadBalancer.certificate
                AccountCertificate cert = certService.getAccountCertificateById(certId).blockingGet().value
                if (cert) {
                    certificateArn = amazonCertificateService.uploadOrGetCert(cert, zone)
                }
            }

            // 3. Wire it all up
            def requiredListenerPorts = [albPort]
            attachTargetGroupToLoadBalancer(amazonClient, loadBalancerInstance, loadBalancerArn, albPort, albProtocol, targetGroup.targetGroupArn, certificateArn)

            // 4. If passthrough... wire up another on port 80
            if (sslRedirectMode == 'passthrough') {
                attachTargetGroupToLoadBalancer(amazonClient, loadBalancerInstance, loadBalancerArn, 80, 'HTTP', targetGroup.targetGroupArn)
                requiredListenerPorts << 80
            }

            // 5. Clean up any old ports
            removeInvalidRules(amazonClient, loadBalancerInstance, loadBalancerArn, requiredListenerPorts, targetGroup)

            instance.setConfigProperty('loadBalancerId', loadBalancer.id)
            morpheus.instance.bulkSave([instance]).blockingGet()
            rtn.success = true
        } catch (ThrottlingException e) {
            log.error "${e} : stack: ${e.printStackTrace()}"
            rtn.success = false
            rtn.msg = "AWS issued an API throttling error.  Please try again later."
        } catch(Exception e) {
            log.error "${e} : stack: ${e.printStackTrace()}"
            rtn.success = false
            rtn.msg = "Error adding instance to load balancer"
        }
        return rtn
    }

    private removeLegacy(NetworkLoadBalancerInstance loadBalancerInstance) {
        // Initially, we had 1 target group per instance... that was incorrect.  Delete the old ones
        try {
            def legacyTargetName = "morpheus-${loadBalancerInstance.instance.id}"
            removeTargetGroup(loadBalancerInstance, legacyTargetName)
        } catch(e) {
            // Ignore
        }
    }

    private getVipServiceMode(NetworkLoadBalancerInstance loadBalancerInstance) {
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

    private getBackendServiceMode(NetworkLoadBalancerInstance loadBalancerInstance) {
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

    private removeTargetGroup(NetworkLoadBalancerInstance loadBalancerInstance, targetName) {
        NetworkLoadBalancer loadBalancer = loadBalancerInstance.loadBalancer
        def cloud = loadBalancer.cloud
        AmazonElasticLoadBalancingClient amazonClient = getAmazonElbClient(cloud, false, loadBalancer.region?.regionCode)

        TargetGroup targetGroup = getTargetGroup(targetName, amazonClient)
        if(targetGroup) {
            // 1.  Remove all the rules that reference this target group
            def loadBalancerArn = loadBalancer.getConfigProperty('arn')
            removeInvalidRules(amazonClient, loadBalancerInstance, loadBalancerArn, [], targetGroup)

            // 2.  Delete the target group
            try {
                amazonClient.deleteTargetGroup(new DeleteTargetGroupRequest().withTargetGroupArn(targetGroup.targetGroupArn))
            } catch(e) {
                // Do not return success false when failing to delete the group
                log.warn "Error in deleting target group: ${targetGroup} : ${e}"
            }
        }
    }

    private getTargetGroup(String targetName, AmazonElasticLoadBalancingClient amazonClient) {
        DescribeTargetGroupsRequest describeTargetsRequest = new DescribeTargetGroupsRequest().withNames(new LinkedList<String>())
        describeTargetsRequest.getNames().add(targetName)

        TargetGroup targetGroup
        try {
            log.debug "Looking for group with name: ${targetName}"
            DescribeTargetGroupsResult describeTargetResult = amazonClient.describeTargetGroups(describeTargetsRequest)
            targetGroup = describeTargetResult.targetGroups.size() > 0 ? describeTargetResult.targetGroups.get(0) : null
        } catch (e) {
            // eat it... exceptions if not found
        }
        return targetGroup
    }

    private attachTargetGroupToLoadBalancer(amazonClient, NetworkLoadBalancerInstance loadBalancerInstance, loadBalancerArn, albPort, albProtocol, targetGroupArn, certificateArn=null) {
        log.debug("attachTargetGroupToLoadBalancer loadBalancerArn:${loadBalancerArn}, albPort:${albPort}, albProtocol:${albProtocol}, targetGroupArn:${targetGroupArn}, certificateArn:${certificateArn}")

        NetworkLoadBalancer loadBalancer = loadBalancerInstance.loadBalancer

        Action action = new Action()
        action.targetGroupArn = targetGroupArn
        action.type = ActionTypeEnum.Forward

        // 1. Find a listener on the ALB with that port
        Listener listener = getListenerByPort(albPort, loadBalancer, amazonClient)

        // 2a. Create the listener (if needed)
        def routingRuleNeeded = true
        def newRulePriority = 1
        def listenerArn
        if(!listener) {
            CreateListenerRequest listenerRequest = new CreateListenerRequest()
            listenerRequest.port = albPort
            listenerRequest.loadBalancerArn = loadBalancerArn
            listenerRequest.protocol = albProtocol
            listenerRequest.defaultActions = new LinkedList<Action>()
            if (certificateArn && listenerRequest.protocol == 'HTTPS') {
                listenerRequest.certificates = new LinkedList<Certificate>()
                listenerRequest.certificates.add(new Certificate().withCertificateArn(certificateArn))
            }
            listenerRequest.defaultActions.add(action)
            log.debug "Creating listener ${listenerRequest}"
            CreateListenerResult createListenerResult = amazonClient.createListener(listenerRequest)
            listenerArn = createListenerResult.listeners.first().listenerArn
        } else {
            // 2b. Make sure the listener has a rule routing to the target.. if not, indicate we need one
            listenerArn = listener.listenerArn
            DescribeRulesResult describeRulesResult = amazonClient.describeRules(new DescribeRulesRequest().withListenerArn(listenerArn))
            def usedPriorities = []
            describeRulesResult.rules?.each { Rule rule ->
                usedPriorities << rule.priority
                if(rule.actions.size() > 0 && rule.actions[0].targetGroupArn == targetGroupArn) {
                    // Found an action that routes to the target.. now check the condition
                    rule.conditions?.each { RuleCondition ruleCondition ->
                        if(ruleCondition.field == 'host-header' && ruleCondition.values.contains(loadBalancerInstance.vipHostname)) {
                            routingRuleNeeded = false
                        }
                    }
                }
            }

            // 2c.  Issue an update to the listener if the protocol is not valid or the certificate changed
            def currentCertArn = listener.certificates?.size() > 0 ? listener.certificates.first().certificateArn : null
            if(listener.protocol != albProtocol || certificateArn != currentCertArn) {
                ModifyListenerRequest modifyListenerRequest = new ModifyListenerRequest().withListenerArn(listenerArn).withCertificates(new LinkedList<Certificate>())
                modifyListenerRequest.protocol = albProtocol
                if(certificateArn) {
                    if(certificateArn && certificateArn != currentCertArn) {
                        modifyListenerRequest.certificates.add(new Certificate().withCertificateArn(certificateArn))
                    }
                }
                amazonClient.modifyListener(modifyListenerRequest)
            }

            // The docs say we can reuse priorities but AWS exceptions if we do... find an available priority
            def index = 1
            while(newRulePriority == 1) {
                if(!usedPriorities.contains(index.toString())) {
                    newRulePriority = index
                }
                index++
            }

        }

        // 3. Create a routing rule if needed
        if(routingRuleNeeded) {
            // Now.. add a rule to route based on the the VIP hostname
            CreateRuleRequest createRuleRequest = new CreateRuleRequest().withConditions(new LinkedList<RuleCondition>()).withActions(new LinkedList<Action>())
            createRuleRequest.priority = newRulePriority
            createRuleRequest.listenerArn = listenerArn
            RuleCondition ruleCondition = new RuleCondition().withValues(new LinkedList<String>())
            ruleCondition.field = 'host-header'
            ruleCondition.values.add(loadBalancerInstance.vipHostname)
            createRuleRequest.conditions.add(ruleCondition)
            createRuleRequest.actions.add(action)
            log.debug "Creating rule ${createRuleRequest}"
            amazonClient.createRule(createRuleRequest)
        }
    }

    private removeInvalidRules(AmazonElasticLoadBalancingClient amazonClient, NetworkLoadBalancerInstance loadBalancerInstance, loadBalancerArn, requiredListenerPorts, TargetGroup targetGroup) {
        log.debug("removeInvalidRules loadBalancerArn:${loadBalancerArn}, requiredListenerPorts:${requiredListenerPorts}, targetGroup:${targetGroup}")

        NetworkLoadBalancer loadBalancer = loadBalancerInstance.loadBalancer

        // Find all listeners for target group
        def listeners = getListenersByTargetGroup(targetGroup, loadBalancer, amazonClient)

        listeners?.each { Listener l ->
            if(!requiredListenerPorts.contains(l.port)) {

                DescribeRulesResult describeRulesResult = amazonClient.describeRules(new DescribeRulesRequest().withListenerArn(l.listenerArn))

                // If this is the last rule.. delete the whole listener
                if(describeRulesResult.rules.size() == 2) {
                    amazonClient.deleteListener(new DeleteListenerRequest().withListenerArn(l.listenerArn))
                } else {
                    // If it was the default action... find another target group to be the default action
                    if(l.defaultActions.size() > 0 && l.defaultActions[0].targetGroupArn == targetGroup.targetGroupArn){
                        ModifyListenerRequest modifyListenerRequest = new ModifyListenerRequest().withListenerArn(l.listenerArn).withDefaultActions(new LinkedList<Action>())
                        Action action = new Action()
                        // Need to find another targetGroup to be the default
                        describeRulesResult.rules.each { Rule rule ->
                            if(rule.actions[0].targetGroupArn != targetGroup.targetGroupArn) {
                                action.targetGroupArn = rule.actions[0].targetGroupArn
                            }
                        }
                        action.type = ActionTypeEnum.Forward
                        modifyListenerRequest.defaultActions.add(action)
                        amazonClient.modifyListener(modifyListenerRequest)
                    }

                    // Remove the rule from this listener
                    for (rule in describeRulesResult.rules) {
                        if (!rule.isDefault && rule.actions.size() > 0 && rule.actions[0].targetGroupArn == targetGroup.targetGroupArn) {
                            amazonClient.deleteRule(new DeleteRuleRequest().withRuleArn(rule.ruleArn))
                        }
                    }
                }
            }
        }
    }

    private getListenersByTargetGroup(TargetGroup targetGroup, NetworkLoadBalancer loadBalancer, AmazonElasticLoadBalancingClient amazonClient) {
        log.debug "Looking for listeners for targetGroup ${targetGroup}"
        def matchedListeners = []
        List<Listener> listeners = getListeners(loadBalancer, amazonClient)
        for (l in listeners) {
            if(l.defaultActions.size() > 0 && l.defaultActions[0].targetGroupArn == targetGroup.targetGroupArn) {
                log.debug "... found a listener for TargetGroup ${targetGroup}"
                matchedListeners << l
            } else {
                DescribeRulesResult describeRulesResult = amazonClient.describeRules(new DescribeRulesRequest().withListenerArn(l.listenerArn))
                for (rule in describeRulesResult.rules) {
                    if(rule.actions.size() > 0 && rule.actions[0].targetGroupArn == targetGroup.targetGroupArn) {
                        // Found an action that routes to the target.. now check the condition
                        matchedListeners << l
                    }
                }
            }
        }
        return matchedListeners
    }

    private List<Listener> getListeners(NetworkLoadBalancer loadBalancer, AmazonElasticLoadBalancingClient amazonClient) {
        DescribeListenersRequest describeListenersRequest = new DescribeListenersRequest()
        describeListenersRequest.loadBalancerArn = loadBalancer.getConfigProperty('arn')
        List<Listener> listeners
        try {
            log.debug "Getting listeners ${describeListenersRequest}"
            DescribeListenersResult describeListenersResult = amazonClient.describeListeners(describeListenersRequest)
            listeners = describeListenersResult.listeners
        } catch (e) {
            // eat it... exceptions if not found
        }
        return listeners
    }

    private getListenerByPort(Integer port, NetworkLoadBalancer loadBalancer, AmazonElasticLoadBalancingClient amazonClient) {
        log.debug "Looking for listener on port ${port}"
        Listener listener
        List<Listener> listeners = getListeners(loadBalancer, amazonClient)
        for (l in listeners) {
            if(!listener && l.port == port) {
                log.debug "... found a listener on port ${port}"
                listener = l
            }
        }
        return listener
    }

    protected uploadOrGetCert(AccountCertificate accountCert, Cloud cloud) {
        log.debug "uploadOrGetCert: ${accountCert}"

        def amazonCertArn

        // Search through all the certs.. looking for one tagged with our cert id
        AWSCertificateManagerClient certClient = getAmazonCertificateClient(cloud)
        ListCertificatesRequest certRequest = new ListCertificatesRequest()
        ListCertificatesResult listCertsResult = certClient.listCertificates(certRequest)
        listCertsResult.certificateSummaryList.each { CertificateSummary summary ->
            log.debug "Current cert arn: ${summary.certificateArn}"
            ListTagsForCertificateRequest listTagsRequest = new ListTagsForCertificateRequest()
            listTagsRequest.certificateArn = summary.certificateArn
            ListTagsForCertificateResult listTagsResult = certClient.listTagsForCertificate(listTagsRequest)
            listTagsResult.tags?.each { com.amazonaws.services.certificatemanager.model.Tag tag ->
                if (tag.key == 'morpheus-cert-id' && tag.value?.toString() == accountCert.id.toString()) {
                    amazonCertArn = summary.certificateArn
                    log.debug "found amazon cert arn ${amazonCertArn} for cert: ${accountCert.id}"
                }
            }
        }

        if (!amazonCertArn) {
            log.debug "No cert found tagged with cert id ${accountCert.id}.. creating new one"
            def certContent = this.morpheus.loadBalancer.certificate.getCertificateContent(accountCert).blockingGet()
            if(certContent.success != true) {
                log.error("Error fetching certificate... Perhaps a remote Cert Servie error? {}", certContent)
                return amazonCertArn
            }

            ImportCertificateRequest importCert = new ImportCertificateRequest()
            importCert.setCertificate(certContent.certFile?.bytes ? ByteBuffer.wrap(certContent.certFile?.bytes) : null)
            importCert.setCertificateChain(certContent.chainFile?.bytes ? ByteBuffer.wrap(certContent.chainFile?.bytes) : null)
            importCert.setPrivateKey(certContent.keyFile?.bytes ? ByteBuffer.wrap(certContent.keyFile?.bytes) : null)

            ImportCertificateResult importCertResult = certClient.importCertificate(importCert)
            amazonCertArn = importCertResult.certificateArn

            AddTagsToCertificateRequest addTagToCertRequest = new AddTagsToCertificateRequest().withTags(new LinkedList<com.amazonaws.services.certificatemanager.model.Tag>())
            addTagToCertRequest.certificateArn = amazonCertArn
            com.amazonaws.services.certificatemanager.model.Tag tag = new com.amazonaws.services.certificatemanager.model.Tag()
            tag.key = 'morpheus-cert-id'
            tag.value = accountCert.id
            addTagToCertRequest.tags.add(0, tag)
            certClient.addTagsToCertificate(addTagToCertRequest)
        }

        return amazonCertArn
    }

    protected ServiceResponse validateLoadBalancer(NetworkLoadBalancer loadBalancer) {
        log.debug "ALB Plugin validateLoadBalancer: ${loadBalancer}"
        ServiceResponse rtn = ServiceResponse.success()

        try {
            log.info("validateLoadBalancer: ${loadBalancer}")

            if(!loadBalancer.name) {
                rtn.errors.name = "A name must be provided"
                rtn.success = false
            }
            if(!loadBalancer.getConfigProperty('amazonVpc')) {
                rtn.errors.vpcs = "A VPC must be selected"
                rtn.success = false
            }
            if(!loadBalancer.getConfigProperty('scheme')) {
                rtn.errors.scheme = "A scheme must be selected"
                rtn.success = false
            }
            if(loadBalancer .getConfigProperty('subnetIds')?.size() < 2) {
                rtn.errors.subnets = "At least 2 subnets must be selected"
                rtn.success = false
            }
            if(loadBalancer.getConfigProperty('securityGroupIds')?.size() < 1) {
                rtn.errors.securityGroups = "At least 1 security group must be selected"
                rtn.success = false
            }
        } catch (e) {
            log.error("validateLoadBalancer error: ${e}", e)
        }

        return rtn
    }

    protected ServiceResponse validateLoadBalancer(NetworkLoadBalancer loadBalancer, Map opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        def networkService = morpheus.network
        def account = opts.account ?: loadBalancer.owner
        def cloud = opts.zone ?: loadBalancer.cloud
        def configMap = loadBalancer.configMap
        def configuredSubnetIds = configMap['subnetIds']
        if (opts.targetSubnetIds) {
            rtn.success = false
            for (lbSubnetId in configuredSubnetIds) {
                def subnetId = lbSubnetId instanceof Double ? lbSubnetId.toLong() : lbSubnetId.toString().toLong()
                Network network
                networkService.listById([subnetId]).blockingSubscribe() { n ->
                    network = n
                }
                def lbAvailabilityZone = network.availabilityZone
                for (targetSubnetId in opts.targetSubnetIds) {
                    subnetId = targetSubnetId instanceof Double ? targetSubnetId.toLong() : targetSubnetId.toString().toLong()
                    Network targetNetwork
                    networkService.listById([subnetId]).blockingSubscribe() { n->
                        targetNetwork = n
                    }
                    def targetAvailabilityZone = targetNetwork.availabilityZone
                    if (lbAvailabilityZone && targetAvailabilityZone && lbAvailabilityZone == targetAvailabilityZone) {
                        rtn.success = true
                    }
                }
            }
        }
        return rtn
    }

    static getAmazonEndpoint(zone) {
        def config = zone.getConfigMap()
        if (config?.endpoint)
            return config.endpoint
        throw new Exception('no amazon endpoint specified')
    }

    AWSCertificateManagerClient getAmazonCertificateClient(Cloud cloud) {
        return AmazonComputeUtility.getAmazonCertificateClient(cloud)
    }

    private getTargetName(NetworkLoadBalancerInstance lbInstance) {
        return "morpheus-${lbInstance.instance.id}-${lbInstance.id}"
    }

    protected getAmazonClient(Cloud cloud, Boolean fresh = false, String region=null) {
        return this.@plugin.getAmazonClient(cloud, fresh, region)
    }

    protected getAmazonElbClient(Cloud cloud, Boolean fresh = false, String region = null) {
        return this.@plugin.getAmazonElbClient(cloud, fresh, region)
    }
}
